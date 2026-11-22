package ai.opencode.feature.drawer.catchup

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Catch Up's swipe, spring, stack and card tunables, as their baked defaults. Android has no Tuning
 * Panel (`:devtools` is Android-native), so only the defaults are ported.
 *
 * Distances are in dp.
 */
object CatchUpTunables {
    // Swipe
    /** Commit distance (× width). */
    const val commitFraction = 0.24f
    /** Projection deceleration (per millisecond, `UIScrollView.DecelerationRate.normal`-like). */
    const val decelerationRate = 0.994f
    /** Flick velocity (pt/s → dp/s). */
    const val velocityThreshold = 500f
    /** Haptic threshold (× commit). */
    const val hapticThreshold = 1.0f
    /** Vertical resistance. */
    const val rubberBand = 0.3f

    // Stack
    /** Back card scale. */
    const val backScale = 0.92f
    /** Back card offset (pt). */
    const val backOffset = 12f
    /** Back card lift (× height). */
    const val backLiftRatio = 0.04f
    /** Back card rotation (rad). */
    const val backRotation = 0f
    /** Back card reveal (× width). */
    const val revealFraction = 0.78f

    // Card
    /** Card corner radius. */
    const val cornerRadius = 16f
    /** Drag rotation (rad). */
    const val dragRotation = 0.16f
    /** Card side inset. */
    const val sideInset = 10f
}

/** Projected-landing math and release decision for Catch Up card swipes. */
object CatchUpSwipeMath {
    /**
     * Standard `UIScrollView` projection: distance travelled by [initialVelocity] (units/s) before
     * coming to rest at [decelerationRate] (per millisecond).
     */
    fun project(initialVelocity: Float, decelerationRate: Float = CatchUpTunables.decelerationRate): Float {
        val rate = min(max(decelerationRate, 0.9f), 0.9995f)
        return (initialVelocity / 1_000f) * rate / (1 - rate)
    }

    /**
     * Decides whether a drag released at [translation] with [velocity] in a container of [width]
     * commits to `markRead` (right), `keepUnread` (left), or snaps back (`null`). All values in the
     * same unit.
     */
    fun decision(
        translation: Offset,
        velocity: Offset,
        width: Float,
        commitFraction: Float = CatchUpTunables.commitFraction,
        decelerationRate: Float = CatchUpTunables.decelerationRate,
        velocityThreshold: Float = CatchUpTunables.velocityThreshold,
    ): CatchUpDisposition? {
        val dir = when {
            abs(translation.x) >= 1 -> if (translation.x > 0) 1f else -1f
            abs(velocity.x) >= 1 -> if (velocity.x > 0) 1f else -1f
            else -> 0f
        }
        if (dir == 0f) return null
        val signedX = translation.x * dir
        val signedV = velocity.x * dir
        if (signedX <= 0) return null

        // A flick back toward center cancels the commit.
        if (signedV <= -velocityThreshold) return null

        val commitDistance = max(width * commitFraction, 1f)
        val projected = signedX + project(signedV, decelerationRate)
        val commitsByProjection = projected >= commitDistance
        val commitsByFlick = signedX >= 12 && signedV >= velocityThreshold
        if (!commitsByProjection && !commitsByFlick) return null
        return if (dir > 0) CatchUpDisposition.markRead else CatchUpDisposition.keepUnread
    }
}

/** Layout shared by the Catch Up chrome and the inset cards. */
object CatchUpMetrics {
    val readColor = Color(red = 0.08f, green = 0.57f, blue = 0.42f, alpha = 1f)
    val readControlColor = Color(red = 0.06f, green = 0.46f, blue = 0.34f, alpha = 1f)
    val keepColor = Color(red = 0.34f, green = 0.38f, blue = 0.52f, alpha = 1f)
    val headerHeight: Dp = 52.dp
    val footerHeight: Dp = 74.dp
    val sideInset: Dp get() = CatchUpTunables.sideInset.dp

    /** Concentric with the 28 pt composer capsule inset 12 pt from the card's edges (`28 + 12 = 40`). */
    val cornerRadius: Dp get() = CatchUpTunables.cornerRadius.dp

    /**
     * A specular rim in dark mode so the inset card stands clear of the dark backdrop; the
     * standard separator in light mode. [separator] is the theme's separator colour.
     */
    fun borderColor(isDark: Boolean, separator: Color, factor: Float = 1f): Color {
        val base = if (isDark) Color.White.copy(alpha = 0.16f) else separator
        return base.copy(alpha = base.alpha * factor)
    }
}
