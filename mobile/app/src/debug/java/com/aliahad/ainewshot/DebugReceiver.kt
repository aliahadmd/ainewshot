package com.aliahad.ainewshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_ALERT [--ei count 3] -p com.aliahad.ainewshot */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                NewsRepo.load(ctx)
                when (intent.action) {
                    "com.aliahad.ainewshot.DEBUG_PODCAST" -> PodcastService.start(ctx, intent.getIntExtra("days", 1), announce = true)
                    "com.aliahad.ainewshot.DEBUG_PLAY" -> { PodcastStudio.load(ctx); PodcastStudio.episodes.value.firstOrNull()?.let { PodcastPlayer.play(ctx, it) } }
                    "com.aliahad.ainewshot.DEBUG_PAUSE" -> PodcastPlayer.pause()
                    "com.aliahad.ainewshot.DEBUG_BRIEFING" -> CoroutineScope(Dispatchers.Default).launch {   // outlives the broadcast
                        NewsRepo.makeBriefing(ctx, "api/v1/agent/daily", "Daily")?.let {
                            NewsRepo.briefing.value = it; NewsRepo.persist(ctx)
                            NewsRepo.deliver(ctx, listOf(it.copy(id = it.id + "-debug" + System.currentTimeMillis())), bypassRate = true)
                        }
                    }
                    else -> {
                        val n = intent.getIntExtra("count", 1)
                        val items = NewsRepo.recent.value.take(n).map { it.copy(id = "debug-${it.id}-${System.currentTimeMillis()}") }
                        NewsRepo.deliver(ctx, items, bypassRate = true)
                    }
                }
            } finally { pending.finish() }
        }
    }
}
