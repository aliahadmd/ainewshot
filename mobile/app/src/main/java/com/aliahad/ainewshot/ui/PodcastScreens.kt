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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
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
import com.aliahad.ainewshot.Episode
import com.aliahad.ainewshot.PodStyle
import com.aliahad.ainewshot.PodcastPlayer
import com.aliahad.ainewshot.PodcastService
import com.aliahad.ainewshot.PodcastStudio
import com.aliahad.ainewshot.Settings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    val v by s.version.collectAsState()
    val st by PodcastStudio.state.collectAsState()
    val episodes by PodcastStudio.episodes.collectAsState()
    var confirmDelete by remember { mutableStateOf<Episode?>(null) }
    val busy = st.stage in 1..5

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("AI Podcast", fontWeight = FontWeight.Bold) })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("Catch up on everything that happened in AI — all news, merged, ranked and read to you by ${if (s.podFormat == "duo") "${s.podHostAName} & ${s.podHostBName}" else s.podHostAName}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "1 day", 3 to "3 days", 7 to "7 days").forEach { (d, label) ->
                        Button(onClick = { PodcastService.start(ctx, d) }, enabled = !busy, modifier = Modifier.weight(1f).height(64.dp),
                            shape = RoundedCornerShape(16.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, fontWeight = FontWeight.Bold)
                                Text(if (d == 1) "Yesterday" else if (d == 3) "Catch up" else "Weekly", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            item {
                key(v) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceRow("Hosts", listOf("duo" to "Two hosts", "solo" to "One host"), s.podFormat) { s.podFormat = it }
                        ChoiceRow("Length", listOf(0 to "Auto", 5 to "5 min", 10 to "10 min", 15 to "15 min", 20 to "20 min", 30 to "30 min"), s.podLengthMin) { s.podLengthMin = it }
                        ChoiceRow("Coverage", listOf("all" to "All news", "selected" to "Curated", "firstParty" to "First-hand"), s.podCoverage) { s.podCoverage = it }
                        ChoiceRow("Tone", PodStyle.tones.map { it.first to it.second }, s.podTone) { s.podTone = it }
                        ChoiceRow("Emotion", listOf(1 to "Subtle", 2 to "Natural", 3 to "Expressive"), s.podExpressiveness) { s.podExpressiveness = it }
                    }
                }
            }
            if (st.stage in 1..5 || st.stage == 7) item { Progress(st) }
            item { Text("Library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (episodes.isEmpty()) item { Text("No episodes yet. Pick a period above — a day takes about 3 minutes.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(episodes, key = { _, e -> e.id }) { _, ep ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(ep.id) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(onClick = { PodcastPlayer.play(ctx, ep); onOpen(ep.id) }) { Icon(Icons.Default.PlayArrow, "Play") }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(ep.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ep.createdAt))} · ${ep.periodLabel} · ${PodcastStudio.mmss(ep.duration)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirmDelete = ep }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { ep ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete episode?") }, text = { Text(ep.title) },
            confirmButton = { TextButton(onClick = { PodcastStudio.delete(ctx, ep); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun key(v: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(v) { content() }

@Composable
private fun Progress(st: com.aliahad.ainewshot.StudioState) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (st.stage == 7) "Something went wrong" else "Producing your ${if (st.days == 1) "daily" else "${st.days}-day"} episode",
                style = MaterialTheme.typography.titleMedium)
            (1..5).forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        st.stage > i && st.stage != 7 -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E9E4F), modifier = Modifier.size(18.dp))
                        st.stage == i -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else -> Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(PodcastStudio.stageLabels[i], fontWeight = if (st.stage == i) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            if (st.stage == 2 || st.stage == 4) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            Text(st.detail, style = MaterialTheme.typography.bodySmall)
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            st.log.takeLast(4).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (st.stage in 1..5) OutlinedButton(onClick = { PodcastService.cancel(ctx) }) { Text("Cancel") }
            Text("You can leave the app — production continues in the background.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val episodes by PodcastStudio.episodes.collectAsState()
    val ep = episodes.firstOrNull { it.id == id } ?: run { onBack(); return }
    val current by PodcastPlayer.current.collectAsState()
    val playing by PodcastPlayer.isPlaying.collectAsState()
    val pos by PodcastPlayer.position.collectAsState()
    val speed by PodcastPlayer.speed.collectAsState()
    val loaded = current?.id == ep.id
    var tab by remember { mutableIntStateOf(0) }
    var speedMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val curLine = if (loaded) PodcastPlayer.currentLine(ep) else null
    LaunchedEffect(curLine, playing) { if (playing && curLine != null && tab == 0) listState.animateScrollToItem(curLine) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(ep.periodLabel) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ep.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(ep.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${PodcastStudio.mmss(ep.duration)} · ${ep.notes.size} stories from ${ep.itemsScanned} items · " +
                ep.lines.map { it.speaker }.distinct().filter { it != "J" }.sorted().joinToString(" & ") { ep.name(it) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val t = if (loaded) pos else 0.0
            Slider(value = t.toFloat().coerceIn(0f, ep.duration.toFloat()), valueRange = 0f..ep.duration.toFloat().coerceAtLeast(1f),
                onValueChange = { if (loaded) PodcastPlayer.seek(it.toDouble()) else PodcastPlayer.play(ctx, ep, it.toDouble()) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PodcastStudio.mmss(t), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { PodcastPlayer.skip(-15.0) }, enabled = loaded) { Text("−15") }
                FilledIconButton(onClick = { PodcastPlayer.toggle(ctx, ep) }, modifier = Modifier.size(56.dp)) {
                    if (loaded && playing) Text("❚❚", fontWeight = FontWeight.Bold) else Icon(Icons.Default.PlayArrow, "Play", Modifier.size(32.dp))
                }
                TextButton(onClick = { PodcastPlayer.skip(30.0) }, enabled = loaded) { Text("+30") }
                Box {
                    TextButton(onClick = { speedMenu = true }) { Text("${speed}×") }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.8f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f).forEach { r ->
                            DropdownMenuItem(text = { Text("${r}×") }, onClick = { PodcastPlayer.setSpeed(ctx, r); speedMenu = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(PodcastStudio.mmss(ep.duration), style = MaterialTheme.typography.bodySmall)
            }
        }
        SecondaryTabRow(selectedTabIndex = tab) {
            listOf("Transcript", "Chapters", "Show notes").forEachIndexed { i, l -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l) }) }
        }
        when (tab) {
            0 -> LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(ep.lines, key = { _, l -> l.id }) { i, l ->
                    Column {
                        if (i == 0 || ep.lines[i - 1].chapter != l.chapter) ep.chapters.getOrNull(l.chapter)?.let {
                            Text(it.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        Column(Modifier.fillMaxWidth()
                            .background(if (curLine == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { PodcastPlayer.play(ctx, ep, l.start) }.padding(8.dp)) {
                            Text(ep.name(l.speaker), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                color = if (l.speaker == "B") Color(0xFFE07A1F) else MaterialTheme.colorScheme.primary)
                            Text(l.text, style = MaterialTheme.typography.bodyMedium)
                            if (l.direction.isNotBlank()) Text(l.direction, fontSize = 11.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ep.chapters.forEach { c ->
                    Card(Modifier.fillMaxWidth().clickable { PodcastPlayer.play(ctx, ep, c.start) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${PodcastStudio.mmss(c.start)}  ${c.title}", fontWeight = if (loaded && PodcastPlayer.currentChapter(ep) == c.id) FontWeight.Bold else FontWeight.Medium)
                            c.stories.mapNotNull { ep.notes.getOrNull(it) }.forEach { n -> Links(n) }
                        }
                    }
                }
            }
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("deep" to "Deep dives", "brief" to "Briefs", "quick" to "Quick hits", "notes" to "Also happened (not read on air)").forEach { (tier, label) ->
                    val group = ep.notes.filter { it.tier == tier }
                    if (group.isNotEmpty()) {
                        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        group.forEach { n -> Text(n.title, fontWeight = FontWeight.Medium); Links(n) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Links(n: com.aliahad.ainewshot.ShowNote) {
    val ctx = LocalContext.current
    n.links.take(3).forEach { l ->
        Text("${l.source}: ${l.title}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) } }.padding(vertical = 2.dp))
    }
}

@Composable
fun MiniPlayer(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val ep by PodcastPlayer.current.collectAsState()
    val playing by PodcastPlayer.isPlaying.collectAsState()
    val pos by PodcastPlayer.position.collectAsState()
    val e = ep ?: return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onOpen(e.id) }) {
        LinearProgressIndicator(progress = { (pos / e.duration).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(PodStyle.SHOW + " · " + PodcastStudio.mmss(pos), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { PodcastPlayer.toggle(ctx, e) }) {
                if (playing) Text("❚❚", fontWeight = FontWeight.Bold) else Icon(Icons.Default.PlayArrow, "Play")
            }
        }
    }
}
