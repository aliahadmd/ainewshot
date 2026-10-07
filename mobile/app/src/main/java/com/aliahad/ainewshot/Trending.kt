package com.aliahad.ainewshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One entry of AIHOT's hot list (Top 10 events by multi-source coverage, refreshed every ~5 min). */
data class TrendTopic(
    val rank: Int,
    val id: String,              // lead item id
    val storyId: String?,
    val zhTitle: String,
    val title: String,           // English
    val summary: String,         // English, first paragraph of the story's AI digest
    val sourceCount: Int,
    val sourceNames: List<String>,
    val latestAt: Long?,
    val aihotUrl: String?,
    val originalUrl: String?,
    val storyUrl: String?,
)

/** "Trending now": official /api/v1/hot-topics + /api/v1/stories/{id}, translated and cached. */
object Trending {
    val topics = MutableStateFlow<List<TrendTopic>>(emptyList())
    val updated = MutableStateFlow<Long?>(null)
    val error = MutableStateFlow<String?>(null)
    private val lock = Mutex()
    private val digests = ConcurrentHashMap<String, Pair<Long, String>>()     // storyId -> (latestAt, zh digest)
    private val rendered = ConcurrentHashMap<String, Rendered>()              // "$id|$zhTitle" -> English
    /** Last story JSON facts we saw (for follow-ups): storyId -> (latestAt, latest). */
    val storyMeta = ConcurrentHashMap<String, Pair<Long, String?>>()

    suspend fun refresh(ctx: Context, force: Boolean = false): List<TrendTopic> = lock.withLock {
        try {
            val body = Net.get("${AIHot.BASE}/api/v1/hot-topics", conditional = !force || topics.value.isNotEmpty())
            if (body == null) { updated.value = System.currentTimeMillis(); return topics.value }   // 304: unchanged
            val arr = JSONObject(body).getJSONArray("items")
            val raw = (0 until arr.length()).map { arr.getJSONObject(it) }
            // Show the list right away (cached translations where we have them), then fill in the rest.
            if (topics.value.isEmpty()) topics.value = raw.map { toTopic(it) }
            // Story digests (only for stories that changed since we last looked), spaced to be polite.
            for (o in raw) {
                val links = o.optJSONObject("links")
                val sid = links?.str("story")?.substringAfterLast('/') ?: continue
                val latest = parseTime(o.str("latestAt")) ?: 0
                if (digests[sid]?.first == latest) continue
                runCatching {
                    val st = JSONObject(Net.get("${AIHot.BASE}/api/v1/stories/$sid") ?: "{}").optJSONObject("story")
                    val digest = st?.str("digest")?.split("\n")?.firstOrNull { it.isNotBlank() } ?: ""
                    digests[sid] = latest to digest
                }
                delay(400)
            }
            val s = Settings.get(ctx)
            val need = raw.filter { rendered["${it.getString("id")}|${it.optString("title")}"] == null }
            if (need.isNotEmpty()) {
                val src = need.map { o ->
                    val sid = o.optJSONObject("links")?.str("story")?.substringAfterLast('/')
                    ApiItem(id = o.getString("id"), title = o.optString("title"), originalTitle = null,
                        summary = sid?.let { digests[it]?.second }, sourceName = "", aihotUrl = null, originalUrl = null,
                        publishedAt = null, discoveredAt = null, category = null, score = null, reason = null)
                }
                val r = Translator.items(src, s)
                need.forEach { o -> r[o.getString("id")]?.let { rendered["${o.getString("id")}|${o.optString("title")}"] = it } }
            }
            val list = raw.map { toTopic(it) }
            topics.value = list
            updated.value = System.currentTimeMillis()
            error.value = null
            list
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "trending", e)
            error.value = e.message ?: e.toString()
            topics.value
        }
    }

    private fun toTopic(o: JSONObject): TrendTopic {
        val links = o.optJSONObject("links")
        val sid = links?.str("story")?.substringAfterLast('/')
        val r = rendered["${o.getString("id")}|${o.optString("title")}"]
        return TrendTopic(
            rank = o.optInt("rank"), id = o.getString("id"), storyId = sid,
            zhTitle = o.optString("title"), title = r?.title ?: o.optString("title"),
            summary = r?.summary ?: sid?.let { digests[it]?.second } ?: "",
            sourceCount = o.optInt("sourceCount"),
            sourceNames = o.optJSONArray("sourceNames").strings().map { AIHot.cleanSource(it) },
            latestAt = parseTime(o.str("latestAt")),
            aihotUrl = links?.str("aihot"), originalUrl = links?.str("original"),
            storyUrl = sid?.let { "${AIHot.BASE}/story/$it" },
        )
    }

    fun asNewsItem(t: TrendTopic) = NewsItem(
        id = "trend-${t.storyId ?: t.id}", kind = "trending", title = t.title, summary = t.summary, zhTitle = t.zhTitle,
        why = "Trending #${t.rank} · covered by ${t.sourceCount} sources" +
            (if (t.sourceNames.isNotEmpty()) ", including " + t.sourceNames.take(3).joinToString(", ") else "") + ".",
        sourceName = "Trending #${t.rank}", aihotUrl = t.storyUrl ?: t.aihotUrl, originalUrl = t.originalUrl,
        publishedAt = t.latestAt, spoken = "Trending now, across ${t.sourceCount} sources: ${t.title}. ${t.summary}",
        storyId = t.storyId,
    )

    /**
     * Full event view: AI digest, latest development, and the coverage timeline (official sources marked),
     * built as Chinese Markdown from the official story JSON and translated in one pass.
     */
    suspend fun storyDoc(ctx: Context, storyId: String): Doc {
        val st = JSONObject(Net.get("${AIHot.BASE}/api/v1/stories/$storyId") ?: "{}").getJSONObject("story")
        storyMeta[storyId] = (parseTime(st.str("latestAt")) ?: 0L) to st.str("latest")
        val sb = StringBuilder()
        sb.append("# ").append(st.optString("title")).append("\n\n")
        sb.append("**").append(st.optInt("sourceCount")).append(" 个信源 · ").append(st.optInt("reportCount")).append(" 篇报道**\n\n")
        st.str("latest")?.let { sb.append("## 最新进展\n").append(it).append("\n\n") }
        st.str("digest")?.let { sb.append("## 来龙去脉\n").append(it).append("\n\n") }
        val reports = st.optJSONArray("reports") ?: JSONArray()
        if (reports.length() > 0) sb.append("## 报道时间线\n")
        for (i in 0 until minOf(reports.length(), 25)) {
            val r = reports.getJSONObject(i)
            val src = r.optJSONObject("source")
            val official = src?.optBoolean("firstParty") == true
            val url = r.optJSONObject("links")?.let { it.str("original") ?: it.str("aihot") }
            val time = r.str("publishedAt")?.take(16)?.replace('T', ' ') ?: ""
            sb.append("- ").append(time).append(" · ")
            if (url != null) sb.append("[").append(r.optString("title")).append("](").append(url).append(")") else sb.append(r.optString("title"))
            sb.append(" · ").append(AIHot.cleanSource(src?.optString("name") ?: "")).append(if (official) " ✓一手" else "").append("\n")
        }
        return Translator.document(sb.toString(), "event story with a coverage timeline (keep '✓一手' as '✓ official')", Settings.get(ctx))
    }
}
