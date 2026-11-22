package ai.opencode.core.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.dp

/**
 * Moving between full screens uses Material's shared-axis X transition (300 ms; the old screen gone
 * in the first 90 ms). Predictive back shrinks toward [BACK_PREVIEW_SCALE]; Reduce Motion
 * cross-fades.
 */
object PageTransitions {
    /** How far each screen moves (Material's shared-axis slide distance). */
    val SlideDistance = 30.dp

    /** One transition (Material's shared-axis duration). */
    const val DURATION_SECONDS = 0.3

    /** The outgoing screen's fade (Material's shared-axis fade-out). */
    const val FADE_OUT_SECONDS = 0.09

    /** The fraction of a transition after which the outgoing screen has faded out and the incoming one fades in. */
    const val FADE_SPLIT = (FADE_OUT_SECONDS / DURATION_SECONDS).toFloat()

    /** The cross-fade under reduce motion. */
    const val REDUCED_FADE_SECONDS = 0.15

    /** How small a screen gets while the back gesture previews leaving it (Material predictive back). */
    const val BACK_PREVIEW_SCALE = 0.9f

    /** How far a screen leans toward the swipe while the back gesture previews leaving it. */
    val BackPreviewShift = 16.dp

    /** The corner radius a screen takes while the back gesture previews leaving it. */
    val BackPreviewCorner = 28.dp

    /**
     * A push: the new screen comes in from the end. [slidePx] is [SlideDistance] in pixels; [rtl]
     * mirrors the direction.
     */
    fun forward(slidePx: Int, rtl: Boolean, reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return crossFade()
        val sign = if (rtl) -1 else 1
        val enter = slideInHorizontally(slideSpec()) { slidePx * sign } + fadeIn(fadeInSpec())
        val exit = slideOutHorizontally(slideSpec()) { -slidePx * sign } + fadeOut(fadeOutSpec())
        return ContentTransform(enter, exit, targetContentZIndex = 0f, sizeTransform = null)
    }

    /** A pop by a button or key: the screen beneath comes back from the start. */
    fun backward(slidePx: Int, rtl: Boolean, reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return crossFade(targetBelow = true)
        val sign = if (rtl) -1 else 1
        val enter = slideInHorizontally(slideSpec()) { -slidePx * sign } + fadeIn(fadeInSpec())
        val exit = slideOutHorizontally(slideSpec()) { slidePx * sign } + fadeOut(fadeOutSpec())
        return ContentTransform(enter, exit, targetContentZIndex = -1f, sizeTransform = null)
    }

    /**
     * A pop the system back gesture drives (seeked by its progress): the top screen shrinks and
     * fades near the end, and the screen beneath slides back in behind it.
     */
    fun predictiveBack(slidePx: Int, rtl: Boolean, reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return crossFade(targetBelow = true)
        val sign = if (rtl) -1 else 1
        val duration = Motion.millis(DURATION_SECONDS)
        val enter = slideInHorizontally(tween(duration, easing = LinearEasing)) { -slidePx * sign }
        val exit = scaleOut(tween(duration, easing = LinearEasing), targetScale = BACK_PREVIEW_SCALE) +
            fadeOut(tween(Motion.millis(DURATION_SECONDS - FADE_OUT_SECONDS * 2), delayMillis = Motion.millis(FADE_OUT_SECONDS * 2)))
        return ContentTransform(enter, exit, targetContentZIndex = -1f, sizeTransform = null)
    }

    /**
     * Material's fade-through, for one screen replacing a sibling: the old fades out, then the new
     * fades in growing from 92 %.
     */
    fun fadeThrough(reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return crossFade()
        val enter = fadeIn(fadeInSpec()) + scaleIn(fadeInSpec(), initialScale = 0.92f)
        return ContentTransform(enter, fadeOut(fadeOutSpec()), targetContentZIndex = 0f, sizeTransform = null)
    }

    private fun crossFade(targetBelow: Boolean = false): ContentTransform {
        val spec = tween<Float>(Motion.millis(REDUCED_FADE_SECONDS))
        return ContentTransform(
            fadeIn(spec),
            fadeOut(spec),
            targetContentZIndex = if (targetBelow) -1f else 0f,
            sizeTransform = null,
        )
    }

    private fun <T> slideSpec() = tween<T>(Motion.millis(DURATION_SECONDS), easing = FastOutSlowInEasing)

    private fun fadeInSpec() = tween<Float>(
        Motion.millis(DURATION_SECONDS - FADE_OUT_SECONDS),
        delayMillis = Motion.millis(FADE_OUT_SECONDS),
        easing = LinearOutSlowInEasing,
    )

    private fun fadeOutSpec() = tween<Float>(Motion.millis(FADE_OUT_SECONDS), easing = FastOutLinearInEasing)
}
