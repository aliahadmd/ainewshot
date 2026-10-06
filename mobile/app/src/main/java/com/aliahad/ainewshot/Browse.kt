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
 * "Curated" and "All news" tabs: everything AIHOT has from the last 7 days, page by page.
 * Items show immediately (source's English title when there is one), then get translated in the background.
 * Translations are cached on disk, so scrolling back or reopening the app costs nothing.
 */
object Browse {
    data class Feed(
        val items: List<NewsItem> = emptyList(),
        val loading: Boolean = false,
        val end: Boolean = false,
        val error: String? = null,
        val updated: Long? = null,
    )

    val feeds = mapOf("selected" to MutableStateFlow(Feed()), "all" to MutableStateFlow(Feed()))
    private val cursors = ConcurrentHashMap<String, String>()
    private val locks = mapOf("selected" to Mutex(), "all" to Mutex())
    private val cache = ConcurrentHashMap<String, Rendered>()
    private var cacheLoaded = false

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "translations.json")

    private fun loadCache(ctx: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        runCatching {
            val o = JSONObject(cacheFile(ctx).readText())
            for (k in o.keys()) {
                val r = o.getJSONObject(k)
                cache[k] = Rendered(r.optString("t"), r.optString("s"), r.str("w"), r.str("p"), null)
            }
        }
    }

    private fun saveCache(ctx: Context) {
        val o = JSONObject()
        cache.entries.toList().takeLast(3000).forEach { (k, r) ->
            o.put(k, JSONObject().put("t", r.title).put("s", r.summary).put("w", r.why ?: JSONObject.NULL).put("p", r.spoken ?: JSONObject.NULL))
        }
        runCatching { cacheFile(ctx).writeText(o.toString()) }
    }

    fun clearCache(ctx: Context) { cache.clear(); cacheFile(ctx).delete() }
    fun cacheCount(ctx: Context): Int { loadCache(ctx); return cache.size }

    /** Reload from the top (pull to refresh / first open). */
    suspend fun refresh(ctx: Context, mode: String) = load(ctx, mode, reset = true)

    /** Next page when the list is scrolled near the end. */
    suspend fun more(ctx: Context, mode: String) {
        val f = feeds.getValue(mode).value
        if (f.loading || f.end) return
        load(ctx, mode, reset = false)
    }

    private suspend fun load(ctx: Context, mode: String, reset: Boolean) = withContext(Dispatchers.Default) {
        val flow = feeds.getValue(mode)
        locks.getValue(mode).withLock {
            loadCache(ctx)
            if (reset) cursors.remove(mode)
            flow.value = flow.value.copy(loading = true, error = null)
            try {
                val cursor = cursors[mode]
                val url = "${AIHot.BASE}/api/v1/items?limit=40&mode=$mode&window=7d" +
                    (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                val o = JSONObject(Net.get(url) ?: "{}")
                val arr = o.optJSONArray("items")
                val api = (0 until (arr?.length() ?: 0)).map { AIHot.parseV1(arr!!.getJSONObject(it)) }
                val page = o.optJSONObject("page")
                val next = if (page?.optBoolean("hasMore") == true) page.str("nextCursor") else null
                if (next != null) cursors[mode] = next else cursors.remove(mode)
                val fresh = api.map { a -> NewsItem.from(a, cache[a.id]) }
                val merged = if (reset) fresh else (flow.value.items + fresh).distinctBy { it.id }
                flow.value = Feed(merged, loading = false, end = next == null, updated = System.currentTimeMillis())
                translate(ctx, mode, api.filter { !cache.containsKey(it.id) })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "browse $mode", e)
                flow.value = flow.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    private fun translate(ctx: Context, mode: String, todo: List<ApiItem>) {
        val s = Settings.get(ctx)
        if (todo.isEmpty() || s.translation != "deepseek" || s.deepseekKey.isBlank()) return
        NewsRepo.appScope.launch {
            val r = Translator.items(todo, s)
            // Only cache real translations (the fallback just echoes the source).
            todo.forEach { a -> r[a.id]?.takeIf { it.spoken != null }?.let { cache[a.id] = it } }
            val flow = feeds.getValue(mode)
            val byId = todo.associateBy { it.id }
            flow.value = flow.value.copy(items = flow.value.items.map { n -> byId[n.id]?.let { NewsItem.from(it, cache[n.id] ?: r[n.id]) } ?: n })
            withContext(Dispatchers.IO) { saveCache(ctx) }
        }
    }
}
