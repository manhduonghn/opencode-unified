package ai.opencode.feature.drawer.list

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates

/*
 * A drawer row's touches. A tap opens the session; a finger resting on the row for
 * [DRAWER_ROW_PRESS_DELAY_MS], or a tap, tells Home first (`pressed`, so its transcript warms), but
 * never a finger that scrolls the list or lands on a moving one; a hold chooses the row for
 * selection mode; moving the held finger lifts the row to pin, unpin or reorder it ([DrawerPinDrag]).
 *
 * One pointer handler:
 * - a tap lands on touch-up and only if the drawer's pan never chose the touch (the pan consumes
 *   it in the `Initial` pass);
 * - the hold (the platform long-press timeout) plays `Haptic.tap` and chooses the row ([held]);
 * - moving the held finger past the touch slop starts the drag, which the finger then owns: the
 *   list never scrolls under it; the row shows lifted on a raised surface.
 */

/** One of selection mode's actions. */
data class DrawerRowMenuItem(
    val title: String,
    val symbol: SFSymbol,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/** What a row's touches do, as closures: the row never holds a store. */
class DrawerRowInteraction(
    val sessionID: String,
    /** Session rows lift by a long-press drag; other rows are only chosen. */
    val reorderable: Boolean,
    /** Whether a hold chooses the row. */
    val holdable: Boolean = true,
    val isPinnedSection: Boolean = false,
    val select: () -> Unit,
    /**
     * A finger rested on the row ([DRAWER_ROW_PRESS_DELAY_MS]) or tapped it, ahead of the tap's
     * selection; never a finger that scrolls the list.
     */
    val pressed: () -> Unit,
    /**
     * Android: the hold chooses the row for selection mode ([DrawerSelection]) in place of the
     * menu; a drag growing out of the hold takes it back.
     */
    val held: () -> Unit,
    /** The list is scrolling or flinging. */
    val isListMoving: () -> Boolean = { false },
    /** The held finger moved: the drag begins at [fingerInList] (dp, the list's coordinates). */
    val beginDrag: ((fingerInList: Offset) -> Unit)? = null,
    /** The finger moved while dragging (dp, the list's coordinates). */
    val moveDrag: (fingerInList: Offset, uptimeMillis: Long) -> Unit = { _, _ -> },
    /** The finger lifted ([cancelled] when the stream was taken away). */
    val endDrag: (fingerInList: Offset, cancelled: Boolean) -> Unit = { _, _ -> },
    val lifted: (String?) -> Unit = {},
)

/**
 * How long a finger rests on a row before it's a press (the session's transcript starts loading),
 * unless the list starts scrolling first.
 */
const val DRAWER_ROW_PRESS_DELAY_MS = 80L

/** How a finger's first moments on a row ended. */
private enum class DrawerRowTouch { Tapped, Taken, Rested }

/** A row's hold: a drag under way. */
@Stable
class DrawerRowHoldState {
    var isDragActive: Boolean by mutableStateOf(false)
        internal set
}

/**
 * The row's touches. [list] and [row] convert the finger into the list's coordinates for the drag;
 * [density] is read from the pointer scope.
 */
fun Modifier.drawerRowInteraction(
    interaction: DrawerRowInteraction,
    hold: DrawerRowHoldState,
    list: () -> LayoutCoordinates?,
    row: () -> LayoutCoordinates?,
): Modifier = composed {
    val current by rememberUpdatedState(interaction)
    val haptics = androidx.compose.runtime.remember { dependency(HapticsClient) }
    pointerInput(hold) {
        fun toList(position: Offset): Offset {
            val listCoordinates = list()
            val rowCoordinates = row()
            val inList = if (listCoordinates != null && rowCoordinates != null && listCoordinates.isAttached && rowCoordinates.isAttached) {
                listCoordinates.localPositionOf(rowCoordinates, position)
            } else {
                position
            }
            return Offset(inList.x / density, inList.y / density)
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointer = down.id
            val slop = viewConfiguration.touchSlop

            // Waits up to [timeoutMillis] for the finger to lift (a tap), or for a drag someone
            // else (the list, the drawer) takes; otherwise the finger rested.
            suspend fun AwaitPointerEventScope.awaitTouch(timeoutMillis: Long): DrawerRowTouch {
                var outcome = DrawerRowTouch.Rested
                withTimeoutOrNull(timeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer }
                        if (change == null || change.isConsumed) {
                            outcome = DrawerRowTouch.Taken
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            outcome = DrawerRowTouch.Tapped
                            break
                        }
                        if ((change.position - down.position).getDistance() > slop) {
                            outcome = DrawerRowTouch.Taken
                            break
                        }
                    }
                }
                return outcome
            }

            // A finger landing on a moving list stops it, and isn't choosing a row: no press.
            val landedOnMovingList = current.isListMoving()
            var touch = awaitTouch(DRAWER_ROW_PRESS_DELAY_MS)
            val pressDue = touch == DrawerRowTouch.Tapped || touch == DrawerRowTouch.Rested
            if (pressDue && !landedOnMovingList && !current.isListMoving()) current.pressed()

            // Before the hold: a tap, or a drag someone else takes.
            if (touch == DrawerRowTouch.Rested) {
                touch = awaitTouch(viewConfiguration.longPressTimeoutMillis - DRAWER_ROW_PRESS_DELAY_MS)
            }
            if (touch == DrawerRowTouch.Tapped) {
                current.select()
                return@awaitEachGesture
            }
            if (touch == DrawerRowTouch.Taken || !current.holdable) return@awaitEachGesture

            // The hold: the row chosen.
            haptics.play(Haptic.tap)
            val held = current.held
            held()
            var start: Offset? = null
            var dragging = false
            var last: PointerInputChange? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointer }
                if (change == null) {
                    if (dragging) {
                        hold.isDragActive = false
                        current.endDrag(last?.let { toList(it.position) } ?: Offset.Zero, true)
                    }
                    return@awaitEachGesture
                }
                last = change
                if (dragging) {
                    change.consume()
                    if (!change.pressed) {
                        hold.isDragActive = false
                        current.endDrag(toList(change.position), false)
                        return@awaitEachGesture
                    }
                    current.moveDrag(toList(change.position), change.uptimeMillis)
                    continue
                }
                // The hold owns the finger now: neither the list nor the drawer takes it.
                change.consume()
                if (!change.pressed) return@awaitEachGesture
                val origin = start ?: change.position.also { start = it }
                val begin = current.beginDrag
                if (current.reorderable && begin != null && (change.position - origin).getDistance() > slop) {
                    held()
                    hold.isDragActive = true
                    dragging = true
                    current.lifted(current.sessionID)
                    begin(toList(change.position))
                }
            }
        }
    }
}
