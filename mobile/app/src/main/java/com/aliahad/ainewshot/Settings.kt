package com.aliahad.ainewshot

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All preferences, backed by SharedPreferences. [version] ticks on every write so Compose can observe. */
class Settings private constructor(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val version = MutableStateFlow(0)

    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { p.edit().putBoolean(k, value).apply(); version.value++ }
    }
    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { p.edit().putInt(k, value).apply(); version.value++ }
    }
    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { p.edit().putString(k, value).apply(); version.value++ }
    }

    // Alerts
    var liveAlerts by bool("liveAlerts", true)
    var pollSeconds by int("pollSeconds", 90)
    var feedMode by str("feedMode", "firstParty")          // firstParty | selected | all
    var minScore by int("minScore", 0)
    // Selected tab: last view (selected | all) and chip (all | firstParty | category id | following), as on aihot.news.
    var feedView by str("feedView", "selected")
    var feedChip by str("feedChip", "all")
    var includeKeywords by str("includeKeywords", "")
    var excludeKeywords by str("excludeKeywords", "")
    var maxAlertAgeHours by int("maxAlertAgeHours", 12)
    var popupMode by str("popupMode", "fullscreen")       // fullscreen | notification
    var maxPerHour by int("maxPerHour", 6)
    var digestThreshold by int("digestThreshold", 3)
    // Trending ("hot list") alerts: a story climbing AIHOT's Top 10 across many sources.
    var trendingAlerts by bool("trendingAlerts", false)
    var trendingTopRank by int("trendingTopRank", 3)
    var trendingMinSources by int("trendingMinSources", 8)
    // Topics you follow: matching *curated* stories are also alerted (comma-separated topic ids).
    var followedTopics by str("followedTopics", "")
    fun followed(): Set<String> = followedTopics.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    // Morning briefing: AIHOT's daily report (published 08:00 Beijing), translated and delivered as a popup.
    var briefingEnabled by bool("briefingEnabled", true)
    var briefingMinutes by int("briefingMinutes", 8 * 60 + 15)
    var briefingWeekly by bool("briefingWeekly", true)
    // Story follow-ups: alert when a story you opened (or were alerted about) has a new development.
    var storyFollowUps by bool("storyFollowUps", true)
    var quietEnabled by bool("quietEnabled", false)
    var quietStart by int("quietStart", 23 * 60)
    var quietEnd by int("quietEnd", 7 * 60)

    // Translation
    var translation by str("translation", "deepseek")     // deepseek | original
    var deepseekModel by str("deepseekModel", "deepseek-flash")
    var interestProfile by str("interestProfile", "I build apps with AI. I care about frontier model releases (Claude, GPT, Gemini, DeepSeek, Qwen), coding agents and developer tools, open-weight models, and major AI industry moves.")

    // Audio
    var audioEnabled by bool("audioEnabled", true)
    var voiceProvider by str("voiceProvider", "mimo")     // mimo | system
    var voice by str("voice", "Mia")
    var voiceStyle by str("voiceStyle", "Crisp, confident tech-news anchor. Brisk but clear pace, warm and calm.")
    var silentInCalls by bool("silentInCalls", true)

    // Podcast
    var podFormat by str("podFormat", "duo")
    var podLengthMin by int("podLengthMin", 0)
    var podCoverage by str("podCoverage", "all")
    var podTone by str("podTone", "morning")
    var podExpressiveness by int("podExpressiveness", 2)
    var podHostAName by str("podHostAName", "Mia")
    var podHostAVoice by str("podHostAVoice", "Mia")
    var podHostAPersona by str("podHostAPersona", "Lead host. A warm, bright, curious woman in her early thirties; a former tech journalist who explains clearly, keeps the show moving and smiles while she talks. Light humour, never over the top.")
    var podHostBName by str("podHostBName", "Dean")
    var podHostBVoice by str("podHostBVoice", "Dean")
    var podHostBPersona by str("podHostBPersona", "Co-host and analyst. A calm, dry-witted man in his forties; an ex-engineer who adds the 'so what' for builders, asks sharp questions and is healthily sceptical of hype. Lower, relaxed voice.")
    var podJingle by bool("podJingle", false)
    var podWriterModel by str("podWriterModel", "deepseek-v4-pro")
    var podAuto by bool("podAuto", false)
    var podAutoMinutes by int("podAutoMinutes", 7 * 60)
    var podAutoWeeklyOnMonday by bool("podAutoWeeklyOnMonday", true)
    var podSaveToMusic by bool("podSaveToMusic", true)
    var podSpeed by str("podSpeed", "1.0")

    // Keys (seeded from the build on first launch)
    var deepseekKey by str("deepseekKey", BuildConfig.DEEPSEEK_KEY)
    var mimoKey by str("mimoKey", BuildConfig.MIMO_KEY)

    /** Checklist items the app can't read from the system; you confirm them once in Reliability setup. */
    fun confirmed(key: String): Boolean? = if (p.getBoolean("confirmed_$key", false)) true else null
    fun setConfirmed(key: String, value: Boolean) { p.edit().putBoolean("confirmed_$key", value).apply(); version.value++ }

    fun keywords(s: String) = s.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    companion object {
        val CONFIRMABLE = setOf("saver", "float", "lockscreen", "ai", "recents", "focus")
        @Volatile private var instance: Settings? = null
        fun get(ctx: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(ctx.applicationContext).also { instance = it }
        }
    }
}
