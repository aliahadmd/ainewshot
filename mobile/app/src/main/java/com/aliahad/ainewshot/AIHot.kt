package com.aliahad.ainewshot

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/** One item as returned by AIHOT (Chinese), before translation. */
data class ApiItem(
    val id: String,
    val title: String,
    val originalTitle: String?,
    val summary: String?,
    val sourceName: String,
    val aihotUrl: String?,
    val originalUrl: String?,
    val publishedAt: Long?,
    val discoveredAt: Long?,
    val category: String?,
    val score: Int?,
    val reason: String?,
    val selected: Boolean = false,
    val tags: List<String> = emptyList(),
    val firstParty: Boolean = false,
)

fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
fun parseTime(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Anonymous, read-only client for https://aihot.news (official v1 API + the site's first-hand timeline). */
object AIHot {
    const val BASE = "https://aihot.news"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun parseV1(o: JSONObject): ApiItem {
        val links = o.optJSONObject("links")
        return ApiItem(
            id = o.getString("id"),
            title = o.optString("title"),
            originalTitle = o.str("originalTitle"),
            summary = o.str("summary"),
            sourceName = o.optJSONObject("source")?.optString("name") ?: "",
            aihotUrl = links?.str("aihot"),
            originalUrl = links?.str("original"),
            publishedAt = parseTime(o.str("publishedAt")),
            discoveredAt = parseTime(o.str("discoveredAt")),
            category = o.str("category"),
            score = o.intOrNull("score"),
            reason = o.str("reason"),
            selected = o.optBoolean("selected", false),
        )
    }

    /** Item from the site timeline behind https://aihot.news/?channel=firstParty (undocumented). */
    fun parseTimeline(o: JSONObject, firstParty: Boolean = true): ApiItem {
        val id = o.getString("id")
        return ApiItem(
            id = id,
            title = o.optString("title"),
            originalTitle = null,
            summary = o.str("summary"),
            sourceName = o.optJSONObject("source")?.optString("name") ?: "",
            aihotUrl = "$BASE/items/$id",
            originalUrl = null,
            publishedAt = parseTime(o.str("publishedAt")),
            discoveredAt = parseTime(o.str("timelineAt")),
            category = o.str("category"),
            score = o.intOrNull("score"),
            reason = o.str("reason"),
            selected = true,
            tags = o.optJSONArray("tags").strings(),
            firstParty = firstParty,
        )
    }

    /** A page of the site timeline. `groups` maps a card's lead item to AIHOT's event group (same story, several reports). */
    data class TimelinePage(val items: List<ApiItem>, val next: String?, val groups: Map<String, Pair<String, Int>>)

    /** channel = "firstParty" for 只看一手, null for all curated (精选). */
    suspend fun timeline(channel: String?, cursor: String? = null, conditional: Boolean = true): TimelinePage? {
        val q = listOfNotNull(channel?.let { "channel=$it" }, cursor?.let { "cursor=" + enc(it) }).joinToString("&")
        val body = Net.get("$BASE/api/site/timeline" + (if (q.isNotEmpty()) "?$q" else ""), conditional) ?: return null
        val o = JSONObject(body)
        val cards = o.getJSONArray("cards")
        val items = mutableListOf<ApiItem>()
        val groups = mutableMapOf<String, Pair<String, Int>>()
        for (i in 0 until cards.length()) {
            val c = cards.getJSONObject(i)
            val it = parseTimeline(c.getJSONObject("item"), firstParty = channel == "firstParty")
            items += it
            c.optJSONObject("group")?.let { g -> g.str("factId")?.let { f -> groups[it.id] = f to g.optInt("additionalSourceCount") } }
        }
        return TimelinePage(items, o.str("nextCursor"), groups)
    }

    /** Ids of every report AIHOT merged into one event. */
    suspend fun groupMembers(factId: String): List<String> {
        val o = JSONObject(Net.get("$BASE/api/site/groups/${enc(factId)}/reports") ?: "{}")
        val arr = o.optJSONArray("reports") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.getJSONObject(it).let { r -> (r.optJSONObject("item") ?: r).str("id") } }
    }

    suspend fun items(mode: String, window: String = "24h", limit: Int = 50, conditional: Boolean = true): List<ApiItem>? {
        val body = Net.get("$BASE/api/v1/items?limit=$limit&mode=$mode&window=$window", conditional) ?: return null
        val arr = JSONObject(body).getJSONArray("items")
        return (0 until arr.length()).map { parseV1(arr.getJSONObject(it)) }
    }

    /** Everything discovered since [since] for a podcast; pages are spaced to stay well under ~60 req/min. */
    suspend fun itemsSince(since: Long, coverage: String, progress: (Int) -> Unit): List<ApiItem> {
        val out = mutableListOf<ApiItem>()
        var cursor: String? = null
        val days = (System.currentTimeMillis() - since) / 86_400_000.0
        for (page in 0 until 80) {
            if (page > 0) delay(700)
            val batch: List<ApiItem>
            val next: String?
            if (coverage == "firstParty") {
                val r = timeline("firstParty", cursor, conditional = false) ?: break
                batch = r.items; next = r.next
            } else {
                val mode = if (coverage == "selected") "selected" else "all"
                val window = if (days <= 1.01) "24h" else "7d"
                val url = "$BASE/api/v1/items?limit=100&mode=$mode&window=$window" + (cursor?.let { "&cursor=" + enc(it) } ?: "")
                val o = JSONObject(Net.get(url) ?: break)
                val arr = o.getJSONArray("items")
                batch = (0 until arr.length()).map { parseV1(arr.getJSONObject(it)) }
                val pg = o.optJSONObject("page")
                next = if (pg?.optBoolean("hasMore") == true) pg.str("nextCursor") else null
            }
            out += batch.filter { (it.discoveredAt ?: it.publishedAt ?: 0) >= since }
            progress(out.size)
            val oldest = batch.mapNotNull { it.discoveredAt ?: it.publishedAt }.minOrNull() ?: 0
            if (next == null || oldest < since) break
            cursor = next
        }
        return out
    }

    /** Agent endpoints return ready-made Chinese Markdown (daily, weekly, monthly, hot, stories). */
    suspend fun markdown(path: String): String = Net.get("$BASE/$path") ?: ""

    /** "OpenAI：官网动态（RSS · 排除…）" -> "OpenAI"; "X：Mistral AI (@MistralAI)" -> "Mistral AI (@MistralAI)". */
    fun cleanSource(s: String): String {
        val base = if (s.startsWith("X：")) s.drop(2) else s
        var name = base.substringBefore("：")
        name = name.substringBefore("（").trim()
        // "Artificial Analysis 完整文章" -> "Artificial Analysis" (keeps names like "IT之家" intact)
        Regex("""^([A-Za-z0-9][A-Za-z0-9 .&'@()/-]{3,}?)\s+[\u3000-\u9fff].*$""").find(name)?.let { name = it.groupValues[1] }
        return name.trim()
    }

    val categories = listOf(
        "ai-models" to "Models", "ai-products" to "Products", "industry" to "Industry",
        "paper" to "Research", "tip" to "Tips & Opinion",
    )
    fun categoryLabel(id: String?) = categories.firstOrNull { it.first == id }?.second ?: "News"

    private val tagEnglish = mapOf(
        "产品更新" to "Product update", "编码" to "Coding", "教程/实践" to "Tutorial", "部署/工程" to "Engineering",
        "安全/对齐" to "Safety", "模型发布" to "Model release", "论文/研究" to "Research", "推理" to "Reasoning",
        "行业动态" to "Industry", "多模态" to "Multimodal", "MCP/工具调用" to "MCP/Tools", "评测/基准" to "Benchmarks",
        "开源生态" to "Open source", "数据/训练" to "Training data", "端侧" to "On-device", "语音" to "Voice",
        "大佬观点" to "Opinion", "开源/仓库" to "Open-source repo", "具身智能" to "Robotics", "图像生成" to "Image gen",
        "视频生成" to "Video gen", "融资/商业" to "Funding", "政策/监管" to "Policy", "芯片/算力" to "Chips/Compute",
        "现象/趋势" to "Trends", "视频" to "Video",
    )
    fun tagEn(t: String) = tagEnglish[t] ?: t
}
