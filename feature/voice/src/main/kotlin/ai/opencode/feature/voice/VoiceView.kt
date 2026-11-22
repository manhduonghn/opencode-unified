package ai.opencode.feature.voice

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.motion.transitionBlurEffect

import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.core.ui.floating
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** The call's store as the voice views take it. */
typealias VoiceStoreOf = StoreOf<VoiceFeature.State, VoiceFeature.Action>

/** One derived value of an optional call; `null` without a call. */
@Composable
internal fun <V> VoiceStoreOf?.watch(select: (VoiceFeature.State) -> V): V? {
    if (this == null) return null
    return observe(select).value
}

/**
 * The composer's own metrics the call bar lays itself out against. Composer is a module above
 * voice; Session's `ComposerTunables` uses the same values.
 */
object ComposerMetrics {
    /** `composer.size.barHeight`. */
    const val barHeight = 56f

    /** `composer.size.cornerRadius`; on this branch the desktop dock's 12. */
    const val cornerRadius = 12f

    /** `composer.spacing.innerHorizontal`. */
    const val innerHorizontal = 6f
}

/** The call's surfaces as Material 3 tonal surfaces: no blur or glass imitation. */
object VoiceCallSurfaceDefaults {
    /**
     * The composer and the call's cards: the desktop dock's raised surface
     * (`--surface-raised-stronger-non-alpha`, white in light, a step above the canvas in dark).
     */
    val color: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.floating

    /** The dock's hairline ring (`--v2-border-border-base`). */
    val border: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.border

    /**
     * The floating call's card and sheet: one step off the page in both modes (`#F3F3F3` on the
     * light canvas, `#252525` on the dark), so the card reads as its own surface with its hairline
     * and shadow. Mixed from the theme's canvas and text, so curated themes and Material You follow.
     */
    val card: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.let { lerpColor(it.canvas, it.text, if (it.isDark) 0.07f else 0.05f) }

    /** The card's hairline, a little stronger than [border] so it holds on the tinted card. */
    val cardBorder: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.let {
            if (it.strongBorders) it.variant.borderStrong.color else lerpColor(it.canvas, it.text, if (it.isDark) 0.15f else 0.13f)
        }

    /** The card's controls: raised over the card (white in light, a step up in dark), worn with [controlBorder]. */
    val controlFill: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.let { if (it.isDark) lerpColor(it.canvas, it.text, 0.13f) else it.canvas }

    /** The controls' hairline. */
    val controlBorder: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.let {
            if (it.strongBorders) it.variant.borderStrong.color else lerpColor(it.canvas, it.text, if (it.isDark) 0.22f else 0.18f)
        }

    /** `UIColor.tertiarySystemFill`: Mute's soft disc. */
    val tertiaryFill: Color
        @Composable @ReadOnlyComposable get() =
            Color(0.4627f, 0.4627f, 0.502f, if (OpenCodeTheme.colors.isDark) 0.24f else 0.12f)

    /** `UIColor.systemRed`: End. */
    val red: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.destructive
}

/**
 * A call surface: the desktop dock's flat fill, a hairline [border] and a small raised shadow
 * (`--v2-elevation-raised`).
 */
fun Modifier.callSurface(shape: Shape, color: Color, border: Color = Color.Unspecified): Modifier =
    callSurface({ shape }, color, border)

/** Animated geometry is observed by the layer/draw scopes, never by composition. */
private fun Modifier.callSurface(shape: () -> Shape, color: Color, border: Color = Color.Unspecified): Modifier = graphicsLayer {
    this.shape = shape()
    shadowElevation = 1.5.dp.toPx()
    clip = false
}.drawBehind {
    val outline = shape().createOutline(size, layoutDirection, this)
    drawOutline(outline, color)
    if (border.isSpecified) drawOutline(outline, border, style = Stroke(1.dp.toPx()))
}

/** Touches on this element and its children are dropped. */
internal fun Modifier.blocksTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Something the composer's own surface grows into, above the bar (the model list). Its
 * content is laid out once at the full open height, its top riding the surface's top edge
 * like a sheet's, while the bar's own contents fade out beneath it. It can rest at more than
 * one height (detents): a drag moves the surface with the finger, and settling springs it to
 * its next rest with the finger's speed. Composer builds it; [ComposerSurface] draws it.
 *
 * @property isOpen grown, or growing.
 * @property isShown drawn: open, or shrinking back into the bar.
 * @property extent how far above the bar the surface reaches when open, as it is now: its rest,
 *   or under a finger (dp).
 * @property fullExtent the tallest it can rest at: the content is laid out at this height.
 * @property isTracking a finger moves it: the surface follows at once.
 * @property settle bumped as it settles to a rest (a drag let go, a detent chosen), so the
 *   surface springs there, from [settleVelocity] (dp a second, up).
 * @property drawn where the surface is drawn, frame by frame: a drag that begins mid-spring
 *   starts from there, so a finger catches the moving surface.
 * @property reach how far the surface reaches, held by whoever opens it, so the opening can
 *   start it in its own frame instead of waiting for the surface to notice.
 * @property ridesKeyboard opening while the keyboard leaves, or folding while it comes back,
 *   follows the keyboard's motion rather than the detent spring.
 */
data class ComposerExpansion(
    val isOpen: Boolean,
    val isShown: Boolean,
    val extent: Float,
    val fullExtent: Float,
    val content: @Composable () -> Unit,
    val onDismiss: () -> Unit,
    val isTracking: Boolean = false,
    val settle: Int = 0,
    val settleVelocity: Float = 0f,
    val drawn: ComposerExpansionDrawn? = null,
    val reach: ComposerExpansionReach? = null,
    val ridesKeyboard: Boolean = false,
) {
    companion object {
        /**
         * The surface growing out of the bar and folding back (`modelPicker.glass.open` /
         * `.close`). Under Reduce Motion the surface doesn't grow (`null`): it cross-fades.
         */
        fun animation(opening: Boolean, reduceMotion: Boolean): MotionSpring? {
            if (reduceMotion) return null
            return if (opening) MotionSpring(0.42, 0.1) else MotionSpring(0.3, 0.0)
        }

        /**
         * How far above the bar the surface has to be for the bar's own row to show (below it)
         * or the expansion's content (above it). Both are read from the surface's reach as
         * drawn, frame by frame, so the two never show at once. The ranges overlap a little
         * (the content arriving out of a blur), so the surface is never empty between them.
         * (`modelPicker.reveal.barFadeStart/End`, `contentFadeStart/End`.)
         */
        val barFade: ClosedFloatingPointRange<Float> = range(10f, 50f)
        val contentFade: ClosedFloatingPointRange<Float> = range(34f, 150f)

        /** About how long the opening or closing spring takes to settle (`modelPicker.glass.settle`). */
        const val settle = 0.6

        /** `modelPicker.reveal.blur`. */
        const val revealBlur = 8f

        /** `modelPicker.glass.dimDark` / `dimLight`. */
        const val dimDark = 0.35f
        const val dimLight = 0.18f

        private fun range(start: Float, end: Float) = min(start, end)..max(max(start, end), min(start, end) + 1)
    }
}

/** How far an expansion's surface reaches (the value it animates to), held by the opener. */
class ComposerExpansionReach {
    val animatable = Animatable(0f)
    val value: Float get() = animatable.value
}

/** How far an expansion's surface reaches as drawn this frame ([ComposerExpansion.drawn]). */
class ComposerExpansionDrawn {
    var reach: Float = 0f
    private val pending = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Runs once, just after the frame where the surface first draws growth: work that would hold that frame back waits for it. */
    fun afterFirstGrowth(work: () -> Unit) {
        pending += work
    }

    fun cancelPending() {
        pending.clear()
    }

    internal fun grew(reach: Float) {
        if (reach <= 0.5f || pending.isEmpty()) return
        val work = pending.toList()
        pending.clear()
        // Read inside a draw: run after it.
        main.post { work.forEach { it() } }
    }
}

/** Opacity (and an arrival blur) read from the surface's reach as it animates, in the layer: every frame gets its own value. */
private fun Modifier.reachFade(
    reach: () -> Float,
    range: ClosedFloatingPointRange<Float>,
    showsAbove: Boolean,
    blurs: Boolean = false,
    minimum: Float = 0f,
): Modifier = graphicsLayer {
    val t = ((reach() - range.start) / max(range.endInclusive - range.start, 1f)).coerceIn(0f, 1f)
    val shown = if (showsAbove) t else 1 - t
    // No blur on content all but gone or all but whole: it costs a full pass and shows nothing.
    val radius = if (blurs && shown > 0.04f && shown < 0.98f) (1 - shown) * ComposerExpansion.revealBlur else 0f
    renderEffect = transitionBlurEffect(radius * density)
    alpha = max(shown, minimum)
}

/**
 * The composer capsule's surface. It grows up out of the bar for a pending request's [panel],
 * the call's rows, or an [expansion] (the model list): one shape, never a second surface, drawn
 * above the capsule's frame so the layout around it never changes; it measures as [content] (the
 * bar) alone.
 *
 * Only the reach shows: unrevealed room above the bar is clipped from both drawing and touches
 * ([TopInsetRect]), so it passes touches through to the transcript beneath. Reduce Motion
 * cross-fades.
 *
 * @param panel stands on the bar at its own height; [panelExtent] is the room above the bar it's
 *   laid out in, held steady while the surface grows or folds, and [panelReveal] is how far up
 *   the surface and the panel show, which only draws, so nothing is laid out again mid-fold.
 * @param panelShown the panel is up: the surface is the full surface.
 * @param controlsTakeTouches the composer is open for typing: its controls take the touches.
 * @param panelHidden something over the composer (the Living Orb's card) hides the panel from
 *   TalkBack too.
 */
@Composable
fun ComposerSurface(
    modifier: Modifier = Modifier,
    panel: (@Composable () -> Unit)? = null,
    panelExtent: () -> Float = { 0f },
    panelReveal: () -> Float = { 0f },
    panelShown: Boolean = false,
    expansion: ComposerExpansion? = null,
    @Suppress("UNUSED_PARAMETER") controlsTakeTouches: Boolean = false,
    panelHidden: Boolean = false,
    content: @Composable () -> Unit,
) {
    val activeExpansion = expansion
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark

    // The expansion's reach, animated here unless the opener holds it.
    val localReach = remember { ComposerExpansionReach() }
    var lastExpansionReach by remember { mutableStateOf<ComposerExpansionReach?>(null) }
    if (activeExpansion?.reach != null && lastExpansionReach !== activeExpansion.reach) lastExpansionReach = activeExpansion.reach
    val reachBox = activeExpansion?.reach ?: lastExpansionReach ?: localReach
    val expansionOpen = activeExpansion?.isOpen == true
    val expansionTarget = if (activeExpansion != null && (expansionOpen || (reduceMotion && activeExpansion.isShown))) activeExpansion.extent else 0f
    val motion = remember { ExpansionMotion() }
    val currentExpansion by rememberUpdatedState(activeExpansion)
    LaunchedEffect(reachBox, expansionTarget, activeExpansion?.settle, activeExpansion?.isTracking) {
        val old = motion.previousTarget
        motion.previousTarget = expansionTarget
        val target = expansionTarget
        val reach = reachBox.animatable
        val exp = currentExpansion
        // Opening and closing ride the expansion's spring; a reach that only follows the bar
        // changes with it, unless the spring is still running: then it retargets the same
        // spring, which a plain change would cut short with a jump.
        val toggled = (old == 0f) != (target == 0f)
        // Under a finger it follows, even down to the bar.
        if (exp != null && exp.isOpen && exp.isTracking) return@LaunchedEffect reach.snapTo(target)
        if (exp?.ridesKeyboard == true) {
            // Android: the keyboard's motion can't be shared; the reach follows on a spring of its length.
            if (reduceMotion) reach.snapTo(target) else reach.animateTo(target, MotionSpring(0.25).spec())
            return@LaunchedEffect
        }
        if (exp != null && !toggled && exp.isOpen) {
            // Anything else that moves an open surface (a rest chosen, a finger let go, the bar
            // moving under it) settles there on release, never jumps; a settle carries the
            // finger's speed, and Reduce Motion eases there.
            var velocity = 0f
            if (exp.settle != motion.settle) {
                motion.settle = exp.settle
                val distance = target - reach.value
                velocity = if (abs(distance) > 1) (exp.settleVelocity / distance).coerceIn(-12f, 12f) * distance else 0f
            }
            reach.animateTo(
                target,
                Motion.release.animation(reduceMotion = reduceMotion),
                initialVelocity = Motion.release.initialVelocity(velocity, reduceMotion),
            )
            return@LaunchedEffect
        }
        val animation = if (exp == null) null else ComposerExpansion.animation(opening = target > 0, reduceMotion = reduceMotion)
        val now = System.nanoTime() / 1e9
        if (animation != null && (toggled || now < motion.motionEnds)) {
            if (toggled) motion.motionEnds = now + ComposerExpansion.settle
            reach.animateTo(target, animation.spec())
        } else {
            reach.snapTo(target)
        }
    }
    LaunchedEffect(activeExpansion == null) {
        // Gone (a reset, or mid-fold): the surface goes with it.
        if (activeExpansion != null) return@LaunchedEffect
        if (reachBox.value == 0f && System.nanoTime() / 1e9 >= motion.motionEnds) return@LaunchedEffect
        motion.motionEnds = Double.NEGATIVE_INFINITY
        reachBox.animatable.snapTo(0f)
    }
    val expansionReach = reachBox.value
    val expanding = expansionReach > 0 || expansionOpen
    val extent = { max(panelExtent(), expansionReach) }
    val surfaceExpanded = panelShown || expansionOpen
    // The room above the bar the surface doesn't reach yet (or any more).
    val unrevealed = { if (!expanding) max(0f, extent() - panelReveal()) else 0f }
    val reducedReach by animateFloatAsState(if (expansionOpen) 1_000f else 0f, Motion.reducedFadeSpec(), label = "reducedReach")
    val dim by animateFloatAsState(
        if (expansionOpen) (if (isDark) ComposerExpansion.dimDark else ComposerExpansion.dimLight) else 0f,
        Motion.dim.animation(reduceMotion = reduceMotion),
        label = "expansionDim",
    )
    val surfaceColor = VoiceCallSurfaceDefaults.color
    val surfaceBorder = VoiceCallSurfaceDefaults.border
    val cornerRadius = ComposerMetrics.cornerRadius
    val showsExpansion = activeExpansion?.isShown == true || (activeExpansion != null && expansionReach > 0)
    val glassFullAlpha by animateFloatAsState(if (surfaceExpanded) 1f else 0f, Motion.reducedFadeSpec(), label = "surfaceFull")

    Layout(
        modifier = modifier,
        content = {
            if (activeExpansion != null) {
                // Everything outside the expansion, above and below the bar, dims a little and dismisses it.
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.backdrop)
                        .wrapContentSize(unbounded = true)
                        .requiredSize(4_000.dp)
                        .background(Color.Black.copy(alpha = dim))
                        .then(if (expansionOpen) Modifier.clickable(interactionSource = null, indication = null) { activeExpansion.onDismiss() } else Modifier)
                        .excludedFromDrawerPan(expansionOpen)
                        .semantics { hideFromAccessibility() },
                )
            }
            // The surface: one shape that grows and shrinks; under Reduce Motion the bar's and the
            // full surface's cross-fade instead.
            Box(Modifier.layoutId(SurfaceSlot.glass)) {
                if (reduceMotion) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - glassFullAlpha }
                            .callSurface({ TopInsetRect(extent(), cornerRadius) }, surfaceColor, surfaceBorder),
                    )
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = glassFullAlpha }.callSurface(RoundedCornerShape(cornerRadius.dp), surfaceColor, surfaceBorder))
                } else {
                    Box(Modifier.fillMaxSize().callSurface({ TopInsetRect(unrevealed(), cornerRadius) }, surfaceColor, surfaceBorder))
                }
            }
            Box(
                Modifier
                    .layoutId(SurfaceSlot.content)
                    // Under an expansion the bar's row fades with the surface's reach, so it only
                    // shows once the surface has nearly settled back onto it.
                    .reachFade(reach = { if (reduceMotion) reducedReach else reachBox.value }, range = ComposerExpansion.barFade, showsAbove = false)
                    .then(if (expansionOpen) Modifier.clearAndSetSemantics {} else Modifier),
            ) { content() }
            if (panel != null) {
                // A request's panel sits on the bar, its position fixed there; only the clip (the
                // surface's reach) grows and folds, so the panel never moves under it. Laid out
                // even before the surface reveals any of it, so its height is known to grow to.
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.panel)
                        .graphicsLayer {
                            shape = BottomRevealShape(min(panelReveal(), extent()), 28f)
                            clip = true
                        }
                        .then(if (panelShown) Modifier else Modifier.blocksTouches())
                        .then(if (panelHidden) Modifier.clearAndSetSemantics {} else Modifier),
                ) { panel() }
            }
            if (activeExpansion != null && showsExpansion) {
                // The surface's own shape clips it, revealing it from the bar up.
                Box(
                    Modifier
                        .layoutId(SurfaceSlot.expansion)
                        .clip(RoundedCornerShape(ComposerMetrics.cornerRadius.dp))
                        .drawWithContent {
                            // Records the reach as each frame of its animation draws.
                            val drawn = activeExpansion.drawn
                            if (drawn != null) {
                                drawn.reach = reachBox.value
                                drawn.grew(reachBox.value)
                            }
                            drawContent()
                        }
                        .then(if (!expansionOpen) Modifier.clearAndSetSemantics {} else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .wrapContentSize(Alignment.TopCenter, unbounded = true)
                            .reachFade(
                                reach = { if (reduceMotion) reducedReach else reachBox.value },
                                range = ComposerExpansion.contentFade,
                                showsAbove = true,
                                blurs = !reduceMotion,
                                // A fully faded view keeps a trace under a finger, so the drag on its grabber lasts.
                                minimum = if (activeExpansion.isTracking) 0.02f else 0f,
                            ),
                    ) { activeExpansion.content() }
                }
            }
        },
        measurePolicy = surfaceMeasurePolicy(extent = extent),
    )
}

private enum class SurfaceSlot { backdrop, glass, content, panel, expansion }

private class ExpansionMotion {
    var previousTarget = 0f
    var settle = 0
    var motionEnds = Double.NEGATIVE_INFINITY
}

/** Laid out as the bar alone: what grows above it never moves the session's insets. */
private fun surfaceMeasurePolicy(extent: () -> Float) = MeasurePolicy { measurables, constraints ->
    val bar = measurables.first { it.layoutId == SurfaceSlot.content }.measure(constraints.copy(minHeight = 0))
    val width = bar.width
    // A self-animating panel reports its current height during measurement. Read the
    // surface extent afterwards so its outline does not trail that panel by one frame.
    val panel = measurables.firstOrNull { it.layoutId == SurfaceSlot.panel }
        ?.measure(Constraints(minWidth = width, maxWidth = width))
    val extentPx = extent().dp.roundToPx()
    val placeables = measurables.filter { it.layoutId != SurfaceSlot.content }.map { measurable ->
        val c = when (measurable.layoutId) {
            SurfaceSlot.glass -> Constraints.fixed(width, bar.height + extentPx)
            // At its own height (it caps itself): [panelExtent] is the room the session keeps for it.
            SurfaceSlot.panel -> Constraints(minWidth = width, maxWidth = width)
            SurfaceSlot.expansion -> Constraints.fixed(width, bar.height + extentPx)
            else -> Constraints()
        }
        measurable.layoutId to (if (measurable.layoutId == SurfaceSlot.panel) panel!! else measurable.measure(c))
    }
    layout(width, bar.height) {
        for ((id, placeable) in placeables) {
            when (id) {
                SurfaceSlot.backdrop -> placeable.place((width - placeable.width) / 2, (bar.height - placeable.height) / 2)
                SurfaceSlot.glass -> placeable.place(0, -extentPx)
                else -> Unit
            }
        }
        bar.place(0, 0)
        for ((id, placeable) in placeables) {
            when (id) {
                // No height of its own: it stands up from the bar's top edge.
                SurfaceSlot.panel -> placeable.place(0, -placeable.height)
                SurfaceSlot.expansion -> placeable.place(0, -extentPx)
                else -> Unit
            }
        }
    }
}

/**
 * A rounded rectangle whose top edge sits [inset] dp below its frame's: the surface growing or
 * folding over a panel without anything being laid out. Used to clip, it also passes touches in
 * the unrevealed room through.
 */
class TopInsetRect(private val inset: Float, private val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val top = min(inset * density.density, size.height)
        val radius = min(cornerRadius * density.density, min(size.width, size.height - top) / 2).coerceAtLeast(0f)
        return Outline.Rounded(RoundRect(0f, top, size.width, size.height, CornerRadius(radius)))
    }

    override fun equals(other: Any?) = other is TopInsetRect && other.inset == inset && other.cornerRadius == cornerRadius
    override fun hashCode() = inset.hashCode() * 31 + cornerRadius.hashCode()
}

/** The bottom [height] dp of a frame, its top corners rounded: a panel revealed from the bar up. */
private class BottomRevealShape(private val height: Float, private val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = min(height * density.density, size.height)
        val top = size.height - h
        val r = min(cornerRadius * density.density, h / 2).coerceAtLeast(0f)
        // Below the fold's rounded edge the sides stay open: a panel's control reaching past its
        // sides onto the surface's corner arc (the request panel's ×) isn't cut in half.
        val path = Path().apply {
            addRoundRect(RoundRect(0f, top, size.width, size.height, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
            if (h > r) addRect(Rect(-size.width, top + r, size.width * 2, size.height))
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is BottomRevealShape && other.height == height && other.cornerRadius == cornerRadius
    override fun hashCode() = height.hashCode() * 31 + cornerRadius.hashCode()
}

/**
 * What sits above the composer (the pills, the background-work capsule, a draft's projects)
 * hides while a panel grown out of the composer (the model list, the level picker) stands over
 * it: gone as the surface grows over it, and back on the frame the surface starts folding.
 */
fun Modifier.hiddenUnderComposerPanel(isHidden: Boolean): Modifier = this then HiddenElement(isHidden, HiddenKind.composerPanel)

/**
 * The composer itself, while the Living Orb's card stands over the screen's foot: it fades on the
 * composer panel's curves and stops taking touches.
 */
fun Modifier.hiddenUnderFloatingCall(isHidden: Boolean): Modifier = this then HiddenElement(isHidden, HiddenKind.floatingCall)

@Suppress("EnumEntryName")
private enum class HiddenKind { composerPanel, floatingCall }

private data class HiddenElement(val isHidden: Boolean, val kind: HiddenKind) : ModifierNodeElement<HiddenNode>() {
    override fun create() = HiddenNode(isHidden, kind)
    override fun update(node: HiddenNode) = node.update(isHidden, kind)
    override fun InspectorInfo.inspectableProperties() {
        name = when (kind) {
            HiddenKind.composerPanel -> "hiddenUnderComposerPanel"
            HiddenKind.floatingCall -> "hiddenUnderFloatingCall"
        }
        properties["isHidden"] = isHidden
    }
}

private class HiddenNode(var isHidden: Boolean, var kind: HiddenKind) :
    Modifier.Node(), LayoutModifierNode, SemanticsModifierNode, PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private val opacity = Animatable(if (isHidden) 0f else 1f)

    fun update(hidden: Boolean, newKind: HiddenKind) {
        kind = newKind
        if (hidden == isHidden) return
        isHidden = hidden
        invalidateSemantics()
        val spec = spec(hidden)
        coroutineScope.launch { opacity.animateTo(if (hidden) 0f else 1f, spec) }
    }

    private fun spec(hiding: Boolean): AnimationSpec<Float> {
        val context = currentValueOf(LocalContext)
        val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        // A swap with the surface: out as it starts to grow, in as it starts to fold, on the same
        // frame, never waiting for it.
        return Motion.swap.animation(if (hiding) MotionPhase.Exit else MotionPhase.Enter, reduceMotion)
    }

    override fun androidx.compose.ui.layout.MeasureScope.measure(measurable: androidx.compose.ui.layout.Measurable, constraints: Constraints): androidx.compose.ui.layout.MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { alpha = opacity.value }
        }
    }

    override val shouldClearDescendantSemantics: Boolean get() = isHidden

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (isHidden) hideFromAccessibility()
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (isHidden && pass == PointerEventPass.Initial) pointerEvent.changes.forEach { it.consume() }
    }

    override fun onCancelPointerInput() = Unit
}

/** Lays out [wide] when it fits the width, else [narrow]. */
@Composable
private fun ViewThatFitsHorizontally(modifier: Modifier = Modifier, wide: @Composable () -> Unit, narrow: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val wideMeasurables = subcompose("wide", wide)
        val intrinsic = wideMeasurables.sumOf { it.maxIntrinsicWidth(constraints.maxHeight.coerceAtMost(Constraints.Infinity - 1)) }
        val fits = constraints.maxWidth == Constraints.Infinity || intrinsic <= constraints.maxWidth
        val chosen = if (fits) wideMeasurables else subcompose("narrow", narrow)
        val placeables = chosen.map { it.measure(constraints.copy(minHeight = 0)) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), h.coerceAtLeast(constraints.minHeight)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * Shown above the composer when the connection was lost and couldn't be restored. Rejoin
 * starts a new call that remembers this one.
 */
@Composable
fun VoiceCallEnded(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ComposerMetrics.cornerRadius.dp)
    Box(
        modifier
            .fillMaxWidth()
            .callSurface(shape, VoiceCallSurfaceDefaults.color, VoiceCallSurfaceDefaults.border)
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        val title = @Composable { Text(stringResource(R.string.voice_call_ended_title), style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)) }
        val actions = @Composable {
            OpenCodeSecondaryButton(stringResource(CoreR.string.common_close), onClick = { store.send(VoiceFeature.Action.EndPressed) })
            OpenCodePrimaryButton(stringResource(R.string.voice_call_ended_rejoin), onClick = { store.send(VoiceFeature.Action.RejoinTapped) })
        }
        ViewThatFitsHorizontally(
            wide = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    title()
                    Box(Modifier.weight(1f))
                    actions()
                }
            },
            narrow = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    title()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
                }
            },
        )
    }
}

/**
 * Shown above the composer when the call couldn't connect: the provider that couldn't start it,
 * one sentence and one fix ([VoiceProviderFailureCard]); for Grok, which only runs on this phone's
 * xAI key, the error as it came, with Check API Key (or Open Settings when the microphone was
 * denied) and Try Again.
 */
@Composable
fun VoiceCallFailure(store: VoiceStoreOf, message: String, modifier: Modifier = Modifier) {
    val diagnosis by store.observe { it.failureDiagnosis }
    val brand by store.observe { it.callPlace?.brand }
    val current = diagnosis
    if (current != null) {
        VoiceProviderFailureCard(
            diagnosis = current,
            brand = brand,
            onFix = { store.send(VoiceFeature.Action.FailureFixTapped(current.fix)) },
            onClose = { store.send(VoiceFeature.Action.EndPressed) },
            modifier = modifier,
        )
    } else {
        VoiceCallFailurePlainCard(store, message, modifier)
    }
}

@Composable
private fun VoiceCallFailurePlainCard(store: VoiceStoreOf, message: String, modifier: Modifier) {
    val isMicrophoneDenied = message == VoiceAudioError.microphoneDenied.message
    val shape = RoundedCornerShape(OpenCodeRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            .callSurface(shape, VoiceCallSurfaceDefaults.color, VoiceCallSurfaceDefaults.border)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon(SFSymbol.ExclamationmarkTriangleFill, null, tint = OpenCodeTheme.colors.warning, size = 18.dp)
                Text(
                    stringResource(R.string.voice_failure_title),
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.warning,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            VoiceFailureCloseButton({ store.send(VoiceFeature.Action.EndPressed) })
        }
        SelectionContainer {
            Text(voiceFailureDisplayText(message), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        }
        val actions = @Composable {
            if (isMicrophoneDenied) {
                OpenCodeSecondaryButton(stringResource(CoreR.string.common_open_settings), onClick = { store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.openSettings)) })
            } else {
                OpenCodeSecondaryButton(stringResource(R.string.voice_failure_check_api_key), onClick = { store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.setUpOnIPhone)) })
            }
            OpenCodePrimaryButton(stringResource(CoreR.string.common_try_again), onClick = { store.send(VoiceFeature.Action.TryAgainTapped) })
        }
        ViewThatFitsHorizontally(
            wide = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() } },
            narrow = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() } },
        )
    }
}
