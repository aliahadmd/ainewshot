package com.aliahad.ainewshot

import org.json.JSONArray
import org.json.JSONObject

/** A translated item shown in the feed, alerts and popups. */
data class NewsItem(
    val id: String,
    val kind: String = "news",            // news | trending | briefing | podcast
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
    val eventKey: String? = null,         // AIHOT event group (factId): reports of the same story share it
    val extraSources: Int = 0,            // other outlets AIHOT merged into this story
    val reportCount: Int = 0,             // reports in AIHOT's event group (incl. this one)
    val selected: Boolean = false,        // in AIHOT's 精选 (curated)
    val channel: String? = null,          // news | x (an X post: the card shows the post text, not a headline)
    val topics: List<String> = emptyList(), // followed topics this matched (labels)
    val storyId: String? = null,          // AIHOT event page (trending)
    val handle: String? = null,           // X posts: @handle, avatar, first image, quoted post (original text)
    val avatarUrl: String? = null,
    val imageUrl: String? = null,
    val imageRatio: Float? = null,
    val quoteAuthor: String? = null,
    val quoteText: String? = null,
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
        put("eventKey", eventKey ?: JSONObject.NULL); put("extraSources", extraSources); put("reportCount", reportCount); put("selected", selected); put("channel", channel ?: JSONObject.NULL)
        put("topics", JSONArray(topics)); put("storyId", storyId ?: JSONObject.NULL)
        put("handle", handle ?: JSONObject.NULL); put("avatarUrl", avatarUrl ?: JSONObject.NULL)
        put("imageUrl", imageUrl ?: JSONObject.NULL); put("imageRatio", imageRatio?.toDouble() ?: JSONObject.NULL)
        put("quoteAuthor", quoteAuthor ?: JSONObject.NULL); put("quoteText", quoteText ?: JSONObject.NULL)
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
            eventKey = o.str("eventKey"), extraSources = o.optInt("extraSources"), reportCount = o.optInt("reportCount"), selected = o.optBoolean("selected"), channel = o.str("channel"),
            topics = o.optJSONArray("topics").strings(), storyId = o.str("storyId"),
            handle = o.str("handle"), avatarUrl = o.str("avatarUrl"), imageUrl = o.str("imageUrl"),
            imageRatio = if (o.isNull("imageRatio") || !o.has("imageRatio")) null else o.optDouble("imageRatio").toFloat(),
            quoteAuthor = o.str("quoteAuthor"), quoteText = o.str("quoteText"),
        )

        fun from(a: ApiItem, r: Rendered?) = NewsItem(
            id = a.id,
            title = r?.title ?: a.originalTitle ?: a.title,
            summary = r?.summary ?: a.summary ?: "",
            zhTitle = a.title, zhSummary = a.summary ?: "",
            originalTitle = a.originalTitle, why = a.reason?.takeIf { it.isNotBlank() }?.let { r?.why ?: it },   // no reason, no line (never invented) spoken = r?.spoken, relevance = r?.relevance,
            score = a.score, category = a.category, sourceName = AIHot.cleanSource(a.sourceName),
            aihotUrl = a.aihotUrl, originalUrl = a.originalUrl, publishedAt = a.publishedAt,
            discoveredAt = a.discoveredAt ?: System.currentTimeMillis(), tags = a.tags, firstParty = a.firstParty,
            eventKey = a.factId, extraSources = a.extraSources, reportCount = a.reportCount, selected = a.selected, channel = a.channel, handle = a.handle, avatarUrl = a.avatarUrl,
            imageUrl = a.imageUrl, imageRatio = a.imageRatio, quoteAuthor = a.quoteAuthor, quoteText = a.quoteText,
        )
    }
}
