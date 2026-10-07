package com.aliahad.ainewshot.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliahad.ainewshot.AIHot
import com.aliahad.ainewshot.Articles
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Site
import com.aliahad.ainewshot.Speaker
import com.aliahad.ainewshot.Topics
import com.aliahad.ainewshot.Translator
import kotlinx.coroutines.launch
import java.time.LocalDate

// MARK: Selected (精选) — the home feed, as aihot.news lays it out

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val status by NewsRepo.status.collectAsState()
    val s = Settings.get(ctx)
    // The site's single-choice chips: 全部 · 一手 · 模型 · 产品 · 行业 · 论文 · 教程 · 观点 (+ our ★ Following).
    var view by rememberSaveable { mutableStateOf(s.feedView) }          // selected | all
    var chip by rememberSaveable { mutableStateOf(s.feedChip.takeIf { it != "following" || s.followed().isNotEmpty() } ?: "all") }
    val following = chip == "following"
    val key = Browse.key(view, if (following) "all" else chip)
    LaunchedEffect(view, chip) { s.feedView = view; s.feedChip = chip }
    val feed by Browse.feed(key).collectAsState()
    val recent by NewsRepo.recent.collectAsState()
    val list = rememberLazyListState()
    val collapsed = remember { mutableStateListOf<LocalDate>() }

    LaunchedEffect(key) {
        val f = Browse.feed(key).value
        if (f.items.isEmpty() || System.currentTimeMillis() - (f.updated ?: 0) > 5 * 60_000) Browse.refresh(ctx, key)
    }
    // A new item reached the alert pipeline: show it here too.
    LaunchedEffect(recent.firstOrNull()?.id) {
        val newest = recent.firstOrNull { it.kind == "news" } ?: return@LaunchedEffect
        if (view == "selected" && (chip == "all" || chip == "firstParty") && Browse.feed(key).value.items.none { it.id == newest.id }) Browse.refresh(ctx, key)
    }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, feed.items.size, key) { if (nearEnd && feed.items.isNotEmpty() && !following) Browse.more(ctx, key) }

    val followedItems = remember(recent) { NewsRepo.grouped(recent.filter { it.kind == "news" && it.topics.isNotEmpty() }).map { it.first } }
    val shown: List<NewsItem> = if (following) followedItems else feed.items
    val days = remember(shown) { shown.groupBy { localDate(it.discoveredAt) }.toSortedMap(compareByDescending { it }) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            SingleChoiceSegmentedButtonRow(Modifier.padding(end = 8.dp)) {
                listOf("selected" to "Selected", "all" to "All").forEachIndexed { i, (v, l) ->
                    SegmentedButton(selected = view == v, onClick = { view = v; if (following && v == "all") chip = "all" }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
        }, actions = {
            IconButton(onClick = { nav.go(Route.Search) }) { Icon(Icons.Default.Search, "Search") }
            if (status.checking || feed.loading) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { scope.launch { NewsRepo.check(ctx, force = true); Browse.refresh(ctx, key) } }) { Icon(Icons.Default.Refresh, "Check now") }
        })
        SetupBanner { nav.go(Route.Setup) }
        VoiceWarning { nav.go(Route.Settings) }
        // Filter chips, as on the site: 一手 · (following) · 模型 · 产品 · 行业 · 论文 · 教程 · 观点
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("all" to "All", "firstParty" to "First-hand") + AIHot.categories +
                (if (view == "selected" && s.followed().isNotEmpty()) listOf("following" to "★ Following") else emptyList())).forEach { (id, label) ->
                FilterChip(selected = chip == id, onClick = { chip = id }, label = { Text(label) })
            }
        }
        PullToRefreshBox(isRefreshing = feed.loading && feed.items.isEmpty(), modifier = Modifier.fillMaxSize(), onRefresh = {
            scope.launch { NewsRepo.check(ctx, force = true) }
            scope.launch { Browse.refresh(ctx, key) }
        }) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                item {
                    val line = status.error ?: status.note ?: status.lastCheck?.let { "Live alerts · checked ${ago(it)}" + if (status.held > 0) " · ${status.held} held" else "" } ?: "Connecting…"
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (status.error != null || status.note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (view == "selected" && chip == "all") item { HotTopicsPanel() }
                // Unread banner on the chip your alerts come from.
                if (view == "selected" && chip == (if (s.feedMode == "firstParty") "firstParty" else "all") && s.feedMode != "all") item {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { UnreadBanner { nav.go(Route.CatchUp) } }
                }
                if (following) item {
                    Text("Selected stories on the topics you follow (Me › Topics).", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (shown.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        when {
                            feed.error != null && !following -> TextButton(onClick = { scope.launch { Browse.refresh(ctx, key) } }) { Text("Couldn't load — retry") }
                            following || (!feed.loading && feed.updated != null) -> Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                days.forEach { (day, rows) ->
                    val isCollapsed = day in collapsed
                    item(key = "day-$day") {
                        // Day totals as the site shows them; the All view only knows today's ("360 articles").
                        val count = when {
                            following -> rows.size
                            view == "selected" -> feed.dayCounts[day.toString()] ?: rows.size
                            else -> feed.dayCounts[day.toString()]
                        }
                        DayHeader(day, count, isCollapsed) {
                            if (isCollapsed) collapsed.remove(day) else collapsed.add(day)
                        }
                    }
                    if (!isCollapsed) items(rows, key = { it.id }) { n -> NewsCard(n, clockOnly = true) { nav.open(n) } }
                }
                if (!following && feed.items.isNotEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        when {
                            feed.error != null -> TextButton(onClick = { scope.launch { Browse.more(ctx, key) } }) { Text("Couldn't load — retry") }
                            feed.loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            feed.end -> Text(if (view == "all") "That's everything from the last 7 days." else "That's all.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
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

// MARK: Article (/items/{id}): header, AI summary, why it matters, full text, reading toolbar

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(id: String, seed: NewsItem?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var art by remember(id) { mutableStateOf<Site.Article?>(null) }
    var failed by remember(id) { mutableStateOf<String?>(null) }
    var head by remember(id) { mutableStateOf(seed) }
    var zh by remember(id) { mutableStateOf<String?>(null) }
    var job by remember(id) { mutableStateOf<kotlinx.coroutines.flow.StateFlow<List<String?>>?>(null) }
    var cachedEn by remember(id) { mutableStateOf<String?>(null) }
    var showZh by rememberSaveable(id) { mutableStateOf(false) }
    var storyTitle by remember(id) { mutableStateOf<String?>(null) }
    var topicNames by remember(id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var explain by remember(id) { mutableStateOf<String?>(null) }
    var explaining by remember(id) { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    val playing by Speaker.playingId.collectAsState()
    val preparing by Speaker.preparingId.collectAsState()
    val list = rememberLazyListState()

    LaunchedEffect(id) {
        NewsRepo.markRead(listOf(id) + NewsRepo.recent.value.filter { r -> seed != null && r.id != id && NewsRepo.sameEvent(r, seed) }.map { it.id })
        NewsRepo.persist(ctx)
        val a = runCatching { Site.article(id) }.getOrElse { failed = it.message; null } ?: return@LaunchedEffect
        art = a
        val h = head
        head = if (h == null || h.summary.isBlank()) NewsItem.from(a.item, Browse.rendered(ctx, listOf(a.item))[id])
        else h.copy(eventKey = h.eventKey ?: a.item.factId, extraSources = maxOf(h.extraSources, a.item.extraSources),
            reportCount = maxOf(h.reportCount, a.item.reportCount), originalUrl = h.originalUrl ?: a.item.originalUrl, score = h.score ?: a.item.score, quoteText = h.quoteText ?: a.item.quoteText,
            quoteAuthor = h.quoteAuthor ?: a.item.quoteAuthor, originalTitle = h.originalTitle ?: a.item.originalTitle,
            category = h.category ?: a.item.category)
        launch {
            topicNames = a.topics.map { (slug, name) -> slug to (Topics.byId(slug)?.label ?: name) }
            storyTitle = a.storyTitle?.let { Browse.plain(ctx, listOf(it)).first() }
        }
        val html = a.bodyHtml ?: return@LaunchedEffect
        val md = Articles.markdown(html)
        zh = md
        Articles.cached(ctx, id)?.let { cachedEn = it; return@LaunchedEffect }
        val s = Settings.get(ctx)
        if (s.translation != "deepseek" || s.deepseekKey.isBlank()) { showZh = true; return@LaunchedEffect }
        job = Articles.translate(ctx, id, md)
    }
    val pieces = job?.collectAsState()?.value ?: emptyList()

    // Pieces land in any order; show everything up to the first one still being translated.
    val enText: String? = cachedEn ?: pieces.takeWhile { it != null }.joinToString("\n\n").takeIf { it.isNotBlank() }
    val translating = cachedEn == null && zh != null && !showZh && (pieces.isEmpty() || pieces.any { it == null })
    val body = if (showZh) zh else enText
    val blocks = remember(body) { body?.let { mdBlocks(it) } ?: emptyList() }
    val item = head

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(item?.sourceName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = {
            if (item != null) TextButton(enabled = !explaining, onClick = {
                explaining = true
                scope.launch {
                    explain = runCatching { Translator.explain(item, Settings.get(ctx)) }.getOrElse { "Couldn't explain: ${it.message}" }
                    explaining = false
                    list.animateScrollToItem(0)
                }
            }) { Text(if (explaining) "Thinking…" else "Explain") }
        })
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item == null) {
                        if (failed != null) Text("Couldn't load this story: $failed", color = MaterialTheme.colorScheme.error)
                        else Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                        return@Column
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.avatarUrl?.let { RemoteImage(it, Modifier.padding(end = 6.dp).size(20.dp).clip(CircleShape), maxWidth = 96) }
                        Text(listOf(item.sourceName, stamp(item.publishedAt ?: item.discoveredAt), ago(item.publishedAt ?: item.discoveredAt))
                            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (art?.item?.selected == true) Pill("● Selected", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        if (item.firstParty) Pill("✓ First-hand", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        Pill(AIHot.categoryLabel(item.category), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        ScoreBadge(item.score, label = true)
                    }
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    item.originalTitle?.takeIf { it != item.title && Translator.isLatin(it) }?.let {
                        Text(it, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    explain?.let { Card { Column(Modifier.padding(14.dp)) { MarkdownText(it) } } }
                    if (item.summary.isNotBlank()) {
                        SectionLabel("AI summary")
                        Text(item.summary, style = MaterialTheme.typography.bodyLarge)
                    }
                    item.why?.takeIf { it.isNotBlank() }?.let {
                        HorizontalDivider()
                        Text("Why it matters", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    item.quoteText?.let { QuoteBlock(item.quoteAuthor, it, maxLines = 20) }
                    ReportsExpander(item.eventKey, item.extraSources, id, item.reportCount)
                    art?.storyId?.let { sid ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().clickable { nav.go(Route.Story(sid, storyTitle ?: "")) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Part of a developing story", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text((storyTitle ?: art?.storyTitle ?: "") + "  ›", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (art != null) {
                        HorizontalDivider()
                        if (zh != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Full text" + if (!showZh) " · AI translation" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(selected = !showZh, onClick = { showZh = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("English", fontSize = 12.sp) }
                                SegmentedButton(selected = showZh, onClick = { showZh = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("中文", fontSize = 12.sp) }
                            }
                        } else Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("AIHOT doesn't show this source's full text, so neither do we.", style = MaterialTheme.typography.bodyMedium)
                                (item.originalUrl ?: art?.item?.originalUrl)?.let { u -> OutlinedButton(onClick = { ctx.openUrl(u) }) { Text("Read the original") } }
                            }
                        }
                        if (zh != null && art?.bodyIsTranslation == true && !showZh) Text("Translated from AIHOT's Chinese version of the original. Use Original for the exact wording.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(blocks) { _, b -> Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) { MarkdownBlock(b) } }
            if (translating) item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(10.dp))
                    Text(if (pieces.isEmpty()) "Translating the full text…" else "Translating… ${pieces.count { it != null }} of ${pieces.size} parts",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item != null) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (topicNames.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        topicNames.forEach { (slug, name) ->
                            Text("# $name", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { nav.go(Route.Topic(slug)) }.padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    }
                    Text("Source: ${item.sourceName}" + ((item.originalUrl ?: art?.item?.originalUrl)?.let { u -> " · " + (android.net.Uri.parse(u).host?.removePrefix("www.") ?: "") } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Reading toolbar (the site's: bookmark · contents · share · original), plus Listen.
        if (item != null) {
            val saved by NewsRepo.saved.collectAsState()
            val isSaved = saved.any { it.id == item.id }
            val headings = remember(blocks) { blocks.withIndex().filter { isHeading(it.value) } }
            NavigationBar {
                NavigationBarItem(selected = false, onClick = { NewsRepo.toggleSaved(ctx, item) },
                    icon = { Text(if (isSaved) "★" else "☆", fontSize = 20.sp) }, label = { Text(if (isSaved) "Saved" else "Save") })
                NavigationBarItem(selected = false, enabled = headings.isNotEmpty(), onClick = { toc = true },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Contents") })
                NavigationBarItem(selected = false, onClick = { Speaker.toggle(ctx, item) },
                    icon = { Text(if (playing == item.id) "■" else "▶", fontSize = 18.sp) },
                    label = { Text(when { preparing == item.id -> "Starting…"; playing == item.id -> "Stop"; else -> "Listen" }) })
                NavigationBarItem(selected = false, onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.originalUrl ?: item.aihotUrl ?: ""}"), "Share"))
                }, icon = { Icon(Icons.Default.Share, null) }, label = { Text("Share") })
                NavigationBarItem(selected = false, onClick = { (item.originalUrl ?: art?.item?.originalUrl ?: item.aihotUrl)?.let { ctx.openUrl(it) } },
                    icon = { Text("↗", fontSize = 20.sp) }, label = { Text("Original") })
            }
            if (toc) ModalBottomSheet(onDismissRequest = { toc = false }) {
                Text("Contents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                LazyColumn(Modifier.padding(bottom = 24.dp)) {
                    items(headings) { (i, h) ->
                        val sub = h.trimStart().startsWith("###")
                        Text(h.trimStart().trimStart('#').trim().replace("**", ""), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                            modifier = Modifier.fillMaxWidth().clickable {
                                toc = false
                                scope.launch { list.animateScrollToItem(1 + i) }   // +1: the header row
                            }.padding(start = if (sub) 36.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp))
                    }
                }
            }
        }
    }
}

// MARK: Bottom navigation (the site's mobile tabs; Podcast in place of the model ranking)

@Composable
fun BottomTabs(tab: Int, onTab: (Int) -> Unit) {
    NavigationBar {
        listOf("Selected" to "⚡", "Hot" to "🔥", "Daily" to "📰", "Podcast" to "🎧", "Me" to "👤").forEachIndexed { i, (label, icon) ->
            NavigationBarItem(selected = tab == i, onClick = { onTab(i) }, icon = { Text(icon, fontSize = 18.sp) }, label = { Text(label) })
        }
    }
}
