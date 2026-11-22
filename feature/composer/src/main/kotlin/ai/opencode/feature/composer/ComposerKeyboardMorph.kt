package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.roundToInt

/** iOS `ComposerTunables.keyboard*` (Composer › Keyboard), declared beside the morph as on iOS. */
object ComposerKeyboardTunables {
    /** iOS `ComposerTunables.keyboardChipTravel`: how far (dp) the row's controls rise as they reveal. */
    val keyboardChipTravel = Tunable("composer.keyboard.chipTravel", "Model reveal travel", default = 6f, range = 0.0..16.0, step = 0.5)

    /** iOS `ComposerTunables.keyboardChipBlur`: the controls' blur (dp) at the start of their reveal. */
    val keyboardChipBlur = Tunable("composer.keyboard.chipBlur", "Model reveal blur", default = 3f, range = 0.0..4.0, step = 0.25)
}

/**
 * iOS `ComposerKeyboardPose`: what the composer's row is doing besides following the keyboard.
 * [pinned] (0…1) is how far a draft, a panel or focus holds the row open.
 */
@Immutable
data class ComposerKeyboardPose(
    val expanded: Boolean = false,
    /** Under Reduce Motion, [expanded] as the layout switches to it. */
    val layoutExpanded: Boolean = false,
    val pinned: Float = 0f,
    val reducedMotion: Boolean = false,
) {
    /** The row's expansion for the keyboard's [progress]. */
    fun expansion(progress: Float): Float =
        if (reducedMotion) (if (layoutExpanded) 1f else 0f) else max(pinned, progress)
}

/**
 * iOS `ComposerKeyboardProgress`: how far the docked keyboard is up, 0…1, frame by frame. iOS
 * samples a marker on the keyboard layout guide with a display link and rides the keys' curve
 * itself for an animated change; Android's `WindowInsets.ime` already moves with the keys
 * (interactive dismissal and reversals included), so [progress] reads it directly. Read it in
 * layout or drawing so a frame only re-lays out what follows the keyboard.
 *
 * The keys leave with the app and come back with it: in the background the row holds where it
 * was. While the card lays out without the keyboard (`excludesKeyboard`) the composer rests as
 * if it were down, in the same frame.
 */
@OptIn(ExperimentalLayoutApi::class)
@Stable
class ComposerKeyboardProgress internal constructor(
    private val density: State<Density>,
    private val isActive: State<Boolean>,
    private val excluded: State<Boolean>,
    private val ime: WindowInsets,
    private val source: WindowInsets,
    private val target: WindowInsets,
    private val navigation: WindowInsets,
) {
    /** The keyboard's full height above the navigation bar, the last time it was up. */
    private var travel = 0f
    private var held = 0f

    val progress: Float
        get() {
            val density = density.value
            val excluded = excluded.value
            val active = isActive.value
            val bottom = navigation.getBottom(density).toFloat()
            val height = max(0f, ime.getBottom(density) - bottom)
            val full = max(source.getBottom(density), target.getBottom(density)) - bottom
            if (full > 0f) travel = full
            if (travel <= 1f && height > 0f) travel = height
            if (!active) return held
            val next = if (travel > 1f && !excluded) (height / travel).coerceIn(0f, 1f) else 0f
            held = next
            return next
        }

    /**
     * Riding the keyboard's own animation (iOS `isAnimating`), not resting or following a finger.
     * Resting on the target is never animating: Compose can leave the source behind after an
     * animation the app controlled (the drawer's keyboard hold), and the row's controls would
     * stay mounted over the field, their touch gate swallowing its taps, for good.
     */
    val isAnimating: Boolean
        get() {
            val density = density.value
            val target = target.getBottom(density)
            return !excluded.value && source.getBottom(density) != target && ime.getBottom(density) != target
        }
}

/** Remembers this screen's [ComposerKeyboardProgress]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberComposerKeyboardProgress(): ComposerKeyboardProgress {
    val density = rememberUpdatedState(LocalDensity.current)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isActive = rememberUpdatedState(lifecycle.isAtLeast(Lifecycle.State.RESUMED))
    val excluded = rememberUpdatedState(LocalDrawerKeyboardSignals.current?.excludesKeyboard == true)
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    val navigation = WindowInsets.navigationBars
    return remember(ime, source, target, navigation) {
        ComposerKeyboardProgress(density, isActive, excluded, ime, source, target, navigation)
    }
}

/**
 * The keyboard morph a composer's pieces read (iOS's `ComposerKeyboardProgress` environment
 * object with the `composerKeyboardPose` environment value).
 */
@Stable
class ComposerKeyboardMorphState internal constructor(
    val keyboard: ComposerKeyboardProgress,
    private val poseState: State<ComposerKeyboardPose>,
) {
    val pose: ComposerKeyboardPose get() = poseState.value

    /** The row's expansion now. Read it in layout or drawing (it changes every keyboard frame). */
    val expansion: Float get() = pose.expansion(keyboard.progress)
}

/** The surrounding [ComposerKeyboardMorph], or null outside one. */
val LocalComposerKeyboardMorph = staticCompositionLocalOf<ComposerKeyboardMorphState?> { null }

/**
 * iOS `ComposerKeyboardMorph`: only this subtree follows the keyboard's frames. Focus is an
 * intent, not a layout clock: it changes before the keys show and after an interactive hide.
 * Gaining focus opens the row on the keyboard's spring from the same frame, whether or not the
 * keyboard follows yet (it lags behind a sheet's dismissal); [pinned] (a draft or a panel holding
 * the row open) moves it on the keyboard's curve too. With [snapsUnpin] the row closes at once
 * when [pinned] ends: the card's own size animation carries the change instead.
 */
@Composable
fun ComposerKeyboardMorph(
    focused: Boolean,
    expanded: Boolean,
    pinned: Boolean,
    reducedMotion: Boolean,
    snapsUnpin: Boolean = false,
    content: @Composable () -> Unit,
) {
    val keyboard = rememberComposerKeyboardProgress()
    val excludes by rememberUpdatedState(LocalDrawerKeyboardSignals.current?.excludesKeyboard == true)
    val focusProgress = remember { Animatable(0f) }
    val pinnedProgress = remember { Animatable(if (pinned) 1f else 0f) }
    var reducedExpanded by remember { mutableStateOf(if (reducedMotion) expanded else null) }
    val seen = remember { booleanArrayOf(focused, pinned) }
    val snapsUnpinNow by rememberUpdatedState(snapsUnpin)

    LaunchedEffect(focused) {
        if (seen[0] == focused) return@LaunchedEffect
        seen[0] = focused
        if (reducedMotion) return@LaunchedEffect
        if (focused) {
            val spec = if (keyboard.progress > 0.99f) null else DockedKeyboard.focusAnimation(false, excludes)
            focusProgress.settle(1f, spec)
        } else {
            // Leaving, the keyboard's own frames close the row (a drag follows the finger):
            // hand over without a jump.
            focusProgress.snapTo(0f)
        }
    }
    LaunchedEffect(keyboard) {
        // Up with the keyboard, its frames own the row again.
        snapshotFlow { keyboard.progress > 0.99f }.distinctUntilChanged().collect { up ->
            if (up) focusProgress.snapTo(0f)
        }
    }
    LaunchedEffect(expanded, reducedMotion) {
        // Under Reduce Motion the layout switches at once.
        reducedExpanded = if (reducedMotion) expanded else null
    }
    LaunchedEffect(pinned) {
        if (seen[1] == pinned) return@LaunchedEffect
        seen[1] = pinned
        // Already open with the keyboard: nothing moves.
        val spec = if (pinned && keyboard.progress > 0.99f) {
            null
        } else if (!pinned && snapsUnpinNow) {
            null
        } else {
            DockedKeyboard.focusAnimation(reducedMotion, excludes)
        }
        pinnedProgress.settle(if (pinned) 1f else 0f, spec)
    }

    val expandedNow by rememberUpdatedState(expanded)
    val reducedNow by rememberUpdatedState(reducedMotion)
    val pose = remember {
        derivedStateOf {
            ComposerKeyboardPose(
                expanded = expandedNow,
                layoutExpanded = if (reducedNow) reducedExpanded ?: expandedNow else expandedNow,
                pinned = max(pinnedProgress.value, focusProgress.value),
                reducedMotion = reducedNow,
            )
        }
    }
    val state = remember(keyboard) { ComposerKeyboardMorphState(keyboard, pose) }
    CompositionLocalProvider(LocalComposerKeyboardMorph provides state) { content() }
}

private suspend fun Animatable<Float, *>.settle(to: Float, spec: FiniteAnimationSpec<Float>?) {
    if (spec == null) snapTo(to) else animateTo(to, spec)
}

/**
 * iOS `KeyboardDrivenComposerLayout`/`KeyboardDrivenTextInset`: the row's expansion frame by
 * frame for the composer's layout and the field's top inset (`topInsetFraction`), 0 inline
 * (answering a request) or outside a morph.
 */
fun ComposerKeyboardMorphState?.layoutProgress(inline: Boolean): Float =
    if (inline || this == null) 0f else expansion

/**
 * iOS `ComposerKeyboardSpacing`: the composer's top padding (`innerVertical`, taken back as the
 * row opens unless folded for dictation, [fold] 0…1 animated with the fold) or its bottom gap
 * (`bottomGap` resting, `keyboardGap` above the keys, on the keyboard's progress). [sides] adds
 * side margins that go from `rest` to `keyboard` on the keyboard's progress, like the bottom gap;
 * null adds none. Measured frame by frame in layout, so the composer's size
 * follows without recomposing.
 */
fun Modifier.composerKeyboardSpacing(
    morph: ComposerKeyboardMorphState?,
    top: Boolean,
    innerVertical: Dp,
    bottomGap: Dp,
    keyboardGap: Dp,
    fold: () -> Float = { 0f },
    sides: ComposerSideMargins? = null,
): Modifier = layout { measurable, constraints ->
    val progress = morph?.keyboard?.progress ?: 0f
    val pad = if (top) {
        innerVertical.toPx() * (1 - (morph?.expansion ?: 0f) * (1 - fold()))
    } else {
        bottomGap.toPx() + (keyboardGap.toPx() - bottomGap.toPx()) * progress
    }.roundToInt().coerceAtLeast(0)
    var side = 0
    if (sides != null) {
        val rest = sides.rest.toPx()
        val keyboard = sides.keyboard.toPx()
        side = (rest + (keyboard - rest) * progress).roundToInt().coerceAtLeast(0)
    }
    val placeable = measurable.measure(constraints.offset(horizontal = -side * 2, vertical = -pad))
    val width = (placeable.width + side * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = (placeable.height + pad).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) { placeable.place(side, if (top) pad else 0) }
}

/** The composer area's side margins at rest and with the keyboard up (iOS `sides`). */
data class ComposerSideMargins(val rest: Dp, val keyboard: Dp)

/**
 * iOS `ComposerKeyboardReveal`: the row's controls fade, blur and rise their travel in with the
 * row's expansion, frame by frame (focus or a draft opening the row rises them too, rather than
 * appearing in place). Collapsed and settled they leave composition, and with it TalkBack; they
 * take touches and TalkBack only once the row is nearly open. [recording] fades them out for the
 * dictation bar on [fade]. Under Reduce Motion they only fade, on `Motion.reduced`, and stay
 * drawn until that fade completes.
 */
@Composable
fun ComposerKeyboardReveal(
    morph: ComposerKeyboardMorphState,
    recording: Boolean,
    modifier: Modifier = Modifier,
    fade: FiniteAnimationSpec<Float>? = null,
    content: @Composable () -> Unit,
) {
    val pose = morph.pose
    // Under Reduce Motion, collapsing: the controls are still fading out (iOS `leaving`),
    // cleared when the fade completes rather than on a timer.
    var fadingOut by remember { mutableStateOf(false) }
    val open by remember(morph) { derivedStateOf { morph.expansion > 0f } }
    val nearlyOpen by remember(morph) { derivedStateOf { morph.expansion > 0.95f } }
    val animating by remember(morph) { derivedStateOf { morph.keyboard.isAnimating } }
    val mounted = pose.expanded || open || animating || fadingOut
    val recordingAlpha = remember { Animatable(if (recording) 0f else 1f) }
    LaunchedEffect(recording) { recordingAlpha.settle(if (recording) 0f else 1f, fade) }
    // Under Reduce Motion the expansion switches at once; the controls fade to it.
    val reducedAlpha = remember { Animatable(if (pose.expansion(morph.keyboard.progress) > 0f) 1f else 0f) }
    LaunchedEffect(pose.reducedMotion, pose.layoutExpanded) {
        if (!pose.reducedMotion) {
            fadingOut = false
            return@LaunchedEffect
        }
        val shown = pose.layoutExpanded
        fadingOut = !shown
        reducedAlpha.animateTo(if (shown) 1f else 0f, Motion.reduced.animation(reduceMotion = true))
        fadingOut = false
    }
    val interactive = pose.expanded && nearlyOpen && !recording
    // iOS `.allowsHitTesting(pose.expanded && progress > 0.95 && !recording)`. Shut, the field has
    // the controls' place, so they let its touches through (still drawn while they fade out under
    // Reduce Motion). Visible on their way in or out they keep their place, as the model picker's
    // flights read their frames, and only swallow touches.
    val gate = when {
        interactive -> Modifier
        !open -> Modifier.clearAndSetSemantics { }.passesTouchesThrough()
        else -> Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
    val reduced = pose.reducedMotion
    val blurPx = with(LocalDensity.current) { ComposerKeyboardTunables.keyboardChipBlur.value.dp.toPx() }
    Box(
        modifier
            .graphicsLayer {
                val progress = if (reduced) reducedAlpha.value else morph.expansion
                alpha = progress * recordingAlpha.value
                translationY = if (reduced) 0f else (1 - progress) * ComposerKeyboardTunables.keyboardChipTravel.value.dp.toPx()
            }
            .transitionBlur {
                if (reduced) 0f else (1 - morph.expansion) * blurPx
            }
            .then(gate),
    ) {
        if (mounted) content()
    }
}

/** Far enough that no touch on any screen lands on what's moved there; exact as a float. */
private const val PASS_THROUGH_OFFSET = 1 shl 15

/**
 * iOS `.allowsHitTesting(false)`: what's inside draws where it is but takes no touches, so they
 * reach whatever lies under it, even in a sibling subtree. Compose hit-tests by placement, and a
 * child it hits ends the search among its siblings whether or not it handles the touch, so the
 * content is placed far off and drawn back in place.
 *
 * Why not drop the content's pointer modifiers instead: Compose has no hit-test opt-out, and a
 * disabled `clickable` is still a pointer node that's hit. The row's controls are other modules'
 * components (the model chips' drag and scrub `pointerInput`s and taps, the agent chip's menu
 * anchor, Material buttons inside them), each of which would need its own way to leave its pointer
 * nodes out of the chain. Used only while the row is shut (expansion 0), where nothing reads the
 * controls' frames: they're mounted then only while the keys still settle or, under Reduce Motion
 * (no flights), fade out.
 */
internal fun Modifier.passesTouchesThrough(): Modifier = this
    .drawWithContent { translate(left = PASS_THROUGH_OFFSET.toFloat()) { this@drawWithContent.drawContent() } }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(-PASS_THROUGH_OFFSET, 0) }
    }
