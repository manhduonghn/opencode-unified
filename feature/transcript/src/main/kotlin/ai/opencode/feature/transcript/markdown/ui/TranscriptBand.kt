package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.rows.DisclosureMotion
import ai.opencode.feature.transcript.rows.LocalRowHeightMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max

/**
 * A band's grow-in or close, timed like the list's disclosure motion (iOS `BandMotion`): the
 * same critically damped spring (`spring(duration:bounce: 0)`), so the band's drawn edge rides
 * the row's growing edge. Pure math; [BandClock] drives it with a Compose spring of the same
 * stiffness (`ω = 2π / duration`, damping ratio 1).
 */
object BandMotion {
    /** UIKit's and SwiftUI's `spring(duration:bounce: 0)`. */
    fun progress(elapsed: Double, duration: Double): Double {
        if (elapsed <= 0) return 0.0
        val omega = 2 * PI / duration
        return 1 - (1 + omega * elapsed) * exp(-omega * elapsed)
    }

    /** The time the spring takes to cover [progress] of its travel. */
    fun elapsed(toReach: Double, duration: Double): Double {
        var low = 0.0
        var high = duration * 3
        repeat(40) {
            val mid = (low + high) / 2
            if (progress(mid, duration) < toReach) low = mid else high = mid
        }
        return (low + high) / 2
    }

    /** The disclosure's duration (`DisclosureMotion.duration`: `Motion.layout`, its exit closing). */
    fun duration(opening: Boolean): Double = DisclosureMotion.duration(opening)

    /** Under Reduce Motion: a cross-fade on `Motion.reduced` (smoothstep). */
    fun fade(elapsed: Double): Double {
        val t = (elapsed / max(MotionTunables.reducedDuration.value, 0.01)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}

/** How much of a [TranscriptBand] is drawn, 0…1 (iOS `EnvironmentValues.bandReveal`). Read while drawing. */
val LocalBandReveal = compositionLocalOf<() -> Float> { { 1f } }

/**
 * Draws a [TranscriptBand] at its motion's reveal each frame until it settles (iOS
 * `BandClock` + `closingBand`): whole once an opening settles, nothing once a closing does.
 * Opening, the band is in the layout at its full height at once and drawn as far as the
 * spring has reached; closing, it leaves the layout at once (so the row's own height
 * animation closes over it) and is drawn below it ([BelowLayout]) until the spring settles.
 * It brings its own [TranscriptMetrics.clustered] gap, so its container must not add spacing for
 * it. Only drawing changes per frame. Under Reduce Motion it only cross-fades, and a closing band
 * just goes.
 */
@Composable
fun BandClock(
    open: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val shown by remember { derivedStateOf { progress.value > 0f } }
    val closing = !open && !reduceMotion && shown
    LaunchedEffect(open, reduceMotion) {
        val target = if (open) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        if (reduceMotion) {
            progress.animateTo(target, tween((Motion.reducedFade * 1000).toInt(), easing = SmoothStep))
        } else {
            progress.animateTo(target, DisclosureMotion.spring(open).spec(0.001f))
        }
    }
    val reveal: () -> Float = remember(progress) { { progress.value } }
    when {
        open -> CompositionLocalProvider(LocalBandReveal provides reveal) { Box(modifier.padding(top = TranscriptMetrics.clustered)) { content() } }
        closing -> BelowLayout(spacing = TranscriptMetrics.clustered, modifier = modifier) {
            CompositionLocalProvider(LocalBandReveal provides reveal) { content() }
        }
    }
}

private val SmoothStep = androidx.compose.animation.core.Easing { t -> t * t * (3 - 2 * t) }

/**
 * Takes no height in its container and places its one child below it, at the child's own
 * height, where the row's layout no longer reaches (iOS `BelowLayout`). A picture of what
 * closed: never hit-tested by the layout above it.
 */
@Composable
fun BelowLayout(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val child = measurables.firstOrNull()?.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else child?.width ?: 0
        layout(width, 0) { child?.place(0, spacing.roundToPx()) }
    }
}

/**
 * The code and table band without its horizontal scroll, for content that wraps or truncates
 * instead: opened tool groups and answered questions (iOS `TranscriptBand`).
 *
 * It is laid out at its full height at once, but drawn only as far as [reveal] reaches: the
 * fill, its opacity and the bottom hairline grow from the top with the row's edge, and the
 * content fades and rises in just behind. Under Reduce Motion it only cross-fades. Fully
 * closed, nothing is drawn: not even a hairline.
 */
@Composable
fun TranscriptBand(
    modifier: Modifier = Modifier,
    /** Content that brings its own line padding takes less of the band's. */
    verticalPadding: Dp = TranscriptBandStyle.verticalPadding,
    reveal: () -> Float = LocalBandReveal.current,
    content: @Composable () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val reduceMotion = rememberReduceMotion()
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    val rise = with(LocalDensity.current) { 6.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val r = reveal()
                if (r <= 0.001f) return@drawBehind
                val grow = if (reduceMotion) 1f else r
                val px = hairline.toPx()
                val height = maxOf(size.height * grow, 2 * px)
                val alpha = r
                val radius = CornerRadius(TranscriptBandStyle.cornerRadius.toPx())
                // The desktop's inset block, drawn down to the band's growing edge.
                drawRoundRect(
                    TranscriptBandStyle.fill(colors).copy(alpha = TranscriptBandStyle.fill(colors).alpha * alpha),
                    Offset.Zero, Size(size.width, height), radius,
                )
                drawRoundRect(
                    colors.border.copy(alpha = colors.border.alpha * alpha),
                    Offset(px / 2, px / 2), Size(size.width - px, height - px), radius, style = Stroke(px),
                )
            }
            .drawWithContent {
                val r = reveal()
                val grow = if (reduceMotion) 1f else r
                // The mask: drawn only as far as the band's edge.
                clipRect(bottom = size.height * grow) { this@drawWithContent.drawContent() }
            }
            .padding(hairline)
            .padding(horizontal = TranscriptBandStyle.horizontalPadding, vertical = verticalPadding),
    ) {
        Box(
            Modifier.graphicsLayer {
                val r = reveal()
                // The content follows a little behind the band's edge.
                val lag = if (reduceMotion) r else ((r - 0.25f) / 0.75f).coerceIn(0f, 1f)
                alpha = lag
                translationY = if (reduceMotion) 0f else (1 - lag) * rise
            },
        ) { content() }
    }
}

/** What a disclosure reveals below its header, opening and closing its height on [DisclosureMotion]. */
@Composable
fun DisclosedContent(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val rowHeight = LocalRowHeightMotion.current
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    val moving = !state.isIdle
    LaunchedEffect(moving) {
        rowHeight?.animatesItself = moving
    }
    val opening = if (reduceMotion) snap() else DisclosureMotion.spring(opening = true).spec(IntSize.VisibilityThreshold)
    val closing = if (reduceMotion) snap() else DisclosureMotion.spring(opening = false).spec(IntSize.VisibilityThreshold)
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = DisclosedTransition.enter + expandVertically(opening, expandFrom = Alignment.Top),
        exit = fadeOut(Motion.swap.animation(MotionPhase.Exit, reduceMotion = reduceMotion)) +
            shrinkVertically(closing, shrinkTowards = Alignment.Top),
        content = content,
    )
}

/**
 * What a disclosure reveals fades in on `Motion.swap` as the row grows over it, and out on its
 * quicker exit as the row closes over it (`Motion.reduced` under Reduce Motion; iOS
 * `AnyTransition.disclosed(reduceMotion:)`). [DisclosedContent] uses it for content below a header.
 */
object DisclosedTransition {
    val enter: EnterTransition
        @Composable get() = fadeIn(Motion.swap.animation(reduceMotion = rememberReduceMotion()))

    val exit: ExitTransition
        @Composable get() = fadeOut(Motion.swap.animation(MotionPhase.Exit, reduceMotion = rememberReduceMotion())) +
            shrinkVertically(snap(), shrinkTowards = Alignment.Top, clip = false)
}
