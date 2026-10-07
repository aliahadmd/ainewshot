package com.aliahad.ainewshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class FeedStatus(
    val checking: Boolean = false,
    val lastCheck: Long? = null,
    val error: String? = null,
    val note: String? = null,
    val held: Int = 0,
    val interruptions: List<Long> = emptyList(),   // watchdog: times live checks stopped for > 20 min
    val lastSync: Long? = null,                    // last successful corrections/retractions sync
)

/**
 * The alert engine.
 *  - Feed: first-hand timeline (= aihot.news 精选 › 只看一手), enriched by the official v1 API; pages back after
 *    being offline; falls back to official API + learned first-hand sources if the site endpoint changes.
 *  - Followed topics: curated stories tagged with a topic you follow are alerted too.
 *  - Trending: optional alerts when a story climbs AIHOT's Top 10 across many sources.
 *  - Corrections & retractions: official selected/snapshot + selected/changes (AIHOT terms §7).
 *  - One alert per event: AIHOT event groups (factId) + a title-similarity check.
 *  - Unread, catch-up and saved stories.
 */
object NewsRepo {
    /** Process-wide scope for background work (kept alive by the live foreground service). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val recent = MutableStateFlow<List<NewsItem>>(emptyList())
    val saved = MutableStateFlow<List<NewsItem>>(emptyList())
    val readIds = MutableStateFlow<Set<String>>(emptySet())
    /** Latest translated morning briefing (its Listen text is used on the Daily tab). */
    val briefing = MutableStateFlow<NewsItem?>(null)
    /** Stories you follow for new developments: storyId -> what we last saw. */
    val followedStories = MutableStateFlow<Map<String, FollowedStory>>(emptyMap())
    val status = MutableStateFlow(FeedStatus())

    private val mutex = Mutex()
    private var loaded = false
    private val seen = LinkedHashSet<String>()
    private val alerted = LinkedHashSet<String>()
    private val curatedSeen = LinkedHashSet<String>()
    private val firstPartySources = LinkedHashSet<String>()
    private val eventOf = mutableMapOf<String, String>()   // item id -> AIHOT factId
    private val pending = mutableListOf<NewsItem>()
    private val popupTimes = mutableListOf<Long>()
    private var bootMode: String? = null
    private var bootDone = false
    private var curatedBoot = false
    private var trendingBoot = false
    private var syncCursor: String? = null
    private var lastPodcastDay: String? = null
    private var lastAttempt: Long? = null      // last time a check ran at all (success or not)
    private var lastBriefingDay: String? = null
    private var lastWeekly: String? = null
    private var lastBriefingTry = 0L
    private var lastStoryCheck = 0L
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
            o.optJSONArray("curatedSeen").strings().forEach { curatedSeen += it }
            o.optJSONArray("firstPartySources").strings().forEach { firstPartySources += it }
            o.optJSONObject("eventOf")?.let { e -> e.keys().forEach { k -> eventOf[k] = e.getString(k) } }
            recent.value = o.optJSONArray("recent").items()
            saved.value = o.optJSONArray("saved").items()
            pending += o.optJSONArray("pending").items()
            readIds.value = o.optJSONArray("read").strings().toSet()
            // Upgrading from a version without read tracking: what's already in the feed counts as seen.
            if (!o.has("read")) readIds.value = recent.value.map { it.id }.toSet()
            bootMode = o.str("bootMode"); bootDone = o.optBoolean("bootDone"); lastPodcastDay = o.str("lastPodcastDay")
            curatedBoot = o.optBoolean("curatedBoot"); trendingBoot = o.optBoolean("trendingBoot"); syncCursor = o.str("syncCursor")
            lastAttempt = if (o.isNull("lastAttempt")) null else o.optLong("lastAttempt").takeIf { it > 0 }
            lastBriefingDay = o.str("lastBriefingDay"); lastWeekly = o.str("lastWeekly")
            o.optJSONObject("briefing")?.let { briefing.value = NewsItem.fromJson(it) }
            o.optJSONObject("followedStories")?.let { f ->
                followedStories.value = f.keys().asSequence().associateWith { FollowedStory.fromJson(f.getJSONObject(it)) }
            }
            val gaps = o.optJSONArray("interruptions")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
            // Gaps recorded before 2.2.0 used "last success", which over-counted network failures: start fresh.
            status.value = status.value.copy(lastCheck = if (o.isNull("lastCheck")) null else o.optLong("lastCheck"),
                interruptions = if (o.has("lastAttempt")) gaps else emptyList())
        }
        status.value = status.value.copy(held = pending.size)
    }

    private fun JSONArray?.items(): List<NewsItem> = if (this == null) emptyList() else (0 until length()).map { NewsItem.fromJson(getJSONObject(it)) }

    private fun save(ctx: Context) {
        val o = JSONObject()
            .put("seen", JSONArray(seen.toList().takeLast(4000)))
            .put("alerted", JSONArray(alerted.toList().takeLast(4000)))
            .put("curatedSeen", JSONArray(curatedSeen.toList().takeLast(3000)))
            .put("firstPartySources", JSONArray(firstPartySources.toList()))
            .put("eventOf", JSONObject(eventOf.entries.toList().takeLast(2000).associate { it.key to it.value }))
            .put("recent", JSONArray(recent.value.take(300).map { it.toJson() }))
            .put("saved", JSONArray(saved.value.take(500).map { it.toJson() }))
            .put("pending", JSONArray(pending.map { it.toJson() }))
            .put("read", JSONArray(readIds.value.toList().takeLast(3000)))
            .put("bootMode", bootMode ?: JSONObject.NULL).put("bootDone", bootDone)
            .put("curatedBoot", curatedBoot).put("trendingBoot", trendingBoot)
            .put("syncCursor", syncCursor ?: JSONObject.NULL)
            .put("lastPodcastDay", lastPodcastDay ?: JSONObject.NULL)
            .put("lastCheck", status.value.lastCheck ?: JSONObject.NULL)
            .put("lastAttempt", lastAttempt ?: JSONObject.NULL)
            .put("lastBriefingDay", lastBriefingDay ?: JSONObject.NULL).put("lastWeekly", lastWeekly ?: JSONObject.NULL)
            .put("briefing", briefing.value?.toJson() ?: JSONObject.NULL)
            .put("followedStories", JSONObject(followedStories.value.mapValues { it.value.toJson() }))
            .put("interruptions", JSONArray(status.value.interruptions.takeLast(50)))
        file(ctx).writeText(o.toString())
    }

    fun persist(ctx: Context) { appScope.launch(Dispatchers.IO) { runCatching { save(ctx) } } }

    /** Runs entirely off the main thread: network on IO, parsing/filtering on Default. */
    suspend fun check(ctx: Context, force: Boolean = false) = withContext(Dispatchers.Default) {
        load(ctx)
        if (!force && System.currentTimeMillis() < backoffUntil) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            val s = Settings.get(ctx)
            // Watchdog: no check *ran* for 30+ min while live alerts are on => the system froze or killed us.
            // (Failed checks, e.g. no network, still count as running; Doze spaces wake-ups ~10-15 min apart.)
            val prev = lastAttempt
            val now = System.currentTimeMillis()
            if (s.liveAlerts && prev != null && now - prev > 30 * 60_000L && status.value.interruptions.lastOrNull() != prev) {
                status.value = status.value.copy(interruptions = (status.value.interruptions + prev).takeLast(50))
                Log.w(TAG, "watchdog: no check ran for ${(now - prev) / 60_000} min")
            }
            lastAttempt = now
            status.value = status.value.copy(checking = true)
            checkNews(ctx)
            runCatching { checkFollowedTopics(ctx) }.onFailure { Log.w(TAG, "topics", it) }
            runCatching { checkTrending(ctx) }.onFailure { Log.w(TAG, "trending alerts", it) }
            runCatching { syncChanges(ctx) }.onFailure { Log.w(TAG, "sync", it) }
            runCatching { checkStoryUpdates(ctx) }.onFailure { Log.w(TAG, "story updates", it) }
            runCatching { maybeBriefing(ctx) }.onFailure { Log.w(TAG, "briefing", it) }
            flushPending(ctx)
            maybeMorningPodcast(ctx)
            withContext(Dispatchers.IO) { save(ctx) }
            Waker.schedule(ctx)
            Speaker.prefetch(ctx, recent.value.take(8))
            runCatching { HeadlinesWidget.refresh(ctx) }
        } finally {
            status.value = status.value.copy(checking = false, held = pending.size)
            mutex.unlock()
        }
    }

    /** Reliability test: a real alert after [seconds], so you can lock the phone or switch apps first. */
    fun scheduleTest(ctx: Context, seconds: Int) {
        val app = ctx.applicationContext
        appScope.launch {
            delay(seconds * 1000L)
            load(app)
            val sample = recent.value.firstOrNull() ?: NewsItem(id = "test", title = "This is a test alert",
                summary = "If you can read and hear this, news will reach you.", sourceName = "AI News Hot")
            deliver(app, listOf(sample.copy(id = "test-${System.currentTimeMillis()}")), bypassRate = true)
        }
    }

    // MARK: First-hand feed

    private suspend fun firstPartyFeed(): List<ApiItem>? {
        return try {
            val first = AIHot.timeline("firstParty") ?: return null
            status.value = status.value.copy(note = null)
            val pages = mutableListOf(first)
            // Back from being offline: page back until we reach a story we already have (max 4 pages).
            if (bootDone) {
                var next = first.next
                while (next != null && pages.size < 4 && pages.last().items.none { it.id in seen }) {
                    delay(700)
                    val p = AIHot.timeline("firstParty", next, conditional = false) ?: break
                    pages += p; next = p.next
                }
            }
            val items = pages.flatMap { it.items }
            firstPartySources += items.map { it.sourceName }
            learnGroups(pages.flatMap { it.groups.entries }.associate { it.key to it.value })
            runCatching { AIHot.items("selected") }.getOrNull()?.forEach { v1Cache[it.id] = it }
            items.map { t ->
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

    /** Map every report in AIHOT's event groups to its factId (looked up once per group). */
    private suspend fun learnGroups(groups: Map<String, Pair<String, Int>>) {
        for ((lead, g) in groups) {
            val (factId, extra) = g
            eventOf[lead] = factId
            if (extra > 0 && eventOf.values.count { it == factId } < 2) {
                runCatching { AIHot.groupMembers(factId).forEach { eventOf[it] = factId } }
                delay(300)
            }
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
            val news = batch.map { withEvent(NewsItem.from(it, rendered[it.id]), it) }
            fresh.forEach { seen += it.id }
            addRecent(news)
            if (bootstrap) { bootDone = true; markRead(news.map { it.id }); return }

            val maxAge = System.currentTimeMillis() - s.maxAlertAgeHours * 3_600_000L
            // With "Live alerts" off the feed still refreshes (backup checks), but nothing pops up.
            val wanted = if (!s.liveAlerts) emptyList() else news.filter { it.discoveredAt >= maxAge && shouldAlert(it, s) }
            val alerts = oncePerEvent(wanted)
            Log.i(TAG, "news: ${fresh.size} new, ${alerts.size} alert(s)" + if (wanted.size > alerts.size) " (${wanted.size - alerts.size} same-event skipped)" else "")
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

    private fun withEvent(n: NewsItem, a: ApiItem? = null): NewsItem =
        n.copy(eventKey = eventOf[n.id] ?: n.eventKey, tags = a?.tags?.takeIf { it.isNotEmpty() } ?: n.tags)

    fun shouldAlert(n: NewsItem, s: Settings): Boolean {
        val hay = (listOf(n.title, n.summary, n.zhTitle, n.zhSummary, n.originalTitle ?: "", n.sourceName) +
            n.tags.flatMap { listOf(it, AIHot.tagEn(it)) }).joinToString(" ").lowercase()
        if (s.keywords(s.excludeKeywords).any { hay.contains(it) }) return false
        if (s.keywords(s.includeKeywords).any { hay.contains(it) }) return true
        if (s.feedMode != "firstParty" && (n.score ?: 100) < s.minScore) return false
        return true
    }

    // MARK: Same event = one alert

    private val stop = setOf("the", "and", "for", "with", "from", "into", "its", "new", "now", "has", "have", "are", "will", "that",
        "this", "how", "what", "can", "via", "over", "after", "says", "launches", "releases", "release", "launch", "announces", "introduces")

    private fun tokens(n: NewsItem) = (n.title + " " + (n.originalTitle ?: "")).lowercase()
        .split(Regex("[^a-z0-9.+-]+")).filter { it.length >= 3 && it !in stop }.toSet()

    /** Same story: same AIHOT event group, or near-identical headlines within 36 h. */
    fun sameEvent(a: NewsItem, b: NewsItem): Boolean {
        if (a.id == b.id) return true
        if (a.eventKey != null && a.eventKey == b.eventKey) return true
        if (kotlin.math.abs(a.discoveredAt - b.discoveredAt) > 36 * 3_600_000L) return false
        val ta = tokens(a); val tb = tokens(b)
        val inter = ta.intersect(tb).size
        if (inter < 3) return false
        return inter.toDouble() / ta.union(tb).size >= 0.5
    }

    /** Drops alerts for stories you were already alerted about; marks them so the feed groups them. */
    private fun oncePerEvent(items: List<NewsItem>): List<NewsItem> {
        val already = recent.value.filter { it.alerted && System.currentTimeMillis() - it.discoveredAt < 48 * 3_600_000L }
        val out = mutableListOf<NewsItem>()
        for (n in items) {
            if (already.any { sameEvent(it, n) } || out.any { sameEvent(it, n) }) { alerted += n.id; continue }
            out += n
        }
        return out
    }

    /** Feed view: one row per event, with the other reports attached. */
    fun grouped(items: List<NewsItem>): List<Pair<NewsItem, List<NewsItem>>> {
        val out = mutableListOf<Pair<NewsItem, MutableList<NewsItem>>>()
        for (n in items) {
            val g = out.firstOrNull { sameEvent(it.first, n) }
            if (g != null) g.second += n else out += n to mutableListOf()
        }
        return out
    }

    // MARK: Followed topics (curated stories)

    private suspend fun checkFollowedTopics(ctx: Context) {
        val s = Settings.get(ctx)
        val followed = s.followed()
        if (followed.isEmpty()) return
        val page = AIHot.timeline(null) ?: return
        learnGroups(page.groups)
        val fresh = page.items.filter { it.id !in curatedSeen && it.id !in seen }
        page.items.forEach { curatedSeen += it.id }
        if (!curatedBoot) { curatedBoot = true; return }
        if (fresh.isEmpty()) return
        val maxAge = System.currentTimeMillis() - s.maxAlertAgeHours * 3_600_000L
        val candidates = fresh.map { withEvent(NewsItem.from(it, null), it) }
            .map { n -> n to Topics.matches(n, followed) }
            .filter { (n, t) -> t.isNotEmpty() && n.discoveredAt >= maxAge }
        if (candidates.isEmpty()) return
        val rendered = Translator.items(candidates.map { (n, _) -> fresh.first { it.id == n.id } }, s)
        val items = candidates.map { (n, t) ->
            val a = fresh.first { it.id == n.id }
            withEvent(NewsItem.from(a, rendered[n.id]), a).copy(topics = t.map { it.label })
        }
        items.forEach { seen += it.id }
        addRecent(items)
        if (!s.liveAlerts) return
        val alerts = oncePerEvent(items.filter { shouldAlert(it, s) })
        Log.i(TAG, "topics: ${items.size} matched, ${alerts.size} alert(s)")
        deliver(ctx, alerts)
    }

    // MARK: Trending alerts

    private suspend fun checkTrending(ctx: Context) {
        val s = Settings.get(ctx)
        if (!s.trendingAlerts) return
        val topics = Trending.refresh(ctx)
        if (topics.isEmpty()) return
        if (!trendingBoot) {
            topics.forEach { t -> alerted += "story:${t.storyId ?: t.id}" }
            trendingBoot = true; return
        }
        if (!s.liveAlerts) return
        val fresh = topics.filter { t ->
            t.rank <= s.trendingTopRank && t.sourceCount >= s.trendingMinSources &&
                "story:${t.storyId ?: t.id}" !in alerted && t.id !in alerted
        }
        if (fresh.isEmpty()) return
        fresh.forEach { alerted += "story:${it.storyId ?: it.id}" }
        fresh.forEach { t -> t.storyId?.let { followStory(ctx, it, t.title, t.latestAt, null) } }
        Log.i(TAG, "trending: ${fresh.size} alert(s)")
        deliver(ctx, fresh.map { Trending.asNewsItem(it) })
    }

    // MARK: Corrections & retractions (official selected/changes)

    private suspend fun syncChanges(ctx: Context) {
        val base = "${AIHot.BASE}/api/v1/selected"
        try {
            if (syncCursor == null) {
                // The first snapshot page carries the sync watermark; we don't need (or keep) the full set.
                syncCursor = JSONObject(Net.get("$base/snapshot?fields=default&limit=1") ?: return).getString("cursor")
                return
            }
            val upserts = mutableListOf<ApiItem>()
            val removed = mutableSetOf<String>()
            for (page in 0 until 5) {
                val o = JSONObject(Net.get("$base/changes?limit=100&cursor=" + URLEncoder.encode(syncCursor, "UTF-8")) ?: break)
                val ch = o.optJSONArray("changes") ?: JSONArray()
                for (i in 0 until ch.length()) {
                    val c = ch.getJSONObject(i)
                    when (c.optString("op")) {
                        "remove" -> c.str("id")?.let { removed += it }
                        "upsert" -> c.optJSONObject("item")?.let { upserts += AIHot.parseV1(it) }
                    }
                }
                syncCursor = o.getString("cursor")     // apply, then advance
                if (!o.optBoolean("hasMore")) break
            }
            if (removed.isNotEmpty()) retract(removed)
            val ours = recent.value.associateBy { it.id }
            val changed = upserts.filter { u -> ours[u.id]?.let { it.zhTitle != u.title || it.zhSummary != (u.summary ?: "") } == true }
            if (changed.isNotEmpty()) {
                val r = Translator.items(changed, Settings.get(ctx))
                val byId = changed.associateBy { it.id }
                recent.value = recent.value.map { n ->
                    byId[n.id]?.let { a ->
                        NewsItem.from(a, r[a.id]).copy(alerted = n.alerted, firstParty = n.firstParty, tags = n.tags,
                            eventKey = n.eventKey, topics = n.topics, discoveredAt = n.discoveredAt)
                    } ?: n
                }
                Log.i(TAG, "sync: ${changed.size} corrected")
            }
            status.value = status.value.copy(lastSync = System.currentTimeMillis())
        } catch (e: HttpError) {
            if (e.code == 409) syncCursor = null    // snapshot_required: re-bootstrap next time
            else throw e
        }
    }

    /** AIHOT withdrew these from its picks: drop them from the feed, held alerts and the popup. */
    private fun retract(ids: Set<String>) {
        recent.value = recent.value.filterNot { it.id in ids }
        pending.removeAll { it.id in ids }
        ids.forEach { Alerts.dismiss(it) }
        Log.i(TAG, "sync: ${ids.size} retracted")
    }

    // MARK: Morning briefing

    /** AIHOT publishes the daily report at 08:00 Beijing; after your chosen time we deliver it once per issue. */
    private suspend fun maybeBriefing(ctx: Context) {
        val s = Settings.get(ctx)
        if (!s.briefingEnabled) return
        val now = java.time.LocalDateTime.now()
        if (now.hour * 60 + now.minute < s.briefingMinutes) return
        if (System.currentTimeMillis() - lastBriefingTry < 20 * 60_000L) return    // one attempt per 20 min
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val weekKey = now.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR).toString() + "-W" + now.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val wantDaily = lastBriefingDay != today
        val wantWeekly = s.briefingWeekly && now.dayOfWeek == java.time.DayOfWeek.MONDAY && now.hour >= 11 && lastWeekly != weekKey
        if (!wantDaily && !wantWeekly) return
        lastBriefingTry = System.currentTimeMillis()
        if (wantDaily) {
            // Deliver each issue once. If today's isn't out yet we get yesterday's id back (null) and retry later.
            val item = makeBriefing(ctx, "api/v1/agent/daily", "Daily", skipId = briefing.value?.id)
            if (item != null) {
                briefing.value = item
                lastBriefingDay = today
                deliver(ctx, listOf(item), bypassRate = true)
            }
        }
        if (wantWeekly) {
            makeBriefing(ctx, "api/v1/agent/weekly", "Weekly", skipId = null)?.let {
                lastWeekly = weekKey
                deliver(ctx, listOf(it), bypassRate = true)
            }
        }
    }

    /** Fetch + translate a report. Returns null (without translating) when the issue is [skipId]. */
    suspend fun makeBriefing(ctx: Context, path: String, kind: String, skipId: String? = null): NewsItem? {
        val md = AIHot.markdown(path)
        if (md.isBlank()) return null
        val issue = Regex("""\d{4}-(\d{2}-\d{2}|W\d{2})""").find(md.lineSequence().firstOrNull() ?: "")?.value
            ?: LocalDate.now(ZoneId.systemDefault()).toString()
        if ("briefing-${kind.lowercase()}-$issue" == skipId) return null
        val doc = Translator.document(md, "${kind.lowercase()} report", Settings.get(ctx))
        val pretty = runCatching { LocalDate.parse(issue).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", java.util.Locale.US)) }.getOrDefault(issue)
        val spoken = doc.spoken.ifBlank { Translator.spokenFallback(doc.markdown) }
        return NewsItem(
            id = "briefing-${kind.lowercase()}-$issue", kind = "briefing",
            title = "Your ${kind.lowercase()} AI briefing · $pretty",
            summary = spoken.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" "),
            spoken = spoken, markdown = doc.markdown, sourceName = "AIHOT $kind",
            aihotUrl = "${AIHot.BASE}/${kind.lowercase()}",
        )
    }

    // MARK: Story follow-ups

    fun followStory(ctx: Context, storyId: String, title: String, latestAt: Long?, latest: String?) {
        val cur = followedStories.value[storyId]
        followedStories.value = followedStories.value + (storyId to FollowedStory(storyId, title, latestAt ?: cur?.latestAt ?: 0,
            latest ?: cur?.latest, cur?.added ?: System.currentTimeMillis()))
        persist(ctx)
    }

    fun unfollowStory(ctx: Context, storyId: String) { followedStories.value = followedStories.value - storyId; persist(ctx) }

    /** Every ~10 min, ask AIHOT (conditional GET, usually a 304) whether followed stories moved on. */
    private suspend fun checkStoryUpdates(ctx: Context) {
        val s = Settings.get(ctx)
        val now = System.currentTimeMillis()
        if (!s.storyFollowUps || followedStories.value.isEmpty() || now - lastStoryCheck < 10 * 60_000L) return
        lastStoryCheck = now
        // Follow a story for 4 days; most news cycles are over by then.
        followedStories.value = followedStories.value.filterValues { now - it.added < 4 * 86_400_000L }
        val updates = mutableListOf<Pair<FollowedStory, JSONObject>>()
        for (f in followedStories.value.values.take(12)) {
            val body = Net.get("${AIHot.BASE}/api/v1/stories/${f.id}", conditional = true) ?: continue
            val st = JSONObject(body).optJSONObject("story") ?: continue
            val latestAt = parseTime(st.str("latestAt")) ?: 0
            val latest = st.str("latest")
            if (f.latest == null) { followedStories.value = followedStories.value + (f.id to f.copy(latestAt = latestAt, latest = latest)); continue }
            if (latestAt > f.latestAt && latest != null && latest != f.latest) updates += f to st
            followedStories.value = followedStories.value + (f.id to f.copy(latestAt = maxOf(latestAt, f.latestAt), latest = latest ?: f.latest))
            delay(300)
        }
        if (updates.isEmpty()) return
        val src = updates.map { (f, st) ->
            ApiItem(id = f.id, title = st.optString("title"), originalTitle = null, summary = st.str("latest"), sourceName = "",
                aihotUrl = null, originalUrl = null, publishedAt = null, discoveredAt = null, category = null, score = null, reason = null)
        }
        val r = Translator.items(src, s)
        val items = updates.map { (f, st) ->
            val en = r[f.id]
            NewsItem(id = "update-${f.id}-${parseTime(st.str("latestAt")) ?: now}", kind = "trending",
                title = "Update: " + (en?.title ?: f.title), summary = en?.summary ?: (st.str("latest") ?: ""),
                why = "Story you follow · now ${st.optInt("sourceCount")} sources, ${st.optInt("reportCount")} reports.",
                sourceName = "Story update", storyId = f.id, aihotUrl = "${AIHot.BASE}/story/${f.id}",
                spoken = "Update on a story you follow: " + (en?.summary ?: ""))
        }
        Log.i(TAG, "story updates: ${items.size}")
        if (s.liveAlerts) deliver(ctx, items)
    }

    // MARK: Unread & saved

    fun unread(): List<NewsItem> = recent.value.filter {
        it.kind == "news" && it.id !in readIds.value && System.currentTimeMillis() - it.discoveredAt < 7 * 86_400_000L
    }

    fun markRead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        readIds.value = readIds.value + ids
    }

    fun markAllRead(ctx: Context) { markRead(recent.value.map { it.id }); persist(ctx) }

    fun isSaved(id: String) = saved.value.any { it.id == id }

    fun toggleSaved(ctx: Context, item: NewsItem) {
        saved.value = if (isSaved(item.id)) saved.value.filterNot { it.id == item.id } else listOf(item) + saved.value
        persist(ctx)
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
        if (pending.isEmpty() || !s.liveAlerts || inQuietHours(s) || popupTimes.size >= s.maxPerHour) return
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

/** A story you follow for new developments (baseline = what AIHOT last said was the latest). */
data class FollowedStory(val id: String, val title: String, val latestAt: Long, val latest: String?, val added: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("latestAt", latestAt)
        .put("latest", latest ?: JSONObject.NULL).put("added", added)

    companion object {
        fun fromJson(o: JSONObject) = FollowedStory(o.getString("id"), o.optString("title"), o.optLong("latestAt"), o.str("latest"), o.optLong("added"))
    }
}
