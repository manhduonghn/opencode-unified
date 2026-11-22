package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone starts before the speech model is ready; [DictationInput] holds what it hears
 * until the recognizer connects. Android's microphone delivers 16-bit PCM already, so a buffer
 * "whose samples all convert to `value`" is simply filled with it.
 */
class DictationInputTests {
    private val micFormat = DictationAudioFormat(sampleRate = 48_000, channelCount = 1)
    private val analyzerFormat = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)

    /** A microphone buffer whose samples are all [value], to tell buffers apart. */
    private fun buffer(value: Short) = DictationBuffer(ShortArray(480) { value }, micFormat)

    /** The analyzer's input stream. */
    private class Stream : DictationInputSink {
        val buffers = mutableListOf<ShortArray>()
        var finished = false

        override fun yield(samples: ShortArray) {
            check(!finished) { "yielded after finish" }
            buffers += samples
        }

        override fun finish() {
            finished = true
        }

        /** The last sample of each non-empty buffer; the stream must have ended. */
        val values: List<Short>
            get() {
                assertTrue("the stream never ended", finished)
                return buffers.filter { it.isNotEmpty() }.map { it.last() }
            }
    }

    private fun connect(input: DictationInput): Stream =
        Stream().also { input.connect(BufferConverter(micFormat, analyzerFormat), it) }

    /** The first words, spoken while the model loaded, reach it first and in order. */
    @Test fun audioHeardWhileTheModelPreparesReachesItFirst() {
        val input = DictationInput()
        input.append(buffer(1))
        input.append(buffer(2))
        input.append(buffer(3))
        val stream = connect(input)
        input.append(buffer(4))
        input.end()
        assertEquals(listOf<Short>(1, 2, 3, 4), stream.values)
    }

    /** Letting go before the model is ready: it still gets everything heard. */
    @Test fun stoppingBeforeTheModelIsReadyKeepsTheAudio() {
        val input = DictationInput()
        input.append(buffer(1))
        input.end()
        input.append(buffer(2))
        assertEquals(listOf<Short>(1), connect(input).values)
    }

    /** A cancel throws away what was heard and anything after. */
    @Test fun cancellingDiscardsTheAudio() {
        val input = DictationInput()
        input.append(buffer(1))
        input.discard()
        input.append(buffer(2))
        assertTrue(input.isDiscarded)
        assertTrue(connect(input).values.isEmpty())
    }

    /**
     * `BufferConverter` always converts to mono 16-bit PCM even when handed a multi-channel target
     * or stereo microphone input, passes matching mono through, and drops route-mismatched buffers.
     */
    @Test fun convertsFloat32AndStereoInputToMonoInt16ForAnalyzerInput() {
        val stereoMic = DictationAudioFormat(sampleRate = 48_000, channelCount = 2)
        val stereoBuffer = DictationBuffer(ShortArray(480 * 2) { 7 }, stereoMic)
        val input = DictationInput()
        input.append(stereoBuffer)
        val stream = Stream()
        input.connect(BufferConverter(stereoMic, stereoMic), stream)
        // A route-changed buffer with a mismatched format is dropped rather than fed through.
        input.append(buffer(9))
        input.end()
        assertTrue(stream.finished)
        val outputs = stream.buffers.filter { it.isNotEmpty() }
        assertEquals(1, outputs.size)
        // Mono at the microphone's rate: one sample per frame.
        assertEquals(480, outputs.first().size)
        assertEquals(7.toShort(), outputs.first().last())

        // Matching mono 16-bit passes straight through without conversion.
        val int16Buffer = DictationBuffer(ShortArray(160) { 11 }, analyzerFormat)
        val passthrough = DictationInput()
        passthrough.append(int16Buffer)
        val passthroughStream = Stream()
        passthrough.connect(BufferConverter(analyzerFormat, analyzerFormat), passthroughStream)
        passthrough.end()
        assertEquals(listOf<Short>(11), passthroughStream.values)
    }
}
