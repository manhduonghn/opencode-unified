package ai.opencode.core.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The one Reduce Motion translation: movement, scale, blur and rotation go neutral and only opacity
 * changes.
 */
object MotionReduce {
    fun offset(value: Dp, reduceMotion: Boolean): Dp = if (reduceMotion) 0.dp else value
    fun offset(value: Float, reduceMotion: Boolean): Float = if (reduceMotion) 0f else value
    fun offset(value: DpOffset, reduceMotion: Boolean): DpOffset = if (reduceMotion) DpOffset.Zero else value
    fun scale(value: Float, reduceMotion: Boolean): Float = if (reduceMotion) 1f else value
    fun blur(value: Dp, reduceMotion: Boolean): Dp = if (reduceMotion) 0.dp else value

    /** Degrees. */
    fun rotation(value: Float, reduceMotion: Boolean): Float = if (reduceMotion) 0f else value
}

/**
 * Where a view sits before it arrives or after it leaves: opacity, scale, blur and offset.
 * [identity] is at rest.
 */
data class MotionPose(
    val opacity: Float = 1f,
    val scale: Float = 1f,
    val blur: Dp = 0.dp,
    val offset: DpOffset = DpOffset.Zero,
) {
    /** Under Reduce Motion only the opacity survives. */
    fun reduced(reduceMotion: Boolean): MotionPose = if (reduceMotion) MotionPose(opacity = opacity) else this

    /** The pose [fraction] of the way from [identity]. */
    fun scaled(by: Float): MotionPose = MotionPose(
        opacity = 1f - (1f - opacity) * by,
        scale = 1f - (1f - scale) * by,
        blur = blur * by,
        offset = DpOffset(offset.x * by, offset.y * by),
    )

    companion object {
        val identity = MotionPose()

        /** A small glyph or badge arriving: from 0.85 and a subtle blur. */
        val microIn: MotionPose
            get() = MotionPose(opacity = 0f, scale = MotionTunables.microInScale.value, blur = MotionTunables.blurSubtle.value)

        /** Content swapping in place, and everything leaving: 0.96 and a subtle blur. */
        val swap: MotionPose
            get() = MotionPose(opacity = 0f, scale = MotionTunables.swapScale.value, blur = MotionTunables.blurSubtle.value)

        /** [swap] shifted vertically by [distance]: a value rolling over like a counter. */
        fun roll(distance: Dp): MotionPose = swap.copy(offset = DpOffset(0.dp, distance))

        fun entering(role: MotionRole): MotionPose = when (role) {
            MotionRole.MicroIn, MotionRole.Press -> microIn
            else -> swap
        }

        @Suppress("UNUSED_PARAMETER")
        fun leaving(role: MotionRole): MotionPose = swap
    }
}

/**
 * Draws [pose] (reduced under Reduce Motion). Blur goes through [transitionBlur], which is off on
 * Android by default (docs/parity/core-ui.md).
 */
fun Modifier.motionPose(pose: MotionPose, reduceMotion: Boolean): Modifier {
    val shown = pose.reduced(reduceMotion)
    return transitionBlur(shown.blur).graphicsLayer {
        scaleX = shown.scale
        scaleY = shown.scale
        translationX = shown.offset.x.toPx()
        translationY = shown.offset.y.toPx()
        alpha = shown.opacity
    }
}
