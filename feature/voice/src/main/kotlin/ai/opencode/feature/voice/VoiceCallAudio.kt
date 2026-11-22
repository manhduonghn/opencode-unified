package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.Tunable
import ai.opencode.server.OpenCodeTransport
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Music or a clip an agent plays on the call (`android_voice_play_audio`). It plays on the call's
 * route as voice communication, so the platform's echo canceller hears it as echo to cancel, and it
 * ducks while either side speaks.
 */
object CallAudioTunables {
    val volume = Tunable("call.audio.volume", "Music Volume", default = 0.8, range = 0.1..1.0, step = 0.05)
    val duckLevel = Tunable("call.audio.duckLevel", "Music While Speaking", default = 0.2, range = 0.0..1.0, step = 0.05)
    val attack = Tunable("call.audio.attack", "Duck Over (s)", default = 0.15, range = 0.03..1.0, step = 0.01)
    val release = Tunable("call.audio.release", "Come Back Over (s)", default = 0.8, range = 0.1..3.0, step = 0.05)
    val hold = Tunable("call.audio.hold", "Hold After Speech (s)", default = 0.6, range = 0.0..3.0, step = 0.05)
    val agentLevel = Tunable("call.audio.agentLevel", "Voice Speaking Level", default = 0.2, range = 0.02..0.9, step = 0.02)
}

data class CallAudioTrack(
    val id: UUID,
    val title: String,
    /** Seconds, when the file says. */
    val duration: Double?,
)

sealed interface CallAudioEvent {
    /** Someone started or stopped speaking over it. */
    data class Ducked(val isDucked: Boolean) : CallAudioEvent

    /** The track played to its end. */
    data class Finished(val id: UUID) : CallAudioEvent
}

sealed interface CallAudioFailure {
    data object NoCall : CallAudioFailure
    data class Download(val reason: String) : CallAudioFailure
    data object TooLarge : CallAudioFailure
    data object Unsupported : CallAudioFailure
    data object Engine : CallAudioFailure

    val message: String
        get() = when (this) {
            NoCall -> "Audio plays only during a live voice call in the OpenCode Android app."
            is Download -> "The phone couldn’t download the audio ($reason)."
            TooLarge -> "The audio is larger than ${CallAudioPlayer.MAX_BYTES / 1_000_000} MB."
            Unsupported -> "The phone couldn’t read that audio. Use an MP3, M4A/AAC, WAV, OGG or FLAC file."
            Engine -> "The call’s audio couldn’t start playing it."
        }
}

sealed interface CallAudioResult {
    data class Started(val track: CallAudioTrack) : CallAudioResult
    data class Failed(val failure: CallAudioFailure) : CallAudioResult
}

/**
 * How loud the music is while people talk: down to `duckLevel` within `attack` of anyone
 * speaking, back up over `release` once both sides have been quiet for `hold`. Exponential
 * approach, reaching 95% in the ramp time.
 */
class CallAudioDucker {
    data class Settings(
        val duckLevel: Double,
        val attack: Double,
        val release: Double,
        val hold: Double,
        val userLevel: Float,
        val agentLevel: Float,
    ) {
        companion object {
            val current: Settings
                get() = Settings(
                    duckLevel = CallAudioTunables.duckLevel.value,
                    attack = CallAudioTunables.attack.value,
                    release = CallAudioTunables.release.value,
                    hold = CallAudioTunables.hold.value,
                    userLevel = VoiceTurnTunables.micVoiceLevel.value.toFloat(),
                    agentLevel = CallAudioTunables.agentLevel.value.toFloat(),
                )
        }
    }

    var gain = 1.0
        private set
    var isDucked = false
        private set
    private var lastSpeech: Double? = null

    /** Advances to [time] (seconds), [dt] after the last step. */
    fun step(time: Double, dt: Double, user: Float, assistant: Float, settings: Settings): Double {
        if (user >= settings.userLevel || assistant >= settings.agentLevel) lastSpeech = time
        val last = lastSpeech
        isDucked = last != null && time - last <= settings.hold
        val target = if (isDucked) settings.duckLevel else 1.0
        // ln(20) time constants: 95% of the way in the ramp's time.
        val ramp = if (target < gain) settings.attack else settings.release
        val k = 1 - exp(-dt * 3 / max(ramp, 0.001))
        gain += (target - gain) * k
        if (abs(target - gain) < 0.001) gain = target
        return gain
    }
}

/**
 * The microphone's level while music plays and neither voice is speaking, for the call log: a
 * high floor there means the music leaks past echo cancellation. Levels only, never audio.
 */
class CallAudioLeakWindow {
    private val quiet = mutableListOf<Float>()
    private var ticks = 0
    private var duckedTicks = 0

    fun add(user: Float, assistant: Float, ducked: Boolean, agentLevel: Float) {
        ticks += 1
        if (ducked) duckedTicks += 1
        if (assistant < agentLevel / 2) quiet.add(user)
    }

    val summary: String?
        get() {
            if (ticks == 0) return null
            var line = "Music: ducked ${duckedTicks * 100 / ticks}% of window"
            if (quiet.isNotEmpty()) {
                val sorted = quiet.sorted()
                val p50 = sorted[sorted.size / 2]
                val p90 = sorted[min(sorted.size - 1, sorted.size * 9 / 10)]
                line += String.format(Locale.US, " · mic while agent quiet p50 %.2f p90 %.2f", p50, p90)
            }
            return line
        }
}

/**
 * The call's music player. `VoiceAudioEngine` attaches it when a call's audio starts and detaches
 * it when the call stops; the reducer reaches it only through [CallAudioClient].
 *
 * A Media3 `ExoPlayer` plays it as voice communication (the call's route, inside the platform echo
 * canceller's reference) without taking audio focus from the call. It runs on its own looper
 * thread, which also steps the ducker.
 */
class CallAudioPlayer private constructor() {
    private val thread = HandlerThread("call-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Everything below is touched only on [thread].
    private var owner: UUID? = null
    private var demo = false
    private var isInterrupted = false
    private var player: ExoPlayer? = null
    private var track: CallAudioTrack? = null
    private var file: File? = null
    private var isPaused = false
    private var ducker = CallAudioDucker()
    private var ticker: Job? = null
    private var leak = CallAudioLeakWindow()
    private val listeners = mutableMapOf<UUID, (CallAudioEvent) -> Unit>()

    /** A call's audio started: tracks can play until [detach] with the same [engine]. */
    fun attach(engine: UUID, demo: Boolean) {
        handler.post {
            stopPlayback()
            owner = engine
            this.demo = demo
            isInterrupted = false
            leak = CallAudioLeakWindow()
        }
    }

    /** The call stopped: the music stops with it. */
    fun detach(engine: UUID) {
        handler.post {
            if (owner != engine) return@post
            stopPlayback()
            owner = null
            player?.release()
            player = null
        }
    }

    /** Another app or call took the audio, or gave it back: a track carries on after. */
    fun interrupted(engine: UUID, interrupted: Boolean) {
        handler.post {
            if (owner != engine) return@post
            isInterrupted = interrupted
            val player = player ?: return@post
            if (track == null || isPaused) return@post
            if (interrupted) player.pause() else player.play()
        }
    }

    /** The microphone while music plays, for the call log; null without music. */
    suspend fun takeLeakWindow(): String? = withContext(dispatcher) {
        val summary = leak.summary
        leak = CallAudioLeakWindow()
        summary
    }

    suspend fun play(url: URI, title: String?): CallAudioResult {
        if (withContext(dispatcher) { owner } == null) return CallAudioResult.Failed(CallAudioFailure.NoCall)
        val downloaded = try {
            download(url)
        } catch (failure: DownloadFailed) {
            return CallAudioResult.Failed(failure.failure)
        } catch (_: IOException) {
            val cancelled = !currentCoroutineContext().isActive
            return CallAudioResult.Failed(CallAudioFailure.Download(if (cancelled) "cancelled" else "network error"))
        }
        if (!currentCoroutineContext().isActive) {
            discard(downloaded)
            return CallAudioResult.Failed(CallAudioFailure.Download("cancelled"))
        }
        return try {
            withContext(dispatcher) { start(downloaded, title(title, url)) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                if (file != downloaded) discard(downloaded)
            }
            throw cancelled
        }
    }

    /** On [thread]: prepares [downloaded] and starts it once it's ready, replacing what plays. */
    private suspend fun start(downloaded: File, title: String): CallAudioResult {
        if (owner == null) {
            discard(downloaded)
            return CallAudioResult.Failed(CallAudioFailure.NoCall)
        }
        stopPlayback()
        val player = player ?: newPlayer().also { player = it }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(downloaded)))
        player.volume = CallAudioTunables.volume.value.toFloat()
        player.prepare()
        val failure = awaitReady(player)
        currentCoroutineContext().ensureActive()
        if (failure != null) {
            player.stop()
            player.clearMediaItems()
            discard(downloaded)
            return CallAudioResult.Failed(if (owner == null) CallAudioFailure.NoCall else failure)
        }
        val duration = player.duration
        val next = CallAudioTrack(UUID.randomUUID(), title, if (duration == C.TIME_UNSET) null else duration / 1000.0)
        file = downloaded
        track = next
        isPaused = false
        ducker = CallAudioDucker()
        if (!isInterrupted) player.play()
        startTicker()
        AppLog.voice.info { "Call audio started (${next.duration?.toInt() ?: 0} s)" }
        return CallAudioResult.Started(next)
    }

    // `setLooper` is Media3's unstable API: the player runs on its own thread, off the main looper.
    @OptIn(UnstableApi::class)
    private fun newPlayer(): ExoPlayer {
        val usage = if (demo) C.USAGE_MEDIA else C.USAGE_VOICE_COMMUNICATION
        val attributes = AudioAttributes.Builder().setUsage(usage).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val player = ExoPlayer.Builder(dependency(AppContext))
            .setLooper(thread.looper)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ false)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finished()
            }
        })
        return player
    }

    /** Null once ready, else why it can't play. */
    private suspend fun awaitReady(player: ExoPlayer): CallAudioFailure? {
        if (player.playbackState == Player.STATE_READY) return null
        return suspendCancellableCoroutine { continuation ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_READY) return
                    player.removeListener(this)
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    val failure = if (isUnreadable(error)) CallAudioFailure.Unsupported else CallAudioFailure.Engine
                    if (continuation.isActive) continuation.resume(failure)
                }
            }
            player.addListener(listener)
            continuation.invokeOnCancellation { handler.post { player.removeListener(listener) } }
        }
    }

    fun pause() {
        handler.post {
            if (track == null || isPaused) return@post
            isPaused = true
            player?.pause()
            ticker?.cancel()
            ticker = null
        }
    }

    fun resume() {
        handler.post {
            if (track == null || !isPaused) return@post
            isPaused = false
            if (!isInterrupted) player?.play()
            startTicker()
        }
    }

    fun stop() {
        handler.post { stopPlayback() }
    }

    /** Stops [id] only if it's still what plays, never a newer track. */
    fun stop(id: UUID) {
        handler.post { if (track?.id == id) stopPlayback() }
    }

    fun events(): Flow<CallAudioEvent> = callbackFlow {
        val id = UUID.randomUUID()
        handler.post { listeners[id] = { event -> trySend(event) } }
        awaitClose { handler.post { listeners.remove(id) } }
    }

    private fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        ticker?.cancel()
        ticker = null
        file?.let(::discard)
        file = null
        track = null
        isPaused = false
    }

    private fun finished() {
        val id = track?.id ?: return
        ticker?.cancel()
        ticker = null
        file?.let(::discard)
        file = null
        track = null
        yield(CallAudioEvent.Finished(id))
    }

    private fun yield(event: CallAudioEvent) {
        for (listener in listeners.values.toList()) listener(event)
    }

    /**
     * Runs while a track plays (on [thread]): reads both voices' levels and moves the music's
     * gain. Plain object, never reducer actions per tick.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            val start = System.nanoTime()
            var last = start
            var settings = CallAudioDucker.Settings.current
            var volume = CallAudioTunables.volume.value
            var ticks = 0
            while (isActive) {
                delay(TICK_MILLIS)
                ticks += 1
                // Tunables move live, but a read 40 times a second isn't needed.
                if (ticks % 20 == 0) {
                    settings = CallAudioDucker.Settings.current
                    volume = CallAudioTunables.volume.value
                }
                val now = System.nanoTime()
                val dt = (now - last) / 1e9
                val time = (now - start) / 1e9
                last = now
                val levels = VoiceAudioLevels.shared.snapshot()
                val wasDucked = ducker.isDucked
                val gain = ducker.step(time, dt, levels.user, levels.assistant, settings)
                player?.volume = (gain * volume).toFloat()
                leak.add(levels.user, levels.assistant, ducker.isDucked, settings.agentLevel)
                if (wasDucked != ducker.isDucked) yield(CallAudioEvent.Ducked(ducker.isDucked))
            }
        }
    }

    private class DownloadFailed(val failure: CallAudioFailure) : Exception()

    private suspend fun download(url: URI): File {
        val client = dependency(OpenCodeTransport).request.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(url.toString()).build())
        // A cancelled play (a newer track, the call ending) stops the download too.
        val cancelling = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            return withContext(Dispatchers.IO) { save(call) }
        } finally {
            cancelling?.dispose()
        }
    }

    /** Blocking, on IO: writes the response to a cache file, refusing more than [MAX_BYTES]. */
    private fun save(call: Call): File {
        call.execute().use { response ->
            if (!response.isSuccessful) throw DownloadFailed(CallAudioFailure.Download("HTTP ${response.code}"))
            val body = response.body
            if (body.contentLength() > MAX_BYTES) throw DownloadFailed(CallAudioFailure.TooLarge)
            val destination = File(dependency(AppContext).cacheDir, "call-audio-${UUID.randomUUID()}")
            var total = 0L
            var tooLarge = false
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (tooLarge) {
                discard(destination)
                throw DownloadFailed(CallAudioFailure.TooLarge)
            }
            return destination
        }
    }

    companion object {
        val shared: CallAudioPlayer by lazy { CallAudioPlayer() }
        const val MAX_BYTES = 200_000_000
        private const val TICK_MILLIS = 25L
        private const val DOWNLOAD_TIMEOUT_SECONDS = 30L

        /** A file the decoders can't read, as against one the player failed to play. */
        fun isUnreadable(error: PlaybackException): Boolean {
            val code = error.errorCode
            val parsing = code >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED &&
                code <= PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
            val decoding = code >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                code <= PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
            return parsing || decoding
        }

        /** Deletes a downloaded file; a failure only logs (it's in the cache folder). */
        private fun discard(file: File) {
            if (!file.delete() && file.exists()) AppLog.voice.debug { "Call audio file not removed" }
        }

        fun title(title: String?, url: URI): String {
            if (!title.isNullOrEmpty()) return title
            val last = (url.path ?: "").trimEnd('/').substringAfterLast('/')
            val name = last.substringBeforeLast('.', last)
            return if (name.isEmpty()) "Audio" else name
        }
    }
}

/** Plays audio on the live call ([CallAudioPlayer]). */
class CallAudioClient(
    /** Downloads and starts it, replacing what plays; returns once it's playing. */
    val play: suspend (url: URI, title: String?) -> CallAudioResult,
    val pause: suspend () -> Unit,
    val resume: suspend () -> Unit,
    val stop: suspend () -> Unit,
    /** Stops that track only if it's still the one playing. */
    val stopTrack: suspend (UUID) -> Unit,
    val events: () -> Flow<CallAudioEvent>,
) {
    companion object Key : DependencyKey<CallAudioClient> {
        override val liveValue: CallAudioClient by lazy {
            // The lab's scripted call has no audio engine: tracks start silently.
            if (VoiceLabDemo.isEnabled) labValue else playerValue
        }

        private val labValue: CallAudioClient by lazy {
            CallAudioClient(
                play = { url, title ->
                    delay(300)
                    CallAudioResult.Started(CallAudioTrack(UUID.randomUUID(), CallAudioPlayer.title(title, url), 192.0))
                },
                pause = {},
                resume = {},
                stop = {},
                stopTrack = {},
                events = { emptyFlow() },
            )
        }

        private val playerValue: CallAudioClient by lazy {
            val player = CallAudioPlayer.shared
            CallAudioClient(
                play = { url, title -> player.play(url, title) },
                pause = { player.pause() },
                resume = { player.resume() },
                stop = { player.stop() },
                stopTrack = { player.stop(it) },
                events = { player.events() },
            )
        }

        override val testValue: CallAudioClient
            get() = CallAudioClient(
                play = { _, _ -> CallAudioResult.Failed(CallAudioFailure.NoCall) },
                pause = {},
                resume = {},
                stop = {},
                stopTrack = {},
                events = { emptyFlow() },
            )
    }
}
