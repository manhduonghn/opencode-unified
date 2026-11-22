package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a docked keyboard covers of `window` before, less what it covers after. Null for a floating
 * keyboard, a show or a hide, a window the keyboard doesn't cover, and steps under half a point or
 * over 80.
 */
object DockedKeyboard {
    fun unanimatedStep(begin: Rect, end: Rect, window: Rect, screenMaxY: Float): Float? {
        if (begin.height <= 0f || end.height <= 0f || abs(begin.bottom - end.bottom) >= 1f ||
            abs(begin.width - end.width) >= 1f || end.bottom < screenMaxY - 1f
        ) return null
        fun covered(keyboard: Rect): Float =
            if (keyboard.overlaps(window)) keyboard.intersect(window).height else 0f
        val step = covered(begin) - covered(end)
        return if (abs(step) > 0.5f && abs(step) <= 80f) step else null
    }

    /**
     * The keyboard's own spring (`Motion.keyboard`) for what
     * moves with it, or null (no animation) under Reduce Motion or while the card lays out without
     * the keyboard (`excludesKeyboard`).
     */
    fun focusAnimation(reduceMotion: Boolean, excludesKeyboard: Boolean): FiniteAnimationSpec<Float>? =
        if (reduceMotion || excludesKeyboard) null else Motion.keyboard.animation()
}

/**
 * The composer through a docked keyboard's unanimated height change (the suggestion strip hiding,
 * and showing again). The keys snap; the composer keeps its drawn top in that frame, then glides
 * after them on `Motion.layout`, by as much as the keyboard's cover of its window changed. Another
 * change on the way keeps its speed.
 */
@Stable
class ComposerKeyboardStep {
    /** Added beneath the composer (px): what the keyboard's top moved that the composer hasn't followed yet. */
    var hold: Float by mutableFloatStateOf(0f)
        private set
    private val spring = Animatable(0f)
    private var job: Job? = null

    fun keyboardChanged(step: Float, scope: CoroutineScope) {
        val running = job?.isActive == true
        job?.cancel()
        job = scope.launch {
            val velocity = if (running) spring.velocity else 0f
            spring.snapTo(hold + step)
            hold = spring.value
            // Starts on the next frame: the frame the keys snapped in can stall, and the composer holds through it.
            withFrameNanos { }
            spring.animateTo(0f, Motion.layout.animation(), initialVelocity = velocity) { hold = value }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        hold = 0f
    }
}

/**
 * Pads the composer's bottom by [ComposerKeyboardStep.hold] through an unanimated IME height change
 * (no IME animation running). None under Reduce Motion.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.composerKeyboardStepHold(): Modifier = composed {
    val step = remember { ComposerKeyboardStep() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val reduceMotion = rememberReduceMotion()
    // Read in a snapshot flow so the keyboard's animation doesn't recompose the whole composer
    // column.
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val previous = remember { floatArrayOf(Float.NaN) }
    LaunchedEffect(ime, imeSource, imeTarget, density, view, reduceMotion) {
        snapshotFlow { ime.getBottom(density) }.collect { bottom ->
            val old = previous[0]
            previous[0] = bottom.toFloat()
            if (old.isNaN() || reduceMotion || old == bottom.toFloat()) return@collect
            val animating = imeSource.getBottom(density) != imeTarget.getBottom(density)
            if (animating) return@collect
            val height = view.rootView.height.toFloat()
            val width = view.rootView.width.toFloat()
            if (height <= 0f) return@collect
            val px = density.density
            // In dp, so the 80 dp cap holds on every screen.
            val begin = Rect(0f, (height - old) / px, width / px, height / px)
            val end = Rect(0f, (height - bottom) / px, width / px, height / px)
            val window = Rect(0f, 0f, width / px, height / px)
            val stepDp = DockedKeyboard.unanimatedStep(begin, end, window, screenMaxY = height / px) ?: return@collect
            step.keyboardChanged(stepDp * px, scope)
        }
    }
    DisposableEffect(Unit) { onDispose { step.stop() } }
    layout { measurable, constraints ->
        val extra = step.hold.roundToInt()
        val placeable = measurable.measure(constraints.offset(vertical = -max(extra, 0)))
        layout(placeable.width, max(placeable.height + extra, 0)) { placeable.place(0, 0) }
    }
}
