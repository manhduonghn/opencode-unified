package ai.opencode.feature.settings

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TranscriptCoverPauseTests {
    @get:Rule val isolation = TestIsolationRule()

    /**
     * A back swipe uncovers Home the moment it moves; a cancelled one covers it again only once its
     * spring back lands fully open. Mirrors [SettingsPresentation]'s calls.
     */
    @Test
    fun settingsCoverFollowsThePresentationNotTheShownFlag() {
        val cover = SettingsCover()
        val reported = mutableListOf<Boolean>()
        cover.onChange = { reported += it }

        // The push: covered once it lands, not while it slides in.
        val push = cover.moving()
        assertFalse(cover.covers)
        cover.landedOpen(push)
        assertTrue(cover.covers)

        // A swipe starts moving: uncovered under the finger, Settings still shown.
        cover.moving()
        assertFalse(cover.covers)
        cover.moving() // every drag step

        // Released short of closing: springing back stays uncovered until it lands.
        val springBack = cover.moving()
        assertFalse(cover.covers)
        cover.landedOpen(springBack)
        assertTrue(cover.covers)
        assertEquals(listOf(true, false, true), reported)

        // A swipe caught mid-push: the push's landing no longer covers.
        val interrupted = cover.moving()
        cover.landedOpen(cover.moving())
        cover.moving()
        cover.landedOpen(interrupted)
        assertFalse(cover.covers)

        // Closing before a spring back lands: its landing doesn't cover.
        val late = cover.moving()
        cover.moving() // dismiss
        cover.landedOpen(late)
        assertFalse(cover.covers)
    }
}
