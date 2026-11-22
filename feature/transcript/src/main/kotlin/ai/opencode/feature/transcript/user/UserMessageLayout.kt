package ai.opencode.feature.transcript.user

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.feature.transcript.TranscriptTunables
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * A steering message accepted: the list moves its row past Working while the row lets its label go,
 * both on this one critically damped spring, so the bubble's two motions (the row moving, the
 * label's space closing) add up to one and nothing passes its place.
 */
object PromotionMotion {
    val duration: Double get() = Motion.layout.curve().duration
    val spring: MotionSpring get() = MotionSpring(duration, 0.0)

    /** The row's own animation of a change: none under Reduce Motion or while the list morphs it. */
    fun <T> animation(reduceMotion: Boolean, morphs: Boolean = false): FiniteAnimationSpec<T> =
        if (reduceMotion || morphs) snap() else spring.spec()

    /** The label leaves toward (and lands from) a few points above, fading. */
    val labelRise: Float get() = TranscriptTunables.promotionLabelRise

    fun labelEnter(density: Float): EnterTransition =
        fadeIn(spring.spec()) + slideInVertically(spring.spec<IntOffset>()) { -(labelRise * density).roundToInt() } +
            expandVertically(spring.spec(), expandFrom = Alignment.Bottom)

    /** Fading up and away while its space closes on the same spring, so the bubble's motions add up to one. */
    fun labelExit(density: Float): ExitTransition =
        fadeOut(spring.spec()) + slideOutVertically(spring.spec<IntOffset>()) { -(labelRise * density).roundToInt() } +
            shrinkVertically(spring.spec(), shrinkTowards = Alignment.Bottom)

    /**
     * A pending label leaves the same way; one that arrives on a message already on screen (the
     * next steering message taking its run's label as the first is accepted) fades in where it
     * lands, on its own animation.
     */
    fun pendingLabelEnter(): EnterTransition =
        fadeIn(Motion.swap.animation()) + expandVertically(spring.spec(), expandFrom = Alignment.Bottom)

    fun pendingLabelExit(density: Float): ExitTransition = labelExit(density)
}

/**
 * Set while the list morphs this row through a promotion: its change lands at once, and the list
 * moves and fades it on the promotion's spring itself.
 */
val LocalPromotionMorph = staticCompositionLocalOf { false }

/**
 * Lays a row out at its own height and pins it to the top of its slot. While the list animates a
 * row to a new height, the content is already laid out for the new one; centred in the old frame it
 * would jump up and slide back. Pinned, it stays put while the slot's edge moves. A fixed height
 * from the list is kept; otherwise the content's own.
 */
@Composable
fun TopAnchoredRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            ?: return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        // Self-sizing asks with no fixed height; an animating list proposes the slot's.
        val height = if (constraints.hasFixedHeight && constraints.hasBoundedHeight) constraints.maxHeight
        else placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { placeable.placeRelative(0, 0) }
    }
}

/** Places the bubble at the trailing edge within [fraction] of the width. */
@Composable
internal fun TrailingFractionLayout(fraction: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val measurable = measurables.firstOrNull() ?: return@Layout layout(0, 0) {}
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else measurable.maxIntrinsicWidth(constraints.maxHeight)
        val childMax = (available * fraction).toInt()
        val placeable = measurable.measure(Constraints(minWidth = 0, maxWidth = childMax, minHeight = 0, maxHeight = constraints.maxHeight))
        layout(available, placeable.height) { placeable.placeRelative(available - placeable.width, 0) }
    }
}
