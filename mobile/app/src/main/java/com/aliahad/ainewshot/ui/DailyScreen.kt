package com.aliahad.ainewshot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.ApiItem
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Rendered
import com.aliahad.ainewshot.Site
import com.aliahad.ainewshot.Speaker
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A translated issue of AIHOT's daily / weekly / monthly report. */
private data class PaperView(
    val paper: Site.Paper,
    val headline: String?, val lead: String?,
    val sections: List<Triple<String, String?, List<Pair<Site.ReportItem, Rendered?>>>>,
    val flashes: List<Pair<Site.ReportItem, String>>,
) {
    val stories get() = sections.sumOf { it.third.size }
    val sources get() = (sections.flatMap { s -> s.third.map { it.first.source } } + flashes.map { it.first.source }).filter { it.isNotBlank() }.distinct().size
    val minutes get() = ((listOfNotNull(lead) + sections.flatMap { s -> s.third.map { (it.second?.summary ?: it.first.summary).orEmpty() } })
        .sumOf { it.split(' ').size } / 200 + 1).coerceAtLeast(2)
}

private object Papers {
    val views = ConcurrentHashMap<String, PaperView>()
    val issues = ConcurrentHashMap<String, List<Site.Issue>>()

    private val labels = mapOf(
        "模型发布/更新" to "Model releases", "产品发布/更新" to "Product launches", "行业动态" to "Industry",
        "论文研究" to "Research papers", "技巧与观点" to "Tips & opinions", "快讯" to "Quick news",
    )

    suspend fun load(ctx: android.content.Context, kind: String, key: String?): PaperView {
        val p = Site.paper(kind, key)
        views["$kind|${p.key}"]?.let { return it }
        fun api(r: Site.ReportItem) = ApiItem(r.id ?: "paper:${r.title.hashCode()}:${r.title.length}", r.title, null, r.summary, r.source,
            null, r.originalUrl, null, null, null, null, null)
        val all = p.sections.flatMap { it.items } + p.flashes
        val r = Browse.rendered(ctx, all.map { api(it) })
        val leadR = p.leadItem?.let { r[api(it).id] }
        val plain = Browse.plain(ctx, listOf(p.headline.orEmpty(), p.lead.orEmpty()) + p.sections.map { it.summary.orEmpty() } +
            p.sections.map { it.label }.filter { it !in labels })
        val extra = p.sections.map { it.label }.filter { it !in labels }
        val view = PaperView(
            paper = p,
            headline = leadR?.title ?: plain[0].ifBlank { null },
            lead = (if (p.kind == "dailies") leadR?.summary else null) ?: plain[1].ifBlank { null },
            sections = p.sections.mapIndexed { i, s ->
                val label = labels[s.label] ?: plain.getOrNull(2 + p.sections.size + extra.indexOf(s.label)) ?: s.label
                Triple(label, plain[2 + i].ifBlank { null }, s.items.map { it to r[api(it).id] })
            },
            flashes = p.flashes.map { it to (r[api(it).id]?.title ?: it.title) },
        )
        views["$kind|${p.key}"] = view
        if (key == null) views["$kind|latest"] = view
        return view
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DailyScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf("dailies") }
    var key by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var view by remember(kind, key) { mutableStateOf(Papers.views["$kind|${key ?: "latest"}"]) }
    var error by remember(kind, key) { mutableStateOf<String?>(null) }
    var issues by remember(kind) { mutableStateOf(Papers.issues[kind] ?: emptyList()) }
    val playing by Speaker.playingId.collectAsState()
    val briefing by NewsRepo.briefing.collectAsState()
    val list = remember(kind, key) { androidx.compose.foundation.lazy.LazyListState() }

    LaunchedEffect(kind, key) {
        if (view == null) view = runCatching { Papers.load(ctx, kind, key) }.getOrElse { error = it.message; null }
    }
    LaunchedEffect(kind) {
        if (issues.isEmpty()) runCatching { Site.issues(kind) }.onSuccess { issues = it; Papers.issues[kind] = it }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            SingleChoiceSegmentedButtonRow(Modifier.padding(end = 8.dp)) {
                listOf("dailies" to "Daily", "weeklies" to "Weekly", "monthlies" to "Monthly").forEachIndexed { i, (k, l) ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l, fontSize = 13.sp) }
                }
            }
        }, actions = {
            view?.let { v ->
                val delivered = briefing?.takeIf { kind == "dailies" && it.id == "briefing-daily-${v.paper.key}" }
                val speech = delivered ?: NewsItem(id = "paper-${v.paper.key}", title = v.headline ?: "", summary = "", spoken = spoken(v))
                TextButton(onClick = { Speaker.toggle(ctx, speech) }) { Text(if (playing == speech.id) "Stop" else "Listen") }
            }
        })
        // Issue picker: latest + recent back issues, like the site's date strip.
        if (issues.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            issues.take(8).forEachIndexed { i, iss ->
                FilterChip(selected = (key ?: issues.first().key) == iss.key, onClick = { key = if (i == 0) null else iss.key },
                    label = { Text(issueLabel(kind, iss.key, i == 0)) })
            }
        }
        val v = view
        if (v == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { error = null; scope.launch { view = runCatching { Papers.load(ctx, kind, key) }.getOrElse { error = it.message; null } } }) { Text("Retry") }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp)); Text("Fetching and translating…")
                }
            }
            return@Column
        }
        val highlights = v.sections.flatMap { it.third }.filter { it.first.title != v.paper.headline && it.first != v.paper.leadItem }.take(3)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Masthead(v) }
            v.headline?.let { h ->
                item {
                    Column(Modifier.fillMaxWidth().clickable(enabled = v.paper.leadItem?.id != null) { v.paper.leadItem?.id?.let { nav.go(Route.Article(it)) } }
                        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(if (kind == "dailies") "HEADLINE" else "THE BIG STORY")
                        Text(h, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        v.lead?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        v.paper.leadItem?.let { Text(it.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            if (highlights.isNotEmpty()) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(if (kind == "dailies") "TODAY'S HIGHLIGHTS" else "HIGHLIGHTS")
                    highlights.forEachIndexed { i, (it, r) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = it.id != null) { it.id?.let { id -> nav.go(Route.Article(id)) } }) {
                            Text("${i + 1}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
                            Column {
                                Text(r?.title ?: it.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(it.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("IN THIS ISSUE")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        v.sections.forEachIndexed { i, (label, _, items) ->
                            AssistChip(onClick = { scope.launch { list.animateScrollToItem(sectionIndex(v, i, highlights.isNotEmpty())) } }, label = { Text("%02d  %s · %d".format(i + 1, label, items.size)) })
                        }
                        if (v.flashes.isNotEmpty()) AssistChip(onClick = { scope.launch { list.animateScrollToItem(sectionIndex(v, v.sections.size, highlights.isNotEmpty())) } },
                            label = { Text("%02d  Quick news · %d".format(v.sections.size + 1, v.flashes.size)) })
                    }
                }
            }
            v.sections.forEachIndexed { i, (label, summary, items) ->
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
                        Text("%02d".format(i + 1), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
                items.forEach { (it, r) ->
                    item {
                        Column(Modifier.fillMaxWidth().clickable(enabled = it.id != null) { it.id?.let { id -> nav.go(Route.Article(id)) } }
                            .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(it.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r?.title ?: it.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            (r?.summary ?: it.summary)?.takeIf { s -> s.isNotBlank() }?.let { s -> Text(s, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
            if (v.flashes.isNotEmpty()) {
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
                        Text("%02d".format(v.sections.size + 1), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                        Text("Quick news", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                v.flashes.forEach { (it, title) ->
                    item {
                        Row(Modifier.fillMaxWidth().clickable(enabled = it.id != null) { it.id?.let { id -> nav.go(Route.Article(id)) } }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("•", modifier = Modifier.width(16.dp), color = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                Text(it.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { ctx.openUrl(v.paper.link) }, modifier = Modifier.padding(8.dp)) { Text("From AIHOT · open this issue on aihot.news") }
            }
        }
    }
}

/** LazyColumn index of section [i] (masthead, headline?, highlights?, index, then each section header + its items). */
private fun sectionIndex(v: PaperView, i: Int, highlights: Boolean): Int {
    var idx = 1 + (if (v.headline != null) 1 else 0) + (if (highlights) 1 else 0) + 1
    for (s in v.sections.take(i)) idx += 1 + s.third.size
    return idx
}

@Composable
private fun Masthead(v: PaperView) {
    val name = when (v.paper.kind) { "weeklies" -> "AI WEEKLY"; "monthlies" -> "AI MONTHLY"; else -> "AI DAILY" }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Text("AIHOT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(issueTitle(v.paper.kind, v.paper.key), style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Stat(v.stories, "stories"); Stat(v.sources, "sources")
                if (v.flashes.isNotEmpty()) Stat(v.flashes.size, "quick news")
                Stat(v.minutes, "min read")
            }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String) {
    Column {
        Text("$n", fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun issueTitle(kind: String, key: String): String = runCatching {
    when (kind) {
        "dailies" -> LocalDate.parse(key).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US))
        "weeklies" -> "Week ${key.substringAfter("W").toInt()}, ${key.take(4)}"
        else -> java.time.YearMonth.parse(key).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
    }
}.getOrDefault(key)

private fun issueLabel(kind: String, key: String, latest: Boolean): String = runCatching {
    when (kind) {
        "dailies" -> LocalDate.parse(key).let { d -> if (d == LocalDate.now()) "Today" else if (d == LocalDate.now().minusDays(1)) "Yesterday" else d.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) }
        "weeklies" -> if (latest) "This week" else "W" + key.substringAfter("W")
        else -> java.time.YearMonth.parse(key).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US))
    }
}.getOrDefault(key)

/** What Listen reads: headline, lead, then each story's headline. */
private fun spoken(v: PaperView): String = buildString {
    append(when (v.paper.kind) { "weeklies" -> "Your AI weekly. "; "monthlies" -> "Your AI monthly. "; else -> "Your AI daily for ${issueTitle(v.paper.kind, v.paper.key)}. " })
    v.headline?.let { append("The headline: ").append(it.trimEnd('.')).append(". ") }
    v.lead?.let { append(it).append(' ') }
    v.sections.forEach { (label, _, items) ->
        append("$label. ")
        items.filter { it.first != v.paper.leadItem }.take(2).forEach { (it, r) -> append((r?.title ?: it.title).trimEnd('.')).append(". ") }
    }
}
