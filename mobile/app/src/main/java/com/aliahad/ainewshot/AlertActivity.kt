package com.aliahad.ainewshot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import com.aliahad.ainewshot.ui.AppTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aliahad.ainewshot.ui.Pill
import com.aliahad.ainewshot.ui.ago
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The "forced popup": a dimmed full-screen card that also shows over the lock screen and wakes the display. */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            AppTheme {
                AlertScreen(onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun AlertScreen(onFinish: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val queue by Alerts.queue.collectAsState()
    val s = Settings.get(ctx)
    var index by remember { mutableIntStateOf(0) }
    var digest by remember { mutableStateOf(queue.size >= s.digestThreshold) }
    var armed by remember { mutableStateOf(false) }   // ignore stray taps for a moment after popping up
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true; delay(800); armed = true }
    LaunchedEffect(queue.isEmpty()) { if (queue.isEmpty()) onFinish() }
    if (queue.isEmpty()) return
    if (index > queue.lastIndex) index = queue.lastIndex

    fun dismiss(item: NewsItem) { Speaker.stop(); Alerts.dismiss(item.id); NewsRepo.markRead(listOf(item.id)); NewsRepo.persist(ctx) }
    fun dismissAll() { Speaker.stop(); Alerts.clear() }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        .systemBarsPadding(), contentAlignment = Alignment.Center) {
        val maxCard = maxHeight * 0.9f
        AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.92f)) {
            Card(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = maxCard), shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                if (digest && queue.size > 1) DigestBody(queue, armed, onOpen = { index = it; digest = false; Speaker.stop() },
                    onListen = { Speaker.toggle(ctx, Speaker.digest(queue)) }, onDismissAll = { dismissAll() })
                else CardBody(ctx, queue[index], index, queue.size, armed,
                    onPrev = { if (index > 0) { Speaker.stop(); index-- } },
                    onNext = { if (index < queue.lastIndex) { Speaker.stop(); index++ } },
                    onDismiss = { dismiss(queue[index]) },
                    onDismissAll = { dismissAll() },
                    onLater = { item -> dismiss(item); LaterWorker.schedule(ctx, item, 30) })
            }
        }
    }
}

@Composable
private fun CardBody(ctx: Context, item: NewsItem, index: Int, count: Int, armed: Boolean,
                     onPrev: () -> Unit, onNext: () -> Unit, onDismiss: () -> Unit, onDismissAll: () -> Unit, onLater: (NewsItem) -> Unit) {
    val playing by Speaker.playingId.collectAsState()
    val preparing by Speaker.preparingId.collectAsState()
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (item.kind) {
                "podcast" -> Pill("🎙 Podcast ready", Color(0x339C27B0), Color(0xFF9C27B0))
                "trending" -> Pill("🔥 Trending", Color(0x33FF9800), Color(0xFFE65100))
                "briefing" -> Pill("Briefing")
                else -> Pill("⚡ " + AIHot.categoryLabel(item.category))
            }
            if (item.firstParty) Pill("✓ Official", Color(0x3322AA55), Color(0xFF1E8E3E))
            item.topics.firstOrNull()?.let { Pill("★ $it") }
            Box(Modifier.weight(1f))
            if (count > 1) {
                Text("‹", fontSize = 22.sp, modifier = Modifier.clickable(enabled = index > 0, onClick = onPrev).padding(horizontal = 8.dp))
                Text("${index + 1}/$count", style = MaterialTheme.typography.labelMedium)
                Text("›", fontSize = 22.sp, modifier = Modifier.clickable(enabled = index < count - 1, onClick = onNext).padding(horizontal = 8.dp))
            }
            Text("✕", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onDismissAll).padding(start = 6.dp, end = 2.dp))
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        item.originalTitle?.takeIf { it != item.title && Translator.isLatin(it) }?.let {
            Text("“$it”", fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodyLarge)
        item.why?.let {
            Text("✦ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(10.dp))
        }
        Text(listOfNotNull(item.sourceName, ago(item.publishedAt ?: item.discoveredAt), item.score?.let { "score $it" },
            item.tags.take(2).joinToString(", ") { AIHot.tagEn(it) }.ifBlank { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.kind == "podcast") {
                Button(enabled = armed, onClick = {
                    PodcastStudio.episodes.value.firstOrNull { it.id == item.episodeId }?.let { PodcastPlayer.play(ctx, it) }
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("episode", item.episodeId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("Play podcast") }
            } else {
                Button(enabled = armed, onClick = {
                    if (item.kind == "briefing") {
                        NewsRepo.briefing.value = item
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("briefing", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } else if (item.kind == "trending" && item.storyId != null) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("story", item.storyId).putExtra("title", item.title)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } else {
                        // The in-app article: full text in English, with the original one tap away.
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("item", item.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text(when (item.kind) { "trending" -> "Read story"; "briefing" -> "Read briefing"; else -> "Read" }) }
                FilledTonalButton(enabled = armed, onClick = { Speaker.toggle(ctx, item) }) {
                    Text(when { preparing == item.id -> "…"; playing == item.id -> "Stop"; else -> "Listen" })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = armed, onClick = { onLater(item) }, modifier = Modifier.weight(1f)) { Text("In 30 min") }
            OutlinedButton(enabled = armed, onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DigestBody(queue: List<NewsItem>, armed: Boolean, onOpen: (Int) -> Unit, onListen: () -> Unit, onDismissAll: () -> Unit) {
    val playing by Speaker.playingId.collectAsState()
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("⚡ ${queue.size} new AI stories", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(queue, key = { _, it -> it.id }) { i, item ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .clickable(enabled = armed) { onOpen(i) }.padding(12.dp)) {
                    Text(item.title, fontWeight = FontWeight.Medium)
                    Text(item.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = armed, onClick = onListen, modifier = Modifier.weight(1f)) {
                Text(if (playing?.startsWith("digest") == true) "Stop" else "Listen to rundown")
            }
            Button(enabled = armed, onClick = { onOpen(0) }) { Text("Step through") }
        }
        TextButton(enabled = armed, onClick = onDismissAll, modifier = Modifier.align(Alignment.End)) { Text("Dismiss all") }
    }
}

/** "In 30 min": bring an alert back later. */
class LaterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val item = NewsItem.fromJson(JSONObject(inputData.getString("item") ?: return Result.success()))
        NewsRepo.route(applicationContext, listOf(item), bypassRate = true)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, item: NewsItem, minutes: Long) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<LaterWorker>()
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf("item" to item.copy(markdown = null).toJson().toString())).build())
        }
    }
}
