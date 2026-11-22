package ai.opencode.feature.composer

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the composer glides by when the keyboard's predictive bar hides or returns without an
 * animation: only what the keyboard's cover of its window changed
 * (`DockedKeyboard.unanimatedStep`).
 */
class ComposerKeyboardStepTests {
    private fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(x, y, x + width, y + height)

    // iPhone 17 Pro Max: the bar hiding takes the keys from 347 to 320 pt.
    private val screenMaxY = 956f
    private val withBar = rect(0f, 609f, 440f, 347f)
    private val withoutBar = rect(0f, 636f, 440f, 320f)

    @Test fun aFullScreenWindowGlidesByTheBar() {
        val window = rect(0f, 0f, 440f, 956f)
        assertEquals(27f, DockedKeyboard.unanimatedStep(withBar, withoutBar, window, screenMaxY))
        assertEquals(-27f, DockedKeyboard.unanimatedStep(withoutBar, withBar, window, screenMaxY))
    }

    /** Stage Manager: a window above the keys keeps its safe area, so its composer holds still. */
    @Test fun aWindowTheKeyboardDoesntCoverDoesntMove() {
        val above = rect(40f, 60f, 360f, 500f)
        assertNull(DockedKeyboard.unanimatedStep(withBar, withoutBar, above, screenMaxY))
        assertNull(DockedKeyboard.unanimatedStep(withoutBar, withBar, above, screenMaxY))
        val beside = rect(500f, 0f, 300f, 956f)
        assertNull(DockedKeyboard.unanimatedStep(withBar, withoutBar, beside, screenMaxY))
    }

    /** A window whose bottom sits between the keys' two tops only gains what the keys covered of it. */
    @Test fun aPartlyCoveredWindowGlidesByItsShare() {
        val window = rect(40f, 60f, 360f, 560f)
        assertEquals(11f, DockedKeyboard.unanimatedStep(withBar, withoutBar, window, screenMaxY))
    }

    @Test fun anUndockedKeyboardDoesntMoveIt() {
        val window = rect(0f, 0f, 440f, 956f)
        val floating = withBar.translate(0f, -200f)
        val floatingShort = withoutBar.translate(0f, -200f)
        assertNull(DockedKeyboard.unanimatedStep(floating, floatingShort, window, screenMaxY))
    }
}
