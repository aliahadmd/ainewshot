package com.aliahad.ainewshot

import android.app.Application
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
        ServiceCompat.startForeground(this, NOTIF_ID, Alerts.liveNotification(this, "Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (loop == null) loop = lifecycleScope.launch {
            while (true) {
                NewsRepo.check(this@LiveService)
                val st = NewsRepo.status.value
                val text = st.error ?: st.lastCheck?.let {
                    "Checked " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                        (if (st.held > 0) " · ${st.held} held" else "")
                } ?: "Connecting…"
                runCatching { NotificationManagerCompat.from(this@LiveService).notify(NOTIF_ID, Alerts.liveNotification(this@LiveService, text)) }
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Settings.get(ctx).liveAlerts) LiveService.start(ctx)
    }
}
