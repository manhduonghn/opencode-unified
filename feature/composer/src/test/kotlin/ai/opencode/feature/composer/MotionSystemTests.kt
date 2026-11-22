package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSystemTests {
    @Test fun composerKeyboardPoseExpandsWithTheKeyboardOrWhatHoldsItOpen() {
        val resting = ComposerKeyboardPose()
        assertEquals(0f, resting.expansion(0f))
        assertEquals(0.4f, resting.expansion(0.4f))
        // A draft holding the row open while the keys leave keeps it open.
        assertEquals(1f, ComposerKeyboardPose(pinned = 1f).expansion(0.3f))
        assertEquals(0.6f, ComposerKeyboardPose(pinned = 0.6f).expansion(0.2f))
        // Reduce Motion switches with the layout, never part-way with the keys.
        val reduced = ComposerKeyboardPose(expanded = true, layoutExpanded = false, pinned = 1f, reducedMotion = true)
        assertEquals(0f, reduced.expansion(0.5f))
        assertEquals(1f, ComposerKeyboardPose(layoutExpanded = true, reducedMotion = true).expansion(0f))
    }
}
