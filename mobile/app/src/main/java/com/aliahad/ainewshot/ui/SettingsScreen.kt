package com.aliahad.ainewshot.ui

import androidx.compose.material.icons.automirrored.filled.ArrowBack

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliahad.ainewshot.Browse
import com.aliahad.ainewshot.BuildConfig
import com.aliahad.ainewshot.KeyCheck
import com.aliahad.ainewshot.LiveService
import com.aliahad.ainewshot.MiMo
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.PodStyle
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Speaker
import com.aliahad.ainewshot.Waker
import kotlinx.coroutines.launch

/**
 * Settings, top to bottom in the order you'd set things up:
 * Reliability → Alerts → Voice → Language & AI → Podcast → API keys → Storage → About.
 * Every control writes straight to [Settings]; text fields save as you pause typing and when you leave the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSetup: () -> Unit, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    val v by s.version.collectAsState()
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            if (onBack != null) androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Reading `v` makes switches/choices reflect saved values immediately; text fields keep their own state.
            if (v < 0) Text("")
            ReliabilitySection(onSetup, resumed)
            AlertsSection(ctx, s)
            BriefingsSection(ctx, s)
            VoiceSection(ctx, s)
            LanguageSection(s)
            PodcastSection(ctx, s)
            KeysSection(ctx, s)
            StorageSection(ctx)
            SectionTitle("About")
            Text("AI News Hot ${BuildConfig.VERSION_NAME} · news from aihot.news · translation by DeepSeek · voices by Xiaomi MiMo",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// MARK: Sections

@Composable
private fun ReliabilitySection(onSetup: () -> Unit, resumed: Int) {
    val ctx = LocalContext.current
    val items = remember(resumed) { Setup.items(ctx) }
    val missing = items.count { it.critical && it.ok != true }
    SectionTitle("Reliability")
    Card(Modifier.fillMaxWidth().clickable(onClick = onSetup),
        colors = CardDefaults.cardColors(containerColor = if (missing == 0) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reliability setup", style = MaterialTheme.typography.titleMedium)
                Text(if (missing == 0) "All essentials done · ${items.count { it.ok == true }} of ${items.size} checks"
                    else "$missing essential setting${if (missing > 1) "s" else ""} missing — news may not reach you",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun AlertsSection(ctx: Context, s: Settings) {
    s.observe()
    val scope = rememberCoroutineScope()
    var advanced by remember { mutableStateOf(false) }
    SectionTitle("Alerts")
    Toggle("Live alerts", "Pop up news the moment it lands. Off: the feed still refreshes, nothing interrupts you.", s.liveAlerts) {
        s.liveAlerts = it
        if (it) LiveService.start(ctx) else LiveService.stop(ctx)
        Waker.schedule(ctx)      // schedules or cancels the screen-off wake-ups
    }
    Group("What to alert me about") {
        ChoiceRow(null, listOf("firstParty" to "First-hand (official)", "selected" to "All curated", "all" to "Everything"), s.feedMode) {
            if (it != s.feedMode) { s.feedMode = it; scope.launch { NewsRepo.check(ctx, force = true) } }
        }
        Hint(when (s.feedMode) {
            "firstParty" -> "Announcements straight from OpenAI, Anthropic, Google… (aihot.news › 精选 › 只看一手), about 8 a day."
            "selected" -> "AIHOT's editors' picks, about 15–25 a day: official news plus the best coverage."
            else -> "Every AI story, hundreds a day. Use keywords or a higher score below."
        })
        // First-hand items all score 60+, so the score filter only means something for the wider feeds.
        if (s.feedMode != "firstParty") LabeledSlider("Minimum AIHOT score", s.minScore, 0..90, hint = "Selected items score 60–85. 0 = everything in the feed above.") { s.minScore = it }
        Field("Always alert on keywords", s.includeKeywords, "claude, anthropic, deepseek") { s.includeKeywords = it }
        Field("Never alert on keywords", s.excludeKeywords, "crypto, funding round") { s.excludeKeywords = it }
        Hint("Comma-separated. Also matches AIHOT tags such as Anthropic, Agent, Coding, Model release, Safety.")
    }
    Group("Follow topics") {
        Hint("Also alert me about curated stories on these topics — not just first-hand announcements. Matches AIHOT's own topic tags.")
        val followed = s.followed()
        com.aliahad.ainewshot.Topics.all.groupBy { it.group }.forEach { (group, topics) ->
            Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                topics.forEach { t ->
                    androidx.compose.material3.FilterChip(selected = t.id in followed, onClick = {
                        s.followedTopics = (if (t.id in followed) followed - t.id else followed + t.id).joinToString(",")
                    }, label = { Text(t.label) })
                }
            }
        }
    }
    Group("Trending alerts") {
        Toggle("Alert me when a story starts trending", "When a story climbs AIHOT's hot list across many independent sources.", s.trendingAlerts) { s.trendingAlerts = it }
        if (s.trendingAlerts) {
            LabeledSlider("Only stories in the top", s.trendingTopRank, 1..10) { s.trendingTopRank = it }
            LabeledSlider("Covered by at least (sources)", s.trendingMinSources, 3..20) { s.trendingMinSources = it }
        }
        Hint("The Hot tab always shows the live Top 10, whether or not alerts are on.")
    }
    Group("How alerts appear") {
        ChoiceRow(null, listOf("fullscreen" to "Pop up over apps", "notification" to "Notification only"), s.popupMode) { s.popupMode = it }
        Hint(if (s.popupMode == "fullscreen") "A full-screen card over whatever you're doing, and over the lock screen."
            else "A heads-up notification; tap it to open the card.")
    }
    Group("When") {
        Toggle("Quiet hours", "Alerts wait and arrive together when quiet hours end.", s.quietEnabled) { s.quietEnabled = it }
        if (s.quietEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeButton(ctx, "From", s.quietStart) { s.quietStart = it }
            TimeButton(ctx, "To", s.quietEnd) { s.quietEnd = it }
        }
        LabeledSlider("At most this many popups per hour", s.maxPerHour, 1..20, hint = "Extra stories wait and arrive as one digest.") { s.maxPerHour = it }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced…") }
    if (advanced) Group("Advanced") {
        ChoiceRow("Check for news every", listOf(60 to "1 min", 90 to "90 s", 120 to "2 min", 300 to "5 min"), s.pollSeconds) { s.pollSeconds = it }
        Hint("While the phone is awake. With the screen off, wake-ups happen about every 4 min (Android's Doze limits this).")
        LabeledSlider("Group into one digest from this many stories", s.digestThreshold, 2..10) { s.digestThreshold = it }
        LabeledSlider("Ignore stories older than (hours)", s.maxAlertAgeHours, 1..72, hint = "Older stories still appear in the feed.") { s.maxAlertAgeHours = it }
    }
    OutlinedButton(onClick = {
        val sample = NewsRepo.recent.value.firstOrNull() ?: NewsItem(id = "test", title = "This is a test alert", summary = "News will look like this.", sourceName = "AI News Hot")
        scope.launch { NewsRepo.deliver(ctx, listOf(sample.copy(id = "test-${System.currentTimeMillis()}")), bypassRate = true) }
    }) { Text("Send a test alert now") }
}

@Composable
private fun BriefingsSection(ctx: Context, s: Settings) {
    s.observe()
    SectionTitle("Briefings & follow-ups")
    Toggle("Morning briefing", "AIHOT's daily report (out 08:00 Beijing), translated and read to you as a popup.", s.briefingEnabled) { s.briefingEnabled = it }
    if (s.briefingEnabled) {
        TimeButton(ctx, "Deliver at", s.briefingMinutes) { s.briefingMinutes = it }
        Toggle("Weekly briefing on Mondays", "The week's biggest stories, after AIHOT's weekly report comes out.", s.briefingWeekly) { s.briefingWeekly = it }
    }
    Toggle("Story follow-ups", "When a story you opened (or were alerted about) has a new development, tell me.", s.storyFollowUps) { s.storyFollowUps = it }
    val followed = NewsRepo.followedStories.collectAsState().value
    if (followed.isNotEmpty()) Hint("Following ${followed.size} stor${if (followed.size == 1) "y" else "ies"} (each for 4 days).")
}

@Composable
private fun VoiceSection(ctx: Context, s: Settings) {
    s.observe()
    SectionTitle("Voice")
    Toggle("Read alerts aloud", "Stays quiet during calls (below) and while a podcast plays.", s.audioEnabled) { s.audioEnabled = it }
    Toggle("Silent during calls and meetings", null, s.silentInCalls) { s.silentInCalls = it }
    Group("Voice") {
        ChoiceRow(null, listOf("mimo" to "Xiaomi MiMo (natural)", "system" to "Phone voice (offline)"), s.voiceProvider) { s.voiceProvider = it }
        if (s.voiceProvider == "mimo") {
            ChoiceRow(null, voiceOptions(ctx), s.voice) { s.voice = it }
            Field("Speaking style", s.voiceStyle, "", lines = 2) { s.voiceStyle = it }
            Hint("Natural-language direction for MiMo, e.g. “calm late-night radio host, slow pace”.")
        } else Hint("Uses the phone's built-in text-to-speech. Works offline; less natural.")
    }
    FilledTonalButton(onClick = {
        Speaker.play(ctx, NewsItem(id = "voice-test-${System.currentTimeMillis()}", title = "", summary = "",
            spoken = "This is AI News Hot. Mistral just released Large 4 in preview, with open weights coming at the end of the month."))
    }) { Text("Test voice") }
}

@Composable
private fun LanguageSection(s: Settings) {
    s.observe()
    SectionTitle("Language & AI")
    Group("Translate Chinese news with") {
        ChoiceRow(null, listOf("deepseek" to "DeepSeek AI", "original" to "No translation"), s.translation) { s.translation = it }
        Hint(if (s.translation == "deepseek") "English titles, summaries and spoken versions." else "Shows the source's own English title when there is one; summaries stay in Chinese.")
        if (s.translation == "deepseek") ChoiceRow("Model", listOf("deepseek-flash" to "Flash (fast, recommended)", "deepseek-v4-pro" to "V4 Pro"), s.deepseekModel) { s.deepseekModel = it }
    }
    Field("About me", s.interestProfile, "", lines = 3) { s.interestProfile = it }
    Hint("Used to personalise Explain and the “for you” score.")
}

@Composable
private fun PodcastSection(ctx: Context, s: Settings) {
    s.observe()
    val scope = rememberCoroutineScope()
    var designName by remember { mutableStateOf("Nova") }
    var designPrompt by remember { mutableStateOf("A woman in her late twenties with a smooth, clear, slightly husky voice; confident late-night radio host; relaxed, warm pace.") }
    var cloneName by remember { mutableStateOf("My voice") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val voices = voiceOptions(ctx)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            status = runCatching { "Added " + MiMo.importVoice(ctx, uri, cloneName) + " — pick it as a voice above." }.getOrElse { "Failed: ${it.message}" }
            s.version.value++
        }
    }

    SectionTitle("Podcast")
    Group("Show") {
        ChoiceRow("Hosts", listOf("duo" to "Two hosts", "solo" to "One host"), s.podFormat) { s.podFormat = it }
        ChoiceRow("Length", listOf(0 to "Auto", 5 to "5 min", 10 to "10 min", 15 to "15 min", 20 to "20 min", 30 to "30 min"), s.podLengthMin) { s.podLengthMin = it }
        ChoiceRow("Coverage", listOf("all" to "All news", "selected" to "Selected", "firstParty" to "First-hand"), s.podCoverage) { s.podCoverage = it }
        ChoiceRow("Tone", PodStyle.tones.map { it.first to it.second }, s.podTone) { s.podTone = it }
        ChoiceRow("Emotion", listOf(1 to "Subtle", 2 to "Natural", 3 to "Expressive"), s.podExpressiveness) { s.podExpressiveness = it }
        Toggle("Sung intro jingle", "MiMo singing mode.", s.podJingle) { s.podJingle = it }
        ChoiceRow("Script writer", listOf("deepseek-v4-pro" to "V4 Pro (best)", "deepseek-flash" to "Flash (faster)"), s.podWriterModel) { s.podWriterModel = it }
    }
    Group(if (s.podFormat == "duo") "Host A" else "Host") {
        Field("Name", s.podHostAName, "") { s.podHostAName = it }
        ChoiceRow("Voice", voices, s.podHostAVoice) { s.podHostAVoice = it }
        Field("Persona", s.podHostAPersona, "", lines = 3) { s.podHostAPersona = it }
    }
    if (s.podFormat == "duo") Group("Host B") {
        Field("Name", s.podHostBName, "") { s.podHostBName = it }
        ChoiceRow("Voice", voices, s.podHostBVoice) { s.podHostBVoice = it }
        Field("Persona", s.podHostBPersona, "", lines = 3) { s.podHostBPersona = it }
    }
    Hint("Personas are sent to MiMo as the [Character] in director mode and to the script writer.")
    // Niche + extra API calls: tucked away unless you've already made a custom voice.
    var customOpen by remember { mutableStateOf(MiMo.customVoices(ctx).isNotEmpty()) }
    TextButton(onClick = { customOpen = !customOpen }) { Text(if (customOpen) "Hide custom voices" else "Custom voices (advanced)…") }
    if (customOpen) Group("Custom voices") {
        val custom = MiMo.customVoices(ctx)
        if (custom.isNotEmpty()) custom.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(MiMo.voiceLabel("file:$f"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { MiMo.deleteVoice(ctx, f); s.version.value++ }) { Text("Delete") }
            }
        } else Hint("None yet. Design one from a description, or clone one from a recording.")
        HorizontalDivider()
        Text("Design from a description", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(designName, { designName = it }, label = { Text("Voice name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(designPrompt, { designPrompt = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        FilledTonalButton(enabled = !busy, onClick = {
            busy = true; status = "Designing…"
            scope.launch {
                status = runCatching { "Created " + MiMo.design(ctx, designName, designPrompt, s.mimoKey) + " — pick it as a voice above." }
                    .getOrElse { "Failed: ${it.message}" }
                busy = false; s.version.value++
            }
        }) { Text("Design voice") }
        Hint("MiMo VoiceDesign invents the voice once; it's then reused via VoiceClone so it sounds identical on every line.")
        HorizontalDivider()
        Text("Clone from a recording", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(cloneName, { cloneName = it }, label = { Text("Voice name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        FilledTonalButton(onClick = { picker.launch("audio/*") }) { Text("Choose a WAV or MP3…") }
        Hint("10–30 seconds of clear speech, under 7 MB.")
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
    Group("Schedule & files") {
        Toggle("Prepare a morning episode", "Produced in the background; you get a popup when it's ready.", s.podAuto) { s.podAuto = it }
        if (s.podAuto) {
            TimeButton(ctx, "Ready by", s.podAutoMinutes) { s.podAutoMinutes = it }
            Toggle("On Mondays, make it a 7-day recap", null, s.podAutoWeeklyOnMonday) { s.podAutoWeeklyOnMonday = it }
        }
        Toggle("Save episodes to Music › AI News Hot", "So any music app can play them.", s.podSaveToMusic) { s.podSaveToMusic = it }
    }
}

@Composable
private fun KeysSection(ctx: Context, s: Settings) {
    s.observe()
    val scope = rememberCoroutineScope()
    var ds by remember { mutableStateOf("") }
    var mm by remember { mutableStateOf("") }
    SectionTitle("API keys")
    Secret("DeepSeek API key", s.deepseekKey) { s.deepseekKey = it }
    if (ds.isNotEmpty()) Hint("DeepSeek: $ds")
    Secret("Xiaomi MiMo API key", s.mimoKey) { s.mimoKey = it }
    if (mm.isNotEmpty()) Hint("MiMo: $mm")
    OutlinedButton(onClick = {
        ds = "testing…"; mm = "testing…"
        scope.launch { ds = KeyCheck.deepseek(s.deepseekKey) }
        scope.launch { mm = KeyCheck.mimo(ctx, s.mimoKey) }
    }) { Text("Test keys") }
}

@Composable
private fun StorageSection(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    val audioMb = remember(tick) { Speaker.cacheBytes(ctx) / 1_048_576.0 }
    val translations = remember(tick) { Browse.cacheCount(ctx) }
    SectionTitle("Storage")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Voice clips: %.1f MB (kept 3 days)".format(audioMb), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { Speaker.clearCache(ctx); tick++ }) { Text("Clear") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Saved translations: $translations", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { Browse.clearCache(ctx); tick++ }) { Text("Clear") }
    }
}

private fun voiceOptions(ctx: Context) = MiMo.builtInVoices.map { it.first to it.first } +
    MiMo.customVoices(ctx).map { ("file:$it") to MiMo.voiceLabel("file:$it") }

// MARK: Building blocks

/**
 * Subscribe this section to settings changes. With Compose strong skipping, a section that only receives the
 * (unchanged) Settings object would otherwise be skipped and keep showing — and acting on — stale values.
 */
@Composable
private fun Settings.observe(): Int = version.collectAsState().value

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Toggle(label: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Hint(hint)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun LabeledSlider(label: String, value: Int, range: IntRange, hint: String? = null, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column {
        Text("$label: ${local.toInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = local, onValueChange = { local = it }, valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (range.last - range.first <= 12) range.last - range.first - 1 else 0, onValueChangeFinished = { onChange(local.toInt()) })
        if (hint != null) Hint(hint)
    }
}

/** Saves 0.6 s after you pause typing, and immediately when the field leaves the screen, so no edit is lost. */
@Composable
fun Field(label: String, value: String, placeholder: String, lines: Int = 1, onChange: (String) -> Unit) {
    var local by remember { mutableStateOf(value) }
    val latest by rememberUpdatedState(local)
    val save by rememberUpdatedState(onChange)
    val saved by rememberUpdatedState(value)
    LaunchedEffect(local) {
        kotlinx.coroutines.delay(600)
        if (local != saved) save(local)
    }
    DisposableEffect(Unit) { onDispose { if (latest != saved) save(latest) } }
    OutlinedTextField(local, { local = it }, label = { Text(label) }, placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(), singleLine = lines == 1, minLines = lines)
}

@Composable
fun Secret(label: String, value: String, onChange: (String) -> Unit) {
    var local by remember { mutableStateOf(value) }
    val latest by rememberUpdatedState(local)
    val save by rememberUpdatedState(onChange)
    val saved by rememberUpdatedState(value)
    LaunchedEffect(local) {
        kotlinx.coroutines.delay(600)
        if (local.trim() != saved) save(local.trim())
    }
    DisposableEffect(Unit) { onDispose { if (latest.trim() != saved) save(latest.trim()) } }
    OutlinedTextField(local, { local = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}

@Composable
fun TimeButton(ctx: Context, label: String, minutes: Int, onChange: (Int) -> Unit) {
    FilledTonalButton(onClick = {
        TimePickerDialog(ctx, { _, h, m -> onChange(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
    }) { Text("$label %02d:%02d".format(minutes / 60, minutes % 60)) }
}
