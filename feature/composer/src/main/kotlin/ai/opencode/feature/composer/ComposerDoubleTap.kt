package ai.opencode.feature.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where (in the field's text layout, px) a quick finger tap on the composer's text began, when it
 * began and ended (uptime millis), and the text it landed on.
 */
data class ComposerTextTap(
    val point: Offset,
    val began: Long,
    val ended: Long,
    val text: String,
)

/**
 * A finger's second tap lands a little below its first, onto the row gap or the model chip's target
 * under the last line. A tap there (the chip's, or the composer's own) that is that double tap's
 * second half selects the word under the first tap, and the tap does nothing else. It counts only
 * when both taps are fingers, the first was a quick tap on the last visible line, and the second
 * began within 0.4 s of it, about 30 dp across and just below the text. Mouse and stylus clicks and
 * TalkBack activation never count. Distances in px; times in uptime millis.
 */
object ComposerDoubleTap {
    /** A first tap must end within this of its touch-down. */
    const val TAP_DURATION_MS = 350L

    /** A first tap that moves further than this (dp) isn't a tap. */
    const val TAP_SLOP_DP = 10f

    /** An inline control's (autocomplete chip's) frame, grown this much (dp), takes the touch. */
    const val CONTROL_SLOP_DP = 4f

    /** The second touch-down must come within this of the first tap's end. */
    const val SECOND_TAP_WINDOW_MS = 400L

    /** The chip's action must run within this of the second touch-down. */
    const val ACTION_WINDOW_MS = 600L

    /** How far across (dp) the second tap may land from the first. */
    const val ACROSS_DP = 30f

    /** How far below the field's bottom (dp) the second tap may land. */
    const val BELOW_DP = 34f

    /**
     * Whether a caret rect is on the last line of text in view (the line a drifting second tap
     * slips off, onto the controls row below).
     */
    fun isOnLastVisibleLine(caret: Rect, lastLineCaret: Rect, lineHeight: Float, visibleBottom: Float): Boolean =
        caret.bottom + lineHeight > visibleBottom + 1 || abs(lastLineCaret.center.y - caret.center.y) < lineHeight / 2

    /**
     * Whether the recorded tap still stands after the selection changed. It stands only while the
     * selection is the caret that tap placed: within the tapped word ([tappedOffset], [word]) or
     * across the space beside it.
     */
    fun keepsTap(tap: ComposerTextTap, text: String, selection: TextRange, tappedOffset: Int, word: TextRange?): Boolean {
        if (tap.text != text || !selection.collapsed) return false
        var lower = tappedOffset
        var upper = tappedOffset
        if (word != null) {
            lower = min(lower, word.min)
            upper = max(upper, word.max)
        }
        val at = selection.start
        return at >= lower - 1 && at <= upper + 1
    }

    /**
     * Whether [second] (its point in the field's viewport, px) is the second half of a double tap
     * begun by [tap] (its point in the same viewport), with the field [fieldHeight] px tall and the
     * chip's action running at [now].
     */
    fun isSecondHalf(
        tap: ComposerTextTap,
        tapViewportX: Float,
        text: String,
        second: ComposerWindowTouch,
        secondViewportPoint: Offset,
        fieldHeight: Float,
        now: Long,
        density: Float,
    ): Boolean {
        if (tap.text != text || !second.isFinger) return false
        if (second.time <= tap.began) return false
        if (second.time - tap.ended >= SECOND_TAP_WINDOW_MS) return false
        if (now - second.time >= ACTION_WINDOW_MS) return false
        if (abs(secondViewportPoint.x - tapViewportX) > ACROSS_DP * density) return false
        return secondViewportPoint.y > fieldHeight && secondViewportPoint.y <= fieldHeight + BELOW_DP * density
    }

    /** The word around [offset], or null where there's none. */
    fun wordAt(layout: TextLayoutResult, offset: Int): TextRange? {
        val length = layout.layoutInput.text.length
        if (length == 0) return null
        val word = layout.getWordBoundary(offset.coerceIn(0, length))
        return if (word.collapsed) null else word
    }
}

/** Where (root px) and when a touch began, and whether a finger made it. */
data class ComposerWindowTouch(val rootPoint: Offset, val time: Long, val isFinger: Boolean)

/**
 * Notes where and when the screen's latest touch began, and what kind it was, so the chip's action
 * can tell a finger's tap from a mouse click or a TalkBack activation
 * ([ComposerSwipeInput.takesDoubleTap]). Fed by [composerWindowTouches] on the screen's root; it
 * never consumes a touch.
 */
class ComposerWindowTouchObserver {
    var latest: ComposerWindowTouch? = null
        private set

    /** The node [composerWindowTouches] is on, to put its touches in root coordinates. */
    internal var coordinates: LayoutCoordinates? = null

    fun noteDown(rootPoint: Offset, time: Long, isFinger: Boolean) {
        latest = ComposerWindowTouch(rootPoint, time, isFinger)
    }
}

/** Feeds [observer] every touch-down under this node, before anything else sees it. */
fun Modifier.composerWindowTouches(observer: ComposerWindowTouchObserver): Modifier =
    onPlaced { observer.coordinates = it }
        .pointerInput(observer) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    for (change in event.changes) {
                        if (!change.pressed || change.previousPressed) continue
                        val coordinates = observer.coordinates?.takeIf { it.isAttached } ?: continue
                        observer.noteDown(coordinates.localToRoot(change.position), change.uptimeMillis, change.type == PointerType.Touch)
                    }
                }
            }
        }
