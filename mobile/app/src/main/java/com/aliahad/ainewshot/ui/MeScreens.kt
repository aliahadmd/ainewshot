package com.aliahad.ainewshot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.AIHot
import com.aliahad.ainewshot.BuildConfig
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Site
import com.aliahad.ainewshot.Topics
import kotlinx.coroutines.launch

// MARK: Me (我的)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val saved by NewsRepo.saved.collectAsState()
    val read by NewsRepo.readIds.collectAsState()
    val recent by NewsRepo.recent.collectAsState()
    val v by Settings.get(ctx).version.collectAsState()
    val unread = remember(read, recent) { NewsRepo.grouped(NewsRepo.unread()).size }
    val following = remember(v) { Settings.get(ctx).followed().size }
    val stories by NewsRepo.followedStories.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Me") })
        LazyColumn(Modifier.fillMaxSize()) {
            item { MeRow("☆", "Saved", "${saved.size} stor${if (saved.size == 1) "y" else "ies"}") { nav.go(Route.Saved) } }
            item { MeRow("#", "Topics", if (following > 0) "Following $following · alerts for these" else "38 topics: companies, research areas, content types") { nav.go(Route.Topics) } }
            item { MeRow("◎", "Catch up", if (unread > 0) "$unread unread first-hand stories" else "You're all caught up") { nav.go(Route.CatchUp) } }
            item { MeRow("⌕", "Search", "Everything from the last 7 days") { nav.go(Route.Search) } }
            item { Header("Tools") }
            item { MeRow("▤", "Model ranking", "AIHOT's leaderboard (opens aihot.news)") { ctx.openUrl("${AIHot.BASE}/leaderboard") } }
            item { MeRow("⚙", "Settings", "Alerts, briefings, voice, podcast, keys") { nav.go(Route.Settings) } }
            item { MeRow("✓", "Reliability setup", "Make sure HyperOS never blocks alerts") { nav.go(Route.Setup) } }
            if (stories.isNotEmpty()) {
                item { Header("Stories you follow") }
                items(stories.values.sortedByDescending { it.latestAt }.take(6), key = { "fs-" + it.id }) { f ->
                    MeRow("↻", f.title, "Alerts when it develops" + (f.latestAt.takeIf { it > 0 }?.let { " · updated ${ago(it)}" } ?: "")) { nav.go(Route.Story(f.id, f.title)) }
                }
            }
            item { Header("About") }
            item { MeRow("i", "AIHOT", "Where all news comes from · aihot.news") { ctx.openUrl(AIHot.BASE) } }
            item { MeRow("§", "Usage rules", "AIHOT's terms for personal readers") { ctx.openUrl("${AIHot.BASE}/terms") } }
            item {
                Text("AI News Hot ${BuildConfig.VERSION_NAME} · translation by DeepSeek · voices by Xiaomi MiMo",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun MeRow(icon: String, title: String, sub: String, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSecondaryContainer) } },
        headlineContent = { Text(title) },
        supportingContent = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) })
}

@Composable
private fun Header(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))
}

// MARK: Topics (/topics)

private object TopicCache {
    var list: List<Site.TopicInfo> = emptyList()
    var latestEn: Map<String, String> = emptyMap()
}

private val groupNames = listOf("company" to "Companies & models", "field" to "Research areas", "genre" to "Content types")

@Composable
private fun FollowButton(slug: String) {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    val v by s.version.collectAsState()
    val on = remember(v) { slug in s.followed() }
    val toggle = { s.followedTopics = (if (on) s.followed() - slug else s.followed() + slug).joinToString(",") }
    if (on) FilledTonalButton(onClick = toggle) { Text("✓ Following") } else OutlinedButton(onClick = toggle) { Text("Follow") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var topics by remember { mutableStateOf(TopicCache.list) }
    var latest by remember { mutableStateOf(TopicCache.latestEn) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val t = runCatching { Site.topics() }.getOrElse { error = it.message; return@LaunchedEffect }
        topics = t; TopicCache.list = t
        val titles = t.mapNotNull { it.latestTitle }
        val en = Browse.plain(ctx, titles)
        latest = titles.zip(en).toMap(); TopicCache.latestEn = latest
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Topics") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("Follow a topic to get its curated stories as alerts and in Selected › ★ Following.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            if (topics.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (error != null) Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error) else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            groupNames.forEach { (g, name) ->
                val inGroup = topics.filter { it.group == g }
                if (inGroup.isNotEmpty()) {
                    item { Header(name) }
                    items(inGroup, key = { it.slug }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Topic(t.slug)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                                Text(topicName(t).take(1), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(topicName(t), style = MaterialTheme.typography.titleSmall)
                                t.latestTitle?.let { Text((latest[it] ?: it), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text((if (t.recent > 0) "${t.recent} in 30 days" else "Nothing new in 30 days") + (t.latestAt?.let { " · latest ${ago(it)}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FollowButton(t.slug)
                        }
                    }
                }
            }
        }
    }
}

private fun milestoneDate(d: String): String = runCatching {
    val date = java.time.LocalDate.parse(d)
    date.format(java.time.format.DateTimeFormatter.ofPattern(if (date.year == java.time.LocalDate.now().year) "MMM d" else "MMM yyyy", java.util.Locale.US))
}.getOrDefault(d)

private fun topicName(t: Site.TopicInfo) = Topics.byId(t.slug)?.label ?: t.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(slug: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var page by remember(slug) { mutableStateOf<Site.TopicPage?>(null) }
    var items by remember(slug) { mutableStateOf<List<NewsItem>>(emptyList()) }
    var definition by remember(slug) { mutableStateOf<String?>(null) }
    var milestones by remember(slug) { mutableStateOf<List<Pair<Site.Milestone, String>>>(emptyList()) }
    var showAll by remember(slug) { mutableStateOf(false) }
    var loading by remember(slug) { mutableStateOf(false) }
    var pageNo by remember(slug) { mutableIntStateOf(1) }
    var error by remember(slug) { mutableStateOf<String?>(null) }

    suspend fun load(n: Int) {
        loading = true
        runCatching { Site.topic(slug, n) }.onSuccess { p ->
            if (n == 1) {
                page = p
                val en = Browse.plain(ctx, listOf(p.definition.orEmpty()) + p.milestones.map { it.title })
                definition = en[0].ifBlank { null }
                milestones = p.milestones.mapIndexed { i, m -> m to en[1 + i] }
            }
            val r = Browse.rendered(ctx, p.items)
            items = (items + p.items.map { NewsItem.from(it, r[it.id]) }).distinctBy { it.id }
            pageNo = n
        }.onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(slug) { load(1) }

    val p = page
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(p?.let { topicName(it.info) } ?: Topics.byId(slug)?.label ?: "Topic", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { Box(Modifier.padding(end = 8.dp)) { FollowButton(slug) } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (p == null) item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (error != null) Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error) else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            if (p != null) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    definition?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("${p.info.recent} stories in the last 30 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (milestones.isNotEmpty()) {
                item { Header("Timeline") }
                items(if (showAll) milestones else milestones.take(6)) { (m, en) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = m.itemId != null || m.storyId != null) {
                        when { m.storyId != null -> nav.go(Route.Story(m.storyId, en)); m.itemId != null -> nav.go(Route.Article(m.itemId)) }
                    }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                        Text(milestoneDate(m.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(66.dp))
                        Text(en, style = MaterialTheme.typography.bodyMedium, fontWeight = if (m.major) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (milestones.size > 6) item {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showAll) "Show less" else "Show all ${milestones.size}") }
                }
                item { HorizontalDivider() }
            }
            if (items.isNotEmpty()) item { Header("Latest selected") }
            items(items.distinctBy { it.id }, key = { it.id }) { NewsCard(it) { nav.open(it) } }
            if (p != null && pageNo < p.pages) item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { scope.launch { load(pageNo + 1) } }) { Text("Load more") }
                }
            }
        }
    }
}
