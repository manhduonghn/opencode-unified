package ai.opencode.core.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The press role's feedback: scales to [MotionTunables.pressScale], or dims under Reduce Motion.
 * For custom surfaces; Material components draw their own ripple.
 */
object MotionPress {
    const val reducedOpacity: Float = 0.72f

    /** The scale a control shows while [isPressed]: 1 under Reduce Motion. */
    fun scale(isPressed: Boolean, reduceMotion: Boolean): Float =
        if (isPressed && !reduceMotion) MotionTunables.pressScale.value else 1f

    /** The opacity a control shows while [isPressed]: [reducedOpacity] only under Reduce Motion. */
    fun opacity(isPressed: Boolean, reduceMotion: Boolean): Float =
        if (isPressed && reduceMotion) reducedOpacity else 1f
}

/** The press role's scale (or Reduce Motion dim) for [isPressed]. */
@Composable
fun Modifier.motionPressEffect(isPressed: Boolean, reduceMotion: Boolean = rememberReduceMotion()): Modifier {
    val phase = if (isPressed) MotionPhase.Enter else MotionPhase.Exit
    val scale by animateFloatAsState(
        targetValue = MotionPress.scale(isPressed, reduceMotion),
        animationSpec = Motion.press.animation(phase, reduceMotion),
        label = "motionPressScale",
    )
    val opacity by animateFloatAsState(
        targetValue = MotionPress.opacity(isPressed, reduceMotion),
        animationSpec = Motion.press.animation(phase, reduceMotion),
        label = "motionPressOpacity",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = opacity
    }
}

/** [motionPressEffect] driven by a control's [interactionSource]. */
@Composable
fun Modifier.motionPress(interactionSource: InteractionSource, reduceMotion: Boolean = rememberReduceMotion()): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    return motionPressEffect(isPressed, reduceMotion)
}
