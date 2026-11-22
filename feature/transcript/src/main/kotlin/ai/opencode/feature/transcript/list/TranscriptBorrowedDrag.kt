package ai.opencode.feature.transcript.list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlin.math.abs

/**
 * A drag borrowed from a control floating over the transcript: a vertical drag that starts on Jump
 * to latest, the queued pill or the background-work capsule scrolls the list as one starting beside
 * it does. A tap stays the control's.
 *
 * The list scrolls through its own `LazyListState` and the release flings with the list's own
 * fling. Every pointer gesture ends with an up or a cancel, so there's no watchdog.
 */
class TranscriptBorrowedDrag {
    sealed interface Event {
        /** The finger moved [dy] px since the last report (down is positive). */
        data class Moved(val dy: Float) : Event

        /** The finger left at [velocity] px/s (down is positive); 0 when the gesture was cancelled. */
        data class Ended(val velocity: Float) : Event
    }

    /** Set by the transcript list while it's on screen. */
    var onEvent: ((Event) -> Unit)? = null

    /** A borrowed drag began (the session dismisses the keyboard, as a drag on the list does). */
    var onBegan: (() -> Unit)? = null

    private var isDragging = false

    fun send(event: Event) {
        when (event) {
            is Event.Moved -> if (!isDragging) {
                isDragging = true
                onBegan?.invoke()
            }
            is Event.Ended -> isDragging = false
        }
        onEvent?.invoke(event)
    }
}

/**
 * Whether a drag on a control is the transcript's: decided as it first reports, so a sideways drag
 * stays the control's. Keyed on where the drag began, so a drag that never ended can't decide the
 * next one.
 */
class BorrowedDragVerdict {
    private var start: Offset? = null
    private var vertical = false

    fun isVertical(start: Offset, translation: Offset): Boolean {
        if (this.start == start) return vertical
        this.start = start
        vertical = abs(translation.y) > abs(translation.x)
        return vertical
    }

    fun end(start: Offset, translation: Offset): Boolean {
        val result = isVertical(start, translation)
        this.start = null
        return result
    }
}

/**
 * A vertical drag starting on this control scrolls the transcript through [drag]. [isEnabled] is
 * read as a drag starts, so a control that hides under the finger (Jump to latest reaching the end)
 * keeps its drag to release.
 */
fun Modifier.passesDragsToTranscript(drag: TranscriptBorrowedDrag?, isEnabled: Boolean = true): Modifier {
    if (drag == null) return this
    return this then PassesDragsElement(drag, isEnabled)
}

private data class PassesDragsElement(val drag: TranscriptBorrowedDrag, val isEnabled: Boolean) : ModifierNodeElement<PassesDragsNode>() {
    override fun create(): PassesDragsNode = PassesDragsNode(drag, isEnabled)

    override fun update(node: PassesDragsNode) {
        node.isEnabled = isEnabled
        node.drag = drag
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "passesDragsToTranscript"
        properties["isEnabled"] = isEnabled
    }
}

private class PassesDragsNode(var drag: TranscriptBorrowedDrag, var isEnabled: Boolean) : DelegatingNode() {
    private val verdict = BorrowedDragVerdict()

    init {
        delegate(SuspendingPointerInputModifierNode { detectBorrowedDrags() })
    }

    private suspend fun PointerInputScope.detectBorrowedDrags() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!isEnabled) return@awaitEachGesture
            val start = down.position
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)

            // Until the touch slop: a tap or a sideways drag stays the control's.
            var position = start
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                tracker.addPointerInputChange(change)
                position = change.position
                val translation = position - start
                if (translation.getDistance() < viewConfiguration.touchSlop) continue
                if (!verdict.isVertical(start, translation)) {
                    verdict.end(start, translation)
                    return@awaitEachGesture
                }
                change.consume()
                break
            }

            // The drag is the transcript's from here, the move past the slop included.
            val sending = drag
            sending.send(TranscriptBorrowedDrag.Event.Moved(position.y - start.y))
            var ended = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        if (change != null) tracker.addPointerInputChange(change)
                        val velocity = tracker.calculateVelocity().y
                        verdict.end(start, (change?.position ?: position) - start)
                        ended = true
                        sending.send(TranscriptBorrowedDrag.Event.Ended(velocity))
                        break
                    }
                    tracker.addPointerInputChange(change)
                    val dy = change.positionChange().y
                    position = change.position
                    change.consume()
                    if (dy != 0f) sending.send(TranscriptBorrowedDrag.Event.Moved(dy))
                }
            } finally {
                // Cancelled (the control left, or the system took the touch): the list settles where it is.
                if (!ended) {
                    verdict.end(start, position - start)
                    sending.send(TranscriptBorrowedDrag.Event.Ended(0f))
                }
            }
        }
    }
}
