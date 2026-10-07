package com.aliahad.ainewshot

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The aihot.news pages we mirror, read from the same JSON the website uses (anonymous, read-only).
 *
 * Full article text: AIHOT shows the translated body on its own article pages only when the source allows it
 * (`readingMode == "full"`). We show it under exactly the same condition and never otherwise (terms §5:
 * don't bypass source authorisation), keeping the source line and the link to the original.
 */
object Site {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun objs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
    private fun itemIdOf(url: String?) = url?.substringAfter("/items/", "")?.substringBefore('?')?.takeIf { it.isNotBlank() }

    // MARK: Article (/items/{id})

    data class Heading(val id: String, val text: String, val level: Int)

    data class Article(
        val item: ApiItem,
        val readingFull: Boolean,
        val bodyHtml: String?,          // Chinese (AIHOT's translation of the original, or the original if Chinese)
        val bodyIsTranslation: Boolean,
        val outline: List<Heading>,
        val topics: List<Pair<String, String>>,   // slug -> Chinese name
        val storyId: String?,
        val storyTitle: String?,
        val reportCount: Int,
    )

    suspend fun article(id: String): Article {
        val o = JSONObject(Net.get("${AIHot.BASE}/api/site/items/${enc(id)}") ?: throw HttpError(304, "unchanged"))
        val g = o.optJSONObject("group")
        val x = o.optJSONObject("x")
        val links = o.optJSONObject("links")
        val quoted = x?.optJSONObject("quoted")
        val item = ApiItem(
            id = o.getString("id"), title = o.optString("title"), originalTitle = o.str("originalTitle"),
            summary = o.str("summary"), sourceName = o.optJSONObject("source")?.optString("name") ?: "",
            aihotUrl = "${AIHot.BASE}/items/$id", originalUrl = links?.str("original"),
            publishedAt = parseTime(o.str("publishedAt")), discoveredAt = parseTime(o.str("timelineAt") ?: o.str("discoveredAt")),
            category = o.str("category"), score = o.intOrNull("score"), reason = o.str("reason"),
            selected = o.optBoolean("selected"), tags = o.optJSONArray("tags").strings(), channel = o.str("channel"),
            factId = g?.str("factId"), extraSources = g?.optInt("additionalSourceCount") ?: 0, reportCount = g?.optInt("reportCount") ?: 0,
            handle = x?.str("handle"), avatarUrl = AIHot.img(x?.str("avatarUrl")),
            quoteAuthor = quoted?.let { q -> listOfNotNull(q.str("authorName"), q.str("handle")?.let { "@$it" }).joinToString(" ") },
            quoteText = quoted?.let { it.str("text") ?: it.str("translation") },
        )
        val body = o.optJSONObject("body")
        val full = o.optString("readingMode") == "full"
        return Article(
            item = item, readingFull = full,
            bodyHtml = if (full) body?.str("zh") ?: body?.str("original") else null,
            bodyIsTranslation = body?.optString("zhKind") == "translation",
            outline = objs(o.optJSONArray("outline")).map { Heading(it.optString("id"), it.optString("text"), it.optInt("level", 2)) },
            topics = objs(o.optJSONArray("topics")).map { it.optString("slug") to it.optString("name") },
            storyId = o.optJSONObject("story")?.str("publicId"), storyTitle = o.optJSONObject("story")?.str("title"),
            reportCount = g?.optInt("reportCount") ?: 0,
        )
    }

    // MARK: Other reports of the same news (the "N reports ›" expander)

    data class Report(val id: String, val title: String, val source: String, val at: Long?, val originalUrl: String?)

    suspend fun reports(factId: String): List<Report> {
        val o = JSONObject(Net.get("${AIHot.BASE}/api/site/groups/${enc(factId)}/reports") ?: "{}")
        return objs(o.optJSONArray("reports")).map {
            Report(it.optString("id"), it.optString("title"), it.optJSONObject("source")?.optString("name") ?: "",
                parseTime(it.str("timelineAt") ?: it.str("publishedAt")), it.str("originalUrl"))
        }
    }

    // MARK: Hot list (/hot): heat index, trend and participants — the official hot-topics omits these

    data class HotEntry(
        val rank: Int, val storyId: String, val title: String, val heat: Double, val trend: String, val trendPct: Double?,
        val participants: Int, val sources: Int, val sourceNames: List<String>, val summary: String?, val latest: String?,
        val cover: String?, val spark: List<Double>,
    )

    suspend fun hot(conditional: Boolean): Pair<Long?, List<HotEntry>>? {
        val body = Net.get("${AIHot.BASE}/api/site/hot", conditional) ?: return null
        val o = JSONObject(body)
        return parseTime(o.str("computedAt")) to objs(o.optJSONArray("entries")).map { e ->
            val st = e.getJSONObject("story")
            HotEntry(
                rank = e.optInt("rank"), storyId = st.getString("publicId"), title = st.optString("title"),
                heat = e.optDouble("heat", 0.0), trend = e.optString("trend", "flat"),
                trendPct = if (e.isNull("trendPct") || !e.has("trendPct")) null else e.optDouble("trendPct"),
                participants = e.optInt("participantCount"), sources = e.optInt("sourceCount"),
                sourceNames = e.optJSONArray("sourceNames").strings().map { AIHot.cleanSource(it) },
                summary = e.str("summary"), latest = e.str("latest"),
                cover = AIHot.img(e.optJSONObject("cover")?.str("url")),
                spark = e.optJSONArray("spark")?.let { a -> (0 until a.length()).map { i -> a.optDouble(i) } } ?: emptyList(),   // NaN = no data yet
            )
        }
    }

    // MARK: Story (/story/{id})

    data class StoryReport(val id: String, val title: String, val summary: String?, val source: String, val official: Boolean,
                           val selected: Boolean, val at: Long?)
    data class Development(val title: String, val at: Long?, val reports: Int, val lead: StoryReport?)

    data class Story(
        val id: String, val title: String, val reportCount: Int, val sourceCount: Int, val latestAt: Long?, val firstAt: Long?,
        val digest: String?, val digestAt: Long?, val latest: String?, val developments: List<Development>,
        val timeline: List<StoryReport>, val heat: List<Double>, val topics: List<Pair<String, String>>,
        val related: List<Pair<String, String>>,
    )

    private fun report(o: JSONObject) = StoryReport(
        o.optString("id"), o.optString("title"), o.str("summary"), AIHot.cleanSource(o.optJSONObject("source")?.optString("name") ?: ""),
        o.optJSONObject("source")?.optBoolean("firstParty") == true, o.optBoolean("selected"), parseTime(o.str("publishedAt")))

    suspend fun story(id: String): Story {
        val o = JSONObject(Net.get("${AIHot.BASE}/api/site/stories/${enc(id)}") ?: throw HttpError(304, "unchanged"))
        return Story(
            id = id, title = o.optString("title"), reportCount = o.optInt("reportCount"), sourceCount = o.optInt("sourceCount"),
            latestAt = parseTime(o.str("latestAt")), firstAt = parseTime(o.str("firstReportAt")),
            digest = o.str("digest"), digestAt = parseTime(o.str("digestUpdatedAt")), latest = o.str("latest"),
            developments = objs(o.optJSONArray("developments")).map { d ->
                Development(d.optString("title"), parseTime(d.str("firstReportAt")), d.optInt("reportCount"), d.optJSONObject("representative")?.let { report(it) })
            },
            timeline = objs(o.optJSONArray("timeline")).map { report(it) },
            heat = objs(o.optJSONArray("heat")).map { it.optDouble("heat", 0.0) },
            topics = objs(o.optJSONArray("topics")).map { it.optString("slug") to it.optString("name") },
            related = objs(o.optJSONArray("related")).map { it.optString("publicId") to it.optString("title") },
        )
    }

    // MARK: Topics (/topics, /topics/{slug})

    data class TopicInfo(val slug: String, val name: String, val group: String, val recent: Int, val latestTitle: String?, val latestAt: Long?)
    data class Milestone(val date: String, val title: String, val itemId: String?, val storyId: String?, val major: Boolean)
    data class TopicPage(val info: TopicInfo, val definition: String?, val milestones: List<Milestone>, val items: List<ApiItem>, val page: Int, val pages: Int)

    private fun topicInfo(t: JSONObject) = TopicInfo(
        t.optString("slug"), t.optString("name"), t.optString("group"), t.optInt("recent"),
        t.optJSONObject("latest")?.str("title"), parseTime(t.optJSONObject("latest")?.str("at")))

    suspend fun topics(): List<TopicInfo> =
        objs(JSONObject(Net.get("${AIHot.BASE}/api/site/topics", conditional = false) ?: "{}").optJSONArray("topics")).map { topicInfo(it) }

    suspend fun topic(slug: String, page: Int = 1): TopicPage {
        val o = JSONObject(Net.get("${AIHot.BASE}/api/site/topics/${enc(slug)}" + (if (page > 1) "?page=$page" else ""), conditional = false) ?: "{}")
        val t = o.getJSONObject("topic")
        val chron = o.optJSONObject("modules")?.optJSONObject("chronicle")
        val milestones = objs(chron?.optJSONArray("milestones")).map {
            val href = it.optString("href")
            Milestone(it.optString("date"), it.str("headline") ?: it.optString("title"), itemIdOf(href),
                href.substringAfter("/story/", "").takeIf { s -> s.isNotBlank() }, it.optBoolean("major"))
        }
        return TopicPage(topicInfo(t), t.str("definition"), milestones.sortedByDescending { it.date },
            objs(o.optJSONArray("items")).map { AIHot.parseTimeline(it, firstParty = false) },
            o.optInt("page", 1), o.optInt("pageCount", 1))
    }

    // MARK: Daily / weekly / monthly newspaper (official /api/v1)

    data class ReportItem(val id: String?, val title: String, val summary: String?, val source: String, val originalUrl: String?)
    data class Section(val label: String, val summary: String?, val items: List<ReportItem>)
    data class Paper(
        val kind: String, val key: String, val headline: String?, val lead: String?, val leadItem: ReportItem?,
        val sections: List<Section>, val flashes: List<ReportItem>, val link: String,
    )
    data class Issue(val key: String, val title: String?)

    private fun reportItem(o: JSONObject): ReportItem {
        val links = o.optJSONObject("links")
        return ReportItem(itemIdOf(links?.str("aihot")), o.optString("title"), o.str("summary"),
            AIHot.cleanSource(o.optJSONObject("source")?.optString("name") ?: ""), links?.str("original"))
    }

    /** kind = dailies | weeklies | monthlies; key = null for the latest issue. */
    suspend fun paper(kind: String, key: String?): Paper {
        val o = JSONObject(Net.get("${AIHot.BASE}/api/v1/$kind/${key ?: "latest"}") ?: throw HttpError(304, "unchanged")).getJSONObject("report")
        val lead = o.optJSONObject("lead")
        val sections = objs(o.optJSONArray("sections")).map { s -> Section(s.optString("label"), s.str("summary"), objs(s.optJSONArray("items")).map { reportItem(it) }) }
        val leadItem = lead?.str("title")?.let { t -> sections.flatMap { it.items }.firstOrNull { it.title == t } }
        return Paper(
            kind = kind, key = o.str("date") ?: o.str("week") ?: o.str("month") ?: key ?: "",
            headline = lead?.str("title") ?: o.str("headline"), lead = lead?.str("leadParagraph") ?: o.str("overview"),
            leadItem = leadItem, sections = sections, flashes = objs(o.optJSONArray("flashes")).map { reportItem(it) },
            link = o.optJSONObject("links")?.str("aihot") ?: "${AIHot.BASE}/${kind.removeSuffix("ies").removeSuffix("s")}",
        )
    }

    suspend fun issues(kind: String): List<Issue> =
        objs(JSONObject(Net.get("${AIHot.BASE}/api/v1/$kind") ?: "{}").optJSONArray("items")).map {
            Issue(it.str("date") ?: it.str("week") ?: it.str("month") ?: "", it.str("leadTitle") ?: it.str("headline"))
        }
}
