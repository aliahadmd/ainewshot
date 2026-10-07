package com.aliahad.ainewshot.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.AIHot
import com.aliahad.ainewshot.ApiItem
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Net
import com.aliahad.ainewshot.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: Navigation

/** Screens pushed on top of the bottom tabs (Back pops them). */
sealed interface Route {
    data class Article(val id: String, val seed: NewsItem? = null) : Route
    data class Story(val id: String, val title: String = "") : Route
    data class Topic(val slug: String) : Route
    data object Topics : Route
    data object Saved : Route
    data object Search : Route
    data object CatchUp : Route
    data object Setup : Route
    data object Settings : Route
}

class Nav(private val push: (Route) -> Unit, val back: () -> Unit, val tab: (Int) -> Unit) {
    fun go(r: Route) = push(r)

    /** Opens whatever an item is: an article, a hot story, or the daily briefing. */
    fun open(n: NewsItem) = when {
        n.kind == "briefing" -> tab(TAB_DAILY)
        n.kind == "trending" && n.storyId != null -> go(Route.Story(n.storyId, n.title))
        else -> go(Route.Article(n.id, n))
    }
}

const val TAB_SELECTED = 0
const val TAB_HOT = 1
const val TAB_DAILY = 2
const val TAB_PODCAST = 3
const val TAB_ME = 4

val LocalNav = staticCompositionLocalOf<Nav> { error("no Nav") }

fun Context.openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

// MARK: Time

private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val dayClock = DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.US)

fun localDate(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()

/** "14:42" today, "Oct 6 · 14:42" before. */
fun stamp(t: Long?): String {
    if (t == null) return ""
    val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
    return if (z.toLocalDate() == LocalDate.now()) z.format(clock) else z.format(dayClock)
}

fun clockTime(t: Long?): String = t?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clock) } ?: ""

// MARK: Images (AIHOT's signed image proxy), memory + disk cached

object Images {
    private val mem = android.util.LruCache<String, ImageBitmap>(60)

    /** The proxy URL carries an expiry and signature; the cache key is the underlying image + size. */
    private fun key(url: String): String = runCatching {
        val u = Uri.parse(url); (u.getQueryParameter("u") ?: url) + "|" + (u.getQueryParameter("mode") ?: "")
    }.getOrDefault(url)

    suspend fun load(ctx: Context, url: String, maxWidth: Int = 1080): ImageBitmap? = withContext(Dispatchers.IO) {
        val k = key(url)
        mem.get(k)?.let { return@withContext it }
        runCatching {
            val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
            val f = File(dir, Integer.toHexString(k.hashCode()) + "_" + k.length)
            val bytes = if (f.exists()) f.readBytes() else download(url)?.also {
                f.writeBytes(it)
                dir.listFiles()?.takeIf { l -> l.size > 500 }?.sortedBy { l -> l.lastModified() }?.take(100)?.forEach { l -> l.delete() }
            } ?: return@runCatching null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { mem.put(k, it) }
        }.getOrNull()
    }

    private fun download(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", Net.UA)
            if (c.responseCode != 200 || c.contentLength > 8_000_000) null else c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier, maxWidth: Int = 1080, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, url) { value = Images.load(ctx, url, maxWidth) }
    if (bmp != null) Image(bmp!!, null, modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

// MARK: Card pieces (aihot.news card anatomy)

/** AIHOT's AI rating; 70+ is highlighted the way the site does. */
@Composable
fun ScoreBadge(score: Int?, label: Boolean = false) {
    if (score == null) return
    val hi = score >= 70
    Text((if (label) "AI score " else "") + score, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = if (hi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(if (hi) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(7.dp))
            .border(1.dp, if (hi) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp))
}

@Composable
fun BookmarkButton(item: NewsItem) {
    val ctx = LocalContext.current
    val saved by NewsRepo.saved.collectAsState()
    val on = saved.any { it.id == item.id }
    IconButton(onClick = { NewsRepo.toggleSaved(ctx, item) }, modifier = Modifier.size(32.dp)) {
        Text(if (on) "★" else "☆", fontSize = 18.sp, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A quoted X post, in its original language (usually English). */
@Composable
fun QuoteBlock(author: String?, text: String, maxLines: Int = 6) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Quote" + (author?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "N reports ›": the other outlets AIHOT merged into this news, loaded and translated on demand. */
@Composable
fun ReportsExpander(factId: String?, others: Int, currentId: String, reports: Int = 0) {
    if (factId == null || (others <= 0 && reports <= 1)) return
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var open by remember(factId) { mutableStateOf(false) }
    var rows by remember(factId) { mutableStateOf<List<Pair<Site.Report, String>>?>(null) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable {
                open = !open
                if (open && rows == null) scope.launch {
                    val reps = runCatching { Site.reports(factId) }.getOrDefault(emptyList()).filter { it.id != currentId }
                    val r = Browse.rendered(ctx, reps.map { ApiItem(it.id, it.title, null, null, it.source, null, it.originalUrl, null, it.at, null, null, null) })
                    rows = reps.map { it to (r[it.id]?.title ?: it.title) }
                }
            }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = when {
                    rows != null && open -> "${rows!!.size + 1} reports of this news"
                    others > 0 -> "$others other source${if (others > 1) "s" else ""} reported this"
                    else -> "$reports reports"
                }
                Text(label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                if (rows == null) Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                rows?.forEach { (rep, title) ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.fillMaxWidth().clickable { nav.go(Route.Article(rep.id)) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(AIHot.cleanSource(rep.source) + " · " + stamp(rep.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * One news row, laid out like aihot.news: source · time, AI score and bookmark; title; summary; image or quoted post;
 * the green "why it matters" line; and the other-reports expander. Read items are dimmed.
 */
@Composable
fun NewsCard(item: NewsItem, unread: Boolean = false, also: Int = 0, clockOnly: Boolean = false, read: Boolean? = null, onClick: () -> Unit) {
    val readIds by NewsRepo.readIds.collectAsState()
    val isRead = read ?: (item.id in readIds && !unread)
    val dim = if (isRead) 0.55f else 1f
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (unread) Box(Modifier.padding(end = 6.dp).size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            item.avatarUrl?.let { RemoteImage(it, Modifier.padding(end = 6.dp).size(18.dp).clip(CircleShape), maxWidth = 96) }
            Text(listOf(item.sourceName, item.handle?.let { "@$it" }.takeIf { item.channel == "x" } ?: "").filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(" · " + (if (clockOnly) clockTime(item.discoveredAt) else stamp(item.publishedAt ?: item.discoveredAt)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (item.selected) Text("● Selected", fontSize = 11.sp, color = Color(0xFFD4A72C), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp).background(Color(0x22D4A72C), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            Spacer(Modifier.weight(1f))
            if (item.topics.isNotEmpty()) Text("★ " + item.topics.first(), fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
            ScoreBadge(item.score)
            BookmarkButton(item)
        }
        if (item.channel == "x" && item.summary.isNotBlank()) {
            // An X post: like the site, show the post itself instead of a headline.
            Text(item.summary, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
        } else {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (isRead) FontWeight.Medium else FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
            if (item.summary.isNotBlank() && item.summary != item.title) Text(item.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim))
        }
        item.imageUrl?.let { url ->
            // A thumbnail, as on the site (tap the story for more).
            RemoteImage(url, Modifier.height(120.dp).aspectRatio((item.imageRatio ?: 1.4f).coerceIn(0.8f, 1.9f), matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(8.dp)), maxWidth = 480)
        }
        item.quoteText?.let { QuoteBlock(item.quoteAuthor, it, maxLines = 4) }
        item.why?.takeIf { it.isNotBlank() }?.let {
            Text("Why it matters: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f * dim),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // No editor's note (most of "All"): the category and tags line instead, as the site does.
        if (item.why.isNullOrBlank() && (item.tags.isNotEmpty() || item.category != null)) {
            Text((listOfNotNull(item.category?.let { AIHot.categoryLabel(it) }) + item.tags.map { "#" + AIHot.tagEn(it) }.distinct().take(4)).joinToString("  "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val others = maxOf(also, item.extraSources)
        if (others > 0 || item.reportCount > 1) {
            if (item.eventKey != null) ReportsExpander(item.eventKey, others, item.id, item.reportCount)
            else Text("+$others other source${if (others > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/** "Today · Oct 7 Wed            11 items ⌄" */
@Composable
fun DayHeader(day: LocalDate, count: Int?, collapsed: Boolean, onToggle: () -> Unit) {
    val today = LocalDate.now()
    val name = when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> null }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onToggle)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (name != null) { Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.width(8.dp)) }
        Text(day.format(DateTimeFormatter.ofPattern("MMM d  EEE", Locale.US)), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (count != null) Text("$count item${if (count != 1) "s" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Heat over time (story pages, hot list). NaN = no data. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val pts = values.filter { !it.isNaN() }
    if (pts.size < 2) return
    Canvas(modifier) {
        val max = pts.max().coerceAtLeast(1.0); val min = 0.0
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path(); var started = false
        values.forEachIndexed { i, v ->
            if (v.isNaN()) return@forEachIndexed
            val o = Offset(i * step, (size.height * (1 - (v - min) / (max - min))).toFloat())
            if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

/** "Current hot topics": the top 5 above the Selected feed, with heat and trend, as on aihot.news. */
@Composable
fun HotTopicsPanel() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val topics by com.aliahad.ainewshot.Trending.topics.collectAsState()
    val updated by com.aliahad.ainewshot.Trending.updated.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (topics.isEmpty() || System.currentTimeMillis() - (updated ?: 0) > 5 * 60_000) com.aliahad.ainewshot.Trending.refresh(ctx)
    }
    if (topics.isEmpty()) return
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xFFE8833A), CircleShape))
                Text("Current hot topics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                Text("Full list →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { nav.tab(TAB_HOT) }.padding(4.dp))
            }
            topics.take(5).forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { t.storyId?.let { nav.go(Route.Story(it, t.title)) } }.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.rank}", fontWeight = FontWeight.Bold, color = if (t.rank <= 3) Color(0xFFE8833A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(22.dp))
                    Text(t.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        t.heat?.let { Text("${it.toInt()} " + when (t.trend) { "up" -> "↗"; "down" -> "↘"; else -> "→" }, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        if (t.participants > 0) Text("${t.participants} people", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
