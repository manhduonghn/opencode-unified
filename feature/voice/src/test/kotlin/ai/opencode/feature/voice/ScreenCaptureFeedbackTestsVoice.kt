package ai.opencode.feature.voice

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ScreenCaptureFeedbackTestsVoice {
    @get:Rule val isolation = TestIsolationRule()

    /** The shutter outside a call is the app's own sound, written to a WAV and played as a UI sound. */
    @Test fun shutterWavIsMono16BitPCM() {
        val samples = VoiceShutterSound.raw.map { it * (ShutterSystemSound.peak / VoiceShutterSound.peak) }.toFloatArray()
        val wav = ShutterSystemSound.wav(samples, sampleRate = 24_000.0)
        fun u32(at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((wav[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun u16(at: Int): Int = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertTrue("PCM, mono, 24 kHz, 16-bit", u16(20) == 1 && u16(22) == 1 && u32(24) == 24_000L && u16(34) == 16)
        assertTrue(u32(40).toInt() == samples.size * 2 && wav.size == 44 + samples.size * 2)
        val loudest = (44 until wav.size step 2).maxOfOrNull { abs(u16(it).toShort().toInt()) } ?: 0
        assertTrue(abs(loudest.toDouble() / Short.MAX_VALUE - ShutterSystemSound.peak.toDouble()) < 0.01)
    }
}
