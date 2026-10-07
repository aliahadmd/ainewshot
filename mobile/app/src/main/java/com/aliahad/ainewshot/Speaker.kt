package com.aliahad.ainewshot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Speaks news. Order of preference:
 *  1. a pre-rendered MiMo clip (alerts are rendered before they pop up; recent stories are prefetched on Wi-Fi),
 *  2. MiMo *streamed* straight into an AudioTrack, so speech starts with the first chunk instead of the whole clip,
 *  3. the phone's own TTS if MiMo doesn't answer within 12 s.
 * Plays on the media stream (your volume buttons control it), ducks other audio, and pauses the podcast.
 */
object Speaker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val playingId = MutableStateFlow<String?>(null)
    val preparingId = MutableStateFlow<String?>(null)
    /** Consecutive MiMo failures (bad key, quota, outage). 3+ shows a warning; any success resets it. */
    val mimoFailures = MutableStateFlow(0)
    val mimoLastError = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private var job: Job? = null
    private var focus: AudioFocusRequest? = null
    private var prefetchJob: Job? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun cacheFile(ctx: Context, text: String, s: Settings): File {
        val h = MessageDigest.getInstance("SHA-256").digest((s.voice + "|" + s.voiceStyle + "|" + text).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(File(ctx.cacheDir, "audio").apply { mkdirs() }, "$h.wav")
    }

    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "audio")

    /** Keep the voice cache small: drop clips older than [days] days. */
    fun purge(ctx: Context, days: Int = 3) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        cacheDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    fun clearCache(ctx: Context) { cacheDir(ctx).listFiles()?.forEach { it.delete() } }

    fun cacheBytes(ctx: Context) = cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun isCached(ctx: Context, item: NewsItem) = cacheFile(ctx, item.speechText, Settings.get(ctx)).exists()

    /** Render (or reuse) a validated MiMo clip. Used for alerts and Wi-Fi prefetch. */
    suspend fun prepare(ctx: Context, item: NewsItem): File? {
        val s = Settings.get(ctx)
        if (s.voiceProvider != "mimo" || s.mimoKey.isBlank()) return null
        val f = cacheFile(ctx, item.speechText, s)
        if (f.exists()) return f
        return runCatching {
            val pcm = MiMo.checkedPcm(ctx, item.speechText, s.voiceStyle, s.voice, s.mimoKey)
            f.writeBytes(MiMo.wav(pcm)); f
        }.onSuccess { mimoFailures.value = 0 }
            .onFailure { Log.w(TAG, "TTS failed", it); mimoFailures.value++; mimoLastError.value = it.message }.getOrNull()
    }

    /** Pre-render the newest stories on unmetered networks so "Listen" is instant. */
    fun prefetch(ctx: Context, items: List<NewsItem>) {
        val app = ctx.applicationContext
        val s = Settings.get(app)
        if (prefetchJob?.isActive == true || s.voiceProvider != "mimo" || s.mimoKey.isBlank()) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) return
        val todo = items.filter { !isCached(app, it) }
        if (todo.isEmpty()) return
        prefetchJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (it in todo) prepare(app, it)
            Log.i(TAG, "prefetched audio for ${todo.size} stories")
        }
    }

    private fun busy(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** Called when alerts arrive: speak the first item (or a rundown for a digest). */
    fun announce(ctx: Context, items: List<NewsItem>) {
        val s = Settings.get(ctx)
        if (!s.audioEnabled || items.isEmpty()) return
        if (s.silentInCalls && busy(ctx)) return
        if (PodcastPlayer.isPlaying.value) return            // don't talk over the podcast
        if (items.first().kind == "podcast") return
        val item = if (items.size >= s.digestThreshold) digest(items) else items.first()
        play(ctx, item, userInitiated = false)
    }

    fun digest(items: List<NewsItem>): NewsItem {
        val titles = items.take(8).map { it.title }
        val sb = StringBuilder("You have ${items.size} new AI stories. ")
        titles.forEachIndexed { i, t -> sb.append(if (i == 0) "First: " else if (i == titles.lastIndex) "And finally: " else "Next: ").append(t).append(". ") }
        return NewsItem(id = "digest-" + items.joinToString("") { it.id }.hashCode(), title = "${items.size} new AI stories", summary = "", spoken = sb.toString())
    }

    fun toggle(ctx: Context, item: NewsItem) { if (playingId.value == item.id) stop() else play(ctx, item) }

    fun play(ctx: Context, item: NewsItem, userInitiated: Boolean = true) {
        stop()
        val app = ctx.applicationContext
        if (userInitiated) PodcastPlayer.pause()
        playingId.value = item.id
        job = scope.launch {
            val s = Settings.get(app)
            val cached = cacheFile(app, item.speechText, s).takeIf { it.exists() }
            requestFocus(app)
            try {
                when {
                    cached != null -> playFile(app, cached)
                    s.voiceProvider == "mimo" && s.mimoKey.isNotBlank() -> if (!stream(app, item, s)) speakSystem(app, item)
                    else -> speakSystem(app, item)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "speak failed", e)
                finished(app)
            }
        }
    }

    private suspend fun playFile(ctx: Context, f: File) {
        val done = CompletableDeferred<Unit>()
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(f.path)
            setOnCompletionListener { done.complete(Unit) }
            setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            prepare(); start()
        }
        done.await()
        finished(ctx)
    }

    /** MiMo → AudioTrack as chunks arrive. Cuts run-on takes at the expected maximum; caches good takes. */
    private suspend fun stream(ctx: Context, item: NewsItem, s: Settings): Boolean = withContext(Dispatchers.IO) {
        preparingId.value = item.id
        val text = item.speechText
        val (minSec, _, maxSec) = MiMo.limits(text)
        val maxBytes = (maxSec * MiMo.RATE).toInt() * 2
        val minBuf = AudioTrack.getMinBufferSize(MiMo.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(MiMo.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuf * 4, MiMo.RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        val all = ByteArrayOutputStream()
        var written = 0
        var started = false
        val first = CompletableDeferred<Unit>()
        try {
            coroutineScope {
                val net = launch {
                    try {
                        MiMo.pcm(ctx, text, s.voiceStyle, s.voice, s.mimoKey) { chunk ->
                            if (!started) { started = true; preparingId.value = null; t.play(); first.complete(Unit) }
                            all.write(chunk)
                            if (written < maxBytes) {
                                val n = min(chunk.size, maxBytes - written)
                                t.write(chunk, 0, n)
                                written += n
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        mimoLastError.value = e.message
                        if (!first.isCompleted) first.completeExceptionally(e) else Log.w(TAG, "stream ended early", e)
                    }
                }
                val ok = runCatching { withTimeout(12_000) { first.await() } }.isSuccess
                if (!ok) { net.cancel(); mimoFailures.value++; return@coroutineScope }
                mimoFailures.value = 0
                net.join()
                // Let the buffered tail play out.
                val frames = written / 2
                while (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) delay(80)
            }
            if (!started) return@withContext false
            val pcm = all.toByteArray()
            val secs = pcm.size / 2.0 / MiMo.RATE
            if (secs in minSec..maxSec) cacheFile(ctx, text, s).writeBytes(MiMo.wav(MiMo.trimSilence(pcm)))
            withContext(Dispatchers.Main) { finished(ctx) }
            true
        } finally {
            preparingId.value = null
            runCatching { t.stop() }; t.release()
            if (track === t) track = null
        }
    }

    private suspend fun speakSystem(ctx: Context, item: NewsItem) {
        if (tts == null) {
            ttsReady = CompletableDeferred()
            tts = TextToSpeech(ctx) { ttsReady.complete(it == TextToSpeech.SUCCESS) }
        }
        if (!ttsReady.await()) { finished(ctx); return }
        tts?.setAudioAttributes(attrs)
        tts?.language = Locale.US
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { finished(ctx) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { scope.launch { finished(ctx) } }
        })
        tts?.speak(item.speechText, TextToSpeech.QUEUE_FLUSH, null, item.id)
    }

    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus!!)
    }

    private fun finished(ctx: Context) {
        playingId.value = null
        preparingId.value = null
        player?.release(); player = null
        focus?.let { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    fun stop() {
        job?.cancel()
        runCatching { player?.stop() }
        player?.release(); player = null
        track?.let { runCatching { it.pause(); it.flush() } }
        tts?.stop()
        playingId.value = null
        preparingId.value = null
    }
}
