package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.transitionBlurEffect
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density

/**
 * A removed composer attachment keeps its pixels for the exit while the durable attachment state
 * changes at once. The removed tile leaves on `Motion.microOut`; its siblings close the gap on
 * `Motion.collapse`. The New Plus Menu's attachment row uses it (the session view wires it).
 */
object AttachmentRemoval {
    /** The siblings closing the gap: `Motion.collapse`, or the reduced fade under Reduce Motion. */
    fun <T> animation(reduceMotion: Boolean): FiniteAnimationSpec<T> = Motion.collapse.animation(reduceMotion = reduceMotion)

    /** The removed tile leaving: `Motion.microOut`. */
    fun <T> exitAnimation(reduceMotion: Boolean): FiniteAnimationSpec<T> = Motion.microOut.animation(reduceMotion = reduceMotion)

    /** Down to the swap pose (scale, a subtle blur, clear); only the fade under Reduce Motion. */
    fun Modifier.attachmentRemovalExit(exit: Float, reduceMotion: Boolean): Modifier {
        val p = exit.coerceIn(0f, 1f)
        if (reduceMotion) return graphicsLayer { alpha = 1f - p }
        val blurred = if (p > 0f) blur(MotionTunables.blurSubtle.value * p) else this
        return blurred.graphicsLayer {
            val scale = 1f + (MotionTunables.swapScale.value - 1f) * p
            scaleX = scale
            scaleY = scale
            alpha = 1f - p
        }
    }

    /**
     * [attachmentRemovalExit] on a retained drawing (the departing attachment's last frame, which
     * plays the exit where it sat while the row reflows without it).
     */
    fun GraphicsLayer.attachmentRemovalExit(exit: Float, reduceMotion: Boolean, density: Density) {
        val p = exit.coerceIn(0f, 1f)
        alpha = 1f - p
        if (reduceMotion) return
        val scale = 1f + (MotionTunables.swapScale.value - 1f) * p
        scaleX = scale
        scaleY = scale
        val radius = with(density) { (MotionTunables.blurSubtle.value * p).toPx() }
        renderEffect = transitionBlurEffect(radius)
    }
}
