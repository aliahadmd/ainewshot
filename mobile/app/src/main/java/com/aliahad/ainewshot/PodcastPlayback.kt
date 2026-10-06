package com.aliahad.ainewshot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Produces an episode in the foreground so it keeps going if you leave the app (≈3 min for a day). */
class PodcastService : LifecycleService() {
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); stopSelf(); return START_NOT_STICKY }
        val days = intent?.getIntExtra("days", 1) ?: 1
        val announce = intent?.getBooleanExtra("announce", false) ?: false
        ServiceCompat.startForeground(this, NOTIF_ID, progress("Starting…", 0f),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (job?.isActive == true) return START_NOT_STICKY
        job = lifecycleScope.launch {
            val ticker = launch {
                while (true) {
                    val st = PodcastStudio.state.value
                    runCatching { NotificationManagerCompat.from(this@PodcastService).notify(NOTIF_ID, progress(PodcastStudio.stageLabels[st.stage] + " · " + st.detail, st.progress)) }
                    delay(1000)
                }
            }
            try {
                val ep = PodcastStudio.generate(this@PodcastService, days)
                if (announce) {
                    NewsRepo.route(this@PodcastService, listOf(NewsItem(id = "podcast-${ep.id}", kind = "podcast",
                        title = "Your podcast is ready: ${ep.title}", summary = ep.summary, sourceName = PodStyle.SHOW,
                        why = "${PodcastStudio.mmss(ep.duration)} · ${ep.periodLabel.lowercase()} · ${ep.notes.size} stories from ${ep.itemsScanned} news items",
                        episodeId = ep.id)), bypassRate = true)
                }
            } catch (_: Throwable) {
            } finally {
                ticker.cancel()
                ServiceCompat.stopForeground(this@PodcastService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun progress(text: String, p: Float) = NotificationCompat.Builder(this, Alerts.CH_PODCAST)
        .setSmallIcon(R.drawable.ic_bolt)
        .setContentTitle("Producing your podcast")
        .setContentText(text)
        .setProgress(100, (p * 100).toInt(), p == 0f)
        .setOngoing(true).setSilent(true)
        .setContentIntent(android.app.PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).putExtra("tab", 1), android.app.PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val NOTIF_ID = 8
        const val ACTION_CANCEL = "cancel"
        fun start(ctx: Context, days: Int, announce: Boolean = false) {
            ctx.startForegroundService(Intent(ctx, PodcastService::class.java).putExtra("days", days).putExtra("announce", announce))
        }
        fun cancel(ctx: Context) { ctx.startService(Intent(ctx, PodcastService::class.java).setAction(ACTION_CANCEL)) }
    }
}

/** Media3 session: lock-screen controls, Bluetooth/headset buttons, Android Auto-style media notification. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** App-side handle on the playback service. */
object PodcastPlayer {
    val current = MutableStateFlow<Episode?>(null)
    val isPlaying = MutableStateFlow(false)
    val position = MutableStateFlow(0.0)
    val speed = MutableStateFlow(1f)
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private fun withController(ctx: Context, block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            val c = try { f.get() } catch (e: Exception) {
                android.util.Log.e(TAG, "podcast player: couldn't connect to PlaybackService", e); return@addListener
            }
            android.util.Log.i(TAG, "podcast player: connected")
            controller = c
            speed.value = Settings.get(ctx).podSpeed.toFloatOrNull() ?: 1f
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying.value = playing
                    position.value = c.currentPosition / 1000.0
                    // Only tick while audio is actually playing: no wake-ups when paused or in the background idle.
                    ticker?.cancel()
                    if (playing) ticker = scope.launch { while (true) { position.value = c.currentPosition / 1000.0; delay(500) } }
                }
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    position.value = c.currentPosition / 1000.0   // seeks show immediately
                }
            })
            // Reconnecting to a session that is already playing: sync state and start ticking.
            isPlaying.value = c.isPlaying
            position.value = c.currentPosition / 1000.0
            if (c.isPlaying) { ticker?.cancel(); ticker = scope.launch { while (true) { position.value = c.currentPosition / 1000.0; delay(500) } } }
            block(c)
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
    }

    fun play(ctx: Context, ep: Episode, at: Double? = null) {
        withController(ctx.applicationContext) { c ->
            if (current.value?.id != ep.id) {
                current.value = ep
                c.setMediaItem(MediaItem.Builder().setMediaId(ep.id).setUri(Uri.fromFile(File(ep.audioPath)))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(ep.title).setArtist(PodStyle.SHOW)
                        .setAlbumTitle(ep.periodLabel).setIsPlayable(true).build()).build())
                c.prepare()
                c.setPlaybackSpeed(speed.value)
            }
            at?.let { c.seekTo((it * 1000).toLong()) }
            c.play()
            android.util.Log.i(TAG, "podcast player: play ${ep.id} at ${at ?: 0.0}")
        }
    }

    fun toggle(ctx: Context, ep: Episode) {
        val c = controller
        if (c != null && current.value?.id == ep.id) { if (c.isPlaying) c.pause() else c.play() } else play(ctx, ep)
    }

    fun seek(t: Double) { controller?.seekTo((t * 1000).toLong()) }
    fun skip(d: Double) { controller?.let { it.seekTo(maxOf(0L, it.currentPosition + (d * 1000).toLong())) } }
    fun setSpeed(ctx: Context, s: Float) { speed.value = s; controller?.setPlaybackSpeed(s); Settings.get(ctx).podSpeed = s.toString() }
    fun pause() { controller?.pause() }
    fun stop() { controller?.stop(); controller?.clearMediaItems(); current.value = null }

    fun currentLine(ep: Episode) = ep.lines.indexOfLast { it.start <= position.value + 0.05 }.takeIf { it >= 0 }
    fun currentChapter(ep: Episode) = ep.chapters.indexOfLast { it.start <= position.value + 0.05 }.takeIf { it >= 0 }
}
