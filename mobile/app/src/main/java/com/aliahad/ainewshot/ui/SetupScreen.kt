package com.aliahad.ainewshot.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliahad.ainewshot.Alerts
import com.aliahad.ainewshot.HyperOS
import com.aliahad.ainewshot.NewsRepo
import com.aliahad.ainewshot.Settings

/** One row of the reliability checklist. `ok == null` means the app can't read it and you confirm it yourself. */
data class SetupItem(
    val key: String,
    val title: String,
    val why: String,
    val ok: Boolean?,
    val critical: Boolean,
    val fix: (Context) -> Unit,
)

object Setup {
    fun items(ctx: Context): List<SetupItem> {
        val s = Settings.get(ctx)
        val list = mutableListOf(
            SetupItem("notify", "Notifications allowed", "Without this nothing can reach you.", HyperOS.notificationsEnabled(ctx) && Alerts.canNotify(ctx), true) {
                HyperOS.channelSettings(it, Alerts.CH_ALERTS)
            },
            SetupItem("overlay", "Display over other apps (floating window)", "Lets the news card pop up over whatever app you're using.", Alerts.canOverlay(ctx), true) { HyperOS.overlay(it) },
            SetupItem("fullscreen", "Full-screen alerts", "Wakes the screen and shows the alert on the lock screen.", Alerts.canFullScreen(ctx), true) { HyperOS.fullScreen(it) },
            if (HyperOS.isXiaomi)
                // On HyperOS the battery-optimisation exemption *is* App info › Power › Battery saver › "No restrictions".
                SetupItem("battery", "HyperOS: Battery saver → No restrictions", "App info › Power › Battery saver. “Battery saver (recommended)” freezes background apps after a while, which stops live alerts.",
                    HyperOS.ignoringBattery(ctx), true) { HyperOS.battery(it) }
            else SetupItem("battery", "Ignore battery optimisation", "Android Doze would otherwise pause checks while the phone sleeps.", HyperOS.ignoringBattery(ctx), true) { HyperOS.battery(it) },
            SetupItem("alarms", "Exact wake-ups while the screen is off", "Lets the app wake briefly every few minutes to check, even in deep sleep.",
                com.aliahad.ainewshot.Waker.canExact(ctx), false) {
                if (android.os.Build.VERSION.SDK_INT >= 31) HyperOS.open(it, android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${it.packageName}")))
            },
        )
        if (HyperOS.isXiaomi) {
            list += SetupItem("bgpopup", "HyperOS: pop-up windows while in background", "Other permissions › “Display pop-up windows while running in the background”. Without it HyperOS silently blocks the popup.",
                HyperOS.opAllowed(ctx, HyperOS.OP_BACKGROUND_START_ACTIVITY), true) { HyperOS.otherPermissions(it) }
            list += SetupItem("lockpopup", "HyperOS: show on lock screen", "Other permissions › “Show on Lock screen”, so the card appears over the lock screen.",
                HyperOS.opAllowed(ctx, HyperOS.OP_SHOW_WHEN_LOCKED), true) { HyperOS.otherPermissions(it) }
            list += SetupItem("autostart", "HyperOS: Autostart", "Lets AI News Hot restart its live checks after HyperOS cleans memory or after a reboot.",
                HyperOS.opAllowed(ctx, HyperOS.OP_AUTO_START), true) { HyperOS.autostart(it) }
            list += SetupItem("float", "HyperOS: Floating notifications", "Notifications › Floating notifications › AI News Hot ON. HyperOS turns this off for new apps.",
                s.confirmed("float"), false) { HyperOS.floatingNotifications(it) }
            list += SetupItem("lockscreen", "HyperOS: Lock screen notifications", "Notifications › Lock screen notifications › AI News Hot ON, so alerts stay visible after you lock.",
                s.confirmed("lockscreen"), false) { HyperOS.lockScreenNotifications(it) }
            list += SetupItem("ai", "HyperOS: AI notification sorting", "HyperOS can fold apps it thinks are unimportant. Mark AI News Hot as important (or turn sorting off).",
                s.confirmed("ai"), false) { HyperOS.aiNotificationSorting(it) }
            list += SetupItem("recents", "Lock the app in Recents", "Open Recents, long-press AI News Hot, tap the lock. One-tap “Clear all” will then skip it.",
                s.confirmed("recents"), false) { }
            if (HyperOS.focusProtocol(ctx) >= 2) {
                val focus = HyperOS.canShowFocus(ctx)
                list += SetupItem("focus", "Super Island (focus notifications)", if (focus == true) "Allowed — headlines can appear in the island."
                    else "Xiaomi whitelists which apps may use the island; if the switch is missing, it isn't available for this app.",
                    focus ?: s.confirmed("focus"), false) { HyperOS.focusNotifications(it) }
            }
        }
        return list
    }

    fun missingCritical(ctx: Context) = items(ctx).filter { it.critical && it.ok != true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = Settings.get(ctx)
    val v by s.version.collectAsState()
    val status by NewsRepo.status.collectAsState()
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    var testQueued by remember { mutableStateOf(false) }
    val items = remember(v, resumed) { Setup.items(ctx) }
    val done = items.count { it.ok == true }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Reliability setup") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$done of ${items.size} done", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${if (HyperOS.isXiaomi) "HyperOS" else "Android"} stops background apps aggressively. Each item below closes one way news could fail to reach you. " +
                "Tap a row to open the exact setting; items marked ? can't be read by apps, so confirm them once you've set them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Watchdog
            val gaps = status.interruptions.filter { it > System.currentTimeMillis() - 86_400_000 }
            Card(colors = CardDefaults.cardColors(containerColor = if (gaps.isEmpty()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (gaps.isEmpty()) "Live alerts ran without interruption in the last 24 h" else "Live alerts were stopped ${gaps.size}× in the last 24 h",
                        fontWeight = FontWeight.SemiBold)
                    Text(status.lastCheck?.let { "Last check ${ago(it)}" } ?: "No check yet", style = MaterialTheme.typography.bodySmall)
                    if (gaps.isNotEmpty()) Text("Most recent: ${ago(gaps.last())}. Usually Battery saver or Autostart.", style = MaterialTheme.typography.bodySmall)
                }
            }

            items.forEach { item -> SetupRow(item, onConfirm = { s.setConfirmed(item.key, it) }) }

            Text("Test it", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("Starts a test alert in 20 seconds. Switch to another app or lock the phone and check that the card pops up and speaks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { NewsRepo.scheduleTest(ctx, 20); testQueued = true }, enabled = !testQueued) {
                Text(if (testQueued) "Test alert in 20 s — go lock your phone" else "Send test alert in 20 s")
            }
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem, onConfirm: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (mark, color) = when (item.ok) {
                true -> "✓" to Color(0xFF2E9E4F)
                false -> "!" to MaterialTheme.colorScheme.error
                null -> "?" to Color(0xFFE0A100)
            }
            Text(mark, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.size(26.dp).background(color, CircleShape).padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title + if (item.critical) "" else "  (recommended)", fontWeight = FontWeight.SemiBold)
                Text(item.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.key != "recents") FilledTonalButton(onClick = { item.fix(ctx) }) { Text(if (item.ok == true) "Open" else "Fix") }
                    if (item.key in Settings.CONFIRMABLE) {
                        TextButton(onClick = { onConfirm(item.ok != true) }) { Text(if (item.ok == true) "Undo" else "I've done this") }
                    }
                }
            }
        }
    }
}
