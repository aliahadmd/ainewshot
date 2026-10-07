package com.aliahad.ainewshot

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.os.PowerManager
import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.createChannels(this)
        NewsRepo.load(this)
        PodcastStudio.load(this)
        PollWorker.schedule(this)
        NewsRepo.appScope.launch { Speaker.purge(this@App) }
        if (Settings.get(this).liveAlerts) LiveService.start(this)
    }
}

/**
 * Foreground service that keeps checking every ~90 s while "Live alerts" is on, so news pops up as it lands.
 * (Android only allows frequent background work inside a visible foreground service.)
 */
class LiveService : LifecycleService() {
    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(this, NOTIF_ID, Alerts.liveNotification(this, "Watching for AI news", "Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (loop == null) loop = lifecycleScope.launch {
            while (true) {
                NewsRepo.check(this@LiveService)
                val st = NewsRepo.status.value
                // The always-on notification doubles as a ticker: newest headline + freshness.
                val latest = NewsRepo.recent.value.firstOrNull { it.kind == "news" }
                val unread = NewsRepo.unread().size
                val meta = st.error ?: st.lastCheck?.let {
                    "checked " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                        (if (unread > 0) " · $unread unread" else "") + (if (st.held > 0) " · ${st.held} held" else "")
                } ?: "Connecting…"
                runCatching {
                    NotificationManagerCompat.from(this@LiveService).notify(NOTIF_ID,
                        Alerts.liveNotification(this@LiveService, latest?.title ?: "Watching for AI news", meta))
                }
                delay(Settings.get(this@LiveService).pollSeconds.coerceAtLeast(60) * 1000L)
            }
        }
        return START_STICKY
    }

    companion object {
        const val NOTIF_ID = 7
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, LiveService::class.java)) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LiveService::class.java)) }
    }
}

/** Backup check every 15 minutes (WorkManager survives process death and reboots). */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        NewsRepo.check(applicationContext)
        if (Settings.get(applicationContext).liveAlerts) LiveService.start(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("poll", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}

/**
 * Doze-safe wake-ups. With the screen off the CPU suspends and the live service's timer stops, so an alarm
 * wakes us every few minutes (Doze stretches this to ~10 min in deep sleep) to check with a short wakelock.
 */
object Waker {
    private const val INTERVAL_MS = 4 * 60_000L

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WakeReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!Settings.get(ctx).liveAlerts) { am.cancel(pi); android.util.Log.i(TAG, "waker: live alerts off, wake-ups cancelled"); return }
        android.util.Log.i(TAG, "waker: next wake-up in ${INTERVAL_MS / 60_000} min")
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        runCatching {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }
}

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AINewsHot:check")
        wl.acquire(60_000)
        NewsRepo.appScope.launch {
            try {
                NewsRepo.check(ctx)
                if (Settings.get(ctx).liveAlerts) LiveService.start(ctx)
            } finally {
                if (wl.isHeld) wl.release()
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Settings.get(ctx).liveAlerts) LiveService.start(ctx)
    }
}
