package ai.opencode.feature.composer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Its `ComposerSurface.usesInteractiveGlass` asserts are dropped: Liquid Glass interactivity has no
 * Android counterpart (the composer is an M3 surface).
 */
class SessionHeaderStyleTests {
    @Test
    fun composerGlassIsInteractiveAndSwipeDownDismissesKeyboard() {
        // A downward swipe >= 18 pt (or a quick downward flick) that is predominantly vertical dismisses the focused composer.
        assertTrue(ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, 22f), isFocused = true))
        assertTrue(ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(10f, 28f), isFocused = true))
        assertTrue(ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, 12f), predictedEndTranslation = Offset(0f, 80f), isFocused = true))
        assertFalse("Too short", ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, 14f), isFocused = true))
        assertFalse("Upward drag", ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, -30f), isFocused = true))
        assertFalse("Too horizontal", ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(25f, 26f), isFocused = true))
        assertFalse("Keyboard already down", ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, 30f), isFocused = false))
        assertFalse("Mic hold owns the touch", ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, 30f), isFocused = true, isHoldingMic = true))
        assertFalse("Multi-line scroll owns the drag", ComposerSwipeDown.shouldDismissKeyboard(translation = Offset(0f, 30f), isFocused = true, isScrollingUp = true))

        // An upward swipe >= 18 pt (or a quick upward flick) on the unfocused composer focuses it.
        assertTrue(ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -22f), isFocused = false))
        assertTrue(ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(8f, -26f), isFocused = false))
        assertTrue(ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -12f), velocityY = -320f, isFocused = false))
        assertTrue(ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -12f), predictedEndTranslation = Offset(0f, -80f), isFocused = false))
        assertFalse("Too short and slow", ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -14f), velocityY = -100f, isFocused = false))
        assertFalse("Downward drag", ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, 24f), isFocused = false))
        assertFalse("Too horizontal", ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(24f, -25f), isFocused = false))
        assertFalse("Keyboard already up", ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -28f), isFocused = true))
        assertFalse("Control or hold owns the touch", ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -28f), isFocused = false, isHoldingMic = true))
        assertFalse("Multi-line scroll owns the drag", ComposerSwipeDown.shouldFocusKeyboard(translation = Offset(0f, -28f), isFocused = false, isScrollingDown = true))
    }
}
