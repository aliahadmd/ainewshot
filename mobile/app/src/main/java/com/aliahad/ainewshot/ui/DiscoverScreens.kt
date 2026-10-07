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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Speaker
import com.aliahad.ainewshot.TrendTopic
import com.aliahad.ainewshot.Trending
import kotlinx.coroutines.launch

// MARK: Trending

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendingList(onOpen: (TrendTopic) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val topics by Trending.topics.collectAsState()
    val updated by Trending.updated.collectAsState()
    val error by Trending.error.collectAsState()
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (topics.isEmpty() || System.currentTimeMillis() - (updated ?: 0) > 5 * 60_000) { loading = true; Trending.refresh(ctx); loading = false }
    }
    PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { loading = true; Trending.refresh(ctx, force = true); loading = false } },
        modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("The stories the most independent sources are covering right now (AIHOT hot list, last 48 h)" +
                    (updated?.let { " · updated ${ago(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (topics.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (error != null) Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                    else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            items(topics, key = { it.id }) { t -> TrendCard(t) { onOpen(t) } }
        }
    }
}

@Composable
private fun TrendCard(t: TrendTopic, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${t.rank}", fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center,
                color = if (t.rank <= 3) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp).background(if (t.rank <= 3) Color(0xFFFFB74D) else MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(top = 5.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (t.summary.isNotBlank()) Text(t.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("🔥 ${t.sourceCount} sources" + (t.latestAt?.let { " · latest ${ago(it)}" } ?: "") +
                    (if (t.sourceNames.isNotEmpty()) " · " + t.sourceNames.take(2).joinToString(", ") else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

data class StoryRequest(val storyId: String, val title: String, val link: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(req: StoryRequest, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var md by remember { mutableStateOf<String?>(null) }
    var spoken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val playing by Speaker.playingId.collectAsState()
    val speech = NewsItem(id = "story-${req.storyId}", title = req.title, summary = "", spoken = spoken.ifBlank { md?.let { com.aliahad.ainewshot.Translator.spokenFallback(it) } })
    val followed by NewsRepo.followedStories.collectAsState()
    val isFollowing = req.storyId in followed
    LaunchedEffect(req.storyId) {
        runCatching { Trending.storyDoc(ctx, req.storyId) }.onSuccess {
            md = it.markdown; spoken = it.spoken
            // Opening a story follows it (if follow-ups are on), so new developments reach you.
            if (Settings.get(ctx).storyFollowUps && req.storyId !in NewsRepo.followedStories.value) {
                val meta = Trending.storyMeta[req.storyId]
                NewsRepo.followStory(ctx, req.storyId, req.title, meta?.first, meta?.second)
            }
        }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Story", maxLines = 1) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(onClick = {
                    if (isFollowing) NewsRepo.unfollowStory(ctx, req.storyId)
                    else Trending.storyMeta[req.storyId].let { NewsRepo.followStory(ctx, req.storyId, req.title, it?.first, it?.second) }
                }) { Text(if (isFollowing) "✓ Following" else "Follow") }
                if (md != null) TextButton(onClick = { Speaker.toggle(ctx, speech) }) { Text(if (playing == speech.id) "Stop" else "Listen") }
                req.link?.let { l -> TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l))) }) { Text("AIHOT") } }
            })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when {
                error != null -> Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                md == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp)); Text("Fetching the story and translating…")
                }
                else -> MarkdownText(md!!)
            }
        }
    }
}

// MARK: Saved

@Composable
fun SavedList(onOpen: (NewsItem) -> Unit) {
    val saved by NewsRepo.saved.collectAsState()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text("Stories you saved for later. Tap ☆ on any story to add it here.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (saved.isEmpty()) item { Text("Nothing saved yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
        items(saved, key = { it.id }) { NewsCard(it) { onOpen(it) } }
    }
}

// MARK: Search

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (NewsItem) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NewsItem>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun run() {
        if (query.trim().length < 2) return
        // Hide the keyboard: on HyperOS a lingering IME otherwise swallows the system Back gesture.
        keyboard?.hide(); focusManager.clearFocus()
        busy = true
        scope.launch { results = runCatching { Browse.search(ctx, query) }.getOrDefault(emptyList()); busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Search the last 7 days") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        OutlinedTextField(query, { query = it }, placeholder = { Text("Anthropic, open-weight models, Cursor…") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { TextButton(onClick = { run() }) { Text("Search") } })
        when {
            busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            results == null -> Text("Searches every AI story from the last 7 days (English queries also search the Chinese equivalent).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            results!!.isEmpty() -> Text("No stories found.", modifier = Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("${results!!.size} stories", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(results!!, key = { it.id }) { NewsCard(it) { onOpen(it) } }
            }
        }
    }
}

// MARK: What did I miss

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchUpScreen(onOpen: (NewsItem) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val read by NewsRepo.readIds.collectAsState()
    val recent by NewsRepo.recent.collectAsState()
    val unread = remember(read, recent) { NewsRepo.grouped(NewsRepo.unread()).map { it.first } }
    val playing by Speaker.playingId.collectAsState()
    val rundown = remember(unread) { Speaker.digest(unread) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("What did I miss") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = unread.isNotEmpty(), onClick = { Speaker.toggle(ctx, rundown) }) {
                Text(if (playing == rundown.id) "Stop" else "Listen to rundown")
            }
            OutlinedButton(enabled = unread.isNotEmpty(), onClick = { NewsRepo.markAllRead(ctx); onBack() }) { Text("Mark all read") }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(if (unread.isEmpty()) "You're all caught up." else "${unread.size} stories you haven't opened, newest first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(unread, key = { it.id }) { NewsCard(it) { onOpen(it) } }
        }
    }
}

@Composable
fun UnreadBanner(onCatchUp: () -> Unit) {
    val ctx = LocalContext.current
    val read by NewsRepo.readIds.collectAsState()
    val recent by NewsRepo.recent.collectAsState()
    val n = remember(read, recent) { NewsRepo.grouped(NewsRepo.unread()).size }
    if (n == 0) return
    Card(Modifier.fillMaxWidth().clickable(onClick = onCatchUp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$n unread", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onCatchUp) { Text("Catch up") }
            TextButton(onClick = { NewsRepo.markAllRead(ctx) }) { Text("Mark read") }
        }
    }
}

@Suppress("unused") private val keepSettingsImport = Settings::class


/** Shown when the natural MiMo voice keeps failing (bad key, quota, outage); speech falls back to the phone voice. */
@Composable
fun VoiceWarning(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val fails by Speaker.mimoFailures.collectAsState()
    val err by Speaker.mimoLastError.collectAsState()
    if (fails < 3 || Settings.get(ctx).voiceProvider != "mimo") return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onSettings),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text("MiMo voice isn't working — using the phone's voice", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text((err?.take(90)?.let { "$it · " } ?: "") + "Check Settings › API keys › Test keys.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
