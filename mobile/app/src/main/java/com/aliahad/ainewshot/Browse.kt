package com.aliahad.ainewshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The Selected / All feeds, exactly as aihot.news filters them (First-hand, Models, Products, … chips), page by page.
 * Items show immediately (source's English title when there is one), then get translated in the background.
 *
 * Every translation in the app (feed, hot list, stories, daily, topics) goes through one disk cache keyed by
 * AIHOT item id, so an item is translated once no matter where you meet it.
 */
object Browse {
    data class Feed(
        val items: List<NewsItem> = emptyList(),
        val loading: Boolean = false,
        val end: Boolean = false,
        val error: String? = null,
        val updated: Long? = null,
        val dayCounts: Map<String, Int> = emptyMap(),   // yyyy-MM-dd (Beijing) -> items that day, as the site shows
    )

    /**
     * view = selected (精选) | all (全部动态); filter = the site's single-choice chip: "all", "firstParty" (一手)
     * or a category id. Note 一手 differs by view: selected first-party posts (~10/day, our alerts) vs every
     * first-party post AIHOT collected.
     */
    fun key(view: String, filter: String) = "$view|$filter"

    private val feeds = ConcurrentHashMap<String, MutableStateFlow<Feed>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cursors = ConcurrentHashMap<String, String>()
    fun feed(key: String): MutableStateFlow<Feed> = feeds.getOrPut(key) { MutableStateFlow(Feed()) }

    // MARK: Shared translation cache

    private val cache = ConcurrentHashMap<String, Rendered>()
    @Volatile private var cacheLoaded = false
    private val cacheLock = Any()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "translations.json")

    private fun loadCache(ctx: Context) {
        if (cacheLoaded) return
        synchronized(cacheLock) {
            if (cacheLoaded) return
            runCatching {
                val o = JSONObject(cacheFile(ctx).readText())
                for (k in o.keys()) {
                    val r = o.getJSONObject(k)
                    cache[k] = Rendered(r.optString("t"), r.optString("s"), r.str("w"), r.str("p"), r.intOrNull("r"))
                }
            }
            cacheLoaded = true
        }
    }

    private suspend fun saveCache(ctx: Context) = withContext(Dispatchers.IO) {
        val o = JSONObject()
        cache.entries.toList().takeLast(8000).forEach { (k, r) ->
            o.put(k, JSONObject().put("t", r.title).put("s", r.summary).put("w", r.why ?: JSONObject.NULL)
                .put("p", r.spoken ?: JSONObject.NULL).put("r", r.relevance ?: JSONObject.NULL))
        }
        synchronized(cacheLock) { runCatching { cacheFile(ctx).writeText(o.toString()) } }
    }

    fun clearCache(ctx: Context) { cache.clear(); cacheFile(ctx).delete(); Articles.clear(ctx) }
    fun cacheCount(ctx: Context): Int { loadCache(ctx); return cache.size }

    fun cached(ctx: Context, id: String): Rendered? { loadCache(ctx); return cache[id] ?: fromRepo(id) }

    /** Items the alert pipeline already translated. */
    private fun fromRepo(id: String): Rendered? =
        (NewsRepo.recent.value.firstOrNull { it.id == id } ?: NewsRepo.saved.value.firstOrNull { it.id == id })
            ?.takeIf { it.spoken != null }?.let { Rendered(it.title, it.summary, it.why, it.spoken, it.relevance) }

    /** Translations for [src], from the cache where possible; only the rest go to DeepSeek. */
    suspend fun rendered(ctx: Context, src: List<ApiItem>): Map<String, Rendered> {
        loadCache(ctx)
        val out = mutableMapOf<String, Rendered>()
        val todo = mutableListOf<ApiItem>()
        for (a in src.distinctBy { it.id }) (cache[a.id] ?: fromRepo(a.id))?.let { out[a.id] = it } ?: todo.add(a)
        if (todo.isEmpty()) return out
        val s = Settings.get(ctx)
        val r = Translator.items(todo, s)
        var changed = false
        todo.forEach { a -> r[a.id]?.let { out[a.id] = it; if (it.spoken != null) { cache[a.id] = it; changed = true } } }
        if (changed) saveCache(ctx)
        return out
    }

    /** Short Chinese strings -> English (cached by text). */
    suspend fun plain(ctx: Context, texts: List<String>): List<String> {
        loadCache(ctx)
        fun k(t: String) = "p:${t.hashCode()}:${t.length}"
        val todo = texts.filter { it.isNotBlank() && !Translator.isLatin(it) && cache[k(it)] == null }.distinct()
        if (todo.isNotEmpty()) {
            val s = Settings.get(ctx)
            val en = Translator.plain(todo, s)
            if (s.translation == "deepseek" && s.deepseekKey.isNotBlank()) {
                todo.zip(en).forEach { (zh, e) -> if (e != zh) cache[k(zh)] = Rendered(e, "", null, "", null) }
                saveCache(ctx)
            }
            return texts.map { t -> cache[k(t)]?.title ?: todo.zip(en).firstOrNull { it.first == t }?.second ?: t }
        }
        return texts.map { t -> cache[k(t)]?.title ?: t }
    }

    // MARK: Feeds

    suspend fun refresh(ctx: Context, key: String) = load(ctx, key, reset = true)

    suspend fun more(ctx: Context, key: String) {
        val f = feed(key).value
        if (f.loading || f.end) return
        load(ctx, key, reset = false)
    }

    private suspend fun load(ctx: Context, key: String, reset: Boolean) = withContext(Dispatchers.Default) {
        val flow = feed(key)
        locks.getOrPut(key) { Mutex() }.withLock {
            loadCache(ctx)
            if (reset) cursors.remove(key)
            if (!reset && flow.value.end) return@withLock
            flow.value = flow.value.copy(loading = true, error = null)
            try {
                val (view, filter) = key.split("|").let { it[0] to it[1] }
                val channel = if (filter == "firstParty") "firstParty" else null
                val category = filter.takeIf { it != "all" && it != "firstParty" }
                val cursor = cursors[key]
                val api: List<ApiItem>
                val next: String?
                var days = emptyMap<String, Int>()
                if (view == "selected") {
                    val page = AIHot.timeline(channel, cursor, conditional = false, category = category) ?: AIHot.TimelinePage(emptyList(), null, emptyMap())
                    api = page.items; next = page.next; days = page.dayCounts
                } else if (channel != null || allPagesWork) {
                    // aihot.news/all, read from the page's own data (the official API has no first-hand filter here).
                    val page = runCatching { allPage(channel, category, cursor?.toInt() ?: 1) }.getOrElse { e ->
                        if (channel != null) throw e
                        allPagesWork = false; null
                    }
                    if (page != null) {
                        api = page.first; next = page.second
                        page.third?.let { days = mapOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString() to it) }
                    } else { val r = v1All(category, cursor); api = r.first; next = r.second }
                } else {
                    val r = v1All(category, cursor); api = r.first; next = r.second
                }
                if (next != null) cursors[key] = next else cursors.remove(key)
                val fresh = api.map { a -> NewsItem.from(a, cache[a.id] ?: fromRepo(a.id)) }
                val merged = if (reset) fresh else (flow.value.items + fresh).distinctBy { it.id }
                val dayCounts = if (reset) days else flow.value.dayCounts + days.filterKeys { it !in flow.value.dayCounts }
                flow.value = Feed(merged, loading = false, end = next == null, updated = System.currentTimeMillis(), dayCounts = dayCounts)
                translate(ctx, key, api.filter { !cache.containsKey(it.id) && fromRepo(it.id) == null })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "browse $key", e)
                flow.value = flow.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    @Volatile private var allPagesWork = true

    /** One page (40) of aihot.news/all: items, next page number, and today's total ("360 articles"). */
    private suspend fun allPage(channel: String?, category: String?, page: Int): Triple<List<ApiItem>, String?, Int?> {
        val q = listOfNotNull(channel?.let { "channel=$it" }, category?.let { "category=$it" }, if (page > 1) "page=$page" else null).joinToString("&")
        val root = TurboStream.decode(Net.get("${AIHot.BASE}/all.data" + (if (q.isNotEmpty()) "?$q" else "")) ?: "") as JSONObject
        val d = root.getJSONObject("routes/all").getJSONObject("data").let { it.optJSONObject("data") ?: it }
        val arr = d.getJSONArray("items")
        val items = (0 until arr.length()).map { AIHot.parseTimeline(arr.getJSONObject(it), firstParty = channel == "firstParty") }
        val next = if (d.optInt("page", page) < d.optInt("pageCount", page)) (page + 1).toString() else null
        return Triple(items, next, if (d.has("todayCount")) d.optInt("todayCount") else null)
    }

    /** Fallback for the All view: the official API (same items, no first-hand filter). */
    private suspend fun v1All(category: String?, cursor: String?): Pair<List<ApiItem>, String?> {
        val url = "${AIHot.BASE}/api/v1/items?limit=40&mode=all&window=7d" + (category?.let { "&category=$it" } ?: "") +
            (cursor?.takeIf { it.toIntOrNull() == null }?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val o = JSONObject(Net.get(url) ?: "{}")
        val arr = o.optJSONArray("items")
        val pg = o.optJSONObject("page")
        return (0 until (arr?.length() ?: 0)).map { AIHot.parseV1(arr!!.getJSONObject(it)) } to
            (if (pg?.optBoolean("hasMore") == true) pg.str("nextCursor") else null)
    }

    private fun translate(ctx: Context, key: String, todo: List<ApiItem>) {
        val s = Settings.get(ctx)
        if (todo.isEmpty() || s.translation != "deepseek" || s.deepseekKey.isBlank()) return
        NewsRepo.appScope.launch {
            val r = rendered(ctx, todo)
            val flow = feed(key)
            val byId = todo.associateBy { it.id }
            flow.value = flow.value.copy(items = flow.value.items.map { n -> byId[n.id]?.let { NewsItem.from(it, r[n.id]) } ?: n })
        }
    }

    /**
     * Search the last 7 days of all AI news (official `q=` parameter). AIHOT's text is Chinese, so an English
     * query is also expanded into its Chinese equivalent and both searches are merged.
     */
    suspend fun search(ctx: Context, query: String): List<NewsItem> = withContext(Dispatchers.Default) {
        loadCache(ctx)
        val s = Settings.get(ctx)
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val queries = mutableListOf(q)
        if (Translator.isLatin(q) && s.deepseekKey.isNotBlank()) runCatching {
            val zh = DeepSeek(s.deepseekKey, "deepseek-flash").chat(
                "Translate this AI-news search query into the short Simplified Chinese keyword(s) a Chinese AI news site would use. " +
                    "Keep product, company and model names in their original form. Reply with the keywords only.", q, json = false, maxTokens = 30)
                .trim().trim('"', '“', '”')
            if (zh.isNotBlank() && zh != q && zh.length <= 40) queries += zh
        }
        val found = LinkedHashMap<String, ApiItem>()
        for (term in queries) {
            val url = "${AIHot.BASE}/api/v1/items?limit=40&mode=all&window=7d&q=" + URLEncoder.encode(term, "UTF-8")
            val arr = runCatching { JSONObject(Net.get(url) ?: "{}").optJSONArray("items") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) AIHot.parseV1(arr.getJSONObject(i)).let { found.putIfAbsent(it.id, it) }
        }
        val api = found.values.sortedByDescending { it.discoveredAt ?: 0 }
        val r = rendered(ctx, api)
        api.map { NewsItem.from(it, r[it.id]) }
    }
}
