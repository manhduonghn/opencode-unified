package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.*

/** Frame-rate meters are plain synchronized state, never reducer actions. */
class VoiceAudioLevels {
    private var user = 0f
    private var assistant = 0f
    /** How bright each voice sounds, 0 dull to 1 bright ([tone]). Held through silence so the orb doesn't drop back between words. */
    private var userTone = 0.4f
    private var assistantTone = 0.4f
    private var echo = VoiceEchoWindow()
    var isMuted = false
        @Synchronized get
        @Synchronized set(value) { field = value; if (value) user = 0f }
    data class Snapshot(val user: Float, val assistant: Float)
    @Synchronized fun snapshot() = Snapshot(user, assistant)
    /** Each voice's brightness ([tone]), for the Living Orb. */
    @Synchronized fun tones() = Snapshot(userTone, assistantTone)
    fun updateUser(pcm16: ByteArray) {
        val level = rmsLevel(pcm16)
        val tone = if (level > toneGate) tone(pcm16) else null
        synchronized(this) {
            user = level
            if (tone != null) userTone = tone
            echo.add(level, assistant)
        }
    }
    /** The microphone's level while the agent plays against while it's quiet, for the call log, and resets the window. Null before both were heard. */
    @Synchronized fun takeEchoWindow(): String? = echo.summary.also { echo = VoiceEchoWindow() }
    fun updateAssistant(playing: FloatArray, offset: Int = 0, count: Int = playing.size - offset) {
        if (count <= 0) return
        var sum = 0f
        for (i in offset until offset + count) sum += playing[i] * playing[i]
        val level = level(sqrt(sum / count))
        val tone = if (level > toneGate) tone(count) { playing[offset + it] } else null
        synchronized(this) {
            assistant = level
            if (tone != null) assistantTone = tone
        }
    }
    @Synchronized fun setLevels(user: Float, assistant: Float) { this.user = if (isMuted) 0f else user; this.assistant = assistant }
    @Synchronized fun clearAssistant() { assistant = 0f }
    @Synchronized fun reset() { user = 0f; assistant = 0f; userTone = 0.4f; assistantTone = 0.4f; echo = VoiceEchoWindow(); isMuted = false }
    companion object Key : DependencyKey<VoiceAudioLevels> {
        override val liveValue by lazy { VoiceAudioLevels() }
        override val testValue get() = VoiceAudioLevels()
        val shared get() = dependency(Key)
        fun rmsLevel(pcm16: ByteArray): Float {
            val frames = pcm16.size / 2
            if (frames == 0) return 0f
            var sum = 0f; var count = 0
            for (i in 0 until frames step max(1, frames / 256)) {
                val sample = PCM.sample(pcm16, i) / 32767f
                sum += sample * sample; count++
            }
            return level(sqrt(sum / count))
        }
        private fun level(rms: Float) = ((20 * log10(rms + 1e-6f) + 48) / 36).coerceIn(0f, 1f)
        /** Below this level a chunk is too quiet to say how bright the voice is. */
        const val toneGate = 0.12f
        /** How bright a voice sounds, from its zero-crossing rate: about 0 for a low hum, 1 for hiss. Cheap enough for the audio thread; no FFT. */
        inline fun tone(count: Int, sample: (Int) -> Float): Float {
            if (count <= 1) return 0.4f
            var crossings = 0
            var previous = sample(0) >= 0
            for (index in 1 until count) {
                val positive = sample(index) >= 0
                if (positive != previous) crossings++
                previous = positive
            }
            val rate = crossings.toFloat() / (count - 1)
            return ((rate - 0.02f) / 0.16f).coerceIn(0f, 1f)
        }
        fun tone(pcm16: ByteArray): Float = tone(pcm16.size / 2) { PCM.sample(pcm16, it).toFloat() }
    }
}

/**
 * Whether the agent's playback leaks into the microphone's level (and so into the orb's "you"):
 * the user level sorted by whether the agent was audible at the time. Levels only, never audio.
 */
class VoiceEchoWindow {
    val whilePlaying = mutableListOf<Float>()
    val whileQuiet = mutableListOf<Float>()

    fun add(user: Float, assistant: Float) {
        if (assistant > playing && whilePlaying.size < capacity) whilePlaying.add(user)
        if (assistant < quiet && whileQuiet.size < capacity) whileQuiet.add(user)
    }

    val summary: String?
        get() {
            if (whilePlaying.isEmpty()) return null
            fun pct(values: List<Float>, p: Double): String {
                if (values.isEmpty()) return "–"
                val sorted = values.sorted()
                return String.format(java.util.Locale.ROOT, "%.2f", sorted[minOf(sorted.size - 1, ((sorted.size - 1) * p).toInt())])
            }
            return "mic level while agent plays p50/p90 ${pct(whilePlaying, 0.5)}/${pct(whilePlaying, 0.9)} (${whilePlaying.size})" +
                " · agent quiet p50/p90 ${pct(whileQuiet, 0.5)}/${pct(whileQuiet, 0.9)} (${whileQuiet.size})"
        }

    companion object {
        /** Agent levels above this count as playing; below [quiet], as quiet. */
        const val playing = 0.25f
        const val quiet = 0.05f
        const val capacity = 200
    }
}

/** PCM16 little-endian mono 24 kHz. Capture is gated until noteConnected.
 * WebSocket transports collect capture and enqueue output. WebRTC's Java ADM can
 * instead use VoiceAudioEngine(externalOwner = true): it owns AudioRecord/Track;
 * feedCapture/recordExternalPlayback supply samples for meters and tracking and
 * externalControl implements mute/route gain/flush/stop without duplicate hardware. */
class VoiceAudioSession(
    val capture: Flow<ByteArray>,
    val events: Flow<VoiceAudioEvent>,
    val enqueue: suspend (ByteArray, String?) -> Unit,
    val flush: suspend () -> Int,
    val setMuted: suspend (Boolean) -> Unit,
    val stop: suspend () -> Unit,
    val setAtEar: suspend (Boolean) -> Unit = {},
    val noteConnected: suspend () -> Unit = {},
    val cancel: () -> Unit = {},
    val quality: VoiceAudioQuality? = null,
    /** Live engine adoption only; injected audio clients need not own hardware. */
    internal val engine: VoiceAudioEngine? = null,
)
enum class VoiceAudioEvent { playbackDrained, interrupted, resumed, routeChanged, mediaServicesReset }

class VoiceAudioClient(
    val start: suspend () -> VoiceAudioSession,
    val startAtEar: (suspend (Boolean) -> VoiceAudioSession)? = null,
    val prepare: suspend (Boolean) -> Int = { 0 },
    val discardPrepared: suspend (Int) -> Unit = {},
) {
    constructor(prestart: VoiceAudioPrestart) : this(
        start = { prestart.start(false) }, startAtEar = { prestart.start(it) },
        prepare = { prestart.prepare(it) }, discardPrepared = { prestart.discard(it) },
    )
    suspend fun start(atEar: Boolean): VoiceAudioSession = startAtEar?.invoke(atEar)
        ?: start().also { if (atEar) it.setAtEar(true) }
    companion object Key : DependencyKey<VoiceAudioClient> {
        override val liveValue by lazy {
            if ((dependency(VoiceAudioLabHooks) ?: VoiceLabDemo.audioHooks).isScriptedDemo) {
                // The scripted call has no coordinator to adopt a prepared engine.
                VoiceAudioClient(start = { VoiceAudioEngine().start() }, startAtEar = { VoiceAudioEngine(atEar = it).start() })
            } else VoiceAudioClient(VoiceAudioPrestart { VoiceAudioEngine(atEar = it).start() })
        }
        override val testValue = VoiceAudioClient(start = {
            VoiceAudioSession(emptyFlow(), emptyFlow(), { _, _ -> }, { 0 }, {}, {})
        })
    }
}

/** Prepared starts don't wait for an earlier engine's stop. Each has its own holder. */
class VoiceAudioPrestart(
    /** Where prepared starts run; tests pass one they can hold. */
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val begin: suspend (Boolean) -> VoiceAudioSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + DependencyValues.current.asContextElement())
    private class Pending(val token: Int) {
        lateinit var task: Deferred<VoiceAudioSession>
        var session: VoiceAudioSession? = null
        var abandoned = false
    }
    private var lastToken = 0
    private var pending: Pending? = null
    fun prepare(atEar: Boolean): Int {
        val (token, replaced) = synchronized(this) {
            val old = pending
            val next = Pending(++lastToken)
            next.task = scope.async(start = CoroutineStart.LAZY) {
                try {
                    val session = begin(atEar)
                    synchronized(next) {
                        next.session = session
                        if (next.abandoned) throw CancellationException()
                    }
                    currentCoroutineContext().ensureActive()
                    session
                } catch (error: Throwable) {
                    val cleanup = synchronized(next) {
                        next.session.also { next.session = null }
                    }
                    cleanup?.let(::stopInBackground)
                    throw error
                }
            }
            pending = next
            next.task.start()
            lastToken to old
        }
        replaced?.let(::stopUnadopted)
        return token
    }
    fun discard(token: Int) {
        val prepared = synchronized(this) {
            if (pending?.token != token) null else pending.also { pending = null }
        }
        prepared?.let(::stopUnadopted)
    }
    suspend fun start(atEar: Boolean): VoiceAudioSession {
        val prepared = synchronized(this) { pending.also { pending = null } } ?: return begin(atEar)
        try {
            val session = prepared.task.await()
            currentCoroutineContext().ensureActive()
            session.setAtEar(atEar)
            synchronized(prepared) { prepared.session = null }
            return session
        } catch (error: Throwable) { stopUnadopted(prepared); throw error }
    }
    private fun stopUnadopted(prepared: Pending) {
        val session = synchronized(prepared) { prepared.abandoned = true; prepared.session.also { prepared.session = null } }
        session?.let(::stopInBackground)
        prepared.task.cancel()
    }
    private fun stopInBackground(session: VoiceAudioSession) {
        session.cancel()
        val stop = session.stop
        scope.launch { stop() }
    }
}

sealed class VoiceAudioError(message: String) : Exception(message) {
    data object microphoneDenied : VoiceAudioError("Allow microphone access in Settings to talk to your session.") {
        /** [message] as the person reads it; [message] itself stays English for comparisons. */
        val displayMessage: String get() = localized(R.string.voice_error_microphone_denied)
    }
    data object formatUnavailable : VoiceAudioError("The audio hardware format is not supported.")
    class engineRejected(val operation: String, val reason: String) : VoiceAudioError(engineRejectedMessage)

    companion object {
        const val engineRejectedMessage = "Couldn’t start call audio. Try again in a moment."
    }
}
object AudioEngineGuard {
    /**
     * The framework's `IllegalStateException`s become errors; a cancellation (itself an
     * `IllegalStateException` on the JVM) passes through.
     */
    fun <T> perform(operation: String, body: () -> T): T = try { body() } catch (error: IllegalStateException) {
        if (error is CancellationException) throw error
        val reason = error.message ?: "no reason given"
        dependency(VoiceCallLogClient).noteCurrent(VoiceCallLog.Note.Kind.audio, "⚠︎ $operation raised ${error.javaClass.simpleName}: $reason")
        throw VoiceAudioError.engineRejected(operation, reason)
    }
}
object VoiceAudioHardware {
    data class Format(val sampleRate: Double, val channels: Int) {
        val isUsable get() = sampleRate > 0 && channels > 0
        val text get() = "${sampleRate.toInt()} Hz × $channels ch"
    }
    fun problem(inputAvailable: Boolean, input: Format, output: Format): String? = buildList {
        if (!inputAvailable) add("no audio input available")
        if (!input.isUsable) add("input ${input.text}")
        if (!output.isUsable) add("output ${output.text}")
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
object PCM {
    const val sampleRate = 24_000.0
    const val chunkFrames = 2_400
    val transportFormat get() = android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate.toInt()).setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO).build()
    val playbackFormat get() = android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(sampleRate.toInt()).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
    fun sample(bytes: ByteArray, frame: Int): Int = ((bytes[frame * 2].toInt() and 255) or (bytes[frame * 2 + 1].toInt() shl 8)).toShort().toInt()
    fun playbackBuffer(from: ByteArray): FloatArray? = if (from.size < 2) null else FloatArray(from.size / 2) { sample(from, it) / 32767f }
    fun hasSpeechEnergy(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        if (pcm.size < 64) return true
        for (i in 0 until pcm.size / 2 step max(1, pcm.size / 2 / 128)) if (abs(sample(pcm, i)) > 24) return true
        return false
    }
    fun data(from: FloatArray): ByteArray = ByteArray(from.size * 2).also { bytes ->
        for (i in from.indices) { val s = (from[i].coerceIn(-1f, 1f) * 32767).toInt(); bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte() }
    }
}

class PlayedAudioTracker {
    private var itemID: String? = null
    private var scheduledFrames = 0L
    private var itemStartFrame = 0L
    private var isTracking = false
    fun enqueue(itemID: String?, frames: Long, playerSampleTime: Long) {
        if (frames <= 0) return
        if (!isTracking || itemID != this.itemID) {
            this.itemID = itemID
            itemStartFrame = max(playerSampleTime, if (isTracking) itemStartFrame + scheduledFrames else playerSampleTime)
            scheduledFrames = 0; isTracking = true
        }
        scheduledFrames += frames
    }
    fun playedMilliseconds(playerSampleTime: Long, sampleRate: Double, outputLatency: Double): Int {
        if (!isTracking || sampleRate <= 0) return 0
        val frames = min(max(0, playerSampleTime - itemStartFrame), scheduledFrames)
        return min((scheduledFrames / sampleRate * 1000).toInt(), max(0, ((frames / sampleRate - outputLatency) * 1000).toInt()))
    }
    fun reset() { itemID = null; scheduledFrames = 0; itemStartFrame = 0; isTracking = false }
}

class PlaybackScheduler(preroll: Long = initialPreroll) {
    sealed interface Placement { data object queued : Placement; data class start(val atFrame: Long) : Placement }
    data class Decision(val placement: Placement, val aheadFrames: Long, val underrunFrames: Long? = null)
    var preroll = preroll; private set
    private var endFrame: Long? = null
    private var lastFrame = 0L
    private var lastItemID: String? = null
    private var lastHadSpeech = false
    fun schedule(frames: Long, itemID: String?, hasSpeech: Boolean, now: Long): Decision {
        if (now < lastFrame) endFrame = null
        lastFrame = now
        val end = endFrame
        val result: Decision
        if (end != null && now < end) {
            val ahead = end - now
            if (!(hasSpeech && !lastHadSpeech && ahead < preroll / 2)) {
                endFrame = end + frames
                result = Decision(Placement.queued, ahead)
            } else {
                val start = now + preroll
                endFrame = start + frames
                result = Decision(Placement.start(start), ahead)
            }
        } else {
            var underrun: Long? = null
            if (end != null && hasSpeech && lastHadSpeech && itemID == lastItemID && now - end < maximumGap) {
                underrun = now - end
                if (preroll > 0) preroll = min(maximumPreroll, preroll + prerollStep)
            }
            val start = now + preroll
            endFrame = start + frames
            result = Decision(Placement.start(start), 0, underrun)
        }
        lastItemID = itemID; lastHadSpeech = hasSpeech
        return result
    }
    fun stop() { endFrame = null; lastItemID = null; lastHadSpeech = false }
    companion object {
        const val initialPreroll = 4800L
        const val prerollStep = 2400L
        const val maximumPreroll = 12000L
        const val maximumGap = 24000L
    }
}
class RenderedGapCounter {
    var gaps = 0; private set
    var gapSamples = 0; private set
    private var zeroRun = 0
    private var inSpeech = false
    fun add(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            if (samples[i] == 0f) { zeroRun++; if (zeroRun > maximumGap) inSpeech = false; continue }
            if (inSpeech && zeroRun >= minimumGap) { gaps++; gapSamples += zeroRun }
            zeroRun = 0; inSpeech = true
        }
    }
    companion object { const val minimumGap = 48; const val maximumGap = 7200 }
}
class RenderedGapMeter {
    @Volatile var counter: RenderedGapCounter? = null; private set
    @Synchronized fun enable() { counter = RenderedGapCounter() }
    /** Off (every call but the lab's), this returns before taking the lock on the audio thread. */
    fun add(buffer: FloatArray, count: Int = buffer.size) {
        val counter = counter ?: return
        synchronized(this) { counter.add(buffer, count) }
    }
}
