package ai.opencode.feature.session

import ai.opencode.feature.session.header.TitleBreath
import ai.opencode.feature.session.header.TitleHold
import ai.opencode.feature.session.header.TitleLoadingStyle
import ai.opencode.feature.session.header.TitleLoadingTunables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The title's loading breath eases in from full, breathes down to 45% and back, and eases back to
 * full from wherever it is, never jumping.
 */
class TitleBreathTests {
    /**
     * Without tuning (everyone outside Developer Mode) the title loads with the shipped breathing,
     * at its shipped period and depth.
     */
    @Test fun todaysLookUsesTheShippedBreathing() {
        assertEquals(TitleLoadingStyle.Breathing, TitleLoadingTunables.STYLE)
        assertTrue(TitleBreath.PERIOD == 1.7 && abs(TitleBreath.DEPTH - 0.55) < 1e-9)
    }

    private val frame = 1.0 / 60

    /** Samples `breath` every frame from `from` for `duration` seconds. */
    private fun samples(breath: TitleBreath, from: Double, duration: Double): List<Double> {
        val values = mutableListOf<Double>()
        var t = from
        while (t <= from + duration + 1e-12) {
            values += breath.opacity(t)
            t += frame
        }
        return values
    }

    /** The largest change between consecutive frames. */
    private fun largestStep(values: List<Double>): Double = values.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0.0

    @Test fun breathingStartsAtFullAndStill() {
        val breath = TitleBreath.rest.retargeted(active = true, reduceMotion = false, time = 10.0)
        val start = breath.value(10.0)
        assertTrue(abs(start.opacity - 1) < 1e-9)
        assertTrue(abs(start.velocity) < 1e-9)
        assertTrue(breath.opacity(10 + frame) > 0.999)
    }

    @Test fun aBreathDimsToFortyFivePercentAndReturns() {
        val breath = TitleBreath.rest.retargeted(active = true, reduceMotion = false, time = 0.0)
        val half = TitleBreath.PERIOD / 2
        assertTrue(abs(breath.opacity(half) - 0.45) < 0.001)
        assertTrue(abs(breath.opacity(TitleBreath.PERIOD) - 1) < 0.001)
        val values = samples(breath, 0.0, TitleBreath.PERIOD * 2)
        assertTrue(values.min() >= 0.45 - 0.001)
        assertTrue(largestStep(values) < 0.02)
        assertFalse(breath.isSettled(100.0))
    }

    @Test fun endingMidBreathEasesBackToFullWithoutAJump() {
        for (endsAt in listOf(0.2, 0.5, 0.85, 1.3)) {
            val breathing = TitleBreath.rest.retargeted(active = true, reduceMotion = false, time = 0.0)
            val before = breathing.value(endsAt)
            val easing = breathing.retargeted(active = false, reduceMotion = false, time = endsAt)
            val after = easing.value(endsAt)
            assertTrue("$endsAt", abs(after.opacity - before.opacity) < 1e-9)
            assertTrue("$endsAt", abs(after.velocity - before.velocity) < 1e-6)
            val values = samples(easing, endsAt, TitleBreath.SETTLE_SECONDS)
            assertTrue("$endsAt", largestStep(values) < 0.08)
            assertTrue("$endsAt", abs(easing.opacity(endsAt + TitleBreath.SETTLE_SECONDS) - 1) < 0.005)
            assertTrue("$endsAt", easing.isSettled(endsAt + TitleBreath.SETTLE_SECONDS))
            assertEquals("$endsAt", 1.0, easing.opacity(endsAt + 5), 0.0)
        }
    }

    /**
     * A read starting while the title is still easing back picks the breath
     * up where the title is, on its way down, rather than from full.
     */
    @Test fun resumingWhileEasingBackCarriesOn() {
        val breathing = TitleBreath.rest.retargeted(active = true, reduceMotion = false, time = 0.0)
        val easing = breathing.retargeted(active = false, reduceMotion = false, time = 0.85)
        val resumeAt = 0.95
        val shown = easing.opacity(resumeAt)
        assertTrue(shown < 0.9)
        val resumed = easing.retargeted(active = true, reduceMotion = false, time = resumeAt)
        assertTrue(abs(resumed.opacity(resumeAt) - shown) < 1e-9)
        assertTrue(abs(resumed.target(resumeAt).opacity - shown) < 1e-6)
        assertTrue(largestStep(samples(resumed, resumeAt, 2.0)) < 0.08)
    }

    @Test fun flappingNeverJumps() {
        // Reads toggling the cue on and off, sampled every frame on one clock.
        val switches = ArrayDeque<Pair<Double, Boolean>>()
        var time = 0.0
        for ((active, length) in listOf(true to 0.22, false to 0.1, true to 0.48, false to 0.08, true to 0.9, false to 0.6)) {
            switches.addLast(time to active)
            time += length
        }
        var breath = TitleBreath.rest
        val values = mutableListOf<Double>()
        for (step in 0 until (time / frame).toInt()) {
            val now = step * frame
            val next = switches.firstOrNull()
            if (next != null && next.first <= now) {
                switches.removeFirst()
                breath = breath.retargeted(active = next.second, reduceMotion = false, time = now)
            }
            values += breath.opacity(now)
        }
        assertTrue(largestStep(values) < 0.08)
        assertTrue(abs(values.last() - 1) < 0.01)
    }

    @Test fun reduceMotionFadesToASteadyDimAndBack() {
        val dimming = TitleBreath.rest.retargeted(active = true, reduceMotion = true, time = 0.0)
        assertEquals(1.0, dimming.opacity(0.0), 0.0)
        assertTrue(abs(dimming.opacity(TitleBreath.SETTLE_SECONDS) - TitleBreath.REDUCED_OPACITY) < 0.005)
        assertTrue(dimming.isSettled(TitleBreath.SETTLE_SECONDS))
        assertEquals(TitleBreath.REDUCED_OPACITY, dimming.restingOpacity, 0.0)
        val values = samples(dimming, 0.0, 3.0)
        assertTrue(values.all { it >= TitleBreath.REDUCED_OPACITY - 0.005 })
        assertTrue(largestStep(values) < 0.08)
        val restoring = dimming.retargeted(active = false, reduceMotion = true, time = 3.0)
        assertTrue(abs(restoring.opacity(3.0) - TitleBreath.REDUCED_OPACITY) < 0.005)
        assertTrue(abs(restoring.opacity(3 + TitleBreath.SETTLE_SECONDS) - 1) < 0.005)
        assertEquals(1.0, restoring.restingOpacity, 0.0)
    }

    @Test fun renderServerPlanPreservesContinuityAcrossStartStopResumeAndReduceMotion() {
        val start = TitleBreath.rest.retargeted(active = true, reduceMotion = false, time = 10.0)
        val startPlan = start.renderServerPlan(10.0)
        assertTrue(abs(startPlan.initialOpacity - 1) < 1e-9)
        assertNull(startPlan.leadInToTroughDuration)
        assertEquals(TitleBreath.PERIOD / 2, startPlan.repeatingHalfPeriod)
        assertTrue(abs(startPlan.targetOpacity - 0.45) < 1e-9)
        assertNull(startPlan.springResponse)

        val stopped = start.retargeted(active = false, reduceMotion = false, time = 10.6)
        val stopPlan = stopped.renderServerPlan(10.6)
        assertTrue(abs(stopPlan.initialOpacity - start.opacity(10.6)) < 1e-9)
        assertNull(stopPlan.repeatingHalfPeriod)
        assertEquals(1.0, stopPlan.targetOpacity, 0.0)
        assertEquals(TitleBreath.RESPONSE, stopPlan.springResponse)

        val resumed = stopped.retargeted(active = true, reduceMotion = false, time = 10.72)
        val resumePlan = resumed.renderServerPlan(10.72)
        assertTrue(abs(resumePlan.initialOpacity - stopped.opacity(10.72)) < 1e-9)
        assertNotNull(resumePlan.leadInToTroughDuration)
        resumePlan.leadInToTroughDuration?.let { leadIn -> assertTrue(leadIn > 0.05 && leadIn < TitleBreath.PERIOD / 2) }
        assertEquals(TitleBreath.PERIOD / 2, resumePlan.repeatingHalfPeriod)
        assertEquals(1.0, resumePlan.targetOpacity, 0.0)

        val reduced = resumed.retargeted(active = true, reduceMotion = true, time = 11.0)
        val reducedPlan = reduced.renderServerPlan(11.0)
        assertTrue(abs(reducedPlan.initialOpacity - resumed.opacity(11.0)) < 1e-9)
        assertEquals(TitleBreath.REDUCED_OPACITY, reducedPlan.targetOpacity, 0.0)
        assertEquals(TitleBreath.RESPONSE, reducedPlan.springResponse)
    }

    @Test fun shaderEnvelopeEasesSmoothlyWithoutJumpingOnMidFlightRetarget() {
        val active = TitleBreath.rest.retargeted(active = true, reduceMotion = true, time = 0.0)
        assertTrue(abs(active.envelope(0.0)) < 1e-6)
        assertTrue(abs(active.envelope(TitleBreath.SETTLE_SECONDS) - 1) < 0.01)

        val mid = 0.14
        val midEnv = active.envelope(mid)
        assertTrue(midEnv > 0.2 && midEnv < 0.9)
        val stopped = active.retargeted(active = false, reduceMotion = true, time = mid)
        assertTrue(abs(stopped.envelope(mid) - midEnv) < 1e-6)
        assertTrue(abs(stopped.envelope(mid + TitleBreath.SETTLE_SECONDS)) < 0.01)
    }
}

/**
 * Which title shows while titles change under a breath. Android's `TitleHold` is a value: each
 * `update`/`settle` returns the next.
 */
class TitleHoldTests {
    private val settle = TitleBreath.SETTLE_SECONDS

    /**
     * Switching sessions in place mid-breath shows the new session's title
     * at once, even before the hold has seen the switch.
     */
    @Test fun switchingMidBreathShowsTheNewTitleAtOnce() {
        var hold = TitleHold(identity = "ses_a", title = "Fix the upload test", isBreathing = true)
        assertEquals("Release notes", hold.title("ses_b", latest = "Release notes"))
        hold = hold.update(identity = "ses_b", title = "Release notes", isBreathing = false, now = 10.0)
        assertEquals("Release notes", hold.shown)
        assertNull(hold.wakeAt)
    }

    /**
     * Within the ease back after a breath, another session still shows its
     * own title straight away.
     */
    @Test fun switchingWhileTheBreathEasesBackShowsTheNewTitle() {
        var hold = TitleHold(identity = "ses_a", title = "Fix the upload test", isBreathing = true)
        hold = hold.update(identity = "ses_a", title = "Fix the upload test", isBreathing = false, now = 10.0)
        hold = hold.update(identity = "ses_b", title = "Release notes", isBreathing = false, now = 10 + settle / 3)
        assertEquals("Release notes", hold.shown)
        assertNull(hold.wakeAt)
    }

    /**
     * A new session that starts breathing right after the switch breathes
     * its own title, never the last session's.
     */
    @Test fun aSessionBreathingRightAfterTheSwitchShowsItsOwnTitle() {
        var hold = TitleHold(identity = "ses_a", title = "Fix the upload test", isBreathing = true)
        hold = hold.update(identity = "ses_a", title = "Fix the upload test", isBreathing = false, now = 10.0)
        assertEquals("Release notes", hold.title("ses_b", latest = "Release notes"))
        hold = hold.update(identity = "ses_b", title = "Release notes", isBreathing = true, now = 10.05)
        assertEquals("Release notes", hold.shown)
        hold = hold.update(identity = "ses_b", title = "Release notes", isBreathing = false, now = 11.5)
        hold = hold.settle(now = 11.5 + settle)
        assertEquals("Release notes", hold.shown)
    }

    /**
     * Auto Rename's title arrives in the same update its breath ends: it
     * waits for the ease back, then shows.
     */
    @Test fun aTitleArrivingAsTheBreathEndsWaitsForTheEaseBack() {
        var hold = TitleHold(identity = "ses_a", title = "Fix the upload test", isBreathing = true)
        hold = hold.update(identity = "ses_a", title = "Upload test races its callback", isBreathing = false, now = 10.0)
        assertEquals("Fix the upload test", hold.shown)
        assertEquals(10 + settle, hold.wakeAt)
        hold = hold.settle(now = 10 + settle / 2)
        assertEquals("Fix the upload test", hold.shown)
        hold = hold.settle(now = 10 + settle)
        assertEquals("Upload test races its callback", hold.shown)
        assertNull(hold.wakeAt)
    }

    /** A title changing while it breathes waits until the breath ends. */
    @Test fun aTitleChangingMidBreathWaitsForItsEnd() {
        var hold = TitleHold(identity = "ses_a", title = "Old", isBreathing = true)
        hold = hold.update(identity = "ses_a", title = "New", isBreathing = true, now = 10.0)
        assertEquals("Old", hold.shown)
        assertNull(hold.wakeAt)
        hold = hold.update(identity = "ses_a", title = "New", isBreathing = false, now = 11.0)
        assertEquals("Old", hold.shown)
        hold = hold.settle(now = 11 + settle)
        assertEquals("New", hold.shown)
    }

    /**
     * With nothing breathing, a new title shows at once, and a draft keeps
     * its identity as it becomes its session.
     */
    @Test fun aTitleWithoutABreathShowsAtOnce() {
        var hold = TitleHold(identity = "draft-3", title = "New Session", isBreathing = false)
        hold = hold.update(identity = "draft-3", title = "Fix the upload test", isBreathing = false, now = 10.0)
        assertEquals("Fix the upload test", hold.shown)
        assertNull(hold.wakeAt)
    }
}
