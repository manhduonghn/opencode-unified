package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A snapshot counts as on screen only once a transcript on screen reports a configuration of that
 * revision rendered, at the next frame, however many frames that takes.
 */
class PerfFreshTrackerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val session = "ses_a"
    private val transcript = 1

    private fun now(): Long = System.nanoTime()

    /** A transcript on screen with rows of `session` laid out from [revision]. */
    private fun showing(revision: Int? = null): PerfFreshTracker {
        val tracker = PerfFreshTracker()
        tracker.appeared(token = transcript)
        tracker.rowsShown(session, token = transcript)
        tracker.rendered(session, revision = revision, token = transcript)
        return tracker
    }

    @Test
    fun aChangedSnapshotWaitsForItsDelayedUpdate() {
        val tracker = showing()
        val revision = tracker.applied(session, now())
        assertTrue(tracker.isWaiting)
        // The transcript's update is held back well past a few frames: the cached rows it shows don't count.
        repeat(10) { assertTrue(tracker.nextFrame().isEmpty()) }
        tracker.rendered(session, revision = revision, token = transcript)
        assertEquals(listOf(session), tracker.nextFrame().map { it.first })
        assertFalse(tracker.isWaiting)
    }

    @Test
    fun anUpdateCarryingAnOlderRevisionDoesNotCount() {
        val tracker = showing()
        val first = tracker.applied(session, now())
        tracker.rendered(session, revision = first, token = transcript)
        val second = tracker.applied(session, now())
        tracker.rendered(session, revision = first, token = transcript)
        assertTrue(tracker.nextFrame().isEmpty())
        tracker.rendered(session, revision = null, token = transcript)
        assertTrue(tracker.nextFrame().isEmpty())
        tracker.rendered(session, revision = second, token = transcript)
        assertEquals(listOf(session), tracker.nextFrame().map { it.first })
    }

    @Test
    fun aTranscriptOffScreenOrWithoutRowsDoesNotCount() {
        val tracker = showing()
        val revision = tracker.applied(session, now())
        tracker.rendered(session, revision = revision, token = transcript)
        tracker.disappeared(token = transcript)
        assertFalse(tracker.isWaiting)
        assertTrue(tracker.nextFrame().isEmpty())
        tracker.appeared(token = transcript)
        tracker.rowsHidden(token = transcript)
        assertTrue(tracker.nextFrame().isEmpty())
        tracker.rowsShown(session, token = transcript)
        assertEquals(listOf(session), tracker.nextFrame().map { it.first })
    }

    @Test
    fun theLastTranscriptToAppearIsInFront() {
        val tracker = showing()
        tracker.appeared(token = 2)
        tracker.rowsShown("ses_child", token = 2)
        assertEquals("ses_child", tracker.activeSessionID)
        tracker.disappeared(token = 2)
        assertEquals(session, tracker.activeSessionID)
        tracker.released(token = transcript)
        assertNull(tracker.activeSessionID)
    }
}
