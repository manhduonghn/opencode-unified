package ai.opencode.feature.voice

import ai.opencode.feature.voice.DotScopeSimulation.Cue
import ai.opencode.feature.voice.DotScopeSimulation.Stage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class DotScopeSimulationTests {
    private val grid = DotScopeGrid(pixelWidth = 1248, pixelHeight = 168, scale = 3f)

    /** The packed xyzw render field, reduced to (x, y) per dot. */
    private fun field(simulation: DotScopeSimulation): FloatArray {
        val rendered = FloatArray(grid.count * 4)
        simulation.render(grid, rendered)
        return FloatArray(grid.count * 2) { rendered[(it / 2) * 4 + it % 2] }
    }

    private class Light(var leftX: Float = 0f, var leftY: Float = 0f, var rightX: Float = 0f, var rightY: Float = 0f)

    /** Summed light per voice over the left and right thirds of the bar. */
    private fun light(field: FloatArray): Light {
        val light = Light()
        for (j in 0 until grid.rows) {
            for (i in 0 until grid.columns) {
                val index = (j * grid.columns + i) * 2
                if (i < grid.columns / 3) {
                    light.leftX += field[index]; light.leftY += field[index + 1]
                } else if (i >= grid.columns * 2 / 3) {
                    light.rightX += field[index]; light.rightY += field[index + 1]
                }
            }
        }
        return light
    }

    /** Rows away from the centre line that the brightest dot of each column sits, at most. */
    private fun swing(field: FloatArray): Float {
        val mid = (grid.rows - 1) / 2f
        var widest = 0f
        for (i in 0 until grid.columns) {
            for (k in 0 until 2) {
                var best = -1f
                var row = 0
                for (j in 0 until grid.rows) {
                    val value = field[(j * grid.columns + i) * 2 + k]
                    if (value > best) { best = value; row = j }
                }
                widest = max(widest, abs(row - mid))
            }
        }
        return widest
    }

    private fun syllable(n: Int) = abs(sin(n / 60.0 * PI * 4.4)).toFloat()

    @Test fun gridMatchesThePrototypeAtBarSize() {
        assertEquals(11f, grid.pitch)
        assertEquals(113, grid.columns)
        assertEquals("Odd, so a flat line sits on the centre row", 13, grid.rows)
        assertEquals(416f, grid.width)
    }

    @Test fun sameInputsGiveTheSamePicture() {
        val a = DotScopeSimulation()
        val b = DotScopeSimulation()
        // Different frame pacing, same total time.
        repeat(120) { a.advance(by = 1.0 / 60, you = 0.6f, agent = 0.2f, connected = true) }
        repeat(240) { b.advance(by = 1.0 / 120, you = 0.6f, agent = 0.2f, connected = true) }
        assertArrayEquals(field(a), field(b), 0f)
    }

    @Test fun quietCallIsTwoNearFlatLines() {
        val simulation = DotScopeSimulation()
        repeat(240) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = true) }
        assertTrue(swing(field(simulation)) <= 1.5f)
        val still = DotScopeSimulation.still(connected = true)
        assertTrue(swing(field(still)) <= 1.5f)
    }

    @Test fun eachVoiceIsStrongestOnItsOwnSide() {
        val you = DotScopeSimulation()
        for (n in 0 until 90) {
            you.advance(by = 1.0 / 60, you = 0.3f + 0.6f * syllable(n), agent = 0f, connected = true)
        }
        val field = field(you)
        val lit = light(field)
        assertTrue(lit.leftX > lit.rightX * 1.5f)
        assertTrue(swing(field) >= 3)
    }

    @Test fun phosphorLeavesFadingTrails() {
        val simulation = DotScopeSimulation()
        field(simulation)
        for (n in 0 until 60) {
            simulation.advance(by = 1.0 / 60, you = 0.3f + 0.6f * syllable(n), agent = 0f, connected = true)
        }
        val rendered = FloatArray(grid.count * 4)
        simulation.render(grid, rendered)
        val dots = (0 until grid.count).map { rendered[it * 4] to rendered[it * 4 + 2] }
        // Some dots are lit only by afterglow: bright, but not fresh.
        assertTrue(dots.any { (x, z) -> x > 0.3f && z < 0.5f })
        assertTrue(dots.any { (x, z) -> x > 0.3f && z > 0.95f })
    }

    @Test fun glowSwellsWithItsVoiceAndSettles() {
        val simulation = DotScopeSimulation()
        repeat(60) { simulation.advance(by = 1.0 / 60, you = 0.8f, agent = 0f, connected = true) }
        assertTrue(simulation.glow.x > 0.4f)
        assertTrue(simulation.glow.y < 0.05f)
        repeat(360) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = true) }
        assertTrue(simulation.glow.x < 0.1f)
    }

    @Test fun soundSchedulerPingsOnceAfterALateWake() {
        val scheduler = DotScopeSimulation.SoundScheduler()
        scheduler.setStage(Stage.connecting)
        scheduler.advance(by = 0.6)
        assertEquals(listOf(Cue.sweepRight), scheduler.poll())
        // Waking 2.2 s late crosses four passes (2–5); only the latest one pings.
        scheduler.advance(by = 2.2)
        assertEquals(listOf(Cue.sweepRight), scheduler.poll())
    }

    @Test fun soundSchedulerJoiningLateSkipsTheTurnaroundsItMissed() {
        // The engine came up 0.6 s after the tap: the beam already turned at Mute (0.57 s).
        val scheduler = DotScopeSimulation.SoundScheduler()
        scheduler.setStage(Stage.connecting, elapsed = 0.6)
        assertTrue(scheduler.poll().isEmpty())
        val ring = scheduler.nextRing ?: return fail("No next ring")
        assertTrue(ring.pass == 2 && ring.cue == Cue.sweepLeft)
        assertTrue(abs((scheduler.nextRingDelay ?: 0.0) - (DotScopeSimulation.SoundScheduler.pingTime(2) - 0.6)) < 1e-9)
        scheduler.advance(by = 0.5)
        assertTrue(scheduler.poll().isEmpty())
        scheduler.advance(by = 0.04)
        assertEquals("The first ping is the next turnaround the beam reaches", listOf(Cue.sweepLeft), scheduler.poll())
    }

    @Test fun soundSchedulerEmitsPingPerPassAndStaysSilentOnReconnect() {
        // Opening directly onto a call in progress stays silent.
        val liveStart = DotScopeSimulation.SoundScheduler()
        liveStart.setStage(Stage.live)
        liveStart.advance(by = 1.0)
        assertTrue(liveStart.poll().isEmpty())

        // Pre-connect failure / hang-up before connecting plays no .ended glide.
        val preConnectEnd = DotScopeSimulation.SoundScheduler()
        preConnectEnd.setStage(Stage.connecting)
        preConnectEnd.advance(by = 0.6)
        assertEquals(listOf(Cue.sweepRight), preConnectEnd.poll())
        preConnectEnd.setStage(Stage.ending)
        assertTrue("A call that never connected must not play .ended", preConnectEnd.poll().isEmpty())

        // Connecting schedules alternating sweepRight / sweepLeft pings at each
        // turnaround, every 0.55 s pass; the launch out of the call button has none.
        val callScheduler = DotScopeSimulation.SoundScheduler()
        callScheduler.setStage(Stage.connecting)
        callScheduler.advance(by = 0.01)
        assertTrue(callScheduler.poll().isEmpty())
        callScheduler.advance(by = 0.02) // elapsed = 0.03 s (pass 0: launching from the call button)
        assertTrue(callScheduler.poll().isEmpty())
        callScheduler.advance(by = 0.50) // elapsed = 0.53 s
        assertTrue(callScheduler.poll().isEmpty())
        callScheduler.advance(by = 0.06) // elapsed = 0.59 s (pass 1: Mute → End at 0.57 s)
        assertEquals(listOf(Cue.sweepRight), callScheduler.poll())
        callScheduler.advance(by = 0.55) // elapsed = 1.14 s (pass 2: End → Mute at 1.12 s)
        assertEquals(listOf(Cue.sweepLeft), callScheduler.poll())
        callScheduler.advance(by = 0.55) // elapsed = 1.69 s (pass 3: Mute → End at 1.67 s)
        assertEquals(listOf(Cue.sweepRight), callScheduler.poll())
        callScheduler.advance(by = 0.55) // elapsed = 2.24 s (pass 4: End → Mute at 2.22 s)
        assertEquals(listOf(Cue.sweepLeft), callScheduler.poll())

        // First connect emits .connected once, and nothing while live.
        callScheduler.setStage(Stage.live)
        assertEquals(listOf(Cue.connected), callScheduler.poll())
        callScheduler.advance(by = 2.0)
        assertTrue(callScheduler.poll().isEmpty())

        // Mid-call reconnect (live → connecting → live) is completely silent.
        callScheduler.setStage(Stage.connecting)
        callScheduler.advance(by = 2.5)
        assertTrue("Mid-call reconnect must not ping", callScheduler.poll().isEmpty())
        callScheduler.setStage(Stage.live)
        assertTrue("Mid-call reconnect must not play the connect chord", callScheduler.poll().isEmpty())

        // Hanging up a connected call plays .ended.
        callScheduler.setStage(Stage.ending)
        assertEquals(listOf(Cue.ended), callScheduler.poll())
    }

    @Test fun hapticCueMappingMatchesScannerPingsAndControls() {
        assertEquals(VoiceCallHaptic.sweepPass, VoiceCallHaptic.forCue(Cue.sweepRight))
        assertEquals(VoiceCallHaptic.sweepPass, VoiceCallHaptic.forCue(Cue.sweepLeft))
        assertEquals(VoiceCallHaptic.connected, VoiceCallHaptic.forCue(Cue.connected))
        assertEquals(VoiceCallHaptic.ended, VoiceCallHaptic.forCue(Cue.ended))
        assertEquals(VoiceCallHaptic.muted, VoiceCallHaptic.forMute(isMuted = true))
        assertEquals(VoiceCallHaptic.unmuted, VoiceCallHaptic.forMute(isMuted = false))
    }

    @Test fun providedBandsReplaceTheStandIn() {
        val lows = DotScopeSimulation()
        val highs = DotScopeSimulation()
        val low = FloatArray(16) { if (it < 4) 0.9f else 0.1f }
        val high = FloatArray(16) { if (it >= 12) 0.9f else 0.1f }
        repeat(60) {
            lows.advance(by = 1.0 / 60, you = 0.7f, agent = 0f, youBands = low, connected = true)
            highs.advance(by = 1.0 / 60, you = 0.7f, agent = 0f, youBands = high, connected = true)
        }
        assertTrue(lows.you.bands[0] > 0.8f)
        assertFalse(field(lows).contentEquals(field(highs)))
    }

    @Test fun connectingPassPingsLineUpWithBeamTurnarounds() {
        val scheduler = DotScopeSimulation.SoundScheduler()
        scheduler.setStage(Stage.connecting)

        val leftEdge = DotScopeSimulation.Scan.edgeInset
        val rightEdge = grid.width - DotScopeSimulation.Scan.edgeInset
        // The beam starts on the call button, at End's end of the bar, and heads for Mute.
        assertTrue(abs(front(elapsed = 0f) - (grid.width - DotScopeSimulation.Scan.launchInset)) < 0.5f)
        data class Emitted(val cue: Cue, val front: Float, val time: Double)
        val emitted = mutableListOf<Emitted>()
        val midPassHeads = mutableListOf<Float>()

        val steps = (2.3 / DotScopeSimulation.stepDuration).roundToInt()
        for (step in 1..steps) {
            scheduler.advance(by = DotScopeSimulation.stepDuration)
            val elapsed = (step * DotScopeSimulation.stepDuration).toFloat()
            val beam = DotScopeSimulation.Scan.connectingBeam(elapsed)
            for (cue in scheduler.poll()) emitted += Emitted(cue, front(elapsed), step * DotScopeSimulation.stepDuration)
            val pass = elapsed / DotScopeSimulation.Scan.sweepPass
            val along = pass - floor(pass)
            if (abs(along - 0.5f) < 0.01f) midPassHeads += beam.head
        }

        assertEquals(listOf(Cue.sweepRight, Cue.sweepLeft, Cue.sweepRight, Cue.sweepLeft), emitted.map { it.cue })
        for (event in emitted) {
            when (event.cue) {
                Cue.sweepRight -> assertTrue("sweepRight at ${event.time}s should fire at left turnaround ($leftEdge), got ${event.front}", abs(event.front - leftEdge) <= 2.5f)
                Cue.sweepLeft -> assertTrue("sweepLeft at ${event.time}s should fire at right turnaround ($rightEdge), got ${event.front}", abs(event.front - rightEdge) <= 2.5f)
                else -> fail("Unexpected cue ${event.cue}")
            }
        }
        assertTrue("Each pass should swell in brightness through the middle", midPassHeads.all { it > 3.4f })
    }

    /**
     * Where the connecting beam's front is [elapsed] seconds into the call, in points: what the
     * call's cue and haptic timings are placed against.
     */
    private fun front(elapsed: Float): Float {
        val beam = DotScopeSimulation.Scan.connectingBeam(elapsed)
        return DotScopeSimulation.Scan.beamX(progress = beam.progress, width = grid.width) + beam.overshoot
    }

    @Test fun theBeamLeavesTheCallButtonFromTheFirstFrame() {
        for (delay in listOf(0.0, 1.0 / 60, 2.0 / 60)) {
            val frame = 1.0 / 60
            val fronts = (0 until 7).map { n -> front((delay + n * frame).toFloat()) }
            val button = grid.width - DotScopeSimulation.Scan.launchInset
            assertTrue("It starts at the call button: $fronts", fronts[0] <= button + 0.01f && fronts[0] > button - 35)
            for ((a, b) in fronts.zipWithNext()) {
                assertTrue("Moving toward Mute every frame, with no hold: $fronts", a - b > 5)
                assertTrue("Smoothly, with no jump: $fronts", a - b < 20)
            }
            // Out from under the button and into the scope's window within a few frames.
            val window = grid.width - DotScopeSimulation.windowInset - DotScopeSimulation.windowFade / 2
            assertTrue("$fronts", fronts[5] < window)
        }
    }

    /**
     * Android's scope skips every other display frame while quiet, and a cover pauses it through
     * `LocalDotScopeCovered`.
     */
    @Test fun quietScopesHalveTheirRateAndCoveringSheetsPauseThem() {
        for (frame in 1L..4L) assertTrue(DotScopeRenderer.drawsFrame(isQuiet = false, frame = frame))
        var drawn = 0
        for (frame in 1L..60L) if (DotScopeRenderer.drawsFrame(isQuiet = true, frame = frame)) drawn++
        assertEquals(30, drawn)
        assertFalse(DotScopeRenderer.isQuiet(lastHeard = 10.0, now = 11.0))
        assertTrue(DotScopeRenderer.isQuiet(lastHeard = 10.0, now = 10 + DotScopeRenderer.QUIET_AFTER + 0.01))
    }
}
