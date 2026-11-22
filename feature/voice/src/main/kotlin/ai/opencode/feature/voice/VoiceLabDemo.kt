package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Shared syllable-paced envelope and transcript: no microphone or network. */
object VoiceScriptedDemo {
    const val loop = 14.0
    val user = 1.4 to 4.0
    val agent = 5.3 to 11.2
    val cutIn = 7.6 to 9.4
    data class TimedEvent(val time: Double, val event: VoiceCallEvent)
    val script: List<TimedEvent> = (listOf(
        TimedEvent(4.1, VoiceCallEvent.UserTranscript("u1", "Can you check why the upload test is flaky?", true)),
        TimedEvent(9.5, VoiceCallEvent.UserTranscript("u2", "Also check the retry test.", true)),
    ) + stream("a1", "Sure. The upload test races the progress callback, so the assertion can run before the last event lands.", 5.3, 5.6, 5.8)
        + stream("a2", "I’ll add an expectation, and run both again.", 9.8, 11.0, 11.2)).sortedBy { it.time }

    fun stream(id: String, text: String, from: Double, to: Double, done: Double): List<TimedEvent> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        return words.mapIndexed { index, word ->
            TimedEvent(from + (to - from) * index / words.size, VoiceCallEvent.AssistantDelta(id, (if (index == 0) "" else " ") + word))
        } + TimedEvent(done, VoiceCallEvent.AssistantDone(id, text))
    }
    fun levels(at: Double): VoiceAudioLevels.Snapshot {
        fun phrase(span: Pair<Double, Double>, rate: Double, seed: Double): Float {
            if (at <= span.first || at >= span.second) return 0f
            val gate = min(1.0, (at - span.first) / 0.12) * min(1.0, (span.second - at) / 0.2)
            val syllable = abs(sin((at - span.first) * PI * rate + seed))
            val jitter = 0.75 + 0.25 * sin(at * 7.3 + seed * 3)
            return (gate * (0.25 + 0.65 * syllable * jitter)).toFloat()
        }
        return VoiceAudioLevels.Snapshot(max(phrase(user, 4.4, 0.3), phrase(cutIn, 4.8, 1.1)), phrase(agent, 4.1, 2.0))
    }
    fun continuousSpeechLevel(at: Double, rate: Double = 4.5, seed: Double = 0.7): Float =
        (0.22 + 0.68 * abs(sin(at * PI * rate + seed)) * (0.7 + 0.3 * sin(at * 6.8 + seed * 2.3))).toFloat()
}

/** All switches go through VoiceLabEnvironment: release builds cannot enable
 * scripted calls, input injection, alternate URLs or lab credentials. Time scale is 1. */
object VoiceLabDemo {
    val isEnabled get() = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_DEMO") != null
    val realtimeURL get() = VoiceLabEnvironment.string("OPENCODE_LAB_REALTIME_URL")
    val liveURL get() = VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_URL")
    val liveRTCURL get() = VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_RTC_URL")
    val hasStandInServer get() = realtimeURL != null || liveURL != null
    val labAPIKey get() = VoiceLabEnvironment.string("OPENCODE_LAB_OPENAI_KEY")
    val playbackPreroll get() = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_PREROLL_MS")?.toLongOrNull()?.times(24)
    val overrideEngine: VoiceEngine? get() {
        VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_ENGINE")?.let { VoiceEngine.ofRawValue(it)?.let { engine -> return engine } }
        if (liveURL != null && realtimeURL == null) return VoiceEngine.gptLive
        if (realtimeURL != null && liveURL == null) return VoiceEngine.realtime
        return null
    }
    val preflight = RealtimePreflightClient(verify = { _, _ -> })
    val audioHooks: VoiceAudioLabHooks get() = object : VoiceAudioLabHooks {
        override val isScriptedDemo = isEnabled
        override val playbackPreroll = VoiceLabDemo.playbackPreroll
        override val input = if (VoiceLabInput.isEnabled) VoiceLabInput.samples() else null
        override val inputDelay = VoiceLabInput.delay
    }
    @Serializable
    data class Script(val clips: List<Clip>) {
        @Serializable
        data class Clip(val who: String, val start: Double, val envelope: List<Float>, val text: String) {
            val duration get() = envelope.size / 100.0
        }
        fun levels(at: Double): VoiceAudioLevels.Snapshot {
            var user = 0f; var agent = 0f
            for (clip in clips) {
                if (at < clip.start || at >= clip.start + clip.duration || clip.envelope.isEmpty()) continue
                val level = clip.envelope[min(clip.envelope.lastIndex, ((at - clip.start) * 100).toInt())]
                if (clip.who == "user") user = max(user, level) else agent = max(agent, level)
            }
            return VoiceAudioLevels.Snapshot(user, agent)
        }
        val events: List<VoiceScriptedDemo.TimedEvent> get() = clips.flatMapIndexed { index, clip ->
            val id = "${clip.who}-$index"
            val end = clip.start + clip.duration
            if (clip.who == "user") listOf(VoiceScriptedDemo.TimedEvent(end, VoiceCallEvent.UserTranscript(id, clip.text, true)))
            else VoiceScriptedDemo.stream(id, clip.text, clip.start, end, end) + VoiceScriptedDemo.TimedEvent(end + 0.1, VoiceCallEvent.AssistantPlaybackFinished)
        }.sortedBy { it.time }
        companion object {
            fun load(): Script? {
                val path = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_SCRIPT") ?: return null
                return try { VoiceWireJson.decodeFromString(serializer(), File(path).readText()) } catch (_: Exception) { null }
            }
        }
    }
    private val nextCallIndex = AtomicInteger()
    private fun nextConnectDelay(): Double {
        val index = nextCallIndex.getAndIncrement()
        val list = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_CONNECT_DELAYS")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
        return if (list.isNotEmpty()) list[min(index, list.lastIndex)] else VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_CONNECT_DELAY")?.toDoubleOrNull() ?: 0.5
    }
    class LabFailure(message: String) : Exception(message)
    val call: VoiceCallClient by lazy {
        val dispatcher = Dispatchers.Default.limitedParallelism(1)
        var current: ScriptedCall? = null
        VoiceCallClient(
            start = { _, _ -> withContext(dispatcher) {
                current?.finish(); current = null
                val failure = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_FAIL")
                if (failure != null) flow { throw LabFailure(failure) }
                else ScriptedCall().also { current = it }.start()
            } },
            completeFunction = { _, _ -> }, setMuted = {}, updateInstructions = { _, _ -> },
            end = { withContext(dispatcher) { current?.finish(); current = null } },
        )
    }
    private class ScriptedCall {
        private val clock = dependency(Clock)
        private val id = dependency(UuidClient).next()
        private val sounds = VoiceCallSounds.shared
        private val levels = VoiceAudioLevels.shared
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + DependencyValues.current.asContextElement())
        private val output = LabCuePlayer(scope)
        private val events = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private var scriptTask: Job? = null
        fun start(): Flow<VoiceCallEvent> {
            val recorded = Script.load()
            val connectDelay = nextConnectDelay()
            scriptTask = scope.launch {
                try {
                    val engineDelay = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_ENGINE_DELAY")?.toDoubleOrNull() ?: 0.0
                    if (engineDelay > 0) clock.sleep(engineDelay.seconds)
                    output.start(id)
                    clock.sleep(connectDelay.seconds)
                    sounds.noteConnected(id); events.send(VoiceCallEvent.Connected)
                    val start = clock.now()
                    val script = (recorded?.events ?: VoiceScriptedDemo.script).toMutableList()
                    VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_POOR_AT")?.toDoubleOrNull()?.let { at ->
                        script += VoiceScriptedDemo.TimedEvent(at, VoiceCallEvent.ConnectionPoor(true))
                        VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_POOR_UNTIL")?.toDoubleOrNull()?.let { script += VoiceScriptedDemo.TimedEvent(it, VoiceCallEvent.ConnectionPoor(false)) }
                    }
                    VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_LINK")?.let { pattern ->
                        val watch = LiveRTCLinkWatch(); watch.add(LiveRTCLinkSample(roundTrip = 0.05))
                        pattern.forEachIndexed { second, mark ->
                            watch.add(LiveRTCLinkSample(roundTrip = if (mark == 'x') 1.0 else 0.05,
                                packetsReceived = (second + 1) * 50.0, samplesReceived = (second + 1) * 48_000.0))?.let { poor ->
                                script += VoiceScriptedDemo.TimedEvent(second.toDouble(), VoiceCallEvent.ConnectionPoor(poor))
                            }
                        }
                    }
                    script.sortBy { it.time }
                    var next = 0
                    while (isActive) {
                        val elapsed = (clock.now() - start).inWholeNanoseconds / 1e9
                        val snapshot = if (VoiceLabEnvironment.flag("OPENCODE_LAB_VOICE_CONTINUOUS")) VoiceAudioLevels.Snapshot(
                            VoiceScriptedDemo.continuousSpeechLevel(elapsed), VoiceScriptedDemo.continuousSpeechLevel(elapsed, 3.2, 1.9))
                        else recorded?.levels(elapsed) ?: VoiceScriptedDemo.levels(elapsed % VoiceScriptedDemo.loop)
                        levels.setLevels(snapshot.user, snapshot.assistant)
                        while (next < script.size && script[next].time <= elapsed) events.send(script[next++].event)
                        clock.sleep(10.milliseconds)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) { events.close(error) }
            }
            return flow { try { for (event in events) emit(event) } finally { withContext(NonCancellable) { finish() } } }
        }
        suspend fun finish() {
            if (!closed.compareAndSet(false, true)) return
            withContext(NonCancellable) {
                scriptTask?.cancelAndJoin()
                output.finish(id)
                scope.cancel(); levels.reset(); events.close()
            }
        }
    }
    /** Output-only AudioTrack: a scripted call never asks for microphone permission. */
    private class LabCuePlayer(private val scope: CoroutineScope) {
        private val clock = dependency(Clock)
        private val sounds = VoiceCallSounds.shared
        private val buffers = Channel<FloatArray>(Channel.UNLIMITED)
        private var track: AudioTrack? = null
        private var player: Job? = null
        fun start(id: UUID) {
            if (!isEnabled || track != null) return
            val format = AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(format).setBufferSizeInBytes(max(9_600, AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
                .setTransferMode(AudioTrack.MODE_STREAM).build().also {
                    if (VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_MUTE_OUTPUT") != null) it.setVolume(0f)
                    it.play()
                }
            player = scope.launch(Dispatchers.IO) {
                for (raw in buffers) {
                    var offset = 0
                    while (isActive && offset < raw.size) {
                        val count = track?.write(raw, offset, raw.size - offset, AudioTrack.WRITE_BLOCKING) ?: break
                        if (count <= 0) break
                        if (sounds.isTelemetryEnabled) sounds.recordMixerBuffer(raw.copyOfRange(offset, offset + count))
                        offset += count
                    }
                }
            }
            sounds.startCallClock(id) { buffers.trySend(it) }
        }
        suspend fun finish(id: UUID) {
            sounds.endCallClock(id, true)?.let { buffers.trySend(it) }
            clock.sleep(260.milliseconds)
            // Stop the track before joining a blocking write.
            track?.pause(); track?.flush(); player?.cancelAndJoin()
            track?.stop(); track?.release(); track = null
            buffers.close(); sounds.flushDebugLogAsync()
        }
    }
}

/** PCM16 mono 24 kHz recording, then silence in the capture's 100 ms chunks. */
object VoiceLabInput {
    val path get() = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_INPUT")
    val isEnabled get() = path != null
    val delay get() = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_INPUT_DELAY")?.toDoubleOrNull() ?: 1.0
    fun samples(): ByteArray {
        val path = path ?: return ByteArray(0)
        val data = try { File(path).readBytes() } catch (_: Exception) { return ByteArray(0) }
        if (data.size <= 44) return ByteArray(0)
        var offset = 12
        while (offset + 8 <= data.size) {
            val id = data.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = (0..3).fold(0L) { n, byte -> n or ((data[offset + 4 + byte].toLong() and 255) shl (byte * 8)) }
            if (id == "data") return data.copyOfRange(offset + 8, min(data.size.toLong(), offset + 8L + size).toInt())
            val next = offset + 8L + size
            if (next > data.size || next <= offset) return ByteArray(0)
            offset = next.toInt()
        }
        return ByteArray(0)
    }
}
