package ai.opencode.feature.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A drag the text field is selecting with is never the composer's swipe. */
class ComposerTextSelectionSwipeTests {
    @Test
    fun aSelectingDragBelongsToTheText() {
        assertFalse("A quick swipe is the composer's", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.05, holdBegan = false, textGestureBegan = false))
        assertTrue("On the selection or a handle", ComposerSwipeDown.textOwnsDrag(startsOnSelection = true, heldFor = 0.05, holdBegan = false, textGestureBegan = false))
        assertTrue("Rested first: the loupe", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.6, holdBegan = false, textGestureBegan = false))
        assertTrue("A long press began", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.1, holdBegan = true, textGestureBegan = false))
        assertTrue("A handle or a double-tap drag took it", ComposerSwipeDown.textOwnsDrag(startsOnSelection = false, heldFor = 0.05, holdBegan = false, textGestureBegan = true))
    }

    @Test
    fun theSelectionClaimsTouchesOnAndNearIt() {
        val rects = listOf(Rect(40f, 22f, 240f, 44f), Rect(0f, 44f, 80f, 66f))
        assertTrue("On it", ComposerSwipeDown.selectionClaims(Offset(100f, 30f), selectionRects = rects))
        assertTrue("By its end handle", ComposerSwipeDown.selectionClaims(Offset(250f, 30f), selectionRects = rects))
        assertTrue("Over its start handle's knob", ComposerSwipeDown.selectionClaims(Offset(40f, 5f), selectionRects = rects))
        assertFalse("Well away from it", ComposerSwipeDown.selectionClaims(Offset(300f, 100f), selectionRects = rects))
        assertFalse("No selection", ComposerSwipeDown.selectionClaims(Offset(100f, 30f), selectionRects = emptyList()))
        val inf = Float.POSITIVE_INFINITY
        assertFalse(
            "Degenerate rects claim nothing",
            ComposerSwipeDown.selectionClaims(Offset.Zero, selectionRects = listOf(Rect(inf, inf, inf, inf), Rect(-inf, -inf, inf, inf))),
        )
    }

    @Test
    fun onlyAFocusedNonEmptySelectionClaims() {
        // "Select a word in this draft": "word" (7..<11) laid out at its rect.
        val rect = Rect(70f, 4f, 112f, 26f)
        val word = TextRange(7, 11)
        val point = rect.center
        assertFalse("Unfocused: the selection isn't shown", ComposerSwipeDown.selectionClaims(point, isFocused = false, selection = word, selectionRects = { listOf(rect) }))
        assertTrue(ComposerSwipeDown.selectionClaims(point, isFocused = true, selection = word, selectionRects = { listOf(rect) }))
        assertFalse("A caret isn't a selection", ComposerSwipeDown.selectionClaims(point, isFocused = true, selection = TextRange(7), selectionRects = { listOf(rect) }))
    }

    /**
     * Once the text field claims a drag, the composer's swipe stays out of it to its last
     * callback, even after the field's flag resets.
     */
    @Test
    fun aClaimedDragStaysTheTexts() {
        val ownership = ComposerSwipeOwnership()
        val start = Offset(40f, 10f)
        assertFalse(ownership.yields(startLocation = start) { false })
        assertTrue("A handle began", ownership.yields(startLocation = start, textClaims = true) { false })
        assertTrue("Still the text's", ownership.yields(startLocation = start, textClaims = false) { false })
        ownership.ended()
        assertFalse("The next drag decides afresh", ownership.yields(startLocation = start) { false })
    }
}
