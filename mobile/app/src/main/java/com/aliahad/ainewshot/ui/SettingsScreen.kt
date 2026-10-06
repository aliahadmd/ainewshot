package com.aliahad.ainewshot.ui

import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliahad.ainewshot.Alerts
import com.aliahad.ainewshot.BuildConfig
import com.aliahad.ainewshot.LiveService
import com.aliahad.ainewshot.MiMo
import com.aliahad.ainewshot.NewsItem
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.PodStyle
import com.aliahad.ainewshot.Settings
import com.aliahad.ainewshot.Speaker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    val v by s.version.collectAsState()
    // Re-read permission state whenever we come back from a system settings page.
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        run {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Reading these makes the screen refresh after a setting changes or we return from system settings,
                // without resetting text fields (which keep their own state).
                if (v < 0 || resumed < 0) Text("")
                PermissionsSection(ctx, s)
                AlertsSection(ctx, s)
                TranslationSection(s)
                AudioSection(ctx, s)
                PodcastSection(ctx, s)
                SectionTitle("API keys")
                Secret("DeepSeek API key", s.deepseekKey) { s.deepseekKey = it }
                Secret("MiMo API key", s.mimoKey) { s.mimoKey = it }
                Text("AI News Hot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun PermissionsSection(ctx: Context, s: Settings) {
    SectionTitle("Permissions & reliability")
    val pm = ctx.getSystemService(PowerManager::class.java)
    PermRow("Notifications", Alerts.canNotify(ctx)) {
        ctx.startActivity(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    PermRow("Pop up over other apps", Alerts.canOverlay(ctx)) {
        ctx.startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    }
    PermRow("Full-screen alerts on lock screen", Alerts.canFullScreen(ctx)) {
        if (Build.VERSION.SDK_INT >= 34) ctx.startActivity(Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
    }
    PermRow("Unrestricted battery (keeps live alerts running)", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }
    if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)) {
        Text("HyperOS also needs “Autostart” and “Display pop-up windows while running in the background” in Other permissions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = { openMiuiPermissions(ctx) }) { Text("Open HyperOS app permissions") }
    }
}

fun openMiuiPermissions(ctx: Context) {
    val tries = listOf(
        Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")).putExtra("extra_pkgname", ctx.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName),
        Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )
    for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

@Composable
private fun PermRow(label: String, ok: Boolean, fix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) Color(0xFF2E9E4F) else MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = { runCatching(fix) }) { Text("Allow") }
    }
}

@Composable
private fun AlertsSection(ctx: Context, s: Settings) {
    val scope = rememberCoroutineScope()
    SectionTitle("Alerts")
    Toggle("Live alerts (checks every ${s.pollSeconds}s)", s.liveAlerts) {
        s.liveAlerts = it
        if (it) LiveService.start(ctx) else LiveService.stop(ctx)
    }
    ChoiceRow("Feed", listOf("firstParty" to "First-hand (official)", "selected" to "All curated", "all" to "Everything"), s.feedMode) { s.feedMode = it }
    Text(when (s.feedMode) {
        "firstParty" -> "Same as aihot.news › 精选 › 只看一手: announcements straight from OpenAI, Anthropic, Google… ≈8 a day."
        "selected" -> "≈15–25 a day, adds media and commentators."
        else -> "Hundreds a day. Use keywords or a high score."
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceRow("How alerts appear", listOf("fullscreen" to "Pop up over apps", "notification" to "Notification only"), s.popupMode) { s.popupMode = it }
    LabeledSlider("Minimum AIHOT score", s.minScore, 0..90) { s.minScore = it }
    Field("Always alert on keywords", s.includeKeywords, "claude, anthropic, deepseek") { s.includeKeywords = it }
    Field("Never alert on keywords", s.excludeKeywords, "crypto, funding round") { s.excludeKeywords = it }
    LabeledSlider("Max popups per hour", s.maxPerHour, 1..20) { s.maxPerHour = it }
    Toggle("Quiet hours (alerts wait, then arrive together)", s.quietEnabled) { s.quietEnabled = it }
    if (s.quietEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeButton(ctx, "From", s.quietStart) { s.quietStart = it }
        TimeButton(ctx, "To", s.quietEnd) { s.quietEnd = it }
    }
    Button(onClick = {
        val sample = NewsRepo.recent.value.firstOrNull() ?: NewsItem(id = "test", title = "This is a test alert", summary = "News will look like this.", sourceName = "AI News Hot")
        scope.launch { NewsRepo.deliver(ctx, listOf(sample.copy(id = "test-${System.currentTimeMillis()}")), bypassRate = true) }
    }) { Text("Send a test alert") }
}

@Composable
private fun TranslationSection(s: Settings) {
    SectionTitle("Translation & AI")
    ChoiceRow("Translate with", listOf("deepseek" to "DeepSeek AI", "original" to "Source's English title only"), s.translation) { s.translation = it }
    ChoiceRow("Model", listOf("deepseek-flash" to "Flash (fast)", "deepseek-v4-pro" to "V4 Pro"), s.deepseekModel) { s.deepseekModel = it }
    Field("About me (for relevance & Explain)", s.interestProfile, "", lines = 3) { s.interestProfile = it }
}

@Composable
private fun AudioSection(ctx: Context, s: Settings) {
    SectionTitle("Audio")
    Toggle("Read alerts aloud", s.audioEnabled) { s.audioEnabled = it }
    Toggle("Stay silent during calls", s.silentInCalls) { s.silentInCalls = it }
    ChoiceRow("Voice engine", listOf("mimo" to "Xiaomi MiMo (natural)", "system" to "Phone voice (offline)"), s.voiceProvider) { s.voiceProvider = it }
    ChoiceRow("MiMo voice", MiMo.builtInVoices.map { it.first to it.first }, s.voice) { s.voice = it }
    Field("Speaking style (MiMo direction)", s.voiceStyle, "", lines = 2) { s.voiceStyle = it }
    FilledTonalButton(onClick = {
        Speaker.play(ctx, NewsItem(id = "voice-test-${System.currentTimeMillis()}", title = "", summary = "",
            spoken = "This is AI News Hot. Mistral just released Large 4 in preview, with open weights coming at the end of the month."))
    }) { Text("Test voice") }
}

@Composable
private fun PodcastSection(ctx: Context, s: Settings) {
    val scope = rememberCoroutineScope()
    var designName by remember { mutableStateOf("Nova") }
    var designPrompt by remember { mutableStateOf("A woman in her late twenties with a smooth, clear, slightly husky voice; confident late-night radio host; relaxed, warm pace.") }
    var status by remember { mutableStateOf("") }
    val voices = MiMo.builtInVoices.map { it.first to it.first } + MiMo.customVoices(ctx).map { ("file:$it") to MiMo.voiceLabel("file:$it") }

    SectionTitle("Podcast")
    ChoiceRow("Script writer", listOf("deepseek-v4-pro" to "V4 Pro (best)", "deepseek-flash" to "Flash (faster)"), s.podWriterModel) { s.podWriterModel = it }
    Toggle("Sung intro jingle (MiMo singing mode)", s.podJingle) { s.podJingle = it }
    Field("Host A name", s.podHostAName, "") { s.podHostAName = it }
    ChoiceRow("Host A voice", voices, s.podHostAVoice) { s.podHostAVoice = it }
    Field("Host A persona ([Character] for MiMo)", s.podHostAPersona, "", lines = 3) { s.podHostAPersona = it }
    if (s.podFormat == "duo") {
        Field("Host B name", s.podHostBName, "") { s.podHostBName = it }
        ChoiceRow("Host B voice", voices, s.podHostBVoice) { s.podHostBVoice = it }
        Field("Host B persona", s.podHostBPersona, "", lines = 3) { s.podHostBPersona = it }
    }
    Text("Design a custom voice: MiMo VoiceDesign invents it from your description once, then it's reused via VoiceClone so it stays identical on every line.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(designName, { designName = it }, label = { Text("Voice name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(designPrompt, { designPrompt = it }, label = { Text("Voice description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    FilledTonalButton(onClick = {
        status = "Designing…"
        scope.launch {
            status = runCatching { "Created " + MiMo.design(ctx, designName, designPrompt, s.mimoKey) + " — pick it as a host voice." }
                .getOrElse { "Failed: ${it.message}" }
            s.version.value++
        }
    }) { Text("Design voice") }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    Toggle("Prepare a morning episode automatically", s.podAuto) { s.podAuto = it }
    if (s.podAuto) {
        TimeButton(ctx, "Ready by", s.podAutoMinutes) { s.podAutoMinutes = it }
        Toggle("On Mondays, make it a 7-day recap", s.podAutoWeeklyOnMonday) { s.podAutoWeeklyOnMonday = it }
    }
    Toggle("Also save episodes to Music › AI News Hot", s.podSaveToMusic) { s.podSaveToMusic = it }
    Text("Show: ${PodStyle.SHOW}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// MARK: small controls

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun LabeledSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var local by remember { mutableStateOf(value.toFloat()) }
    Column {
        Text("$label: ${local.toInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = local, onValueChange = { local = it }, valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { onChange(local.toInt()) })
    }
}

@Composable
fun Field(label: String, value: String, placeholder: String, lines: Int = 1, onChange: (String) -> Unit) {
    var local by remember { mutableStateOf(value) }
    OutlinedTextField(local, { local = it; onChange(it) }, label = { Text(label) }, placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(), singleLine = lines == 1, minLines = lines)
}

@Composable
fun Secret(label: String, value: String, onChange: (String) -> Unit) {
    var local by remember { mutableStateOf(value) }
    OutlinedTextField(local, { local = it; onChange(it.trim()) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
}

@Composable
fun TimeButton(ctx: Context, label: String, minutes: Int, onChange: (Int) -> Unit) {
    FilledTonalButton(onClick = {
        TimePickerDialog(ctx, { _, h, m -> onChange(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
    }) { Text("$label %02d:%02d".format(minutes / 60, minutes % 60)) }
}
