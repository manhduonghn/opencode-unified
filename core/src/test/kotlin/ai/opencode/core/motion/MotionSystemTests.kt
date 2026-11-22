package ai.opencode.core.motion

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.tuning.TuningTitle
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class MotionSystemTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun motionRolesRegisterInFourSectionsWithTheApprovedStartingValues() {
        val store = TuningStore(null)
        store.setEnabled(true)
        Motion.register(store)

        val rows = store.descriptors.filter { it.group == "motion" }
        val titles = ArrayList<String>()
        for (row in rows) {
            val title = row.section?.let(TuningTitle::format).orEmpty()
            if (title !in titles) titles += title
        }
        assertEquals(Motion.sectionTitles, titles)
        assertTrue("every key is motion.<section>.<name>", rows.all { it.key.split('.').size >= 3 })
        assertTrue(
            rows.map { it.key }.toSet().containsAll(
                listOf(
                    "motion.springs.microIn.duration", "motion.springs.panelOpen.bounce", "motion.springs.releaseOmega",
                    "motion.springs.keyboard", "motion.fades.press", "motion.fades.reduced", "motion.fades.staggerCap",
                    "motion.geometry.pressScale", "motion.geometry.widthLead", "motion.geometry.flickVelocity",
                    "motion.ambient.breathPeriod", "motion.ambient.sweepPeriod",
                ),
            ),
        )

        assertEquals(MotionCurve.EaseOut(0.10), Motion.press.curve())
        assertEquals("press lets go on microIn", MotionCurve.Spring(0.26, 0.15), Motion.press.curve(MotionPhase.Exit))
        assertEquals(MotionCurve.Spring(0.26, 0.15), Motion.microIn.curve())
        assertEquals(MotionCurve.Spring(0.18, 0.0), Motion.microIn.curve(MotionPhase.Exit))
        assertEquals(MotionCurve.Spring(0.18, 0.0), Motion.microOut.curve())
        assertEquals(MotionCurve.EaseOut(0.22), Motion.swap.curve())
        assertEquals(MotionCurve.EaseOut(0.14), Motion.swap.curve(MotionPhase.Exit))
        assertEquals(MotionCurve.Spring(0.34, 0.0), Motion.layout.curve())
        assertEquals(MotionCurve.Spring(0.26, 0.0), Motion.layout.curve(MotionPhase.Exit))
        assertEquals(MotionCurve.Spring(0.26, 0.0), Motion.collapse.curve())
        assertEquals(MotionCurve.Spring(0.40, 0.10), Motion.panelOpen.curve())
        assertEquals(MotionCurve.Spring(0.30, 0.0), Motion.panelOpen.curve(MotionPhase.Exit))
        assertEquals(MotionCurve.Spring(0.30, 0.0), Motion.panelClose.curve())
        assertEquals(MotionCurve.Spring(0.44, 0.0), Motion.surface.curve())
        assertTrue(abs(Motion.release.curve().duration - 2 * PI / 28.5) < 1e-9)
        assertEquals(0.0, Motion.release.curve().bounce, 0.0)
        assertEquals(MotionCurve.Spring(0.38, 0.0), Motion.flight.curve())
        assertEquals(MotionCurve.Spring(0.267, 0.0), Motion.keyboard.curve())
        assertEquals(MotionCurve.EaseOut(0.32), Motion.dim.curve())
        assertEquals(MotionCurve.EaseInOut(0.20), Motion.reduced.curve())

        // Bounce only when something summoned arrives, never on an exit.
        for (role in MotionRole.entries) {
            assertTrue("$role exits without bounce", role.curve(MotionPhase.Exit).bounce == 0.0 || role == MotionRole.Press)
        }
        assertEquals(0.97f, MotionTunables.pressScale.defaultValue)
        assertEquals(0.85f, MotionTunables.microInScale.defaultValue)
        assertEquals(0.96f, MotionTunables.swapScale.defaultValue)
        assertEquals(3.dp, MotionTunables.blurSubtle.defaultValue)
        assertEquals(5.dp, MotionTunables.rollDistance.defaultValue)
        assertEquals(250.0, MotionTunables.flickVelocity.defaultValue, 0.0)
        assertEquals(1.65, MotionTunables.widthLead.defaultValue, 0.0)
        assertEquals(1.70, MotionTunables.breathPeriod.defaultValue, 0.0)
        assertEquals(1.60, MotionTunables.sweepPeriod.defaultValue, 0.0)

        assertEquals(0.0, MotionRole.staggerDelay(0), 0.0)
        assertTrue(abs(MotionRole.staggerDelay(3) - 0.06) < 1e-9)
        assertEquals("a group arrives within the cap", 0.10, MotionRole.staggerDelay(12), 0.0)
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedAliasesKeepTodaysValuesSoNothingVisibleChanges() {
        assertEquals(0.22, Motion.fadeStandard, 0.0)
        assertEquals(0.14, Motion.fadeFast, 0.0)
        assertEquals(0.20, Motion.reducedFade, 0.0)
        assertEquals(tween<Float>(200, easing = Motion.EaseInOut), Motion.reducedFadeSpec<Float>())
        assertEquals(tween<Float>(220, delayMillis = 50, easing = Motion.EaseOut), Motion.easeOut<Float>(Motion.fadeStandard, Motion.contentDelay))
        assertEquals(tween<Float>(200, easing = Motion.EaseInOut), Motion.easeOut<Float>(Motion.fadeFast, reduceMotion = true))
        assertEquals(0.05, Motion.contentDelay, 0.0)
        assertEquals(0.02, Motion.staggerStep, 0.0)
        assertEquals(8.dp, Motion.blurPanel)
        assertEquals(14.dp, Motion.blurSheet)
        assertEquals(18.dp, Motion.blurBackdrop)
        assertEquals(6.dp, Motion.entranceRise)
        assertEquals(10.dp, Motion.panelRise)
        assertTrue(TuningContext.session.groups.contains("motion"))

        // Overrides left on the retired component-named keys are dropped.
        assertTrue(Motion.isRetiredKey("motion.morphOpen.duration"))
        assertTrue(Motion.isRetiredKey("motion.reducedFade"))
        assertFalse(Motion.isRetiredKey("motion.springs.panelOpen.duration"))
        assertFalse(Motion.isRetiredKey("motion.screenshotFlash.duration"))
        val store = TuningStore(null)
        store.setEnabled(true)
        store.set("motion.panelOpen.duration", TuneValue.Number(0.5))
        store.set("motion.screenshotFlash.duration", TuneValue.Number(0.5))
        Motion.register(store)
        assertNull(store.overrides["motion.panelOpen.duration"])
        assertEquals(TuneValue.Number(0.5), store.overrides["motion.screenshotFlash.duration"])
    }

    /** Compose specs and the solver are checked. */
    @Test
    fun everyRoleBecomesTheOneReduceMotionCrossFadeInEveryStack() {
        val fade = MotionCurve.EaseInOut(0.20)
        val fadeSpec = tween<Float>(200, easing = Motion.EaseInOut)
        for (role in MotionRole.entries) {
            for (phase in MotionPhase.entries) {
                assertEquals("$role $phase", fade, role.curve(phase, reduceMotion = true))
                // Compose: no delay, no velocity.
                assertEquals(fadeSpec, role.animation<Float>(phase, reduceMotion = true, delaySeconds = 0.3))
                assertEquals(0f, role.initialVelocity(4f, reduceMotion = true))
                assertEquals(fadeSpec, role.staggered<Float>(4, phase, reduceMotion = true))
                // SpringSolver: critically damped over 0.20 s, no velocity.
                val solver = SpringSolver(role, phase, value = 0.0, velocity = 900.0, target = 1.0, reduceMotion = true)
                assertTrue(abs(solver.omega - 2 * PI / 0.20) < 1e-6)
                assertEquals(1.0, solver.zeta, 0.0)
                assertEquals(0.0, solver.velocity, 0.0)
            }
        }
        // Poses keep only opacity; the helpers drop travel, scale, rotation and blur.
        assertEquals(MotionPose(opacity = 0f), MotionPose.microIn.reduced(true))
        assertEquals(MotionPose(opacity = 0f), MotionPose.roll(5.dp).reduced(true))
        assertEquals(MotionPose(opacity = 0f, scale = 0.85f, blur = 3.dp), MotionPose.microIn.reduced(false))
        assertEquals(MotionPose(opacity = 0f, scale = 0.96f, blur = 3.dp, offset = DpOffset(0.dp, (-5).dp)), MotionPose.roll((-5).dp))
        assertTrue(MotionReduce.offset(12.dp, reduceMotion = true) == 0.dp && MotionReduce.offset(12.dp, reduceMotion = false) == 12.dp)
        assertTrue(MotionReduce.scale(0.9f, reduceMotion = true) == 1f && MotionReduce.scale(0.9f, reduceMotion = false) == 0.9f)
        assertEquals(0.dp, MotionReduce.blur(3.dp, reduceMotion = true))
        assertEquals(0f, MotionReduce.rotation(90f, reduceMotion = true))
    }

    /** Compose specs and the solver share the curve's numbers. */
    @Test
    fun everyRoleDrivesSwiftUIUIKitCoreAnimationAndTheSolverWithTheSameNumbers() {
        for (role in MotionRole.entries) {
            for (phase in MotionPhase.entries) {
                val curve = role.curve(phase)
                assertEquals(curve.animation<Float>(), role.animation<Float>(phase, reduceMotion = false))
                assertEquals(4f, role.initialVelocity(4f, reduceMotion = false))
                when (curve) {
                    is MotionCurve.Spring -> {
                        assertEquals(MotionSpring(curve.duration, curve.bounce).spec<Float>(), curve.animation<Float>())
                        val solver = SpringSolver(role, phase, value = 0.0, target = 1.0)
                        val expected = SpringSolver.parameters(curve.duration, curve.bounce)
                        assertTrue("$role", abs(solver.omega - expected.omega) < 1e-6 && abs(solver.zeta - expected.zeta) < 1e-6)
                    }
                    is MotionCurve.EaseOut -> assertEquals(tween<Float>(Motion.millis(curve.duration), easing = Motion.EaseOut), curve.animation<Float>())
                    is MotionCurve.EaseInOut -> assertEquals(tween<Float>(Motion.millis(curve.duration), easing = Motion.EaseInOut), curve.animation<Float>())
                }
            }
        }
        // Release is critically damped at ω.
        val release = SpringSolver(MotionRole.Release, value = 0.0, velocity = 300.0, target = 100.0)
        assertTrue(abs(release.omega - 28.5) < 1e-6)
        assertEquals(1.0, release.zeta, 0.0)
        assertEquals(300.0, release.velocity, 0.0)
        // Delays and staggers carry through when motion is on.
        assertEquals(delayed(MotionSpring(0.34, 0.0).spec<Float>(), 0.05), Motion.layout.animation<Float>(reduceMotion = false, delaySeconds = 0.05))
        assertEquals(delayed(MotionSpring(0.26, 0.15).spec<Float>(), 0.04), Motion.microIn.staggered<Float>(2, reduceMotion = false))
    }

    @Test
    fun solverRetargetsOntoARoleKeepingPositionAndVelocity() {
        val solver = SpringSolver(MotionRole.PanelOpen, value = 0.0, target = 100.0)
        repeat(8) { solver.step(dt = 1.0 / 120.0, snapWhenSettled = false) }
        val position = solver.value
        val velocity = solver.velocity
        solver.retarget(0.0, role = MotionRole.PanelClose)
        assertTrue(solver.value == position && solver.velocity == velocity)
        val close = SpringSolver.parameters(0.30, 0.0)
        assertTrue(abs(solver.omega - close.omega) < 1e-6 && solver.zeta == close.zeta)
        solver.retarget(50.0, role = MotionRole.Release, preservingVelocity = -400.0)
        assertTrue(solver.velocity == -400.0 && abs(solver.omega - 28.5) < 1e-6)
        solver.retarget(0.0, role = MotionRole.Release, reduceMotion = true)
        assertEquals("Reduce Motion settles without the throw", 0.0, solver.velocity, 0.0)
    }

    @Test
    fun releaseCommitsOnOneFlickRuleWithAReverseFlickVeto() {
        assertTrue(MotionRelease.shouldCommit(progress = 0.6, velocity = 0.0))
        assertFalse(MotionRelease.shouldCommit(progress = 0.4, velocity = 0.0))
        assertTrue("halfway commits", MotionRelease.shouldCommit(progress = 0.5, velocity = 0.0))
        assertTrue("a flick commits from anywhere", MotionRelease.shouldCommit(progress = 0.1, velocity = 250.0))
        assertFalse(MotionRelease.shouldCommit(progress = 0.1, velocity = 249.0))
        assertFalse("a flick back vetoes however far it got", MotionRelease.shouldCommit(progress = 0.9, velocity = -250.0))
        assertTrue(MotionRelease.shouldCommit(progress = 0.9, velocity = -200.0))
        assertTrue(MotionRelease.shouldCommit(translation = 160.0, velocity = 0.0, distance = 300.0))
        assertFalse(MotionRelease.shouldCommit(translation = 10.0, velocity = 0.0, distance = 0.0))
        assertEquals(2.0, MotionRelease.relativeVelocity(600.0, from = 100.0, to = 400.0), 0.0)
        assertEquals("no distance left, no velocity", 0.0, MotionRelease.relativeVelocity(600.0, from = 400.0, to = 400.0), 0.0)
    }

    @Test
    fun rubberBandHasAnExactInverseAndAContinuousSlope() {
        val band = MotionRubberBand(dimension = 320.0)
        var excess = -900.0
        while (excess <= 900.0) {
            val stretch = band.stretch(excess)
            assertTrue("never past its dimension", abs(stretch) < 320)
            assertTrue("inverse at $excess", abs(band.excess(forStretch = stretch) - excess) < 1e-6)
            // Slope matches the numeric derivative.
            val h = 1e-3
            val numeric = (band.stretch(excess + h) - band.stretch(excess - h)) / (2 * h)
            if (abs(excess) > 1) assertTrue(abs(numeric - band.slope(excess)) < 1e-4)
            excess += 37.5
        }
        assertEquals(0.0, band.stretch(0.0), 0.0)
        assertTrue("follows at the coefficient right past the end", abs(band.slope(0.0) - 0.55) < 1e-9)
        // Banding a dragged value and unbanding the drawn one round-trips, so a
        // catch mid-return picks the surface up where it's drawn.
        val bounds = 0.0..240.0
        var raw = -400.0
        while (raw <= 640.0) {
            val drawn = band.band(raw, bounds)
            assertTrue(abs(band.unband(drawn, bounds) - raw) < 1e-6)
            raw += 20
        }
        // Continuous at the ends.
        assertTrue(abs(band.band(-1e-6, bounds) - 0) < 1e-5)
        assertTrue(abs(band.band(240 + 1e-6, bounds) - 240) < 1e-5)
    }

    @Test
    fun ambientLoopsLockToOneClock() {
        val period = MotionTunables.breathPeriod.defaultValue
        assertEquals(0.0, AmbientClock.breath(0.0), 0.0)
        assertTrue(abs(AmbientClock.breath(period / 2) - 1) < 1e-9)
        assertTrue("same phase every period", abs(AmbientClock.breath(1000 * period + 0.3) - AmbientClock.breath(0.3)) < 1e-6)
        assertTrue(abs(AmbientClock.sweep(MotionTunables.sweepPeriod.defaultValue * 2.25) - 0.25) < 1e-6)
        assertTrue(abs(AmbientClock.sweep(0.4, fraction = 0.5) - 0.5) < 1e-6)
        assertEquals(0.75, AmbientClock.phase(-0.25, period = 1.0), 0.0)
        assertEquals(AmbientClock.Sample(breath = 0.5, sweep = 0.0, isSettled = true), AmbientClock.Sample.settled)
        assertEquals(1.0 / 60.0, AmbientRate.Foreground.minimumInterval, 0.0)
        assertEquals(1.0 / 30.0, AmbientRate.Background.minimumInterval, 0.0)
    }

    @Test
    fun springSolverMatchesFlightSpringAndIsFrameRateInvariant() {
        for (bounce in listOf(0.0, 0.18, -0.15)) {
            val duration = 0.42
            val params = SpringSolver.parameters(duration, bounce)
            val flightParams = FlightSpring.parameters(duration, bounce)
            assertTrue(abs(params.omega - flightParams.first) < 1e-6)
            assertTrue(abs(params.zeta - flightParams.second) < 1e-6)

            val solver60 = SpringSolver.of(duration = duration, bounce = bounce, value = 0.0, velocity = 0.0, target = 240.0)
            val solver120 = SpringSolver.of(duration = duration, bounce = bounce, value = 0.0, velocity = 0.0, target = 240.0)
            val flight60 = FlightSpring(value = 0.0, velocity = 0.0)

            repeat(18) {
                solver60.step(dt = 1.0 / 60.0, snapWhenSettled = false)
                flight60.step(toward = 240.0, omega = flightParams.first, zeta = flightParams.second, dt = 1.0 / 60.0)
                solver120.step(dt = 1.0 / 120.0, snapWhenSettled = false)
                solver120.step(dt = 1.0 / 120.0, snapWhenSettled = false)

                assertTrue(abs(solver60.value - flight60.value) < 1e-5)
                assertTrue(abs(solver60.velocity - flight60.velocity) < 1e-4)
                assertTrue(abs(solver60.value - solver120.value) < 1e-4)
                assertTrue(abs(solver60.velocity - solver120.velocity) < 1e-3)
            }
        }
    }

    @Test
    fun springSolverRetargetPreservesPositionAndVelocityOnMidFlightReversal() {
        val solver = SpringSolver(MotionTunables.panelOpenSpring.spring, value = 0.0, target = 100.0)
        repeat(8) { solver.step(dt = 1.0 / 120.0, snapWhenSettled = false) }
        val posBeforeReverse = solver.value
        val velBeforeReverse = solver.velocity
        assertTrue(posBeforeReverse > 10 && posBeforeReverse < 90)
        assertTrue(velBeforeReverse > 100)

        solver.retarget(0.0, spring = MotionTunables.panelCloseSpring.spring)
        assertEquals(posBeforeReverse, solver.value, 0.0)
        assertEquals(velBeforeReverse, solver.velocity, 0.0)

        // First 120 Hz step after reversing must continue smoothly with positive inertial momentum
        // before decelerating back toward 0, rather than snapping velocity to 0.
        val firstAfter = solver.step(dt = 1.0 / 120.0, snapWhenSettled = false)
        assertTrue(firstAfter.value > posBeforeReverse)
        assertTrue(firstAfter.velocity > 0 && firstAfter.velocity < velBeforeReverse)
    }

    @Test
    fun motionBlurRevealRespectsBounds() {
        // MotionBlurReveal gates blur to 0 at >= 0.98 (resting open) and under Reduce Motion,
        // while keeping blur through 0 so the filter group never tears down mid-collapse.
        assertEquals(14f, MotionBlurReveal.blurRadius(progress = 0f, maxBlur = 14f, reduceMotion = false), 0f)
        assertTrue(abs(MotionBlurReveal.blurRadius(progress = 0.01f, maxBlur = 14f, reduceMotion = false) - 13.86f) < 1e-5f)
        assertEquals(0f, MotionBlurReveal.blurRadius(progress = 0.99f, maxBlur = 14f, reduceMotion = false), 0f)
        assertEquals(0f, MotionBlurReveal.blurRadius(progress = 1f, maxBlur = 14f, reduceMotion = false), 0f)
        assertTrue(abs(MotionBlurReveal.blurRadius(progress = 0.5f, maxBlur = 14f, reduceMotion = false) - 7f) < 1e-5f)
        assertEquals(0f, MotionBlurReveal.blurRadius(progress = 0.5f, maxBlur = 14f, reduceMotion = true), 0f)
    }

    /**
     * Deleted: the `proMotionRange` (`CAFrameRateRange` 80–120) assertions, since Android has no
     * per-link frame-rate request (the loop runs at the display's refresh rate), and `isRunning`
     * (the loop is a suspend function that runs until it returns).
     */
    @Test
    fun motionDisplayLinkTicksAtProMotionRangeAndStopsOnRequest() = runTest {
        val clock = object : MonotonicFrameClock {
            var nanos = 1_000_000_000L
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                nanos += 8_333_333L
                return onFrame(nanos)
            }
        }
        val ticks = mutableListOf<MotionTick>()
        withContext(clock) {
            motionFrameLoop(frameDurationSeconds = 1.0 / 120.0) { tick ->
                ticks += tick
                if (ticks.size >= 4) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
        }
        assertEquals(4, ticks.size)
        assertTrue(ticks.all { it.dt > 0 && it.rawFrameDuration >= 1.0 / 240.0 })
    }

    /** Verbatim, as the reference. */
    private class FlightSpring(var value: Double, var velocity: Double = 0.0) {
        fun step(toward: Double, omega: Double, zeta: Double, dt: Double) {
            val target = toward
            val x0 = value - target
            val v0 = velocity
            if (abs(zeta - 1) < 1e-4) {
                val decay = exp(-omega * dt)
                val a = v0 + omega * x0
                value = target + (x0 + a * dt) * decay
                velocity = (v0 - omega * a * dt) * decay
            } else if (zeta < 1) {
                val wd = omega * sqrt(1 - zeta * zeta)
                val decay = exp(-zeta * omega * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                value = target + decay * (x0 * c + (v0 + zeta * omega * x0) / wd * s)
                velocity = decay * (v0 * c - (zeta * omega * v0 + omega * omega * x0) / wd * s)
            } else {
                val root = sqrt(zeta * zeta - 1)
                val r1 = -omega * (zeta - root)
                val r2 = -omega * (zeta + root)
                val c1 = (v0 - r2 * x0) / (r1 - r2)
                val c2 = x0 - c1
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                value = target + c1 * e1 + c2 * e2
                velocity = c1 * r1 * e1 + c2 * r2 * e2
            }
        }

        companion object {
            fun parameters(duration: Double, bounce: Double): Pair<Double, Double> {
                val omega = 2 * Math.PI / maxOf(duration, 0.01)
                val zeta = if (bounce >= 0) 1 - bounce else 1 / (1 + bounce)
                return omega to maxOf(zeta, 0.01)
            }
        }
    }
}
