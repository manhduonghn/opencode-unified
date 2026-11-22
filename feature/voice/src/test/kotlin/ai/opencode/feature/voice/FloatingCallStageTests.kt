package ai.opencode.feature.voice

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.store.StoreOf
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * One Shape: which stage the floating call settles on and its haptics, and the shape's geometry
 * between the orb, the card and the sheet.
 *
 * Compose's [Rect] is Float.
 */
class FloatingCallStageTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun TestScope.store(
        state: FloatingCallStageFeature.State = FloatingCallStageFeature.State(),
        haptics: VoiceFeatureTestSupport.HapticsRecorder,
    ) = TestStore(state, FloatingCallStageFeature(), this) { haptics.install(this) }

    @Test fun tappingTheOrbGrowsItIntoTheCard() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(haptics = log)
        store.send(FloatingCallStageFeature.Action.OrbTapped) { it.copy(stage = FloatingCallStage.card) }
        // Only the orb takes the tap.
        store.send(FloatingCallStageFeature.Action.OrbTapped)
        assertEquals(listOf("tap"), log.log)
    }

    @Test fun aDragTicksAtEachStageItCrossesAndSnapsWhereItLands() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.card), log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.card) }
        // Still within the card's reach: nothing to feel.
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.card))
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.full)) { it.copy(dragTarget = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.full))
        store.send(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.full)) {
            it.copy(dragTarget = null, stage = FloatingCallStage.full)
        }
        assertEquals(listOf("threshold", "snap"), log.log)
    }

    @Test fun aDragLetGoWhereItStartedSettlesQuietly() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.card), log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.orb)) { it.copy(dragTarget = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.DragCrossed(FloatingCallStage.card)) { it.copy(dragTarget = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.card)) { it.copy(dragTarget = null) }
        assertEquals(listOf("threshold", "threshold"), log.log)
    }

    @Test fun aFlickDownFromTheSheetStepsBackToTheCard() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.full), log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.card)) {
            it.copy(dragTarget = null, stage = FloatingCallStage.card)
        }
        assertEquals(listOf("snap"), log.log)
    }

    @Test fun aTapMidDragIsIgnored() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(haptics = log)
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.OrbTapped)
        assertTrue(log.log.isEmpty())
    }

    @Test fun theChevronAndEscapeStepBackQuietly() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.full), log)
        store.send(FloatingCallStageFeature.Action.StepBack) { it.copy(stage = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.StepBack) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.StepBack)
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full)) { it.copy(stage = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.MinimizeTapped) { it.copy(stage = FloatingCallStage.orb) }
        assertTrue(log.log.isEmpty())
    }

    /**
     * A pending request docks in the composer, which the card and the sheet cover: the call
     * shrinks back to the orb. The orb already leaves it showing, and a drag in progress is the
     * user's.
     */
    @Test fun aRequestDockingShrinksTheCallToTheOrb() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.full), log)
        store.send(FloatingCallStageFeature.Action.RequestDocked) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.RequestDocked)
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.card)) { it.copy(stage = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.RequestDocked) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full)) { it.copy(stage = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.RequestDocked)
        assertTrue(log.log.isEmpty())
    }

    /**
     * A keyboard rising (a field the card doesn't cover, a sheet's search) would bury Mute and
     * End at the screen's foot: the call shrinks to the orb, quietly, but never under a finger
     * mid-drag.
     */
    @Test fun aKeyboardRisingShrinksTheCallToTheOrb() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val store = store(FloatingCallStageFeature.State(stage = FloatingCallStage.card), log)
        store.send(FloatingCallStageFeature.Action.KeyboardRose) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.KeyboardRose)
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full)) { it.copy(stage = FloatingCallStage.full) }
        store.send(FloatingCallStageFeature.Action.KeyboardRose) { it.copy(stage = FloatingCallStage.orb) }
        store.send(FloatingCallStageFeature.Action.Requested(FloatingCallStage.card)) { it.copy(stage = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.DragBegan) { it.copy(dragTarget = FloatingCallStage.card) }
        store.send(FloatingCallStageFeature.Action.KeyboardRose)
        assertTrue(log.log.isEmpty())
    }

    /**
     * The sheet always covers the composer; the card only where it stands over the composer's
     * span, so on a wide screen the composer beside it stays. Unmeasured, it's taken to cover.
     */
    @Test fun theCardCoversTheComposerOnlyWhereItOverlapsIt() {
        val phone = Rect(Offset(12f, 860f), Size(416f, 60f))
        val card = Rect(Offset(12f, 384f), Size(416f, 560f))
        assertFalse(FloatingCallStage.orb.covers(composer = phone, card = card))
        assertTrue(FloatingCallStage.card.covers(composer = phone, card = card))
        assertTrue(FloatingCallStage.full.covers(composer = phone, card = card))
        // A tablet in landscape: a centred column, the card at the trailing edge past it.
        val column = Rect(Offset(300f, 950f), Size(560f, 60f))
        val trailing = Rect(Offset(1366f - 12f - 416f, 500f), Size(416f, 560f))
        assertFalse(FloatingCallStage.card.covers(composer = column, card = trailing))
        assertTrue(FloatingCallStage.full.covers(composer = column, card = trailing))
        val leading = Rect(Offset(12f, 500f), Size(416f, 560f))
        assertTrue(FloatingCallStage.card.covers(composer = column, card = leading))
        assertTrue(FloatingCallStage.card.covers(composer = null, card = trailing))
        assertTrue(FloatingCallStage.card.covers(composer = column, card = null))
    }

    /**
     * Beside a tablet's sidebar the composer's column sits off the screen's centre: the overlap
     * check still gets it (the orb's rest keeps only a centred composer), so a card at the far
     * edge leaves it showing.
     */
    @Test fun anOffCentreComposerColumnStillCountsForTheCover() {
        val center = FloatingCallCenter()
        center.screenWidth = 1180f
        val column = Rect(Offset(200f, 760f), Size(520f, 60f))
        center.noteComposer(column)
        center.noteComposerColumn(column)
        assertNull(center.composer)
        assertEquals(column, center.composerColumn)
        val trailing = Rect(Offset(1180f - 12f - 416f, 240f), Size(416f, 560f))
        assertFalse(FloatingCallStage.card.covers(composer = center.composerColumn, card = trailing))
        val leading = Rect(Offset(12f, 240f), Size(416f, 560f))
        assertTrue(FloatingCallStage.card.covers(composer = center.composerColumn, card = leading))
        center.noteComposerColumn(Rect.Zero)
        assertEquals(column, center.composerColumn)
    }

    @Test fun theCallKeepsItsStage() = runTest {
        val log = VoiceFeatureTestSupport.HapticsRecorder()
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = VoiceFeatureTestSupport.distantPast)
        val store = TestStore(VoiceFeature.State(server, session), VoiceFeature(), this) { log.install(this) }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.OrbTapped)) {
            it.copy(floatingStage = it.floatingStage.copy(stage = FloatingCallStage.card))
        }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.DragBegan)) {
            it.copy(floatingStage = it.floatingStage.copy(dragTarget = FloatingCallStage.card))
        }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.DragEnded(FloatingCallStage.full))) {
            it.copy(floatingStage = it.floatingStage.copy(dragTarget = null, stage = FloatingCallStage.full))
        }
        assertEquals(listOf("tap", "snap"), log.log)
    }

    // MARK: Geometry

    private val geometry = FloatingCallStageGeometry(
        screen = Size(440f, 956f),
        rest = frameOf(440 - 18 - 64, 800 - 64, 64, 64),
        low = 800.0,
        card = Size(416f, 440f),
        fullTop = 70.0,
    )

    @Test fun theShapeIsTheOrbTheCardAndTheSheet() {
        assertSameRect(geometry.rest, geometry.frame(0.0))
        assertSameRect(frameOf(12, 360, 416, 440), geometry.frame(1.0))
        // Its foot runs past the screen's bottom edge, out of sight.
        assertSameRect(frameOf(0, 70, 440, 956 + 48 - 70), geometry.frame(2.0))
        assertSameRect(frameOf(0, 70, 440, 956 - 70), geometry.visible(geometry.frame(2.0)))
        assertEquals(listOf(736.0, 360.0, 70.0), geometry.tops)
    }

    @Test fun growingFromTheOrbTheWidthLeads() {
        val half = geometry.frame(0.5)
        // Three quarters of the way across, half of the way up.
        assertTrue(abs(half.width - (64 + (416 - 64) * 0.75)) < 0.001)
        assertTrue(abs(half.height - (64 + (440 - 64) * 0.5)) < 0.001)
        assertEquals(800f, half.bottom, FLOAT_SLACK)
        assertTrue(abs(half.right - (422 + (428 - 422) * 0.75)) < 0.001)
    }

    @Test fun theFingerHoldsTheTopEdgeAndPastTheTopItRubberBands() {
        for (step in 0..8) {
            val progress = step * 0.25
            val lift = geometry.lift(progress)
            assertTrue(abs(geometry.progress(atLift = lift) - progress) < 0.0001)
            // With the orb resting below the card, the travel is the top edge's.
            assertTrue(abs(geometry.frame(progress).top - (736 - lift)) < 0.0001 + FLOAT_SLACK)
        }
        val over = geometry.progress(atLift = 736.0 - 70 + 200)
        assertTrue(over > 2)
        // 200 pt past the top moves the sheet only about 52 pt.
        assertTrue(abs(70 - geometry.frame(over).top - 52.4) < 0.5)
        // A finger catching it there holds it where it's drawn.
        assertTrue(abs(geometry.lift(over) - (736 - 70 + 200)) < 0.01)
        assertTrue(abs(geometry.progress(atLift = geometry.lift(over)) - over) < 0.0001)
        assertEquals(0.0, geometry.progress(atLift = -100.0), 0.0)
    }

    @Test fun anOrbCarriedAboveTheCardsTopStillFollowsTheFinger() {
        // Dragged up to rest above where the card's top would be.
        val high = geometry.copy(rest = frameOf(358, 200, 64, 64))
        assertTrue(high.tops[0] < high.tops[1])
        // Every point of travel up moves the shape on, never back, never a jump.
        var last = 0.0
        for (lift in 0..600 step 2) {
            val progress = high.progress(atLift = lift.toDouble())
            assertTrue(progress >= last)
            assertTrue(progress - last < 0.05)
            last = progress
        }
        assertTrue(high.progress(atLift = 1.0) < 0.02)
        // Let go a little way up: back to the orb, not on to the sheet.
        assertEquals(FloatingCallStage.orb, high.settle(high.progress(atLift = 30.0), 0.0).stage)
        assertEquals(FloatingCallStage.card, high.settle(high.progress(atLift = 100.0), 0.0).stage)
    }

    @Test fun cornersRoundAsTheOrbThenSquareAtTheSheetsFoot() {
        val orb = geometry.radii(geometry.frame(0.0))
        assertTrue(orb.top == 32.0 && orb.bottom == 32.0)
        // The design system's sheet radius here.
        val card = geometry.radii(geometry.frame(1.0))
        assertTrue(card.top == 16.0 && card.bottom == 16.0)
        val sheet = geometry.radii(geometry.frame(2.0))
        assertTrue(sheet.top == 16.0 && sheet.bottom == 0.0)
    }

    @Test fun theBloomFillsTheOrbThenSettlesAtTheTop() {
        val orb = geometry.bloom(geometry.frame(0.0))
        assertEquals(geometry.rest.center.x.toDouble(), orb.centerX, 0.0)
        assertEquals(geometry.rest.center.y.toDouble(), orb.centerY, 0.0)
        assertEquals(32.0, orb.radius, 0.0)
        val card = geometry.bloom(geometry.frame(1.0))
        assertTrue(card.centerY == 360.0 + 166 && card.radius == 96.0)
        val sheet = geometry.bloom(geometry.frame(2.0))
        assertTrue(sheet.centerY == 70.0 + 150 && sheet.radius == 76.0)
    }

    @Test fun aReleaseSettlesOnTheNearestStageAndAFlickSkipsAhead() {
        // Let go just past halfway to the card, still: the card.
        assertEquals(FloatingCallStage.card, geometry.settle(0.55, 0.0).stage)
        assertEquals(FloatingCallStage.orb, geometry.settle(0.3, 0.0).stage)
        // A flick up from just above the orb carries on to the card.
        assertEquals(FloatingCallStage.card, geometry.settle(0.1, -600.0).stage)
        // A flick down from just below the sheet steps back to the card, not the orb.
        assertEquals(FloatingCallStage.card, geometry.settle(1.9, 600.0).stage)
        // A hard fling from the card reaches the orb.
        assertEquals(FloatingCallStage.orb, geometry.settle(1.0, 4000.0).stage)
        // The finger's speed carries into the spring, in stages a second.
        val carried = geometry.settle(0.5, -376.0)
        assertTrue(abs(carried.velocity - 1) < 0.001)
    }

    @Test fun theCardStaysOnTheScreenAndOnTheOrbsSide() {
        val narrow = geometry.copy(screen = Size(375f, 667f), low = 560.0, rest = frameOf(18, 496, 64, 64), trailing = false)
        val card = narrow.cardFrame
        assertTrue(card.left == 12f && card.width == 375f - 24)
        assertTrue(card.top >= narrow.fullTop + 40)
        assertEquals(560f, card.bottom, FLOAT_SLACK)
    }

    /** A docked request is part of the composer's frame, so the card stands above it, shorter if it must. */
    @Test fun theCardStandsAboveADockedRequest() {
        val docked = geometry.copy(low = geometry.low - 300)
        val card = docked.cardFrame
        assertEquals(docked.low.toFloat(), card.bottom, FLOAT_SLACK)
        assertTrue(card.top >= docked.fullTop + 40)
    }
}

/** Compose rects are Float. */
private const val FLOAT_SLACK = 0.001f

private fun frameOf(x: Number, y: Number, width: Number, height: Number): Rect =
    Rect(Offset(x.toFloat(), y.toFloat()), Size(width.toFloat(), height.toFloat()))

private fun assertSameRect(expected: Rect, actual: Rect, message: String = "", slack: Float = FLOAT_SLACK) {
    val same = abs(expected.left - actual.left) <= slack && abs(expected.top - actual.top) <= slack &&
        abs(expected.right - actual.right) <= slack && abs(expected.bottom - actual.bottom) <= slack
    assertTrue("$message: expected $expected, got $actual", same)
}

private fun Rect.offsetBy(dy: Float): Rect = translate(0f, dy)

/**
 * Each scene's floating window keeps its own touches and call: another scene closing leaves them
 * alone.
 */
class FloatingCallWindowTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun aSceneClosingLeavesAnotherScenesCall() {
        val center = FloatingCallCenter()
        val calling = Any()
        val other = Any()
        center.noteCall(true, calling)
        // Another scene opening without a call, then closing.
        center.noteCall(false, other)
        assertTrue(center.hasCall)
        center.noteCall(false, calling)
        assertFalse(center.hasCall)
    }

    // Android has one window per activity; `FloatingCallOverlay` passes touches outside
    // `FloatingCallTouchArea` through by only attaching pointer input to the shape (and the full
    // screen only while the sheet is up), which needs Compose UI to test.
}

/**
 * Lift and Spin Up: out of the call button into the orb, and the hang-up back into it, frame by
 * frame.
 */
class FloatingCallConnectTests {
    @get:Rule val isolation = TestIsolationRule()

    private val geometry = FloatingCallStageGeometry(
        screen = Size(440f, 956f),
        rest = frameOf(440 - 18 - 64, 800 - 64, 64, 64),
        low = 800.0,
        card = Size(416f, 440f),
        fullTop = 70.0,
    )

    /** The call button on a 440 × 956 screen, and the orb's rest above it. */
    private val callButton = frameOf(440 - 40 - 17, 956 - 68 - 17, 34, 34)
    private val orbRest = frameOf(440 - 18 - 64, 956 - 94 - 12 - 64, 64, 64)

    /** One drawn frame: the shape, how much of it shows, and the spin's speed. */
    private data class Drawn(val time: Double, val frame: Rect, val alpha: Double, val spin: Double)

    /**
     * Fails on any frame where nothing is drawn, or where the shape's position or size or the
     * spin's speed jumps: faster than [speed] points a second, or the spin's speed changing
     * faster than [spinning] radians a second per second.
     */
    private fun expectContinuous(frames: List<Drawn>, what: String, speed: Double = 3000.0, spinning: Double = 400.0) {
        for (frame in frames) {
            assertTrue("$what: drawn at ${frame.time}", frame.alpha > 0.99 && frame.frame.width >= 1 && frame.frame.height >= 1)
        }
        for ((a, b) in frames.zipWithNext()) {
            val dt = max(1e-6, b.time - a.time)
            val moved = maxOf(
                hypot(b.frame.center.x - a.frame.center.x, b.frame.center.y - a.frame.center.y),
                abs(b.frame.width - a.frame.width),
                abs(b.frame.height - a.frame.height),
            ).toDouble()
            assertTrue("$what: no jump at ${b.time} ($moved pt)", moved / dt < speed)
            assertTrue("$what: the spin's speed carries on at ${b.time}", abs(b.spin - a.spin) / dt < spinning)
        }
    }

    private class Lifted(val motion: FloatingCallConnectMotion, val frames: List<Drawn>)

    /** The lift from the press, at [hz], until [until] seconds or it rests. */
    private fun lift(hz: Double = 120.0, until: Double = 2.0, connectAt: Double? = null): Lifted {
        val motion = FloatingCallConnectMotion()
        val rest = orbRest
        motion.start(from = callButton, rest = { rest }, at = 0.0, runs = false)
        val frames = mutableListOf(Drawn(0.0, motion.frame!!, 1.0, motion.spin!!.sample(0.0).rate))
        var t = 0.0
        while (t < until) {
            t += 1 / hz
            if (connectAt != null && t - 1 / hz < connectAt && t >= connectAt) motion.connected(at = t, reduceMotion = false, runs = false)
            motion.step(t)
            frames += Drawn(t, motion.frame ?: rest, 1.0, motion.spin!!.sample(t).rate)
        }
        return Lifted(motion, frames)
    }

    @Test fun theOrbStartsAsTheCallButtonAndLiftsPastItsRestIntoIt() {
        val lifted = lift()
        val frames = lifted.frames
        assertSameRect(callButton, frames[0].frame, "It starts exactly as the call button")
        assertTrue("It lifts a little past its rest", frames.any { it.frame.center.y < orbRest.center.y - 1 })
        expectContinuous(frames, "Lifting")
        assertTrue("It settles exactly on its rest and hands over", lifted.motion.frame == null && frames.last().frame == orbRest)
        assertEquals("The white button has given way to the spinner", 0.0, lifted.motion.button, 0.0)
    }

    @Test fun theGlyphStartsTheSpinThatSpeedsUpAndCarriesOnIntoTheSwirl() {
        val frames = lift(until = 3.0, connectAt = 1.4).frames
        assertTrue("The glyph starts turning on the press", frames[0].spin == 0.0 && frames[1].spin > 0)
        val peak = frames.first { it.time >= 1.4 }.spin
        assertTrue("It speeds up while connecting", peak > frames.first { it.time >= 0.1 }.spin * 2)
        expectContinuous(frames, "Spinning")
        assertTrue("It never turns back", frames.all { it.spin >= 0 })
        assertTrue("Its speed has gone into the live orb's swirl", frames.last().spin < peak * 0.01)
    }

    @Test fun connectingAtRestSwellsAndSettlesBackExactly() {
        val motion = FloatingCallConnectMotion()
        val rest = orbRest
        motion.start(from = callButton, rest = { rest }, at = 0.0, runs = false)
        var t = 0.0
        while (motion.frame != null) {
            t += 1.0 / 120
            motion.step(t)
        }
        motion.connected(at = t, reduceMotion = false, runs = false)
        var widest = 0f
        val frames = mutableListOf<Drawn>()
        while (motion.frame != null) {
            t += 1.0 / 120
            motion.step(t)
            widest = max(widest, motion.frame?.width ?: 0f)
            frames += Drawn(t, motion.frame ?: rest, 1.0, 0.0)
        }
        assertTrue("It swells on connect", widest > rest.width * 1.03f)
        assertEquals(rest, frames.last().frame)
        expectContinuous(frames, "Swelling")
    }

    /** Lands from [from] (moving at [velocity]) into a button [target] says is where it is at a time, frame by frame, until it has landed. */
    private fun land(from: Rect, velocity: List<Double>?, target: (Double) -> Rect): List<Drawn> {
        val motion = FloatingCallStageMotion()
        var t = 0.0
        motion.land(target(0.0), from, velocity = velocity, fades = false, target = { target(t) }, geometry = geometry, reduceMotion = false, runs = false)
        val frames = mutableListOf(Drawn(0.0, motion.frame(geometry), motion.opacity, 0.0))
        while (!motion.hasLanded && t < 3) {
            t += 1.0 / 120
            motion.step(1.0 / 120)
            frames += Drawn(t, motion.frame(geometry), motion.opacity, 0.0)
        }
        assertTrue(motion.hasLanded)
        return frames
    }

    /** No axis of the shape passes the button's. */
    private fun expectNeverPasses(frames: List<Drawn>, button: (Double) -> Rect, what: String) {
        val axes: List<(Rect) -> Float> = listOf({ it.center.x }, { it.center.y }, { it.width }, { it.height })
        for (axis in axes) {
            var side: Boolean? = null
            for (frame in frames) {
                val away = axis(frame.frame) - axis(button(frame.time))
                if (abs(away) <= 0.001f) continue
                if (side != null) assertEquals("$what: never past the button at ${frame.time}", side, away > 0)
                side = away > 0
            }
        }
    }

    @Test fun hangingUpMidLiftLandsExactlyInTheButtonWithTheSpeedItHas() {
        val motion = FloatingCallConnectMotion()
        val rest = orbRest
        motion.start(from = callButton, rest = { rest }, at = 0.0, runs = false)
        var t = 0.0
        var before: Rect? = null
        while (t < 0.25) {
            t += 1.0 / 120
            before = motion.frame
            motion.step(t)
        }
        val flight = motion.hangUp(at = t, reduceMotion = false, runs = false)!!
        assertNull("The landing takes the shape", motion.frame)
        val button = callButton
        val frames = land(flight.frame, flight.velocity) { button }
        assertSameRect(flight.frame, frames[0].frame, "It lands from where it is")
        expectContinuous(listOf(Drawn(-1.0 / 120, before!!, 1.0, 0.0)) + frames, "Hanging up mid-lift")
        assertTrue("It keeps its upward speed for a moment", frames[1].frame.center.y < frames[0].frame.center.y)
        expectNeverPasses(frames.drop(12), { button }, "Landing")
        assertSameRect(button, frames.last().frame, "It lands exactly in the button")
    }

    @Test fun aHangUpFromTheCardNeverPassesTheButton() {
        val button = callButton
        // Shrinking fast toward the button, faster than a spring could stop in time.
        val frames = land(geometry.frame(1.0), listOf(400.0, 6000.0, -8000.0, -8000.0)) { button }
        expectContinuous(frames, "From the card", speed = 9000.0)
        expectNeverPasses(frames, { button }, "From the card")
        assertSameRect(button, frames.last().frame)
    }

    @Test fun aLandingFollowsTheButtonAsTheKeyboardMovesIt() {
        // The keyboard drops away mid-flight: the button slides 300 pt down.
        val high = callButton.offsetBy(-300f)
        val low = callButton
        val button: (Double) -> Rect = { t ->
            val k = min(1.0, max(0.0, (t - 0.1) / 0.25))
            high.offsetBy((300 * k * k * (3 - 2 * k)).toFloat())
        }
        val frames = land(orbRest.offsetBy(-300f), null, button)
        expectContinuous(frames, "Following the button")
        assertSameRect(low, frames.last().frame, "It lands exactly where the button ends up")
    }

    @Test fun hangingUpWindsTheSpinDownOntoAnUprightGlyph() {
        val motion = FloatingCallConnectMotion()
        val rest = orbRest
        motion.start(from = callButton, rest = { rest }, at = 0.0, runs = false)
        var t = 0.0
        while (t < 0.8) {
            t += 1.0 / 120
            motion.step(t)
        }
        val speed = motion.spin!!.sample(t).rate
        motion.hangUp(at = t, reduceMotion = false, runs = false)
        val rates = mutableListOf(speed)
        var angle = motion.glyphAngle
        while (motion.step(t + 1.0 / 120) == MotionFrameAction.Continue && t < 3) {
            t += 1.0 / 120
            assertTrue("It never turns back", motion.glyphAngle >= angle)
            angle = motion.glyphAngle
            rates += motion.spin!!.sample(t).rate
        }
        for ((a, b) in rates.zipWithNext()) assertTrue("No jump in its speed", abs(b - a) * 120 < 700)
        val turns = motion.glyphAngle / (2 * PI)
        assertTrue("Upright at a whole turn", abs(turns - turns.roundToLong()) < 1e-9)
        assertTrue("Settled soon after it lands", t < 2)
    }
}

/**
 * The transcript composed for real. It must keep up with captions arriving after a pause, and lay
 * out a word wider than its line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingCallTranscriptTests {
    private class CaptionStore(initial: VoiceFeature.State) : StoreOf<VoiceFeature.State, VoiceFeature.Action> {
        val flow = MutableStateFlow(initial)
        override val state: VoiceFeature.State get() = flow.value
        override val states: Flow<VoiceFeature.State> get() = flow
        override fun send(action: VoiceFeature.Action) {}
    }

    // Frames every 16 ms, as on a device (Robolectric's default is every millisecond).
    @Before fun frameRate() = ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))

    private var composed: ComposeView? = null

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun compose(store: CaptionStore, width: Int): ComposeView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        composed = view
        activity.setContentView(view)
        view.setContent {
            Box(Modifier.size(width.dp, 400.dp)) { FloatingCallTranscript(store, Modifier.fillMaxSize()) }
        }
        idle(50)
        return view
    }

    // The transcript's frame loop waits for the real clock, which Robolectric's frames never reach.
    @After fun dispose() = composed?.disposeComposition() ?: Unit

    /**
     * Words arriving after a pause are revealed at once, without suspending; the reveal loop
     * must see each word it revealed, or it never ends and the app stops responding.
     */
    @Test fun captionsAfterAPauseAreRevealedWithoutHanging() {
        val store = CaptionStore(VoiceFeature.State(VoiceFeatureTestSupport.server, VoiceFeatureTestSupport.session))
        compose(store, width = 320)
        // The reveal runs on the real clock: a real pause makes the next words due at once.
        Thread.sleep(100)
        store.flow.value = store.flow.value.copy(liveAssistantText = "Checking the upload test now ")
        idle(50)
        Thread.sleep(100)
        store.flow.value = store.flow.value.copy(liveAssistantText = "Checking the upload test now and the retry test ")
        idle(50)
    }

    /** A path or link longer than the line wraps within its own row instead of crashing. */
    @Test fun aWordWiderThanTheLineWrapsInItsOwnRow() {
        val word = "/Users/kit/project/packages/opencode/src/session/" + "deeply/nested/".repeat(20) + "upload.test.ts"
        val store = CaptionStore(
            VoiceFeature.State(VoiceFeatureTestSupport.server, VoiceFeatureTestSupport.session).copy(liveAssistantText = "Open $word please "),
        )
        compose(store, width = 160)
        idle(2_000)
    }
}
