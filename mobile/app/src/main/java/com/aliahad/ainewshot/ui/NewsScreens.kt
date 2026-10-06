package com.aliahad.ainewshot.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.aliahad.ainewshot.Alerts
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Speaker
import com.aliahad.ainewshot.Translator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(onOpen: (NewsItem) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val items by NewsRepo.recent.collectAsState()
    val status by NewsRepo.status.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { NewsRepo.check(ctx) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            Column {
                Text("AI News Hot", fontWeight = FontWeight.Bold)
                val line = status.error ?: status.note ?: status.lastCheck?.let { "Checked ${ago(it)}" + if (status.held > 0) " · ${status.held} held" else "" } ?: "Connecting…"
                Text(line, style = MaterialTheme.typography.bodySmall,
                    color = if (status.error != null || status.note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, actions = {
            if (status.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { NewsRepo.check(ctx, force = true) } }) { Icon(Icons.Default.Refresh, "Check now") }
        })
        PermissionBanner(onSettings)
        Column(Modifier.padding(horizontal = 12.dp)) { ChoiceRow(null, listOf(0 to "Latest", 1 to "Alerted"), filter) { filter = it } }
        PullToRefreshBox(isRefreshing = status.checking, onRefresh = { scope.launch { NewsRepo.check(ctx, force = true) } }, modifier = Modifier.fillMaxSize()) {
            val rows = items.filter { filter == 0 || it.alerted }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                if (rows.isEmpty()) item {
                    Text(if (status.lastCheck == null) "Loading the latest first-hand AI news…" else "Nothing here yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                items(rows, key = { it.id }) { NewsCard(it) { onOpen(it) } }
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
fun PermissionBanner(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    // Re-check whenever the app comes back to the foreground (e.g. after granting in system settings).
    var resumed by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    if (resumed < 0) return
    val missing = buildList {
        if (!Alerts.canNotify(ctx)) add("notifications")
        if (s.popupMode == "fullscreen" && !Alerts.canOverlay(ctx)) add("pop-up over other apps")
        if (!Alerts.canFullScreen(ctx)) add("full-screen alerts")
    }
    if (missing.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text("Allow ${missing.joinToString(", ")} so news can pop up.", Modifier.weight(1f).padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onSettings) { Text("Fix") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(AIHot.categoryLabel(item.category))
                if (item.firstParty) Pill("✓ Official source", Color(0x3322AA55), Color(0xFF1E8E3E))
                item.tags.take(3).forEach { Pill(AIHot.tagEn(it), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { item.link?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }) { Text("Read article") }
                FilledTonalButton(onClick = { Speaker.toggle(ctx, item) }) {
                    Text(when { preparing == item.id -> "Preparing…"; playing == item.id -> "Stop"; else -> "Listen" })
                }
                OutlinedButton(enabled = !explaining, onClick = {
                    explaining = true
                    scope.launch {
                        explain = runCatching { Translator.explain(item, Settings.get(ctx)) }.getOrElse { "Couldn't explain: ${it.message}" }
                        explaining = false
                    }
                }) { Text(if (explaining) "Thinking…" else "Explain") }
            }
            item.aihotUrl?.let { url -> TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open on AIHOT") } }
            explain?.let { Card { Column(Modifier.padding(14.dp)) { MarkdownText(it) } } }
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
