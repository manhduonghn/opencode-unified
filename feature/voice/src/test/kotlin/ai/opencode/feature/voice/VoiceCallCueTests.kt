package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration

/** The call's sounds are Wisp's, bundled per pack. */
class VoiceCallCueTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
    private val packs = VoiceCallSoundPack.entries.filter { it != VoiceCallSoundPack.silent }

    @Test fun everyPackBundlesEveryCueSoftAndClean() {
        for (pack in packs) for (cue in VoiceCallCue.entries) {
            val samples = VoiceCallSoundBank.rawSamples(cue, pack)
            assertTrue("$pack $cue loads", samples.size > 900)
            // Wisp's levels: nothing louder than its connect.
            assertTrue("$pack $cue peak ${peak(samples)}", peak(samples) > 0.02f && peak(samples) < 0.25f)
            // Each starts and ends at rest, so none clicks.
            assertTrue("$pack $cue ends", abs(samples.first()) < 0.002f && abs(samples.last()) < 0.002f)
        }
    }

    /** Wisp's own render of Breath Ping, its default: lengths exact, peaks within IMA4's error. */
    @Test fun breathPingMatchesWispsRender() {
        val wisp = mapOf(
            VoiceCallCue.callStart to (13_440 to 0.16f), VoiceCallCue.connectingLoop to (36_000 to 0.045f),
            VoiceCallCue.connect to (23_040 to 0.2f), VoiceCallCue.hangUp to (9_120 to 0.16f),
            VoiceCallCue.toolLook to (2_280 to 0.05f), VoiceCallCue.toolOpen to (1_800 to 0.06f),
            VoiceCallCue.toolSteer to (2_760 to 0.055f), VoiceCallCue.toolStep to (1_008 to 0.035f),
        )
        assertEquals(VoiceCallSoundPack.breathPing, VoiceCallSoundPack.default)
        for ((cue, expected) in wisp) {
            val samples = VoiceCallSoundBank.rawSamples(cue, VoiceCallSoundPack.breathPing)
            assertEquals("$cue length", expected.first, samples.size)
            assertTrue("$cue peak ${peak(samples)}", abs(peak(samples) - expected.second) < 0.006f)
        }
    }

    /** The loop is one period: wrapping round is no bigger a step than any inside it. */
    @Test fun connectingLoopIsSeamless() {
        for (pack in packs) {
            val loop = VoiceCallSoundBank.rawSamples(VoiceCallCue.connectingLoop, pack)
            assertEquals(36_000, loop.size)
            val largestStep = (1 until loop.size).maxOf { abs(loop[it] - loop[it - 1]) }
            assertTrue("$pack", abs(loop.first() - loop.last()) <= largestStep)
        }
    }

    @Test fun silentPlaysNothing() {
        for (cue in VoiceCallCue.entries) assertTrue(VoiceCallSoundBank.rawSamples(cue, VoiceCallSoundPack.silent).isEmpty())
    }

    @Test fun scannerCuesMapToConnectAndHangUp() {
        assertTrue(VoiceCallSoundBank.samples(DotScopeSimulation.Cue.connected).contentEquals(VoiceCallSoundBank.samples(VoiceCallCue.connect)))
        assertTrue(VoiceCallSoundBank.samples(DotScopeSimulation.Cue.ended).contentEquals(VoiceCallSoundBank.samples(VoiceCallCue.hangUp)))
        assertTrue(VoiceCallSoundBank.samples(DotScopeSimulation.Cue.sweepRight).isEmpty())
        assertTrue(VoiceCallSoundBank.samples(DotScopeSimulation.Cue.sweepLeft).isEmpty())
    }

    /** The hang-up tail plays the whole cue before it's stopped, in every pack. */
    @Test fun endTailOutlastsTheHangUpCue() {
        for (pack in packs) {
            val samples = VoiceCallSoundBank.rawSamples(VoiceCallCue.hangUp, pack)
            val length = samples.size / PCM.sampleRate
            assertTrue("$pack tail for a $length s hang-up", VoiceCallSoundBank.endTailWait(samples) > length + 0.05)
        }
    }

    @Test fun toolsPickTheirCueAsWispDoes() {
        // The screenshot plays the shutter as it captures instead.
        assertNull(VoiceCallCue.tool("capture_app_screenshot"))
        assertEquals(VoiceCallCue.toolLook, VoiceCallCue.tool("read_session"))
        assertEquals(VoiceCallCue.toolOpen, VoiceCallCue.tool("open_session"))
        assertEquals(VoiceCallCue.toolOpen, VoiceCallCue.tool("create_session"))
        assertEquals(VoiceCallCue.toolSteer, VoiceCallCue.tool("send_message"))
        assertEquals(VoiceCallCue.toolSteer, VoiceCallCue.tool("ios_voice_watch_session"))
        assertEquals(VoiceCallCue.toolStep, VoiceCallCue.tool("shell"))
        assertEquals(VoiceCallCue.toolStep, VoiceCallCue.tool("functions.read"))
        assertEquals(VoiceCallCue.toolStep, VoiceCallCue.tool("edit"))
        // A script by what its code calls, Wisp's names included.
        assertEquals(VoiceCallCue.toolLook, VoiceCallCue.tool("execute", code = "await tools.wisp.look({})"))
        assertEquals(VoiceCallCue.toolOpen, VoiceCallCue.tool("execute", code = "await tools.wisp.computer({op: 'click'})"))
        assertEquals(VoiceCallCue.toolSteer, VoiceCallCue.tool("execute", code = "await tools.seer.current()"))
        assertEquals(VoiceCallCue.toolStep, VoiceCallCue.tool("execute", code = "await tools.shell({command: 'ls'})"))
        assertNull(VoiceCallCue.tool("execute", code = "return 1 + 1"))
        assertNull(VoiceCallCue.tool("ios_voice_say"))
        assertNull(VoiceCallCue.tool("ios_voice_end_call"))
    }

    @Test fun toolCuesAreSpacedAsWispSpacesThem() {
        val throttle = VoiceToolCueThrottle()
        val admitted = listOf(
            VoiceCallCue.toolLook to 0.0, VoiceCallCue.toolOpen to 0.4, VoiceCallCue.toolOpen to 0.5,
            VoiceCallCue.toolStep to 1.5, VoiceCallCue.toolSteer to 1.5, VoiceCallCue.toolStep to 2.8,
        ).map { throttle.admit(it.first, it.second) }
        // None within 450 ms of the last; a step none within 1.2 s.
        assertEquals(listOf(true, false, true, false, true, true), admitted)
    }

    @Test fun toolCuesPlayOnlyWhileLive() = runTest {
        val quiet = object : VoiceHapticPlaying {
            override val isCallActive = true
            override fun prepare() {}
            override fun play(pattern: VoiceHapticPattern, at: Duration, kind: VoiceHapticEngine.Kind) = true
            override fun cancel(kind: VoiceHapticEngine.Kind) {}
            override fun endCall() {}
            override suspend fun finish() {}
        }
        val sounds = VoiceCallSounds(clock = Clock.liveValue, haptics = quiet, scope = backgroundScope)
        val played = mutableListOf<Int>()
        val loop = mutableListOf<Boolean>()
        val id = UUID.randomUUID()
        sounds.startCallClock(
            id,
            loop = { raw -> synchronized(loop) { loop.add(raw != null) } },
            playTool = { raw -> synchronized(played) { played.add(raw.size) } },
        ) {}
        assertEquals("The connecting loop starts with the call", listOf(true), synchronized(loop) { loop.toList() })
        sounds.toolCalled("shell", null)
        assertTrue("Not while connecting", synchronized(played) { played.isEmpty() })

        sounds.noteConnected(id)
        assertEquals("The loop lets go on connect", listOf(true, false), synchronized(loop) { loop.toList() })
        sounds.toolCalled("read_session", null)
        sounds.toolCalled("open_session", null)
        sounds.toolCalled("ios_voice_say", null)
        assertEquals(listOf(VoiceCallSoundBank.samples(VoiceCallCue.toolLook).size), synchronized(played) { played.toList() })

        sounds.endCallClock(id, playEndCue = false)
        sounds.toolCalled("send_message", null)
        assertEquals("Not after the call", 1, synchronized(played) { played.size })
    }

    @Test fun shutterIsSoftShortAndClean() {
        val samples = VoiceShutterSound.raw
        assertTrue(samples.size / PCM.sampleRate < 0.2)
        assertTrue("A tool cue's level", abs(peak(samples) - VoiceShutterSound.peak) < 0.001f)
        assertTrue("Starts and ends at rest", abs(samples.first()) < 0.002f && abs(samples.last()) < 0.002f)
        assertTrue(VoiceShutterSound.samples(VoiceCallSoundPack.silent).isEmpty())
    }

    /**
     * A call owns the shutter from its tap: it never reaches the system's player, and it plays on
     * the call's engine only once live.
     */
    @Test fun shutterPlaysOnTheCallOnlyWhileLive() = runTest {
        val quiet = object : VoiceHapticPlaying {
            override val isCallActive = true
            override fun prepare() {}
            override fun play(pattern: VoiceHapticPattern, at: Duration, kind: VoiceHapticEngine.Kind) = true
            override fun cancel(kind: VoiceHapticEngine.Kind) {}
            override fun endCall() {}
            override suspend fun finish() {}
        }
        val sounds = VoiceCallSounds(clock = Clock.liveValue, haptics = quiet, scope = backgroundScope)
        assertFalse("No call: the app’s UI sound plays it", sounds.shutter())

        val played = mutableListOf<Int>()
        val id = UUID.randomUUID()
        sounds.startCallClock(id, playTool = { raw -> synchronized(played) { played.add(raw.size) } }) {}
        assertTrue("Connecting: the call owns the audio", sounds.shutter())
        assertTrue("Quiet until live", synchronized(played) { played.isEmpty() })

        sounds.noteConnected(id)
        assertTrue(sounds.shutter())
        assertEquals(listOf(VoiceShutterSound.samples().size), synchronized(played) { played.toList() })

        sounds.endCallClock(id, playEndCue = false)
        assertFalse("After the call: the app’s UI sound plays it", sounds.shutter())
        assertEquals(1, synchronized(played) { played.size })
    }
}
