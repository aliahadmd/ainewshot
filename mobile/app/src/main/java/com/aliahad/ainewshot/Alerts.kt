package com.aliahad.ainewshot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SysSettings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Notification channels, the alert queue and the "forced popup". */
object Alerts {
    const val CH_ALERTS = "alerts"
    const val CH_ALERTS_QUIET = "alerts_quiet"
    const val CH_LIVE = "live"
    const val CH_PODCAST = "podcast"

    /** Items currently waiting in the popup (AlertActivity observes this). */
    val queue = MutableStateFlow<List<NewsItem>>(emptyList())

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "AI news alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pops up when important AI news arrives"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        })
        // Used when the full-screen popup is already showing: sound + shade entry, but no banner over the popup.
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS_QUIET, "AI news (with popup)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "History entries for alerts that already popped up full-screen"
        })
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Live monitoring", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps AI News Hot checking for news every minute or two"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_PODCAST, "Podcast production", NotificationManager.IMPORTANCE_LOW))
    }

    fun canOverlay(ctx: Context) = SysSettings.canDrawOverlays(ctx)
    fun canFullScreen(ctx: Context) = Build.VERSION.SDK_INT < 34 ||
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun canNotify(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun present(ctx: Context, items: List<NewsItem>) {
        if (items.isEmpty()) return
        queue.value = (queue.value + items.filter { i -> queue.value.none { it.id == i.id } })
        val s = Settings.get(ctx)
        // Forced popup: with "display over other apps" granted, Android lets us open the alert screen from the background.
        val popped = s.popupMode == "fullscreen" && canOverlay(ctx) && runCatching {
            ctx.startActivity(Intent(ctx, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Log.w(TAG, "popup start failed", it) }.isSuccess
        notify(ctx, items, quiet = popped)
        Speaker.announce(ctx, items)
    }

    private fun notify(ctx: Context, items: List<NewsItem>, quiet: Boolean) {
        if (!canNotify(ctx)) return
        val nm = NotificationManagerCompat.from(ctx)
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val digest = items.size >= Settings.get(ctx).digestThreshold
        val list = if (digest) listOf(items.first()) else items
        for ((i, item) in list.withIndex()) {
            val title = if (digest) "${items.size} new AI stories" else item.title
            val text = if (digest) items.joinToString(" · ") { it.title } else item.summary
            val b = NotificationCompat.Builder(ctx, if (quiet) CH_ALERTS_QUIET else CH_ALERTS)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSubText(if (item.kind == "podcast") "Podcast" else item.sourceName)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(open)
            if (!quiet) b.setFullScreenIntent(open, true)   // no overlay permission: wake the screen / heads-up instead
            if (!digest) item.link?.let { url ->
                val read = PendingIntent.getActivity(ctx, item.id.hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                b.addAction(0, "Read", read)
            }
            try { nm.notify(1000 + (item.id.hashCode() and 0xffff) + i, b.build()) } catch (e: SecurityException) { Log.w(TAG, "notify", e) }
        }
    }

    fun dismiss(id: String) { queue.value = queue.value.filterNot { it.id == id } }
    fun clear() { queue.value = emptyList() }

    fun liveNotification(ctx: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }
}
