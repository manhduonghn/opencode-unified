package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long a press on the composer's call controls may, or must, last. A thumb or palm resting on
 * the bottom corner while the phone is held stays down far longer than a tap; its release must do
 * nothing.
 */
object ComposerCallPress {
    /** A press released within this is a tap. Past it the release does nothing, however far the finger drifted. */
    val tapWindow: Duration = 350.milliseconds

    /**
     * Stop held this long starts a call. Half a second was short enough for a resting thumb
     * mid-run.
     */
    val stopHold: Duration = 1.seconds

    /**
     * Whether a press that began at [began] and was released at [released] (uptime millis) was
     * a tap. A release with no touch-down seen (assistive activation) counts as one.
     */
    fun isTap(began: Long?, released: Long): Boolean {
        if (began == null) return true
        return released - began < tapWindow.inWholeMilliseconds
    }
}

/**
 * The empty composer's call button. Only a tap starts the call: a press held past
 * [ComposerCallPress.tapWindow] (a resting thumb, drifting or not) or slid off the button does
 * nothing on release. TalkBack and Switch Access always start it. [modifier] applies inside the tap
 * target, to the circle itself (where a floating call breaks out of).
 */
@Composable
fun ComposerCallButton(
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
) {
    val currentOnCall by rememberUpdatedState(onCall)
    val interactions = remember { MutableInteractionSource() }
    Box(
        Modifier
            .indication(interactions, ripple(bounded = false, radius = 22.dp))
            .pointerInput(Unit) {
                timedPresses(interactions, hold = null, onHold = {}, onTap = { currentOnCall() })
            }
            .semantics {
                role = Role.Button
                onClick { currentOnCall(); true }
            }
            .openCodeTapTarget(accessibility = false)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) { label() }
}

/**
 * The run's Stop. A tap stops the run; held [ComposerCallPress.stopHold] it starts a call instead
 * ([onHold]), with a threshold haptic as it does. A release in between, or after the hold, does
 * neither. TalkBack's activation always stops; the call is its own action.
 */
@Composable
fun ComposerStopButton(
    holdStartsCall: Boolean,
    onStop: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
) {
    val currentOnStop by rememberUpdatedState(onStop)
    val currentOnHold by rememberUpdatedState(onHold)
    val interactions = remember { MutableInteractionSource() }
    var holds by remember { mutableIntStateOf(0) }
    HapticEffect(Haptic.threshold, holds)
    Box(
        modifier
            .indication(interactions, ripple(bounded = false, radius = 22.dp))
            .pointerInput(holdStartsCall) {
                val hold = if (holdStartsCall) ComposerCallPress.stopHold else null
                timedPresses(
                    interactions,
                    hold = hold,
                    onHold = {
                        holds += 1
                        currentOnHold()
                    },
                    onTap = { currentOnStop() },
                )
            }
            // Assistive activation always stops; the call is its own action.
            .semantics {
                role = Role.Button
                onClick { currentOnStop(); true }
            }
            .openCodeTapTarget(accessibility = false),
        contentAlignment = Alignment.Center,
    ) { label() }
}

/**
 * Times each press from touch-down. Movement never resets the timing, so a thumb that drifts while
 * resting is still timed from when it landed. A release on the control within
 * [ComposerCallPress.tapWindow] runs [onTap]; one later, slid off, or taken by a scroll does
 * nothing. With a [hold], a press still down that long runs [onHold], and its release does nothing.
 */
private suspend fun PointerInputScope.timedPresses(
    interactions: MutableInteractionSource,
    hold: Duration?,
    onHold: () -> Unit,
    onTap: () -> Unit,
) {
    coroutineScope {
        val emitScope = this
        awaitEachGesture {
            val down = awaitFirstDown()
            val began = down.uptimeMillis
            val press = PressInteraction.Press(down.position)
            emitScope.emit(interactions, press)
            var held = false
            var release: PointerInputChange? = null
            var latest = began
            while (true) {
                val holdLeft = if (hold != null && !held) hold.inWholeMilliseconds - (latest - began) else null
                val event = if (holdLeft != null) {
                    withTimeoutOrNull(holdLeft.coerceAtLeast(0)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    // Held the whole hold: the call starts now, and this press's release does nothing.
                    held = true
                    onHold()
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                latest = change.uptimeMillis
                if (change.changedToUp()) {
                    if (!change.isConsumed && !change.isOutOfBounds(size, extendedTouchPadding)) release = change
                    break
                }
                // A scroll or another gesture took the touch.
                if (change.isConsumed) break
            }
            val tapped = release
            emitScope.emit(interactions, if (tapped != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            if (tapped != null && !held && ComposerCallPress.isTap(began, tapped.uptimeMillis)) {
                tapped.consume()
                onTap()
            }
        }
    }
}

private fun CoroutineScope.emit(interactions: MutableInteractionSource, interaction: PressInteraction) {
    launch { interactions.emit(interaction) }
}
