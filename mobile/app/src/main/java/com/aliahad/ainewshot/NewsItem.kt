package com.aliahad.ainewshot

import org.json.JSONArray
import org.json.JSONObject

/** A translated item shown in the feed, alerts and popups. */
data class NewsItem(
    val id: String,
    val kind: String = "news",            // news | briefing | podcast
    val title: String,
    val summary: String,
    val zhTitle: String = "",
    val zhSummary: String = "",
    val originalTitle: String? = null,
    val why: String? = null,
    val spoken: String? = null,
    val relevance: Int? = null,
    val score: Int? = null,
    val category: String? = null,
    val sourceName: String = "",
    val aihotUrl: String? = null,
    val originalUrl: String? = null,
    val publishedAt: Long? = null,
    val discoveredAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val firstParty: Boolean = false,
    val alerted: Boolean = false,
    val episodeId: String? = null,
    val markdown: String? = null,
) {
    val speechText: String get() = spoken?.takeIf { it.isNotBlank() } ?: "$title. $summary"
    val link: String? get() = originalUrl ?: aihotUrl

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("title", title); put("summary", summary)
        put("zhTitle", zhTitle); put("zhSummary", zhSummary); put("originalTitle", originalTitle ?: JSONObject.NULL)
        put("why", why ?: JSONObject.NULL); put("spoken", spoken ?: JSONObject.NULL)
        put("relevance", relevance ?: JSONObject.NULL); put("score", score ?: JSONObject.NULL)
        put("category", category ?: JSONObject.NULL); put("sourceName", sourceName)
        put("aihotUrl", aihotUrl ?: JSONObject.NULL); put("originalUrl", originalUrl ?: JSONObject.NULL)
        put("publishedAt", publishedAt ?: JSONObject.NULL); put("discoveredAt", discoveredAt)
        put("tags", JSONArray(tags)); put("firstParty", firstParty); put("alerted", alerted)
        put("episodeId", episodeId ?: JSONObject.NULL); put("markdown", markdown ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = NewsItem(
            id = o.getString("id"), kind = o.optString("kind", "news"), title = o.optString("title"),
            summary = o.optString("summary"), zhTitle = o.optString("zhTitle"), zhSummary = o.optString("zhSummary"),
            originalTitle = o.str("originalTitle"), why = o.str("why"), spoken = o.str("spoken"),
            relevance = o.intOrNull("relevance"), score = o.intOrNull("score"), category = o.str("category"),
            sourceName = o.optString("sourceName"), aihotUrl = o.str("aihotUrl"), originalUrl = o.str("originalUrl"),
            publishedAt = if (o.isNull("publishedAt")) null else o.optLong("publishedAt"),
            discoveredAt = o.optLong("discoveredAt", System.currentTimeMillis()),
            tags = o.optJSONArray("tags").strings(), firstParty = o.optBoolean("firstParty"),
            alerted = o.optBoolean("alerted"), episodeId = o.str("episodeId"), markdown = o.str("markdown"),
        )

        fun from(a: ApiItem, r: Rendered?) = NewsItem(
            id = a.id,
            title = r?.title ?: a.originalTitle ?: a.title,
            summary = r?.summary ?: a.summary ?: "",
            zhTitle = a.title, zhSummary = a.summary ?: "",
            originalTitle = a.originalTitle, why = r?.why ?: a.reason, spoken = r?.spoken, relevance = r?.relevance,
            score = a.score, category = a.category, sourceName = AIHot.cleanSource(a.sourceName),
            aihotUrl = a.aihotUrl, originalUrl = a.originalUrl, publishedAt = a.publishedAt,
            discoveredAt = a.discoveredAt ?: System.currentTimeMillis(), tags = a.tags, firstParty = a.firstParty,
        )
    }
}
