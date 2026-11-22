package ai.opencode.feature.composer

import androidx.compose.ui.geometry.Offset

/** The composer field's side of a swipe on the composer around it: a selecting drag belongs to the text. */
interface ComposerSwipeInput {
    val isSwipeYieldedToText: Boolean

    /** [rootPoint] is in root px. */
    fun selectionClaims(rootPoint: Offset): Boolean

    /** Whether a control's tap is the second half of a double tap on the text, which then selects a word. */
    fun takesDoubleTap(): Boolean = false

    /** Before a send reads the draft: reports text the keyboard hasn't committed yet. */
    fun flushEdits() {}

    /** After a send cleared the draft, so the keyboard's next commit lands in the empty field. */
    fun takeParentText() {}
}

class ComposerFieldLink {
    val windowTouches = ComposerWindowTouchObserver()

    var input: ComposerSwipeInput? = null
}
