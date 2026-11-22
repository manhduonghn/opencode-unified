package ai.opencode.feature.transcript.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shownOnlyInAWindowWithNoHiddenOrTransparentAncestor` (`ViewVisibilityObserver`, KVO on ancestor
 * layers) has no Compose counterpart.
 */
class TranscriptCoverPauseTests {
    @Test
    fun pausesWheneverTheReaderCantSeeTheList() {
        assertFalse(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = false, covered = false, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = true, sceneInBackground = false, covered = false, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = true, covered = false, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = false, covered = true, drawn = true))
        assertTrue(TranscriptTimelineGate.pauses(listHidden = false, sceneInBackground = false, covered = false, drawn = false))
    }
}
