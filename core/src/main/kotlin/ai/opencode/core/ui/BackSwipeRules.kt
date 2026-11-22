package ai.opencode.core.ui

import ai.opencode.core.motion.MotionRelease
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The pure rules behind rightward back pans, for the drawer's open pan and Catch Up-style drags.
 * System back stays Android's.
 */
object BackSwipeRules {
    /** How far from the leading edge a touch always belongs to the back/drawer gesture. */
    val edgeWidth: Dp = 28.dp

    /** How long a finger can rest before its later sideways move belongs to the hold (seconds). */
    const val HOLD_THRESHOLD_SECONDS = 0.45

    /** Travel before the pan picks an axis. */
    val decisionDistance: Dp = 8.dp

    /** How far around a selection handle a touch takes the handle. */
    val selectionHandleReach: Dp = 24.dp

    enum class Decision { Wait, Begin, Fail }

    /** A touch that rested before moving keeps doing what the hold began. */
    fun yieldsToHold(heldForSeconds: Double, holdBegan: Boolean): Boolean =
        holdBegan || heldForSeconds >= HOLD_THRESHOLD_SECONDS

    /**
     * The back pan's choice once the finger moved [dx], [dy] (px, with [decisionDistancePx]
     * from [decisionDistance]); `dx > 0` is toward the trailing side in LTR (mirror for RTL).
     */
    fun decision(
        dx: Float,
        dy: Float,
        decisionDistancePx: Float,
        atEdge: Boolean,
        claimed: Boolean,
        heldForSeconds: Double,
        holdBegan: Boolean,
    ): Decision {
        if (dx * dx + dy * dy < decisionDistancePx * decisionDistancePx) return Decision.Wait
        if (dx <= 0 || abs(dx) <= abs(dy)) return Decision.Fail
        if (atEdge) return Decision.Begin
        if (claimed || yieldsToHold(heldForSeconds, holdBegan)) return Decision.Fail
        return Decision.Begin
    }

    /**
     * The release rule every back swipe shares. [velocity] in dp/s; [offset] and [width] in one
     * unit.
     */
    fun goesBack(offset: Float, width: Float, velocity: Float): Boolean =
        MotionRelease.shouldCommit(translation = offset.toDouble(), velocity = velocity.toDouble(), distance = width.toDouble())
}
