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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.ApiItem
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Site
import com.aliahad.ainewshot.Speaker
import com.aliahad.ainewshot.Topics
import com.aliahad.ainewshot.TrendTopic
import com.aliahad.ainewshot.Trending
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: Hot (热点): the 10 most discussed stories of the last 48 h

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val topics by Trending.topics.collectAsState()
    val updated by Trending.updated.collectAsState()
    val error by Trending.error.collectAsState()
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (topics.isEmpty() || System.currentTimeMillis() - (updated ?: 0) > 5 * 60_000) { loading = true; Trending.refresh(ctx); loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            Column {
                Text("Hot", fontWeight = FontWeight.Bold)
                Text("Most discussed in AI, last 48 hours" + (updated?.let { " · updated ${clockTime(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { loading = true; Trending.refresh(ctx, force = true); loading = false } },
            modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (topics.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        if (error != null) Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                items(topics, key = { it.id }) { t -> HotRow(t) { t.storyId?.let { nav.go(Route.Story(it, t.title)) } } }
            }
        }
    }
}

@Composable
private fun HotRow(t: TrendTopic, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NO.%02d".format(t.rank), fontWeight = FontWeight.Black, fontSize = 15.sp,
                color = if (t.rank <= 3) Color(0xFFE8833A) else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            TrendLabel(t.trend, t.trendPct)
            Spacer(Modifier.weight(1f))
            if (t.spark.size > 2) Sparkline(t.spark, Modifier.width(56.dp).height(18.dp).padding(end = 8.dp))
            t.heat?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(it.roundToInt().toString(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("heat", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (t.summary.isNotBlank()) Text(t.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        t.latest?.takeIf { it.isNotBlank() }?.let {
            Text("Latest: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(buildString {
            append("${t.sourceCount} sources")
            if (t.sourceNames.isNotEmpty()) append(" incl. ").append(t.sourceNames.take(2).joinToString(", "))
            if (t.participants > 0) append(" · ${t.participants} people")
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun TrendLabel(trend: String?, pct: Double?) {
    val (text, color) = when (trend) {
        "up" -> "↑ ${pct?.let { abs(it).roundToInt() } ?: ""}%" to Color(0xFFD9534F)
        "down" -> "↓ ${pct?.let { abs(it).roundToInt() } ?: ""}%" to Color(0xFF2E9E6B)
        null -> return
        else -> "steady" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
}

// MARK: Story (/story/{id}): overview, heat, developments and the full coverage timeline

private data class StoryView(
    val title: String, val digest: String?, val latest: String?,
    val developments: List<Pair<Site.Development, String>>,     // + English title
    val timeline: List<Pair<Site.StoryReport, com.aliahad.ainewshot.Rendered?>>,
    val related: List<Pair<String, String>>,
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StoryScreen(id: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var st by remember(id) { mutableStateOf<Site.Story?>(null) }
    var view by remember(id) { mutableStateOf<StoryView?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var newestFirst by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("all") }       // all | official | selected
    val playing by Speaker.playingId.collectAsState()
    val followed by NewsRepo.followedStories.collectAsState()
    val isFollowing = id in followed

    LaunchedEffect(id) {
        val story = runCatching { Site.story(id) }.getOrElse { error = it.message; null } ?: return@LaunchedEffect
        st = story
        Trending.storyMeta[id] = (story.latestAt ?: 0L) to story.latest
        // Opening a story follows it (if follow-ups are on), so new developments reach you.
        if (Settings.get(ctx).storyFollowUps && id !in NewsRepo.followedStories.value) NewsRepo.followStory(ctx, id, title.ifBlank { story.title }, story.latestAt, story.latest)
        val texts = listOf(story.title, story.digest.orEmpty(), story.latest.orEmpty()) + story.developments.map { it.title } + story.related.map { it.second }
        val en = Browse.plain(ctx, texts)
        val reports = story.timeline + story.developments.mapNotNull { it.lead }
        val r = Browse.rendered(ctx, reports.distinctBy { it.id }.map { ApiItem(it.id, it.title, null, it.summary, it.source, null, null, it.at, it.at, null, null, null) })
        val d = story.developments.size
        view = StoryView(en[0], en[1].ifBlank { null }, en[2].ifBlank { null },
            story.developments.mapIndexed { i, dev -> dev to en[3 + i] },
            story.timeline.map { it to r[it.id] },
            story.related.mapIndexed { i, (rid, _) -> rid to en[3 + d + i] })
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Story", maxLines = 1) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(onClick = {
                    if (isFollowing) NewsRepo.unfollowStory(ctx, id)
                    else Trending.storyMeta[id].let { NewsRepo.followStory(ctx, id, view?.title ?: title, it?.first, it?.second) }
                }) { Text(if (isFollowing) "✓ Following" else "Follow") }
                view?.let { v ->
                    val speech = NewsItem(id = "story-$id", title = v.title, summary = "", spoken = listOfNotNull(v.title, v.digest, v.latest?.let { "Latest: $it" }).joinToString(". "))
                    TextButton(onClick = { Speaker.toggle(ctx, speech) }) { Text(if (playing == speech.id) "Stop" else "Listen") }
                }
            })
        val s = st
        val v = view
        if (s == null || v == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                if (error != null) Text("Couldn't load: $error", color = MaterialTheme.colorScheme.error)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp)); Text("Loading the story…")
                }
            }
            return@Column
        }
        val shown = v.timeline.filter { (rep, _) -> when (filter) { "official" -> rep.official; "selected" -> rep.selected; else -> true } }
            .let { if (newestFirst) it.sortedByDescending { p -> p.first.at ?: 0 } else it.sortedBy { p -> p.first.at ?: 0 } }
        val devs = if (newestFirst) v.developments.sortedByDescending { it.first.at ?: 0 } else v.developments.sortedBy { it.first.at ?: 0 }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Developing story", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(v.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${s.reportCount} reports · ${s.sourceCount} sources" + (s.latestAt?.let { " · updated ${ago(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.heat.count { it > 0 } > 2) {
                        Text("Heat over time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Sparkline(s.heat, Modifier.fillMaxWidth().height(56.dp))
                    }
                    v.digest?.let {
                        SectionLabel("AI overview")
                        it.split("\n").filter { p -> p.isNotBlank() }.forEach { p -> Text(p.trim(), style = MaterialTheme.typography.bodyMedium) }
                        Text("AI-written from the coverage" + (s.digestAt?.let { t -> " · updated ${ago(t)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    v.latest?.let {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Latest" + (s.latestAt?.let { t -> " · ${stamp(t)}" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = newestFirst, onClick = { newestFirst = true }, label = { Text("Newest first") })
                        FilterChip(selected = !newestFirst, onClick = { newestFirst = false }, label = { Text("Oldest first") })
                    }
                }
            }
            if (devs.isNotEmpty()) {
                item { SectionLabel("Developments (${devs.size})", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(devs) { (d, en) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = d.lead != null) { d.lead?.let { nav.go(Route.Article(it.id)) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Box(Modifier.padding(top = 6.dp, end = 10.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                        Column {
                            Text(stamp(d.at) + " · ${d.reports} report${if (d.reports != 1) "s" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(en, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            d.lead?.let { Text(it.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Coverage")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All ${v.timeline.size}") })
                        FilterChip(selected = filter == "official", onClick = { filter = "official" }, label = { Text("First-hand ${v.timeline.count { it.first.official }}") })
                        FilterChip(selected = filter == "selected", onClick = { filter = "selected" }, label = { Text("Selected ${v.timeline.count { it.first.selected }}") })
                    }
                }
            }
            items(shown) { (rep, r) ->
                Column(Modifier.fillMaxWidth().clickable { nav.go(Route.Article(rep.id)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stamp(rep.at) + " · " + rep.source + (if (rep.official) "  ✓ first-hand" else "") + (if (rep.selected) "  ● selected" else ""),
                        style = MaterialTheme.typography.labelSmall, color = if (rep.official) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r?.title ?: rep.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    (r?.summary ?: rep.summary)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (s.topics.isNotEmpty() || v.related.isNotEmpty()) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.related.isNotEmpty()) {
                        SectionLabel("Related stories")
                        v.related.forEach { (rid, rt) -> Text("$rt  ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { nav.go(Route.Story(rid, rt)) }.padding(vertical = 4.dp)) }
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.topics.forEach { (slug, name) ->
                            Text("# " + (Topics.byId(slug)?.label ?: name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { nav.go(Route.Topic(slug)) }.padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    }
                    TextButton(onClick = { ctx.openUrl("${com.aliahad.ainewshot.AIHot.BASE}/story/$id") }) { Text("Open on AIHOT") }
                }
            }
        }
    }
}

// MARK: Saved (收藏)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(onBack: () -> Unit) {
    val nav = LocalNav.current
    val saved by NewsRepo.saved.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Saved") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        LazyColumn(Modifier.fillMaxSize()) {
            if (saved.isEmpty()) item { Text("Nothing saved yet. Tap ☆ on any story to keep it here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            items(saved, key = { it.id }) { NewsCard(it) { nav.open(it) } }
        }
    }
}

// MARK: Search

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
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
        OutlinedTextField(query, { query = it }, placeholder = { Text("Titles and summaries: Anthropic, open-weight models…") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { TextButton(onClick = { run() }) { Text("Search") } })
        when {
            busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            results == null -> Text("Searches every AI story from the last 7 days (English queries also search the Chinese equivalent).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            results!!.isEmpty() -> Text("No stories found.", modifier = Modifier.padding(16.dp))
            else -> LazyColumn {
                item { Text("${results!!.size} stories", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                items(results!!, key = { it.id }) { NewsCard(it) { nav.open(it) } }
            }
        }
    }
}

// MARK: What did I miss

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchUpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
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
        LazyColumn {
            item {
                Text(if (unread.isEmpty()) "You're all caught up." else "${unread.size} first-hand stories you haven't opened, newest first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(unread, key = { it.id }) { NewsCard(it, unread = true) { nav.open(it) } }
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
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$n unread", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onCatchUp) { Text("Catch up") }
            TextButton(onClick = { NewsRepo.markAllRead(ctx) }) { Text("Mark read") }
        }
    }
}

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
            Text((err?.take(90)?.let { "$it · " } ?: "") + "Check Me › Settings › API keys › Test keys.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
