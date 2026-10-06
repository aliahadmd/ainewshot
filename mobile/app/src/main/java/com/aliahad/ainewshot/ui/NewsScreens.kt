package com.aliahad.ainewshot.ui

import android.content.Intent
import android.net.Uri
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
fun FeedScreen(onOpen: (NewsItem) -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val status by NewsRepo.status.collectAsState()

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
            if (status.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { NewsRepo.check(ctx, force = true) } }) { Icon(Icons.Default.Refresh, "Check now") }
        })
        SetupBanner(onSetup)
        androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab) {
            val first = when (Settings.get(ctx).feedMode) { "selected" -> "Alerts"; "all" -> "Alerts"; else -> "First-hand" }
            listOf(first, "Curated", "All news").forEachIndexed { i, l ->
                androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l) })
            }
        }
        when (tab) {
            0 -> FirstHandList(onOpen)
            1 -> BrowseList("selected", onOpen)
            else -> BrowseList("all", onOpen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstHandList(onOpen: (NewsItem) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by NewsRepo.recent.collectAsState()
    val status by NewsRepo.status.collectAsState()
    LaunchedEffect(Unit) { NewsRepo.check(ctx) }
    PullToRefreshBox(isRefreshing = status.checking, onRefresh = { scope.launch { NewsRepo.check(ctx, force = true) } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(when (Settings.get(ctx).feedMode) {
                    "selected" -> "Curated news you're alerted about (Settings › Alerts)."
                    "all" -> "All news you're alerted about (Settings › Alerts)."
                    else -> "Official announcements — these are the ones that pop up."
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (items.isEmpty()) item {
                Text(if (status.lastCheck == null) "Loading the latest first-hand AI news…" else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            items(items, key = { it.id }) { NewsCard(it) { onOpen(it) } }
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

    PullToRefreshBox(isRefreshing = feed.loading && feed.items.isEmpty(), onRefresh = { scope.launch { com.aliahad.ainewshot.Browse.refresh(ctx, mode) } },
        modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(if (mode == "all") "Everything from the last 7 days, newest first. Translated as you scroll."
                    else "AIHOT's editors' picks from the last 7 days — official sources plus the best coverage and commentary.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(feed.items, key = { it.id }) { NewsCard(it) { onOpen(it) } }
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
fun NewsCard(item: NewsItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (item.alerted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(AIHot.categoryLabel(item.category))
                if (item.firstParty) Pill("✓ Official", Color(0x3322AA55), Color(0xFF1E8E3E))
                if (item.alerted) Pill("Alerted", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(item.sourceName, ago(item.publishedAt ?: item.discoveredAt), item.score?.let { "score $it" }).joinToString(" · "),
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(item.sourceName, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
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

data class DocRequest(val title: String, val path: String, val purpose: String, val link: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onOpen: (DocRequest) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Reports") })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AIHOT's editorial reports, translated to English and ready to listen to.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                DocRequest("Daily report", "api/v1/agent/daily", "daily report", "${AIHot.BASE}/daily"),
                DocRequest("Trending now", "api/v1/agent/hot", "trending list", "${AIHot.BASE}/hot"),
                DocRequest("Weekly report", "api/v1/agent/weekly", "weekly report", "${AIHot.BASE}/weekly"),
                DocRequest("Monthly report", "api/v1/agent/monthly", "monthly report", "${AIHot.BASE}/monthly"),
            ).forEach { d ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(d) }) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(18.dp))
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
