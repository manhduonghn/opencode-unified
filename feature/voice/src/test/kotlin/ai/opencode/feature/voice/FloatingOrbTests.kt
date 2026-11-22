package ai.opencode.feature.voice

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.TuneValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Living Orb's floating orb: its looks, what a touch on it was, how it drifts and rests, and its
 * surface's bounds and lifetime.
 *
 * Its Android half, the motions letting go of what they captured, is
 * [stoppingTheLiftLetsGoOfItsRest].
 */
class FloatingOrbTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun Rect.containsRect(other: Rect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    @Test fun stylePresetsSwitchBetweenTheOrbAndTheCapsule() {
        val presets = FloatingCallTunables.styles
        assertEquals("call.float.style", presets.key)
        assertEquals("call", presets.group)
        assertEquals(listOf("Orb", "Capsule with End"), presets.presets.map { it.name })
        // The first is what ships: the orb, at its default.
        val first = presets.changes(applying = 0)
        assertTrue(first.containsKey("call.float.kind") && first["call.float.kind"] == null)
        assertEquals(TuneValue.Choice("capsule"), presets.changes(applying = 1)["call.float.kind"])
        assertEquals(FloatingCallStyle.orb, FloatingCallTunables.style.defaultValue)
    }

    /**
     * Floaty: only a tap expands. Anything that moved past the slop, or was held past the
     * tap's time, is a drag however it went, and a tap on a drifting orb only catches it.
     */
    @Test fun onlyATapExpandsTheOrb() {
        assertEquals(FloatingCallTouch.tap, FloatingCallTouch(travel = 3.0, duration = 0.1, wasMoving = false, slop = 10.0, time = 0.3))
        assertEquals(FloatingCallTouch.caught, FloatingCallTouch(travel = 3.0, duration = 0.1, wasMoving = true, slop = 10.0, time = 0.3))
        // An upward drag (what grew the card before) is a drag, short or long.
        for (travel in listOf(10.0, 24.0, 60.0, 300.0)) {
            for (duration in listOf(0.05, 0.2, 0.8)) {
                assertEquals(FloatingCallTouch.drag, FloatingCallTouch(travel = travel, duration = duration, wasMoving = false, slop = 10.0, time = 0.3))
            }
        }
        // Pressed and held still: it moved nowhere, and doesn't expand.
        assertEquals(FloatingCallTouch.drag, FloatingCallTouch(travel = 2.0, duration = 0.6, wasMoving = false, slop = 10.0, time = 0.3))
    }

    /**
     * A hard fling into a corner drifts on, slows, and rests inside the keep-outs: past the edge
     * only briefly and never off screen, eased back by the soft spring rather than stopped dead
     * at it.
     */
    @Test fun aFlingRestsInBounds() {
        val bounds = Rect(Offset(60f, 168f), Size(282f, 520f))
        // The orb's centre on a 402 pt wide screen, 44 pt in from each edge.
        val reach = Rect(Offset(44f, 128f), Size(314f, 600f))
        for (velocity in listOf(Offset(-6000f, -6000f), Offset(4000f, 900f), Offset(0f, 5000f), Offset(300f, -200f))) {
            val drift = FloatingCallDrift(position = Offset(300f, 600f), velocity = velocity, friction = 3.2, edgeDuration = 0.4, edgeBounce = 0.0)
            var rested = false
            var farthest = 0f
            var steps = 0
            while (!rested && steps < 120 * 6) {
                rested = drift.step(1.0 / 120, bounds, reach)
                assertTrue("$velocity stays on screen", reach.inflate(0.5f).contains(drift.position))
                val outX = maxOf(bounds.left - drift.position.x, drift.position.x - bounds.right, 0f)
                val outY = maxOf(bounds.top - drift.position.y, drift.position.y - bounds.bottom, 0f)
                farthest = maxOf(farthest, outX, outY)
                steps += 1
            }
            assertTrue("$velocity came to rest", rested)
            assertTrue("$velocity rests inside the keep-outs", bounds.inflate(0.01f).contains(drift.position))
            if (velocity.x == -6000f) assertTrue("a hard fling runs a little past the edge and eases back, not a hard stop", farthest > 4)
        }
        // A gentle push glides on with friction, well past where it was let go.
        val gentle = FloatingCallDrift(position = Offset(200f, 400f), velocity = Offset(0f, -300f), friction = 3.2, edgeDuration = 0.4, edgeBounce = 0.0)
        @Suppress("ControlFlowWithEmptyBody")
        while (!gentle.step(1.0 / 120, bounds)) {}
        assertTrue("it coasts speed ÷ friction", abs(gentle.position.y - (400 - 300 / 3.2)) < 2)
    }

    /** Let go outside the keep-outs (a keyboard raised the composer under it), it eases back in without passing the edge. */
    @Test fun theSoftEdgeEasesItBackIn() {
        val bounds = Rect(Offset(60f, 168f), Size(282f, 300f))
        val drift = FloatingCallDrift(position = Offset(342f, 560f), friction = 3.2, edgeDuration = 0.4, edgeBounce = 0.0)
        var last = drift.position.y
        while (!drift.step(1.0 / 120, bounds)) {
            assertTrue("it only moves back in", drift.position.y <= last + 0.001f)
            assertTrue("it never overshoots into the space", drift.position.y >= bounds.bottom - 0.5f)
            last = drift.position.y
        }
        assertTrue(abs(drift.position.y - bounds.bottom) < 0.06f)
    }

    /** Grabbed mid-drift, it stops dead under the finger: no more drift, and it doesn't rest anywhere until it's let go again. */
    @Test fun aFingerCatchesItMidDrift() {
        val motion = FloatingCallFloatMotion()
        val bounds = Rect(Offset(60f, 168f), Size(282f, 520f))
        val rested = mutableListOf<Offset>()
        motion.hold(Offset(300f, 600f))
        motion.release(Offset(-2000f, -1500f), { FloatingCallFloatMotion.Bounds(bounds, bounds.inflate(40f)) }, reduceMotion = false) { rested += it }
        repeat(12) { motion.step(1.0 / 120) }
        val flying = motion.position
        assertTrue("it drifts with the throw", flying != null && flying.x < 300f)
        assertTrue("still fast enough that a tap only catches it", hypot(motion.velocity.x, motion.velocity.y) > 120f)

        motion.hold(flying!!)
        assertTrue(motion.isHeld)
        assertEquals(Offset.Zero, motion.velocity)
        assertEquals(MotionFrameAction.Stop, motion.step(1.0 / 120))
        assertEquals("held, it stays under the finger", flying, motion.position)
        motion.move(Offset(120f, 300f))
        assertEquals(Offset(120f, 300f), motion.position)
        assertTrue("caught, it never rested where the throw was heading", rested.isEmpty())

        motion.release(Offset.Zero, { FloatingCallFloatMotion.Bounds(bounds, bounds) }, reduceMotion = false) { rested += it }
        assertEquals(MotionFrameAction.Stop, motion.step(1.0 / 120))
        assertEquals("let go inside, it rests where it was put", listOf(Offset(120f, 300f)), rested)
        assertNull(motion.position)
        motion.stop()
    }

    @Test fun theOrbRestsAboveTheComposerOnlyWhileTheCardIsAtRest() {
        val center = FloatingCallCenter()
        center.screenWidth = 440f
        val resting = Rect(Offset(20f, 860f), Size(400f, 56f))
        center.noteComposer(resting)
        assertEquals(resting, center.composer)
        // The card sliding with the drawer moves the composer: the orb stays put.
        center.noteComposer(resting.translate(180f, 0f))
        assertEquals(resting, center.composer)
        // The keyboard raising it is a new rest.
        val raised = resting.translate(0f, -340f)
        center.noteComposer(raised)
        assertEquals(raised, center.composer)
        // Beside a tablet's sidebar the composer stands off-centre and stays.
        val column = Rect(Offset(400f, 1100f), Size(600f, 56f))
        center.screenWidth = 1180f
        center.noteComposer(column)
        assertEquals(raised, center.composer)
        center.noteComposer(column, besideSidebar = true)
        assertEquals(column, center.composer)
    }

    /** The transcript ends above wherever the orb actually rests over the composer's column, and makes no room beside it or well above it. */
    @Test fun theTranscriptMakesRoomForTheOrbWhereItRests() {
        val composer = Rect(Offset(0f, 800f), Size(440f, 60f))
        val gap = 12f
        val size = 56f
        val rest = Rect(Offset(360f, 800f - gap - size), Size(size, size))
        assertEquals("At rest: its size and the gap", size + gap, FloatingCallRestArea.clearance(rest, composer, gap), 0.001f)
        val nudged = rest.translate(0f, -30f)
        assertEquals("Nudged up: all of it", size + gap + 30f, FloatingCallRestArea.clearance(nudged, composer, gap), 0.001f)
        val keyboard = composer.translate(0f, -300f)
        assertEquals("Over a keyboard", size + gap, FloatingCallRestArea.clearance(rest.translate(0f, -300f), keyboard, gap), 0.001f)
        assertEquals("Dragged up over the transcript", 0f, FloatingCallRestArea.clearance(rest.translate(0f, -400f), composer, gap), 0.001f)
        val wide = Rect(Offset(300f, 800f), Size(600f, 60f))
        assertEquals("Beside the column", 0f, FloatingCallRestArea.clearance(rest.translate(600f, 0f), wide, gap), 0.001f)
        assertEquals(0f, FloatingCallRestArea.clearance(Rect.Zero, composer, gap), 0.001f)
    }

    /**
     * The session works the orb's rest out from its own composer in the same pass, so the room
     * holds as the keyboard rises or falls and the composer grows, rather than reading a rest the
     * window hasn't moved yet (it swung 68 → 0 → 68).
     */
    @Test fun theRoomHoldsAsTheComposerMoves() {
        val center = FloatingCallCenter()
        val gap = FloatingCallTunables.composerGap.value.toFloat()
        val size = 56f
        center.orbLayout = FloatingCallOrbLayout(
            screen = Size(440f, 956f),
            safeArea = FloatingCallEdgeInsets(top = 62f, leading = 0f, bottom = 34f, trailing = 0f),
            size = Size(size, size),
        )
        fun room(composerTop: Float, height: Float = 60f): Float {
            val composer = Rect(Offset(0f, composerTop), Size(440f, height))
            val orb = center.restingOrb(composerTop) ?: return -1f
            return FloatingCallRestArea.clearance(orb, composer, gap)
        }
        // At rest, the keyboard rising, the composer growing, the keyboard falling.
        for ((top, height) in listOf(862f to 60f, 520f to 60f, 440f to 140f, 862f to 60f)) {
            assertEquals("Composer top $top", size + gap, room(top, height), 0.001f)
        }
        // Dragged just above the composer, it holds there as the composer rises into it, and the room follows the orb.
        center.place = FloatingCallPlace(x = 380f, y = 862f - gap - size / 2 - 10f)
        assertEquals(size + gap + 10f, room(862f), 0.001f)
        assertEquals("Pushed up by the keyboard: it rests on the composer", size + gap, room(520f), 0.001f)
        // No call window: no room.
        center.orbLayout = null
        assertNull(center.restingOrb(862f))
    }

    // MARK: FloatingOrbSurfaceTests: the surface's motion reads the screen as it is each frame,
    // and lets go of the surface when the call is over.

    private val orb = Size(64f, 64f)
    private val portrait = Size(440f, 956f) to FloatingCallEdgeInsets(top = 62f, leading = 0f, bottom = 34f, trailing = 0f)
    private val landscape = Size(956f, 440f) to FloatingCallEdgeInsets(top = 0f, leading = 62f, bottom = 21f, trailing = 62f)

    private fun area(layout: FloatingCallLayout): FloatingCallRestArea =
        FloatingCallRestArea(layout.screen, layout.safeArea, composerTop = layout.screen.height - 100, size = orb)

    /**
     * A keyboard the composer doesn't ride (the drawer's search) would hide the orb resting
     * above the composer: it rests above the keyboard, and back down once the keyboard has gone.
     * The composer's own keyboard, which raises the composer above it, changes nothing.
     */
    @Test fun theOrbRestsAboveAKeyboardTheComposerDoesntRide() {
        val gap = FloatingCallTunables.composerGap.value.toFloat()
        var area = FloatingCallRestArea(portrait.first, portrait.second, composerTop = 850f, size = orb)
        val rested = area.frame(FloatingCallPlace())
        assertEquals(850f - gap, rested.bottom)
        area = area.copy(keyboardTop = 620f)
        val raised = area.frame(FloatingCallPlace())
        assertEquals(620f - gap, raised.bottom)
        assertEquals(rested.center.x, raised.center.x)
        // Dragged low, it still clears the keyboard.
        assertTrue(area.frame(FloatingCallPlace(x = 100f, y = 900f)).bottom <= 620f - gap)
        // Raised by its keyboard, the composer already sits above it.
        area = area.copy(composerTop = 560f)
        assertEquals(560f - gap, area.frame(FloatingCallPlace()).bottom)
        area = area.copy(composerTop = 850f, keyboardTop = null)
        assertEquals(rested, area.frame(FloatingCallPlace()))
    }

    /** Turned to landscape mid-throw, the orb carries on inside the new screen, not from where it was on the old one, and rests in its keep-outs. */
    @Test fun aRotationMidDriftKeepsTheOrbOnTheNewScreen() {
        val layout = FloatingCallLayout(portrait.first, portrait.second)
        val motion = FloatingCallFloatMotion()
        val rested = mutableListOf<Offset>()
        motion.hold(Offset(360f, 700f))
        motion.release(Offset(-600f, 500f), {
            val now = area(layout)
            FloatingCallFloatMotion.Bounds(now.bounds, now.reach)
        }, reduceMotion = false) { rested += it }
        repeat(6) { motion.step(1.0 / 120) }
        assertTrue("Still drifting low on the tall screen", motion.position?.let { it.y > 600f } == true)

        layout.screen = landscape.first
        layout.safeArea = landscape.second
        val turned = area(layout)
        motion.clamp(into = turned.reach.union(turned.bounds))
        var steps = 0
        while (steps < 120 * 6) {
            val position = motion.position ?: break
            assertTrue("On the turned screen at step $steps", turned.reach.inflate(0.5f).contains(position))
            motion.step(1.0 / 120)
            steps += 1
        }
        assertTrue("It rests inside the turned screen's keep-outs", rested.size == 1 && turned.bounds.inflate(0.01f).contains(rested[0]))
    }

    /** Turned mid-lift, the shape lifts into the new screen's rest. */
    @Test fun aRotationMidLiftLandsOnTheNewRest() {
        val layout = FloatingCallLayout(portrait.first, portrait.second)
        val motion = FloatingCallConnectMotion()
        motion.start(from = Rect(Offset(383f, 871f), Size(34f, 34f)), rest = { area(layout).frame(FloatingCallPlace()) }, at = 0.0, runs = false)
        var t = 0.0
        while (t < 0.2) {
            t += 1.0 / 120
            motion.step(t)
        }
        layout.screen = landscape.first
        layout.safeArea = landscape.second
        var last = motion.frame
        while (motion.frame != null && t < 3) {
            last = motion.frame
            t += 1.0 / 120
            motion.step(t)
        }
        val rest = area(layout).frame(FloatingCallPlace())
        assertNull("It settled", motion.frame)
        assertTrue("The new rest is on the turned screen", Rect(Offset.Zero, landscape.first).containsRect(rest))
        assertTrue("It settled on the new rest", last?.let { hypot(it.center.x - rest.center.x, it.center.y - rest.center.y) < 0.5f } == true)
    }

    private class Owner

    /** Starts a lift whose rest captures an owner nothing else holds; returns a weak handle on it. */
    private fun startHolding(motion: FloatingCallConnectMotion): WeakReference<Owner> {
        val owner = Owner()
        motion.start(from = Rect(Offset.Zero, Size(34f, 34f)), rest = { owner.hashCode(); Rect(Offset(100f, 100f), Size(64f, 64f)) }, at = 0.0, runs = false)
        return WeakReference(owner)
    }

    @Test fun stoppingTheLiftLetsGoOfItsRest() {
        val motion = FloatingCallConnectMotion()
        val released = startHolding(motion)
        collect()
        assertNotNull(released.get())
        motion.stop()
        var attempts = 0
        while (released.get() != null && attempts < 50) {
            collect()
            attempts += 1
        }
        assertNull("Its rest held what made it", released.get())
    }

    private fun collect() {
        System.gc()
        Thread.sleep(10)
    }
}
