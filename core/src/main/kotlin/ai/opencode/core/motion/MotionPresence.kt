package ai.opencode.core.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Blur, opacity and optional rise for content entering a morphing surface. Blur runs only for 0.02
 * < [progress] < 0.98; Reduce Motion is opacity-only.
 */
fun Modifier.motionBlurReveal(
    progress: Float,
    maxBlur: Dp = Motion.blurPanel,
    rise: Dp = 0.dp,
    reduceMotion: Boolean = false,
): Modifier {
    val clamped = progress.coerceIn(0f, 1f)
    val radius = MotionBlurReveal.blurRadius(clamped, maxBlur.value, reduceMotion)
    val offset = MotionBlurReveal.verticalOffset(clamped, rise.value, reduceMotion)
    val opacity = if (clamped <= 0.02f) 0f else clamped
    val blurred = this.transitionBlur(radius.dp)
    return blurred.graphicsLayer {
        alpha = opacity
        translationY = offset.dp.toPx()
    }
}

/** The pure maths behind [motionBlurReveal]. */
object MotionBlurReveal {
    fun blurRadius(progress: Float, maxBlur: Float, reduceMotion: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (reduceMotion || clamped >= 0.98f) return 0f
        return (1 - clamped) * maxBlur
    }

    fun verticalOffset(progress: Float, rise: Float, reduceMotion: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (reduceMotion) return 0f
        return (1 - clamped) * rise
    }
}

// Presence

/**
 * Shows [content] while [isOpen], fading and scaling it in and out on [role] (a fade alone under
 * Reduce Motion).
 */
@Composable
fun MotionPresence(
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    role: MotionRole = MotionRole.MicroIn,
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable () -> Unit,
) {
    val enterSpec = role.animation<Float>(MotionPhase.Enter, reduceMotion)
    val exitSpec = role.animation<Float>(MotionPhase.Exit, reduceMotion)
    val entering = MotionPose.entering(role)
    val leaving = MotionPose.leaving(role)
    var enter = fadeIn(enterSpec, initialAlpha = entering.opacity)
    var exit = fadeOut(exitSpec, targetAlpha = leaving.opacity)
    if (!reduceMotion) {
        enter += scaleIn(enterSpec, initialScale = entering.scale)
        exit += scaleOut(exitSpec, targetScale = leaving.scale)
    }
    AnimatedVisibility(isOpen, modifier, enter = enter, exit = exit) { content() }
}

// Transitions

/** Which way a rolling value moves as it changes. */
enum class MotionRollDirection { Up, Down }

/**
 * Role-timed transitions for `AnimatedVisibility` and `AnimatedContent`; reduced cross-fades under
 * Reduce Motion.
 */
data class MotionTransition(val enter: EnterTransition, val exit: ExitTransition) {
    val content: ContentTransform get() = enter togetherWith exit

    companion object {
        /** Arrives in the micro-in pose on micro in, leaves in the swap pose on micro out. */
        fun micro(reduceMotion: Boolean): MotionTransition {
            if (reduceMotion) return reduced()
            return MotionTransition(
                enter = pose(MotionPose.microIn, MotionRole.MicroIn, MotionPhase.Enter),
                exit = poseOut(MotionPose.swap, MotionRole.MicroOut, MotionPhase.Exit),
            )
        }

        /** Swaps in place: the swap pose in and out on the swap role. */
        fun swap(reduceMotion: Boolean): MotionTransition {
            if (reduceMotion) return reduced()
            return MotionTransition(
                enter = pose(MotionPose.swap, MotionRole.Swap, MotionPhase.Enter),
                exit = poseOut(MotionPose.swap, MotionRole.Swap, MotionPhase.Exit),
            )
        }

        /**
         * Rolls like a counter: in from [MotionTunables.rollDistance] away, out the other way, on
         * [role].
         */
        fun roll(
            direction: MotionRollDirection,
            reduceMotion: Boolean,
            density: Density,
            role: MotionRole = Motion.layout,
        ): MotionTransition {
            if (reduceMotion) return reduced()
            val sign = if (direction == MotionRollDirection.Up) 1 else -1
            val distance = with(density) { MotionTunables.rollDistance.value.roundToPx() } * sign
            val swapPose = MotionPose.swap
            val enter = fadeIn(role.animation(MotionPhase.Enter)) +
                scaleIn(role.animation(MotionPhase.Enter), initialScale = swapPose.scale) +
                slideInVertically(role.animation(MotionPhase.Enter)) { distance }
            val exit = fadeOut(role.animation(MotionPhase.Enter)) +
                scaleOut(role.animation(MotionPhase.Enter), targetScale = swapPose.scale) +
                slideOutVertically(role.animation(MotionPhase.Enter)) { -distance }
            return MotionTransition(enter, exit)
        }

        private fun reduced(): MotionTransition = MotionTransition(
            enter = fadeIn(MotionRole.Reduced.animation(reduceMotion = true)),
            exit = fadeOut(MotionRole.Reduced.animation(reduceMotion = true)),
        )

        private fun pose(from: MotionPose, role: MotionRole, phase: MotionPhase): EnterTransition =
            fadeIn(role.animation(phase), initialAlpha = from.opacity) +
                scaleIn(role.animation(phase), initialScale = from.scale)

        private fun poseOut(to: MotionPose, role: MotionRole, phase: MotionPhase): ExitTransition =
            fadeOut(role.animation(phase), targetAlpha = to.opacity) +
                scaleOut(role.animation(phase), targetScale = to.scale)
    }
}

/** Shows [value] through [content], swapping (or rolling, given [roll]) when it changes. */
@Composable
fun <T> MotionSwap(
    value: T,
    modifier: Modifier = Modifier,
    roll: MotionRollDirection? = null,
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable (T) -> Unit,
) {
    val density = LocalDensity.current
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val transition = if (roll != null) {
                MotionTransition.roll(roll, reduceMotion, density)
            } else {
                MotionTransition.swap(reduceMotion)
            }
            transition.content
        },
        label = "motionSwap",
    ) { shown -> content(shown) }
}
