package ai.opencode.feature.transcript.rows

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionSpring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A row opening or closing (iOS `DisclosureMotion`): opening on `Motion.layout`, closing on its
 * quicker exit (`Motion.collapse`), both critically damped so nothing passes its place and
 * settles back. The disclosure's one curve, shared with bands that draw themselves with the
 * row's edge (`BandMotion`).
 */
object DisclosureMotion {
    fun phase(opening: Boolean): MotionPhase = if (opening) MotionPhase.Enter else MotionPhase.Exit

    /** Closing is quicker than opening. */
    fun duration(opening: Boolean): Double = Motion.layout.curve(phase(opening)).duration

    /** `spring(duration:bounce: 0)` for [opening]. */
    fun spring(opening: Boolean): MotionSpring = MotionSpring(duration(opening))

    /** The rows moving inside the row, on the same curve (iOS `animation(opening:)`). */
    fun <T> animation(opening: Boolean): FiniteAnimationSpec<T> = Motion.layout.animation(phase(opening))
}

/** Set [animatesItself] while a part animates its own height, so the list row doesn't animate it again. */
class RowHeightMotion {
    var animatesItself by mutableStateOf(false)
}

val LocalRowHeightMotion = staticCompositionLocalOf<RowHeightMotion?> { null }
