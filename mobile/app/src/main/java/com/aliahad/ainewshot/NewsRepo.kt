package com.aliahad.ainewshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class FeedStatus(
    val checking: Boolean = false,
    val lastCheck: Long? = null,
    val error: String? = null,
    val note: String? = null,
    val held: Int = 0,
)

/**
 * Polls AIHOT, translates, filters and routes alerts. Mirrors the macOS engine:
 * first-hand feed (= web 精选 › 只看一手) enriched by the official v1 API, a learned-source fallback,
 * quiet start on first run, no stale alerts, quiet hours and a popups-per-hour cap.
 */
object NewsRepo {
    val recent = MutableStateFlow<List<NewsItem>>(emptyList())
    val status = MutableStateFlow(FeedStatus())

    private val mutex = Mutex()
    private var loaded = false
    private val seen = LinkedHashSet<String>()
    private val alerted = LinkedHashSet<String>()
    private val firstPartySources = LinkedHashSet<String>()
    private val pending = mutableListOf<NewsItem>()
    private val popupTimes = mutableListOf<Long>()
    private var bootMode: String? = null
    private var bootDone = false
    private var lastPodcastDay: String? = null
    private val v1Cache = mutableMapOf<String, ApiItem>()
    private var backoffUntil = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, "state.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            o.optJSONArray("seen").strings().forEach { seen += it }
            o.optJSONArray("alerted").strings().forEach { alerted += it }
            o.optJSONArray("firstPartySources").strings().forEach { firstPartySources += it }
            val r = o.optJSONArray("recent") ?: JSONArray()
            recent.value = (0 until r.length()).map { NewsItem.fromJson(r.getJSONObject(it)) }
            val p = o.optJSONArray("pending") ?: JSONArray()
            pending += (0 until p.length()).map { NewsItem.fromJson(p.getJSONObject(it)) }
            bootMode = o.str("bootMode"); bootDone = o.optBoolean("bootDone"); lastPodcastDay = o.str("lastPodcastDay")
        }
        status.value = status.value.copy(held = pending.size)
    }

    private fun save(ctx: Context) {
        val o = JSONObject()
            .put("seen", JSONArray(seen.toList().takeLast(4000)))
            .put("alerted", JSONArray(alerted.toList().takeLast(4000)))
            .put("firstPartySources", JSONArray(firstPartySources.toList()))
            .put("recent", JSONArray(recent.value.take(300).map { it.toJson() }))
            .put("pending", JSONArray(pending.map { it.toJson() }))
            .put("bootMode", bootMode ?: JSONObject.NULL).put("bootDone", bootDone)
            .put("lastPodcastDay", lastPodcastDay ?: JSONObject.NULL)
        file(ctx).writeText(o.toString())
    }

    suspend fun check(ctx: Context, force: Boolean = false) {
        load(ctx)
        if (!force && System.currentTimeMillis() < backoffUntil) return
        if (!mutex.tryLock()) return
        try {
            status.value = status.value.copy(checking = true)
            checkNews(ctx)
            flushPending(ctx)
            maybeMorningPodcast(ctx)
            save(ctx)
        } finally {
            status.value = status.value.copy(checking = false, held = pending.size)
            mutex.unlock()
        }
    }

    private suspend fun firstPartyFeed(): List<ApiItem>? {
        return try {
            val page = AIHot.firstPartyPage() ?: return null
            status.value = status.value.copy(note = null)
            firstPartySources += page.first.map { it.sourceName }
            runCatching { AIHot.items("selected") }.getOrNull()?.forEach { v1Cache[it.id] = it }
            page.first.map { t ->
                val v = v1Cache[t.id]
                if (v != null) v.copy(tags = t.tags, firstParty = true, discoveredAt = v.discoveredAt ?: t.discoveredAt) else t
            }
        } catch (e: HttpError) {
            if (e.code == 429) throw e
            fallback(e)
        } catch (e: org.json.JSONException) {
            fallback(e)
        }
    }

    /** Site endpoint changed or blocked: official API filtered by the first-hand sources learned so far. */
    private suspend fun fallback(e: Throwable): List<ApiItem>? {
        Log.w(TAG, "first-hand timeline failed", e)
        status.value = status.value.copy(
            note = if (firstPartySources.isEmpty()) "First-hand feed unavailable; showing all curated news"
            else "First-hand feed unavailable; filtering by ${firstPartySources.size} known official sources")
        val v1 = AIHot.items("selected") ?: return null
        return if (firstPartySources.isEmpty()) v1 else v1.filter { it.sourceName in firstPartySources }.map { it.copy(firstParty = true) }
    }

    private suspend fun checkNews(ctx: Context) {
        val s = Settings.get(ctx)
        try {
            val items = if (s.feedMode == "firstParty") firstPartyFeed() else AIHot.items(s.feedMode)
            status.value = status.value.copy(lastCheck = System.currentTimeMillis(), error = null)
            items ?: return
            val fresh = items.filter { it.id !in seen }
            if (fresh.isEmpty()) return
            if (bootMode != s.feedMode) { bootDone = false; bootMode = s.feedMode }
            val bootstrap = !bootDone
            val batch = if (bootstrap) fresh.take(12) else fresh
            val rendered = Translator.items(batch, s)
            val news = batch.map { NewsItem.from(it, rendered[it.id]) }
            fresh.forEach { seen += it.id }
            addRecent(news)
            if (bootstrap) { bootDone = true; return }

            val maxAge = System.currentTimeMillis() - s.maxAlertAgeHours * 3_600_000L
            val alerts = news.filter { it.discoveredAt >= maxAge && shouldAlert(it, s) }
            Log.i(TAG, "news: ${fresh.size} new, ${alerts.size} alert(s)")
            deliver(ctx, alerts)
        } catch (e: HttpError) {
            status.value = status.value.copy(error = e.message)
            if (e.code == 429) backoffUntil = System.currentTimeMillis() + (e.retryAfter ?: 60) * 1000
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            status.value = status.value.copy(error = e.message ?: e.toString())
            Log.w(TAG, "check failed", e)
        }
    }

    fun shouldAlert(n: NewsItem, s: Settings): Boolean {
        val hay = (listOf(n.title, n.summary, n.zhTitle, n.zhSummary, n.originalTitle ?: "", n.sourceName) +
            n.tags.flatMap { listOf(it, AIHot.tagEn(it)) }).joinToString(" ").lowercase()
        if (s.keywords(s.excludeKeywords).any { hay.contains(it) }) return false
        if (s.keywords(s.includeKeywords).any { hay.contains(it) }) return true
        if ((n.score ?: 100) < s.minScore) return false
        return true
    }

    // MARK: Routing

    suspend fun deliver(ctx: Context, items: List<NewsItem>, bypassRate: Boolean = false) {
        if (items.isEmpty()) return
        items.forEach { alerted += it.id }
        recent.value = recent.value.map { if (it.id in alerted) it.copy(alerted = true) else it }
        val s = Settings.get(ctx)
        // Pre-render speech so the voice starts the moment the popup appears (MiMo takes 5-20 s).
        if (s.audioEnabled && s.voiceProvider == "mimo") {
            withTimeoutOrNull(35_000) {
                coroutineScope { items.take(4).map { async { Speaker.prepare(ctx, it) } }.awaitAll() }
            }
        }
        route(ctx, items, bypassRate)
    }

    fun route(ctx: Context, items: List<NewsItem>, bypassRate: Boolean = false) {
        val s = Settings.get(ctx)
        val now = System.currentTimeMillis()
        popupTimes.removeAll { it < now - 3_600_000 }
        if (inQuietHours(s) || (!bypassRate && popupTimes.size >= s.maxPerHour)) {
            pending += items.filter { i -> pending.none { it.id == i.id } }
            status.value = status.value.copy(held = pending.size)
            return
        }
        popupTimes += now
        Alerts.present(ctx, items)
    }

    private fun flushPending(ctx: Context) {
        val s = Settings.get(ctx)
        val now = System.currentTimeMillis()
        popupTimes.removeAll { it < now - 3_600_000 }
        if (pending.isEmpty() || inQuietHours(s) || popupTimes.size >= s.maxPerHour) return
        val items = pending.toList()
        pending.clear()
        popupTimes += now
        Alerts.present(ctx, items)
    }

    fun inQuietHours(s: Settings): Boolean {
        if (!s.quietEnabled) return false
        val t = LocalTime.now(); val m = t.hour * 60 + t.minute
        return if (s.quietStart <= s.quietEnd) m >= s.quietStart && m < s.quietEnd else m >= s.quietStart || m < s.quietEnd
    }

    /** Morning podcast, produced in the background so it's ready when you pick up the phone. */
    private fun maybeMorningPodcast(ctx: Context) {
        val s = Settings.get(ctx)
        if (!s.podAuto) return
        val now = java.time.LocalDateTime.now()
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        if (now.hour * 60 + now.minute < s.podAutoMinutes || lastPodcastDay == today || PodcastStudio.busy) return
        lastPodcastDay = today
        val days = if (now.dayOfWeek == java.time.DayOfWeek.MONDAY && s.podAutoWeeklyOnMonday) 7 else 1
        PodcastService.start(ctx, days, announce = true)
    }

    private fun addRecent(items: List<NewsItem>) {
        val ids = items.map { it.id }.toSet()
        recent.value = (items + recent.value.filter { it.id !in ids }).sortedByDescending { it.discoveredAt }.take(300)
    }
}
