package com.aliahad.ainewshot.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.AIHot
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Speaker
import com.aliahad.ainewshot.Translator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    tabState: androidx.compose.runtime.MutableIntState,
    onOpen: (NewsItem) -> Unit, onSetup: () -> Unit, onStory: (StoryRequest) -> Unit,
    onSearch: () -> Unit, onCatchUp: () -> Unit, onSettings: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by tabState
    val status by NewsRepo.status.collectAsState()
    val s = Settings.get(ctx)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            Column {
                Text("AI News Hot", fontWeight = FontWeight.Bold)
                val line = status.error ?: status.note ?: status.lastCheck?.let { "Live · checked ${ago(it)}" + if (status.held > 0) " · ${status.held} held" else "" } ?: "Connecting…"
                Text(line, style = MaterialTheme.typography.bodySmall,
                    color = if (status.error != null || status.note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
            if (status.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { NewsRepo.check(ctx, force = true) } }) { Icon(Icons.Default.Refresh, "Check now") }
        })
        SetupBanner(onSetup)
        VoiceWarning(onSettings)
        androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            val first = when {
                s.followed().isNotEmpty() -> "For you"
                s.feedMode == "firstParty" -> "First-hand"
                else -> "Alerts"
            }
            listOf(first, "Trending", "Curated", "All news", "Saved").forEachIndexed { i, l ->
                androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l) })
            }
        }
        when (tab) {
            0 -> FirstHandList(onOpen, onCatchUp)
            1 -> TrendingList { t -> onStory(StoryRequest(t.storyId ?: return@TrendingList, t.title, t.storyUrl)) }
            2 -> BrowseList("selected", onOpen)
            3 -> BrowseList("all", onOpen)
            else -> SavedList(onOpen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstHandList(onOpen: (NewsItem) -> Unit, onCatchUp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by NewsRepo.recent.collectAsState()
    val read by NewsRepo.readIds.collectAsState()
    val status by NewsRepo.status.collectAsState()
    val s = Settings.get(ctx)
    // One row per event: other reports of the same story fold into "+N sources".
    val rows = remember(items) { NewsRepo.grouped(items.filter { it.kind == "news" }) }
    LaunchedEffect(Unit) { NewsRepo.check(ctx) }
    PullToRefreshBox(isRefreshing = status.checking, onRefresh = { scope.launch { NewsRepo.check(ctx, force = true) } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item { UnreadBanner(onCatchUp) }
            item {
                Text(when {
                    s.followed().isNotEmpty() -> "Official announcements plus curated stories on topics you follow — these are the ones that pop up."
                    s.feedMode == "selected" -> "Curated news you're alerted about (Settings › Alerts)."
                    s.feedMode == "all" -> "All news you're alerted about (Settings › Alerts)."
                    else -> "Official announcements — these are the ones that pop up."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rows.isEmpty()) item {
                Text(if (status.lastCheck == null) "Loading the latest first-hand AI news…" else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            items(rows, key = { it.first.id }) { (lead, others) ->
                NewsCard(lead, unread = lead.id !in read, also = others.size) { onOpen(lead) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseList(mode: String, onOpen: (NewsItem) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed by com.aliahad.ainewshot.Browse.feeds.getValue(mode).collectAsState()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(mode) {
        val f = com.aliahad.ainewshot.Browse.feeds.getValue(mode).value
        if (f.items.isEmpty() || System.currentTimeMillis() - (f.updated ?: 0) > 10 * 60_000) com.aliahad.ainewshot.Browse.refresh(ctx, mode)
    }
    // Load the next page when we get within 8 items of the end.
    val nearEnd by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, feed.items.size) { if (nearEnd && feed.items.isNotEmpty()) com.aliahad.ainewshot.Browse.more(ctx, mode) }
    var forYou by androidx.compose.runtime.saveable.rememberSaveable(mode) { mutableStateOf(false) }
    val shown = remember(feed.items, forYou) {
        if (forYou) feed.items.sortedWith(compareByDescending<NewsItem> { it.relevance ?: 0 }.thenByDescending { it.discoveredAt }) else feed.items
    }

    PullToRefreshBox(isRefreshing = feed.loading && feed.items.isEmpty(), onRefresh = { scope.launch { com.aliahad.ainewshot.Browse.refresh(ctx, mode) } },
        modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(if (mode == "all") "Everything from the last 7 days, newest first. Translated as you scroll."
                    else "AIHOT's editors' picks from the last 7 days — official sources plus the best coverage and commentary.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(selected = !forYou, onClick = { forYou = false }, label = { Text("Newest") })
                    androidx.compose.material3.FilterChip(selected = forYou, onClick = { forYou = true }, label = { Text("For you first") })
                }
            }
            items(shown, key = { it.id }) { NewsCard(it) { onOpen(it) } }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        feed.error != null -> TextButton(onClick = { scope.launch { com.aliahad.ainewshot.Browse.more(ctx, mode) } }) { Text("Couldn't load — retry") }
                        feed.loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        feed.end -> Text("That's everything from the last 7 days.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun NewsCard(item: NewsItem, unread: Boolean = false, also: Int = 0, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (item.alerted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (unread) Box(Modifier.padding(top = 7.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                Pill(AIHot.categoryLabel(item.category))
                if (item.firstParty) Pill("✓ Official", Color(0x3322AA55), Color(0xFF1E8E3E))
                item.topics.take(2).forEach { Pill("★ $it", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                if ((item.relevance ?: 0) >= 80) Pill("For you", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                if (item.alerted) Pill("Alerted", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold)
            if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val sources = also + item.extraSources
            Text(listOfNotNull(item.sourceName, if (sources > 0) "+$sources source${if (sources > 1) "s" else ""}" else null,
                ago(item.publishedAt ?: item.discoveredAt), item.score?.let { "score $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SetupBanner(onSetup: () -> Unit) {
    val ctx = LocalContext.current
    // Re-check whenever the app comes back to the foreground (e.g. after granting in system settings).
    var resumed by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val missing = remember(resumed) { Setup.missingCritical(ctx) }
    val gaps = NewsRepo.status.collectAsState().value.interruptions.count { it > System.currentTimeMillis() - 86_400_000 }
    if (missing.isEmpty() && gaps == 0) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onSetup),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(if (missing.isNotEmpty()) "${missing.size} setting${if (missing.size > 1) "s" else ""} could stop news from reaching you"
                else "Live alerts were interrupted $gaps× today", Modifier.weight(1f).padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onSetup) { Text("Fix") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ItemScreen(item: NewsItem, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val playing by Speaker.playingId.collectAsState()
    val preparing by Speaker.preparingId.collectAsState()
    val scope = rememberCoroutineScope()
    var explain by remember { mutableStateOf<String?>(null) }
    var explaining by remember { mutableStateOf(false) }
    val savedList by NewsRepo.saved.collectAsState()
    val isSaved = savedList.any { it.id == item.id }
    val others = remember(item.id) { NewsRepo.recent.value.filter { it.id != item.id && NewsRepo.sameEvent(it, item) } }
    LaunchedEffect(item.id) { NewsRepo.markRead(listOf(item.id) + others.map { it.id }); NewsRepo.persist(ctx) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(item.sourceName, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            TextButton(onClick = { NewsRepo.toggleSaved(ctx, item) }) { Text(if (isSaved) "★ Saved" else "☆ Save") }
            IconButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.link ?: ""}"), "Share"))
            }) { Icon(Icons.Default.Share, "Share") }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(AIHot.categoryLabel(item.category))
                if (item.firstParty) Pill("✓ Official source", Color(0x3322AA55), Color(0xFF1E8E3E))
                item.tags.map { AIHot.tagEn(it) }.distinct().filter { it != AIHot.categoryLabel(item.category) }.take(4)
                    .forEach { Pill(it, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            item.originalTitle?.takeIf { it != item.title && Translator.isLatin(it) }?.let {
                Text("“$it”", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Text(item.summary, style = MaterialTheme.typography.bodyLarge)
            item.why?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(listOfNotNull(ago(item.publishedAt ?: item.discoveredAt), item.score?.let { "AIHOT score $it" }, item.relevance?.let { "for you $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.topics.takeIf { it.isNotEmpty() }?.let { Text("Following: " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            if (others.isNotEmpty()) {
                Text("Also reported by", style = MaterialTheme.typography.titleSmall)
                others.forEach { o ->
                    Text("${o.sourceName}: ${o.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { o.link?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } })
                }
            }
            item.aihotUrl?.let { url -> TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open on AIHOT") } }
            explain?.let { Card { Column(Modifier.padding(14.dp)) { MarkdownText(it) } } }
        }
        // Actions stay on screen no matter how long the story is.
        androidx.compose.material3.Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { item.link?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }, modifier = Modifier.weight(1f)) { Text("Read") }
                FilledTonalButton(onClick = { Speaker.toggle(ctx, item) }, modifier = Modifier.weight(1f)) {
                    Text(when { preparing == item.id -> "Starting…"; playing == item.id -> "Stop"; else -> "Listen" })
                }
                OutlinedButton(enabled = !explaining, modifier = Modifier.weight(1f), onClick = {
                    explaining = true
                    scope.launch {
                        explain = runCatching { Translator.explain(item, Settings.get(ctx)) }.getOrElse { "Couldn't explain: ${it.message}" }
                        explaining = false
                    }
                }) { Text(if (explaining) "Thinking…" else "Explain") }
            }
        }
    }
}

data class DocRequest(val title: String, val path: String, val purpose: String, val link: String,
                      val markdown: String? = null, val spoken: String? = null)   // preloaded (e.g. a delivered briefing)

/** A delivered briefing, preloaded so the reader opens instantly. */
fun briefingDoc(b: NewsItem) = DocRequest(b.title, "api/v1/agent/daily", "daily report", b.aihotUrl ?: "${AIHot.BASE}/daily", b.markdown, b.spoken)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingsScreen(onOpen: (DocRequest) -> Unit, onTrending: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today by NewsRepo.briefing.collectAsState()
    var loading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Briefings") })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AIHOT's editorial reports, translated and ready to listen to. Your morning briefing arrives automatically (Settings › Briefings).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Card(Modifier.fillMaxWidth().clickable {
                val b = today
                if (b != null) onOpen(briefingDoc(b))
                else if (!loading) { loading = true; scope.launch {
                    NewsRepo.makeBriefing(ctx, "api/v1/agent/daily", "Daily")?.let { NewsRepo.briefing.value = it; NewsRepo.persist(ctx) }
                    loading = false
                } }
            }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("☀ Today's briefing", style = MaterialTheme.typography.labelLarge)
                    Text(today?.title ?: if (loading) "Preparing…" else "Tap to get today's briefing", style = MaterialTheme.typography.titleMedium)
                    today?.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
            listOf(
                DocRequest("Daily report", "api/v1/agent/daily", "daily report", "${AIHot.BASE}/daily"),
                DocRequest("Weekly report", "api/v1/agent/weekly", "weekly report", "${AIHot.BASE}/weekly"),
                DocRequest("Monthly report", "api/v1/agent/monthly", "monthly report", "${AIHot.BASE}/monthly"),
            ).forEach { d ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(d) }) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(18.dp))
                }
            }
            Card(Modifier.fillMaxWidth().clickable(onClick = onTrending)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Trending now", style = MaterialTheme.typography.titleMedium)
                    Text("Live Top 10 stories across sources, with full event timelines", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScreen(req: DocRequest, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var md by remember { mutableStateOf<String?>(null) }
    var spoken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val playing by Speaker.playingId.collectAsState()
    val speechItem = NewsItem(id = "doc-${req.path}", title = req.title, summary = "", spoken = spoken.ifBlank { md?.let { Translator.spokenFallback(it) } })
    LaunchedEffect(req) {
        if (req.markdown != null) { md = req.markdown; spoken = req.spoken ?: ""; return@LaunchedEffect }
        runCatching {
            val doc = Translator.document(AIHot.markdown(req.path), req.purpose, Settings.get(ctx))
            md = doc.markdown; spoken = doc.spoken
        }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(req.title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            if (md != null) TextButton(onClick = { Speaker.toggle(ctx, speechItem) }) { Text(if (playing == speechItem.id) "Stop" else "Listen") }
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(req.link))) }) { Text("Source") }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when {
                error != null -> Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                md == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp)); Text("Fetching and translating…")
                }
                else -> MarkdownText(md!!)
            }
        }
    }
}
