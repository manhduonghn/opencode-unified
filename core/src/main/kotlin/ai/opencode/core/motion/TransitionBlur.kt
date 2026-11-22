package ai.opencode.core.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.Dp

/** Blurs inside transitions, switched off here in one place: Material motion never blurs. */
object TransitionBlur {
    /** Whether transition blurs draw. Off on Android. */
    const val enabled: Boolean = false
}

/** A [TransitionBlur] on this content. Draws nothing on Android. */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
fun Modifier.transitionBlur(radius: Dp): Modifier = this

/** [transitionBlur] for a radius read in the draw phase. The lambda is never called. */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
fun Modifier.transitionBlur(radius: () -> Float): Modifier = this

/** A [TransitionBlur] as a `graphicsLayer` render effect: always none on Android. */
@Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
fun transitionBlurEffect(radiusPx: Float): RenderEffect? = null
