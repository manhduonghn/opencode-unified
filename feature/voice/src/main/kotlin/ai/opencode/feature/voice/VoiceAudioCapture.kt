package ai.opencode.feature.voice

import kotlinx.coroutines.channels.Channel
import kotlin.math.min

/** Whether the microphone may reach the call: shut from the engine's start (at the tap, for the connecting cues) until the call first connects. */
class CaptureGate {
    @Volatile var isOpen = false; private set
    fun open() { isOpen = true }
    fun close() { isOpen = false }
}

/** Audio-thread conversion/batching. Android AudioRecord converts hardware to our
 * 24-kHz format; external ADM callbacks can supply their own rate/channel count. */
class CaptureForwarder(
    private val gate: CaptureGate, private val continuation: Channel<ByteArray>?,
    private val quality: VoiceAudioQuality, private val levels: VoiceAudioLevels = VoiceAudioLevels.shared,
) {
    private val pending = ByteArray(PCM.chunkFrames * 2)
    private var used = 0
    private var phase = 0.0
    private var previous = 0.0
    fun reset() { used = 0; phase = 0.0; previous = 0.0 }
    fun receive(samples: ShortArray, count: Int, sampleRate: Int = 24000, channels: Int = 1) {
        if (!gate.isOpen || levels.isMuted) { used = 0; phase = 0.0; return }
        if (sampleRate <= 0 || channels <= 0) return
        val ratio = sampleRate / PCM.sampleRate
        val frames = min(count, samples.size) / channels
        for (frame in 0 until frames) {
            var value = 0.0
            for (c in 0 until channels) value += samples[frame * channels + c]
            value /= channels
            while (phase <= 0) {
                val sample = (previous + (value - previous) * (1 + phase)).toInt().coerceIn(-32768, 32767)
                pending[used++] = sample.toByte(); pending[used++] = (sample shr 8).toByte()
                if (used == pending.size) { val chunk = pending.copyOf(); used = 0; levels.updateUser(chunk); emit(chunk) }
                phase += ratio
            }
            phase -= 1; previous = value
        }
    }
    fun emit(chunk: ByteArray) {
        var dropped = false
        if (continuation != null && continuation.trySend(chunk).isFailure) {
            dropped = continuation.tryReceive().isSuccess // bufferingNewest(50)
            continuation.trySend(chunk)
        }
        quality.captured(dropped)
    }
}
