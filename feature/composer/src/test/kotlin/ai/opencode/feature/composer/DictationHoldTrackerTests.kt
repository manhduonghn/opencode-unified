package ai.opencode.feature.composer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private typealias Arm = DictationFeature.Hold.Arm

/**
 * Holding the mic: progress toward lock (up) and cancel (left) follows the finger wherever it goes;
 * the leading target arms at its line, with hysteresis on the way back and no axis latch. Distances
 * are dp.
 */
class DictationHoldTrackerTests {
    private fun track(points: List<Pair<Float, Float>>): DictationHoldTracker {
        val tracker = DictationHoldTracker()
        for ((x, y) in points) tracker.update(Offset(x, y))
        return tracker
    }

    /** Every armed state along a path, in order, without repeats. */
    private fun arms(points: List<Pair<Float, Float>>): List<Arm?> {
        val tracker = DictationHoldTracker()
        val seen = mutableListOf<Arm?>(null)
        for ((x, y) in points) {
            tracker.update(Offset(x, y))
            if (seen.last() != tracker.armed) seen += tracker.armed
        }
        return seen
    }

    private fun p(vararg points: Pair<Number, Number>) = points.map { it.first.toFloat() to it.second.toFloat() }

    @Test fun slidingUpArmsLockAt56Points() {
        assertNull(track(p(0 to -20, 0 to -55)).armed)
        assertEquals(Arm.Lock, track(p(0 to -20, 0 to -56)).armed)
    }

    @Test fun slidingLeftArmsCancelAt96Points() {
        assertNull(track(p(-20 to 0, -95 to 0)).armed)
        assertEquals(Arm.Cancel, track(p(-20 to 0, -96 to 0)).armed)
    }

    @Test fun eachDisarmsNinePointsShortOfItsLine() {
        assertEquals(Arm.Lock, track(p(0 to -60, 0 to -48)).armed)
        assertNull(track(p(0 to -60, 0 to -46)).armed)
        assertEquals(Arm.Cancel, track(p(-100 to 0, -88 to 0)).armed)
        assertNull(track(p(-100 to 0, -86 to 0)).armed)
    }

    /** One drag: past cancel, then up past lock, without returning to the start. */
    @Test fun oneDragSwingsFromCancelToLock() {
        val path = p(-40 to 0, -100 to 0, -100 to -20, -90 to -50, -70 to -80, -40 to -100)
        assertEquals("Cancel hands straight over to lock", listOf(null, Arm.Cancel, Arm.Lock), arms(path))
        assertEquals(Arm.Lock, track(path).armed)
    }

    @Test fun andBackFromLockToCancel() {
        val path = p(0 to -70, -40 to -70, -90 to -40, -110 to -10)
        assertEquals(Arm.Cancel, arms(path).last())
        assertTrue(arms(path).contains(Arm.Lock))
    }

    /** Reversing toward the start disarms; coming back out re-arms. */
    @Test fun reversingDisarmsAndReArms() {
        assertEquals(listOf(null, Arm.Lock, null, Arm.Lock), arms(p(0 to -60, 0 to -20, 0 to -60)))
        assertEquals(listOf(null, Arm.Cancel, null, Arm.Cancel), arms(p(-100 to 0, -40 to 0, -100 to 0)))
    }

    /** The leading target wins a diagonal; none arms until one reaches its line. */
    @Test fun theLeadingTargetWinsADiagonal() {
        assertEquals(Arm.Lock, track(p(-60 to -60)).armed)
        assertEquals(Arm.Cancel, track(p(-120 to -40)).armed)
        assertNull(track(p(-40 to -40)).armed)
    }

    /** A finger trembling on a line or on the boundary between the two arms once and stays put. */
    @Test fun jitterAtABoundaryNeverFlickers() {
        val onTheLockLine = (0 until 20).map { 0f to (if (it % 2 == 0) -56f else -52f) }
        assertEquals(listOf(null, Arm.Lock), arms(onTheLockLine))
        // Both past their lines, near equal progress (lock 1.43, cancel 1.40–1.50).
        val betweenThem = (0 until 20).map { (if (it % 2 == 0) -134f else -144f) to -80f }
        assertEquals(2, arms(betweenThem).size)
    }

    /** Feedback blends between the targets near 45° instead of switching. */
    @Test fun feedbackBlendsAroundTheDiagonal() {
        assertEquals(1f, DictationHoldTracker.weights(Offset(0f, -50f)).lock)
        assertEquals(1f, DictationHoldTracker.weights(Offset(-50f, 0f)).cancel)
        val diagonal = DictationHoldTracker.weights(Offset(-50f, -50f))
        assertTrue(abs(diagonal.lock - 0.5f) < 0.01f)
        val nearUp = DictationHoldTracker.weights(Offset(-30f, -50f)).lock
        assertTrue(nearUp > 0.5f && nearUp < 1f)
    }

    @Test fun onlyShortStillPressesAreTaps() {
        assertTrue(track(p(2 to 3)).isTap(afterSeconds = 0.2))
        assertFalse(track(p(2 to 3)).isTap(afterSeconds = 0.31))
        assertFalse(track(p(12 to 0, 2 to 0)).isTap(afterSeconds = 0.2))
        assertFalse(track(p(0 to -20, 0 to -60)).isTap(afterSeconds = 0.2))
    }

    /**
     * The system taking the touch (Control Center, a call banner, another
     * finger, a scroll view, the app leaving) is not a lift, whenever it comes.
     */
    @Test fun aCancelledTouchIsNeverATapOrARelease() {
        val still = track(p(2 to 3))
        assertEquals(DictationHoldTracker.Ending.Interrupted, still.ending(afterSeconds = 0.1, cancelled = true))
        assertEquals(DictationHoldTracker.Ending.Interrupted, still.ending(afterSeconds = 1.2, cancelled = true))
        assertEquals(
            DictationHoldTracker.Ending.Interrupted,
            track(p(0 to -20, 0 to -60)).ending(afterSeconds = 1.2, cancelled = true),
        )
        assertEquals(DictationHoldTracker.Ending.Tap, still.ending(afterSeconds = 0.1, cancelled = false))
        assertEquals(DictationHoldTracker.Ending.Release, still.ending(afterSeconds = 1.2, cancelled = false))
    }
}

/**
 * The mic's press reports each ending once, including when the mic's view goes away mid-press. The
 * composable's `DisposableEffect` calls [MicPress.dispose].
 */
class MicPressTests {
    private class Events {
        val log = mutableListOf<String>()
    }

    private fun press(events: Events) = MicPress(
        MicPressCallbacks(
            onBegan = { events.log += "began" },
            onDrag = {},
            onArmed = {},
            onReleased = { events.log += "released" },
            onLocked = { events.log += "locked" },
            onInterrupted = { events.log += "interrupted" },
        ),
    )

    @Test fun aPressTornDownMidHoldEndsAsAnInterruption() {
        val events = Events()
        val held = press(events)
        held.began(uptimeMillis = 0)
        held.dispose()
        assertEquals(listOf("began", "interrupted"), events.log)
    }

    @Test fun aFinishedPressTornDownReportsNothingMore() {
        val events = Events()
        val tapped = press(events)
        tapped.began(uptimeMillis = 0)
        tapped.ended(afterSeconds = 0.05, cancelled = false)
        tapped.dispose()
        assertEquals(listOf("began", "locked"), events.log)
    }

    @Test fun aHeldReleaseAndASystemCancelEndOnce() {
        val events = Events()
        val held = press(events)
        held.began(uptimeMillis = 0)
        held.ended(afterSeconds = 1.0, cancelled = false)
        held.ended(afterSeconds = 1.0, cancelled = true)
        held.began(uptimeMillis = 0)
        held.ended(afterSeconds = 1.0, cancelled = true)
        assertEquals(listOf("began", "released", "began", "interrupted"), events.log)
    }
}
