package com.aliahad.ainewshot

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.BreakIterator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

// MARK: Model

data class StoryLink(val title: String, val source: String, val url: String)
data class ShowNote(val id: Int, val title: String, val importance: Int, val tier: String, val links: List<StoryLink>)
data class PodLine(val id: Int, val speaker: String, val direction: String, var text: String, val chapter: Int, var start: Double = 0.0, var end: Double = 0.0)
data class Chapter(val id: Int, val title: String, var start: Double = 0.0, val stories: List<Int> = emptyList())

data class Episode(
    val id: String, val createdAt: Long, val periodDays: Int, val coverage: String,
    val title: String, val summary: String, val hostNames: Map<String, String>,
    val lines: List<PodLine>, val chapters: List<Chapter>, val notes: List<ShowNote>,
    val audioPath: String, val duration: Double, val itemsScanned: Int,
) {
    val periodLabel get() = if (periodDays == 1) "Last 24 hours" else "Last $periodDays days"
    fun name(sp: String) = hostNames[sp] ?: sp

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("createdAt", createdAt); put("periodDays", periodDays); put("coverage", coverage)
        put("title", title); put("summary", summary); put("hostNames", JSONObject(hostNames))
        put("lines", JSONArray(lines.map { JSONObject().put("id", it.id).put("speaker", it.speaker).put("direction", it.direction)
            .put("text", it.text).put("chapter", it.chapter).put("start", it.start).put("end", it.end) }))
        put("chapters", JSONArray(chapters.map { JSONObject().put("id", it.id).put("title", it.title).put("start", it.start).put("stories", JSONArray(it.stories)) }))
        put("notes", JSONArray(notes.map { n -> JSONObject().put("id", n.id).put("title", n.title).put("importance", n.importance).put("tier", n.tier)
            .put("links", JSONArray(n.links.map { JSONObject().put("title", it.title).put("source", it.source).put("url", it.url) })) }))
        put("audioPath", audioPath); put("duration", duration); put("itemsScanned", itemsScanned)
    }

    companion object {
        fun fromJson(o: JSONObject): Episode {
            val hn = o.getJSONObject("hostNames")
            val lines = o.getJSONArray("lines"); val chs = o.getJSONArray("chapters"); val ns = o.getJSONArray("notes")
            return Episode(
                o.getString("id"), o.getLong("createdAt"), o.getInt("periodDays"), o.optString("coverage"),
                o.getString("title"), o.optString("summary"), hn.keys().asSequence().associateWith { hn.getString(it) },
                (0 until lines.length()).map { lines.getJSONObject(it).let { l -> PodLine(l.getInt("id"), l.getString("speaker"), l.optString("direction"), l.getString("text"), l.getInt("chapter"), l.getDouble("start"), l.getDouble("end")) } },
                (0 until chs.length()).map { chs.getJSONObject(it).let { c -> Chapter(c.getInt("id"), c.getString("title"), c.getDouble("start"), c.optJSONArray("stories")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()) } },
                (0 until ns.length()).map { ns.getJSONObject(it).let { n -> ShowNote(n.getInt("id"), n.getString("title"), n.getInt("importance"), n.getString("tier"),
                    n.getJSONArray("links").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { l -> StoryLink(l.getString("title"), l.getString("source"), l.getString("url")) } } }) } },
                o.getString("audioPath"), o.getDouble("duration"), o.getInt("itemsScanned"),
            )
        }
    }
}

// MARK: Style presets (MiMo natural-language + audio-tag control)

object PodStyle {
    const val SHOW = "AI News Hot Daily"
    val tones = listOf(
        Triple("morning", "Morning coffee", "A relaxed morning show people listen to over coffee. Unhurried, warm and clear; friendly energy without rushing."),
        Triple("newsroom", "Newsroom", "A professional news briefing. Crisp, authoritative, efficient pace, measured emotion."),
        Triple("casual", "Casual chat", "Two friends who love tech chatting casually. Playful, conversational, genuine reactions."),
        Triple("energetic", "Energetic", "A high-energy tech show. Upbeat, quick pace, enthusiastic but always credible."),
    )
    fun scene(t: String) = tones.firstOrNull { it.first == t }?.third ?: tones[0].third
    fun writerRule(level: Int) = when (level) {
        1 -> "SUBTLE: about 15% of lines start with a (style) tag; use 2-4 inline tags in the whole episode, only [pause] or a soft [chuckles]. Understated emotion."
        3 -> "EXPRESSIVE: about 75% of lines start with a vivid, specific (style) tag, e.g. \"(genuinely stunned)\", \"(teasing)\", \"(quietly concerned)\"; at least one inline reaction or breath every 2-3 lines ([laughs], [sighs], [inhales], [pause], [gasps]); mixed emotions welcome. Stay credible — it is still news."
        else -> "NATURAL: about 40% of lines start with a (style) tag such as \"(amused)\", \"(thoughtful)\", \"(surprised)\", \"(serious)\"; roughly one inline tag every 4 lines where a real host would react ([laughs], [chuckles], [sighs], [pause], [inhales]). Vary emotions across the show."
    }
    fun ttsRule(level: Int) = when (level) {
        1 -> "Keep the delivery understated and steady."
        3 -> "Be vivid and performative: let the emotion colour pitch, pace and breath."
        else -> "Natural, lively delivery with real but restrained emotion."
    }
}

// MARK: Studio

data class StudioState(
    val stage: Int = 0,          // 0 idle, 1 fetch, 2 edit, 3 write, 4 record, 5 mix, 6 done, 7 failed
    val detail: String = "",
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val days: Int = 0,
)

object PodcastStudio {
    val stageLabels = listOf("Ready", "Collecting news", "Editing: merging & ranking stories", "Writing the script",
        "Recording voices", "Mixing & exporting", "Done", "Failed")
    val state = MutableStateFlow(StudioState())
    val episodes = MutableStateFlow<List<Episode>>(emptyList())
    val busy get() = state.value.stage in 1..5
    private var newsCache: Triple<String, Long, List<ApiItem>>? = null

    private fun root(ctx: Context) = File(ctx.filesDir, "podcasts").apply { mkdirs() }

    fun load(ctx: Context) {
        episodes.value = root(ctx).listFiles()?.mapNotNull { d ->
            runCatching { Episode.fromJson(JSONObject(File(d, "episode.json").readText())) }.getOrNull()
        }?.filter { File(it.audioPath).exists() }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun delete(ctx: Context, ep: Episode) {
        if (PodcastPlayer.current.value?.id == ep.id) PodcastPlayer.stop()
        File(root(ctx), ep.id).deleteRecursively()
        episodes.value = episodes.value.filterNot { it.id == ep.id }
    }

    private fun set(stage: Int, detail: String, progress: Float = 0f) { state.value = state.value.copy(stage = stage, detail = detail, progress = progress) }
    private fun note(s: String) { Log.i(TAG, "podcast: $s"); state.value = state.value.copy(log = state.value.log + s) }
    private fun detail(s: String, p: Float? = null) { state.value = state.value.copy(detail = s, progress = p ?: state.value.progress) }

    suspend fun generate(ctx: Context, days: Int): Episode {
        state.value = StudioState(stage = 1, days = days)
        try {
            val ep = run(ctx, days)
            episodes.value = listOf(ep) + episodes.value
            episodes.value.drop(30).forEach { delete(ctx, it) }
            set(6, "Ready · ${mmss(ep.duration)}")
            return ep
        } catch (e: kotlinx.coroutines.CancellationException) {
            set(0, "Cancelled"); throw e
        } catch (e: Throwable) {
            Log.e(TAG, "podcast failed", e)
            state.value = state.value.copy(stage = 7, error = e.message ?: e.toString())
            throw e
        }
    }

    private suspend fun run(ctx: Context, days: Int): Episode {
        val s = Settings.get(ctx)
        if (s.deepseekKey.isBlank()) throw HttpError(401, "Add a DeepSeek API key in Settings.")
        if (s.mimoKey.isBlank()) throw HttpError(401, "Add a MiMo API key in Settings.")
        val minutes = if (s.podLengthMin > 0) s.podLengthMin else mapOf(1 to 8, 3 to 12, 7 to 18)[days] ?: 10
        val since = System.currentTimeMillis() - days * 86_400_000L

        // 1. Collect
        set(1, "Fetching news…")
        val key = "$days-${s.podCoverage}"
        val items = newsCache?.takeIf { it.first == key && System.currentTimeMillis() - it.second < 1_800_000 }?.third
            ?: AIHot.itemsSince(since, s.podCoverage) { n -> detail("Fetched $n news items…") }.also { newsCache = Triple(key, System.currentTimeMillis(), it) }
        note("Collected ${items.size} items from the ${if (days == 1) "last 24 hours" else "last $days days"}")
        if (items.isEmpty()) throw HttpError(204, "No news found for this period.")

        // 2. Edit
        set(2, "Merging duplicate coverage across ${items.size} items…")
        val flash = DeepSeek(s.deepseekKey, "deepseek-flash")
        var stories = Editor.edit(items, flash) { d, t -> detail("Edited $d of $t batches", d.toFloat() / t) }
        if (stories.size > 60 || items.size > 300) {
            detail("Removing duplicates across batches…")
            stories = runCatching { Editor.merge(stories, flash) }.getOrDefault(stories)
        }
        val plan = Editor.plan(stories, items, minutes)
        note("${stories.size} stories kept → ${plan.deep.size} deep dives, ${plan.brief.size} briefs, ${plan.quick.size} quick hits, ${plan.rest.size} in show notes")

        // 3. Write
        set(3, "Writing a $minutes-minute ${if (s.podFormat == "duo") "two-host" else "solo"} script…")
        var script = Writer.write(plan, items, days, since, minutes, s, DeepSeek(s.deepseekKey, s.podWriterModel))
        if (s.podJingle) script = script.copy(lines = listOf(PodLine(-1, "J", "jingle",
            "(singing) A I News Hot, every story that you missed, A I News Hot, here's your morning list!", 0)) + script.lines)
        val lines = script.lines.mapIndexed { i, l -> l.copy(id = i) }
        note("Script: ${lines.size} lines, ${script.chapters.size} chapters, ~${lines.sumOf { it.text.split(" ").size }} words")

        // 4. Record
        set(4, "Recording ${lines.size} lines…")
        val pcms = Recorder.record(ctx, lines, script.chapters, s) { d -> detail("Recorded $d of ${lines.size} lines", d.toFloat() / lines.size) }

        // 5. Mix
        set(5, "Levelling voices and exporting…")
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)) + "-${days}d"
        val dir = File(root(ctx), id).apply { mkdirs() }
        val mixed = withContext(Dispatchers.Default) { Mixer.mix(lines, pcms, File(dir, "episode.m4a")) }
        val chapters = script.chapters.map { c -> c.copy(start = mixed.lines.firstOrNull { it.chapter == c.id }?.start ?: 0.0) }
        val ep = Episode(id, System.currentTimeMillis(), days, s.podCoverage, script.title, script.summary,
            mapOf("A" to s.podHostAName, "B" to s.podHostBName, "J" to "Jingle"), mixed.lines, chapters, plan.notes,
            mixed.file.path, mixed.duration, items.size)
        File(dir, "episode.json").writeText(ep.toJson().toString())
        if (s.podSaveToMusic) runCatching { exportToMusic(ctx, ep) }.onFailure { Log.w(TAG, "music export", it) }
        note("Episode ready: ${mmss(ep.duration)}")
        return ep
    }

    /** Also publish to Music/AI News Hot so any music app can play it. */
    private fun exportToMusic(ctx: Context, ep: Episode) {
        val f = File(ep.audioPath)
        val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.US)) + " " + ep.title.replace(Regex("[/:]"), "-")
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.${f.extension}")
            put(MediaStore.Audio.Media.MIME_TYPE, if (f.extension == "m4a") "audio/mp4" else "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AI News Hot")
            put(MediaStore.Audio.Media.TITLE, ep.title)
            put(MediaStore.Audio.Media.ARTIST, PodStyle.SHOW)
            put(MediaStore.Audio.Media.IS_PODCAST, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    fun mmss(t: Double): String {
        val s = t.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

// MARK: Editor (map: per-batch story extraction; reduce: cross-batch dedupe; then tiering)

data class EditedStory(val t: String, val ids: List<Int>, val imp: Int)
data class Plan(
    val deep: List<Pair<String, EditedStory>>, val brief: List<Pair<String, EditedStory>>,
    val quick: List<Pair<String, EditedStory>>, val rest: List<EditedStory>,
    val notes: List<ShowNote>, val keyToNote: Map<String, Int>,
)

object Editor {
    suspend fun edit(items: List<ApiItem>, ds: DeepSeek, progress: (Int, Int) -> Unit): List<EditedStory> = coroutineScope {
        val system = """
            You are the news editor of an AI-industry podcast. Below is one batch of raw news items (mostly Chinese), one per line:
            index|score|★ if editor-picked|category|source|title|summary
            ${Translator.GUARD}
            Group items that cover the SAME event into one story. Be inclusive: keep every item with real news value for people following AI
            (launches, model releases, research, funding, policy, security incidents, notable tools, tutorials with substance, notable opinions).
            Drop ONLY: pure ads/promotions, giveaways, trivial social chatter, content-free reposts, and exact duplicates (merge those instead).
            When unsure, keep it with imp 1.
            imp: 5 = industry-moving, 4 = important, 3 = notable, 2 = minor, 1 = small but worth a mention. Use score and ★ as hints, not rules.
            For each story give a concise, factual English headline (max 14 words, keep product/company names exact).
            Reply JSON only: {"stories":[{"t":"headline","ids":[indices],"imp":n}]}
        """.trimIndent()
        val chunks = items.indices.chunked(250)
        val sem = Semaphore(4)
        var done = 0
        val results = chunks.map { r ->
            async {
                sem.withPermit {
                    val lines = r.joinToString("\n") { i ->
                        val a = items[i]
                        "$i|${a.score ?: 0}|${if (a.selected || a.firstParty) "★" else ""}|${a.category ?: "-"}|${AIHot.cleanSource(a.sourceName)}|${a.title}|${(a.summary ?: "").take(110)}"
                    }
                    var out = emptyList<EditedStory>()
                    for (attempt in 0..1) {
                        val ok = runCatching {
                            val arr = JSONObject(ds.chat(system, lines, json = true, maxTokens = 8000)).getJSONArray("stories")
                            out = (0 until arr.length()).mapNotNull { k ->
                                val o = arr.getJSONObject(k)
                                val ids = o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }?.filter { it in r } ?: emptyList()
                                if (ids.isEmpty()) null else EditedStory(o.optString("t"), ids, o.optInt("imp", 1).coerceIn(1, 5))
                            }
                        }.isSuccess
                        if (ok) break
                    }
                    done++; progress(done, chunks.size)
                    out
                }
            }
        }.awaitAll().flatten()
        if (results.isEmpty()) throw HttpError(502, "The editor pass returned nothing")
        results
    }

    suspend fun merge(stories: List<EditedStory>, ds: DeepSeek): List<EditedStory> {
        val list = stories.mapIndexed { k, s -> "$k|${s.imp}|${s.ids.size}|${s.t}" }.joinToString("\n")
        val system = """
            These are story headlines from several editors (key|importance|sources|headline). Many describe the same event.
            Merge entries that are the same event (same launch, same paper, same deal). Do not merge merely related stories. Keep every key exactly once.
            For merged groups give the best headline and the highest importance (+1 if 3 or more entries were merged, max 5).
            Reply JSON only: {"groups":[{"t":"headline","from":[keys],"imp":n}]}
        """.trimIndent()
        val groups = JSONObject(ds.chat(system, list, json = true, maxTokens = 16000)).getJSONArray("groups")
        val used = mutableSetOf<Int>()
        val out = mutableListOf<EditedStory>()
        for (g in 0 until groups.length()) {
            val o = groups.getJSONObject(g)
            val keys = o.optJSONArray("from")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }
                ?.filter { it in stories.indices && it !in used } ?: emptyList()
            if (keys.isEmpty()) continue
            used += keys
            out += EditedStory(o.optString("t"), keys.flatMap { stories[it].ids }.distinct().sorted(), o.optInt("imp", 1).coerceIn(1, 5))
        }
        stories.forEachIndexed { k, s -> if (k !in used) out += s }    // never lose a story
        return out
    }

    fun plan(stories: List<EditedStory>, items: List<ApiItem>, minutes: Int): Plan {
        fun weight(s: EditedStory): Double {
            val best = s.ids.maxOf { (items[it].score ?: 0).toDouble() }
            val picked = if (s.ids.any { items[it].selected || items[it].firstParty }) 8.0 else 0.0
            return s.imp * 100.0 + min(s.ids.size, 8) * 6 + best + picked
        }
        val ranked = stories.sortedByDescending { weight(it) }
        val nDeep = (minutes * 0.45).toInt().coerceIn(2, 12)
        val nBrief = max(3, (minutes * 0.9).toInt())
        val nQuick = max(4, (minutes * 1.3).toInt())
        val deep = mutableListOf<Pair<String, EditedStory>>(); val brief = mutableListOf<Pair<String, EditedStory>>()
        val quick = mutableListOf<Pair<String, EditedStory>>(); val rest = mutableListOf<EditedStory>()
        for (s in ranked) {
            val key = "S${deep.size + brief.size + quick.size + 1}"
            when {
                deep.size < nDeep && s.imp >= 3 -> deep += key to s
                brief.size < nBrief && s.imp >= 2 -> brief += key to s
                quick.size < nQuick -> quick += key to s
                else -> rest += s
            }
        }
        fun links(s: EditedStory) = s.ids.take(6).map { i ->
            val a = items[i]
            StoryLink(a.originalTitle ?: a.title, AIHot.cleanSource(a.sourceName), a.originalUrl ?: a.aihotUrl ?: "${AIHot.BASE}/items/${a.id}")
        }
        val notes = mutableListOf<ShowNote>(); val keyToNote = mutableMapOf<String, Int>()
        for ((tier, group) in listOf("deep" to deep, "brief" to brief, "quick" to quick)) for ((k, s) in group) {
            keyToNote[k] = notes.size
            notes += ShowNote(notes.size, s.t, s.imp, tier, links(s))
        }
        rest.forEach { notes += ShowNote(notes.size, it.t, it.imp, "notes", links(it)) }
        return Plan(deep, brief, quick, rest, notes, keyToNote)
    }
}

// MARK: Writer

data class Script(val title: String, val summary: String, val chapters: List<Chapter>, val lines: List<PodLine>)

object Writer {
    suspend fun write(plan: Plan, items: List<ApiItem>, days: Int, since: Long, minutes: Int, s: Settings, ds: DeepSeek): Script {
        val duo = s.podFormat == "duo"
        val words = minutes * 145            // measured: MiMo voices + gaps ≈ 145 spoken words per minute
        val lineTarget = words / 22          // LLMs follow a line count far better than a word count
        val df = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
        val now = LocalDateTime.now()
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(since), java.time.ZoneId.systemDefault())
        val period = if (days == 1) "the last 24 hours (up to ${now.format(df)})" else "the last $days days (${start.format(df)} to ${now.format(df)})"

        fun report(i: Int, full: Boolean) = JSONObject().apply {
            val a = items[i]
            put("source", AIHot.cleanSource(a.sourceName)); put("title", a.title)
            a.originalTitle?.takeIf { Translator.isLatin(it) }?.let { put("original_title", it) }
            if (full) { put("summary", a.summary ?: ""); a.reason?.let { put("why", it) } }
        }
        val data = JSONArray()
        plan.deep.forEach { (k, st) -> data.put(JSONObject().put("key", k).put("tier", "deep").put("headline", st.t).put("importance", st.imp).put("reports", JSONArray(st.ids.take(4).map { report(it, true) }))) }
        plan.brief.forEach { (k, st) -> data.put(JSONObject().put("key", k).put("tier", "brief").put("headline", st.t).put("importance", st.imp).put("reports", JSONArray(st.ids.take(2).map { report(it, true) }))) }
        plan.quick.forEach { (k, st) -> data.put(JSONObject().put("key", k).put("tier", "quick").put("headline", st.t).put("reports", JSONArray(st.ids.take(1).map { report(it, false) }))) }
        val user = JSONObject().put("stories", data).put("more_in_show_notes", plan.rest.size).toString()

        val hosts = if (duo) "A = ${s.podHostAName}: ${s.podHostAPersona}\nB = ${s.podHostBName}: ${s.podHostBPersona}"
        else "A = ${s.podHostAName}: ${s.podHostAPersona} (solo host, speaking directly to the listener)"
        val system = """
            You are the head writer of "${PodStyle.SHOW}", a ${if (duo) "two-host" else "solo"} AI-news podcast. Write the complete script of an episode covering $period.
            ${Translator.GUARD} The story data is mostly Chinese; the script must be natural spoken American English.

            HOSTS
            $hosts
            TONE: ${PodStyle.scene(s.podTone)}
            LENGTH: $minutes minutes ≈ $words spoken words ≈ $lineTarget lines of about 22 words. Write AT LEAST $lineTarget lines — scripts that come in short are rejected.
            Rough budget: cold open 5%, deep dives 50%, briefs 20%, quick hits 15%, wrap 10%.

            STRUCTURE (each chapter has a short title)
            1. "Cold open": hook with the single biggest story in one or two lines, then welcome the listener, name the period, preview three headlines.
            2. Deep-dive chapters for tier "deep" stories (you may group related ones by theme): what happened, key numbers, why it matters, what to watch next.${if (duo) " The hosts react, ask each other questions and occasionally disagree." else ""}
            3. A "Briefs" chapter for tier "brief" stories, 2-4 lines each.
            4. A "Quick hits" chapter: rapid-fire one-liners for every tier "quick" story${if (duo) ", hosts alternating" else ""}.
            5. "Wrap": the key takeaway, mention that the show notes list all stories (${plan.rest.size} more beyond those covered), sign off.

            FACTS
            - Use only facts present in the story data. Attribute claims ("OpenAI says…", "according to The Decoder…"). Flag rumours as rumours.
            - Never invent numbers, dates, quotes, or opinions of real people. Hosts may give their own reactions and analysis.

            WRITING FOR THE EAR
            - Short sentences, contractions, natural transitions. No URLs, markdown, emoji or lists.
            - Write numbers and versions as spoken ("GPT six point one", "about two point five billion dollars", "seventy-one percent").
            - Each line at most 60 words.${if (duo) " Neither host speaks more than 3 lines in a row; keep the exchange balanced." else ""}

            DELIVERY MARKUP (for Xiaomi MiMo expressive TTS)
            - "d": a director's note for THIS line: emotion, pace, emphasis. e.g. "amused, a little faster, lean on 'open weights'". Required on every line.
            - Put the emotion in BOTH places: the detailed note in "d", and (per the target below) a short leading tag in "t".
            - "t": the spoken words. It may START with one style tag in parentheses, e.g. "(excited) …", "(thoughtful) …", "(deadpan) …", "(sighs) …".
            - Inline audio tags in square brackets may appear anywhere in "t": [laughs], [chuckles], [sighs], [pause], [inhales], [clears throat], [whispers].
            - ${PodStyle.writerRule(s.podExpressiveness)}
            - Match emotion to content: sober for safety incidents, layoffs, outages or harm; curious for research; upbeat for launches. Never laugh about harm.
            - Do not write stage directions as plain words outside tags.

            OUTPUT: JSON only:
            {"title":"catchy episode title (max 8 words)","summary":"two-sentence episode description",
             "chapters":[{"title":"…","stories":["S1","S2"]}],
             "lines":[{"c":chapterIndex,"s":"A"${if (duo) "|\"B\"" else ""},"d":"…","t":"…"}]}
        """.trimIndent()

        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val o = JSONObject(ds.chat(system, user, json = true, maxTokens = min(24_000, words * 3 + 3000)))
                val chArr = o.optJSONArray("chapters") ?: JSONArray().put(JSONObject().put("title", "Episode"))
                val chapters = (0 until chArr.length()).map { i ->
                    val c = chArr.getJSONObject(i)
                    Chapter(i, c.optString("title", "Chapter ${i + 1}"), stories = c.optJSONArray("stories").strings().mapNotNull { plan.keyToNote[it] })
                }
                val la = o.getJSONArray("lines")
                var lines = (0 until la.length()).mapNotNull { i ->
                    val l = la.getJSONObject(i)
                    val t = l.optString("t").trim()
                    if (t.isEmpty()) null
                    else PodLine(i, if (duo && l.optString("s").uppercase() == "B") "B" else "A", l.optString("d"), t,
                        l.optInt("c", 0).coerceIn(0, chapters.size - 1))
                }
                if (lines.size < 6) throw HttpError(502, "Script too short")
                lines = ensureStyleTags(lines, s.podExpressiveness)
                return Script(o.optString("title", "AI News Hot"), o.optString("summary"), chapters, lines)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e; Log.w(TAG, "writer attempt ${attempt + 1}", e)
            }
        }
        throw last ?: HttpError(502, "Writer failed")
    }

    /** If the writer under-used leading style tags, derive them from the director's note so MiMo's tag control is exercised. */
    fun ensureStyleTags(lines: List<PodLine>, level: Int): List<PodLine> {
        val target = mapOf(1 to 0.15, 2 to 0.4, 3 to 0.75)[level] ?: 0.4
        var need = (target * lines.size).toInt() - lines.count { it.text.startsWith("(") }
        if (need <= 0) return lines
        val cands = lines.indices.filter { !lines[it].text.startsWith("(") && lines[it].direction.isNotBlank() }
        if (cands.isEmpty()) return lines
        val step = max(1, cands.size / need)
        val out = lines.map { it.copy() }
        var i = 0
        while (i < cands.size && need > 0) {
            val l = out[cands[i]]
            val words = l.direction.split(",").first().trim().split(" ").take(4).joinToString(" ")
            if (words.isNotBlank()) { l.text = "(${words.lowercase()}) ${l.text}"; need-- }
            i += step
        }
        return out
    }
}

// MARK: Recorder (one MiMo call per line, director-mode style prompt)

object Recorder {
    suspend fun record(ctx: Context, lines: List<PodLine>, chapters: List<Chapter>, s: Settings, progress: (Int) -> Unit): List<ByteArray> = coroutineScope {
        val duo = s.podFormat == "duo"
        fun style(l: PodLine): String {
            if (l.speaker == "J") return "Sing this as a short, bright, catchy radio-show jingle with a confident, upbeat melody."
            val (name, persona) = if (l.speaker == "B") s.podHostBName to s.podHostBPersona else s.podHostAName to s.podHostAPersona
            val chapter = chapters.firstOrNull { it.id == l.chapter }?.title ?: ""
            val other = if (l.speaker == "B") s.podHostAName else s.podHostBName
            return "[Character] $name. $persona\n" +
                "[Scene] Recording \"${PodStyle.SHOW}\". ${PodStyle.scene(s.podTone)} " +
                (if (duo) "Talking with co-host $other, for listeners." else "Talking directly to the listener.") + " Current segment: $chapter.\n" +
                "[Guidance] ${l.direction.ifBlank { "Natural delivery." }}. ${PodStyle.ttsRule(s.podExpressiveness)} Natural American English, clear diction, podcast microphone close."
        }
        val sem = Semaphore(5)      // MiMo allows 100 RPM; 5 in flight is plenty and polite
        var done = 0
        var failures = 0
        val out = lines.map { l ->
            async {
                sem.withPermit {
                    val voice = if (l.speaker == "B") s.podHostBVoice else s.podHostAVoice
                    val pcm = try {
                        val parts = split(clean(l.text))
                        val buf = java.io.ByteArrayOutputStream()
                        parts.forEachIndexed { i, p ->
                            if (i > 0) buf.write(ByteArray((0.12 * MiMo.RATE).toInt() * 2))
                            buf.write(MiMo.checkedPcm(ctx, p, style(l), voice, s.mimoKey))
                        }
                        buf.toByteArray()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "TTS line ${l.id} failed", e); failures++; ByteArray(0)
                    }
                    done++; progress(done)
                    pcm
                }
            }
        }.awaitAll()
        if (failures > max(2, lines.size / 8)) throw HttpError(502, "MiMo failed on $failures of ${lines.size} lines. Try again in a minute.")
        out
    }

    fun clean(s: String) = s.replace(Regex("""https?://\S+"""), "").replace(Regex("[*_#`]"), "").trim()

    fun split(s: String, maxLen: Int = 480): List<String> {
        if (s.length <= maxLen) return listOf(s)
        val it = BreakIterator.getSentenceInstance(Locale.US).apply { setText(s) }
        val parts = mutableListOf<String>(); var cur = StringBuilder()
        var start = it.first(); var end = it.next()
        while (end != BreakIterator.DONE) {
            val sent = s.substring(start, end)
            if (cur.length + sent.length > maxLen && cur.isNotEmpty()) { parts += cur.toString(); cur = StringBuilder() }
            cur.append(sent); start = end; end = it.next()
        }
        if (cur.isNotEmpty()) parts += cur.toString()
        return parts
    }
}

// MARK: Mixer (level-match, natural gaps, AAC export via MediaCodec)

object Mixer {
    data class Result(val file: File, val duration: Double, val lines: List<PodLine>)

    suspend fun mix(lines: List<PodLine>, pcms: List<ByteArray>, out: File): Result {
        val rate = MiMo.RATE.toDouble()
        val total = pcms.sumOf { it.size / 2 } + lines.size * 24_000
        val buf = ShortArray(total + 48_000)
        var n = 0
        fun silence(sec: Double) { n += (sec * rate).toInt() }
        val timed = lines.map { it.copy() }
        silence(0.35)
        for ((i, l) in lines.withIndex()) {
            coroutineContext.ensureActive()
            if (i > 0) {
                val p = lines[i - 1]
                silence(if (l.chapter != p.chapter) 0.85 else if (p.speaker == "J") 0.5 else if (l.speaker == p.speaker) 0.22 else 0.3)
            }
            timed[i].start = n / rate
            val s = normalized(pcms[i])
            System.arraycopy(s, 0, buf, n, min(s.size, buf.size - n))
            n += s.size
            timed[i].end = n / rate
        }
        silence(0.8)
        val samples = buf.copyOf(min(n, buf.size))
        val duration = samples.size / rate
        return try {
            encodeAac(samples, out)
            Result(out, duration, timed)
        } catch (e: Exception) {
            Log.w(TAG, "AAC encode failed, writing WAV", e)
            val wav = File(out.parentFile, "episode.wav")
            wav.writeBytes(MiMo.wav(MiMo.bytes(samples)))
            Result(wav, duration, timed)
        }
    }

    /** Bring every line to the same loudness (≈ -19 dBFS RMS) with a soft limiter. */
    fun normalized(data: ByteArray): ShortArray {
        val s = MiMo.shorts(data)
        if (s.isEmpty()) return s
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        val rms = sqrt(sum / s.size) / 32768
        if (rms < 0.002) return s
        val gain = min(4.0, 0.112 / rms)
        for (i in s.indices) {
            var v = s[i] * gain / 32768
            if (abs(v) > 0.9) v = (if (v > 0) 1 else -1) * (0.9 + 0.1 * tanh((abs(v) - 0.9) / 0.1))
            s[i] = (v * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        return s
    }

    fun encodeAac(samples: ShortArray, out: File) {
        out.delete()
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MiMo.RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(samples) }.array()
        val info = MediaCodec.BufferInfo()
        var track = -1
        var pos = 0
        var inputDone = false
        fun pts(bytePos: Int) = bytePos / 2L * 1_000_000L / MiMo.RATE
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!
                        ib.clear()
                        val len = min(ib.remaining(), pcm.size - pos)
                        if (len <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts(pos), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.put(pcm, pos, len)
                            codec.queueInputBuffer(i, 0, len, pts(pos), 0)
                            pos += len
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start()
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { muxer.stop() }; muxer.release()
        }
    }
}
