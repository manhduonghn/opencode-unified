package ai.opencode.feature.settings

import ai.opencode.core.diagnostics.HidesFromProblemReports
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.PageTransitions
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.LocalScreenCovered
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings opens full screen over the drawer and the session with its own stack, keeps what's
 * beneath from being touched while it's up, and goes back to what was showing (the open drawer
 * exactly as it was). Home stays composed underneath, never replaced by a navigation push, so a
 * session's work never restarts on the way back.
 *
 * Settings comes and goes on Material's shared axis ([PageTransitions]), and back at its root is
 * Android's predictive back preview. On a pushed page the stack's own back pops it first
 * ([SettingsView]'s `NavDisplay`). Under reduce motion it cross-fades in place.
 *
 * Home (`:feature:drawer`) wraps its content with this and passes Settings' content.
 *
 * While Settings covers Home completely and at rest ([SettingsCover]), what's beneath reads
 * [LocalScreenCovered] as true and pauses its running animations.
 *
 * @param isShown
 * @param atRoot whether Settings shows its root page (`path.isEmpty()`): only then does the back
 *   gesture close Settings.
 * @param onClose the back gesture committed (send `SettingsFeature.Action.BackTapped`, which closes
 *   at the root via `SettingsDelegate.Close`).
 * @param settings Settings itself ([SettingsView]); kept composed while it leaves.
 * @param cover Home can hold it to read [SettingsCover.covers] itself.
 * @param content what's beneath (Home).
 */
@Composable
fun SettingsPresentation(
    isShown: Boolean,
    atRoot: Boolean,
    onClose: () -> Unit,
    settings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    cover: SettingsCover = remember { SettingsCover() },
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    // 0 closed, 1 open.
    val progress = remember { Animatable(if (isShown) 1f else 0f) }
    // How far the back gesture has previewed closing: 0 none, 1 all the way.
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var closing by remember { mutableStateOf(false) }
    var isMounted by remember { mutableStateOf(isShown) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val shown by rememberUpdatedState(isShown)
    // A cancelled back swipe springs home on the release role.
    val springBack = MotionRole.Release.animation<Float>()
    val density = LocalDensity.current
    val slidePx = with(density) { PageTransitions.SlideDistance.toPx() }
    val leanPx = with(density) { PageTransitions.BackPreviewShift.toPx() }
    val cornerPx = with(density) { PageTransitions.BackPreviewCorner.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    LaunchedEffect(isShown, reduceMotion) {
        if (isShown) {
            // Mounted first, then brought in, so the page moves rather than appearing in place.
            focus.clearFocus(force = true)
            closing = false
            isMounted = true
            backProgress.snapTo(0f)
            // Opens Settings fully; Home counts as covered once it lands.
            val move = cover.moving()
            progress.animateTo(1f, if (reduceMotion) FadeSpec else MoveSpec)
            if (shown) cover.landedOpen(move)
        } else if (isMounted) {
            cover.moving()
            closing = true
            progress.animateTo(0f, if (reduceMotion) FadeSpec else MoveSpec)
            isMounted = false
            backProgress.snapTo(0f)
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (reduceMotion) return@graphicsLayer
                    val p = progress.value
                    val back = backProgress.value
                    if (back > 0f) {
                        // The back gesture shows Home behind Settings, coming back from the start.
                        alpha = 1f
                        translationX = -slidePx * p * (1 - back) * direction
                    } else {
                        alpha = homeAlpha(p, closing)
                        translationX = -slidePx * p * direction
                    }
                }
                .then(if (isMounted) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            CompositionLocalProvider(LocalScreenCovered provides (cover.covers || LocalScreenCovered.current)) {
                content()
            }
        }
        if (isMounted) {
            // Keeps what's beneath from being touched while Settings is up.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                    .clearAndSetSemantics {},
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = progress.value
                        if (reduceMotion) {
                            alpha = p
                            return@graphicsLayer
                        }
                        val back = backProgress.value
                        alpha = settingsAlpha(p, closing)
                        // Shrinks, rounds and leans toward the swipe while the back gesture previews closing.
                        val scale = 1f - (1f - PageTransitions.BACK_PREVIEW_SCALE) * back
                        scaleX = scale
                        scaleY = scale
                        val lean = if (backEdge == BackEventCompat.EDGE_LEFT) leanPx else -leanPx
                        translationX = slidePx * (1 - p) * direction + lean * back
                        shape = RoundedCornerShape(cornerPx * back)
                        clip = back > 0f
                    },
            ) {
                // Settings shows server credentials.
                HidesFromProblemReports()
                settings()
            }
            // Registered with Settings' content, each time Settings mounts, so it is the newest
            // back handler: it takes the gesture over anything Home registered beneath (the
            // session's stack, the drawer), whose handlers would otherwise win and close Settings
            // without following the finger. Settings' own stack (composed just above) handles
            // pushed pages; this one is enabled only at the root. It stays registered while
            // Settings leaves, so a commit never restarts it mid-gesture.
            PredictiveBackHandler(enabled = isShown && atRoot) { events ->
                try {
                    events.collect { event ->
                        // Home shows under the finger: its animations run again.
                        if (event.progress > 0f) cover.moving()
                        backEdge = event.swipeEdge
                        if (!reduceMotion) backProgress.snapTo(event.progress)
                    }
                    // Committed: close from where the finger left it (isShown → false animates out).
                    onClose()
                } catch (cancelled: CancellationException) {
                    // Cancelled: spring back open, covered again once it settles.
                    val move = cover.moving()
                    scope.launch {
                        backProgress.animateTo(0f, springBack)
                        if (shown) cover.landedOpen(move)
                    }
                    throw cancelled
                }
            }
        }
    }
}

/**
 * Home's opacity at [p] (0 closed, 1 open): it fades out over the first part of opening, and back
 * in over the last part of closing, after Settings has gone (Material's fade-through).
 */
private fun homeAlpha(p: Float, closing: Boolean): Float {
    val split = PageTransitions.FADE_SPLIT
    val fraction = if (closing) 1f - p / (1f - split) else 1f - p / split
    return fraction.coerceIn(0f, 1f)
}

/** Settings' opacity at [p]: in after Home has faded while opening, out first while closing. */
private fun settingsAlpha(p: Float, closing: Boolean): Float {
    val split = PageTransitions.FADE_SPLIT
    val fraction = if (closing) (p - (1f - split)) / split else (p - split) / (1f - split)
    return fraction.coerceIn(0f, 1f)
}

/** Material's shared-axis move (built per use so Developer Mode slow motion stretches it). */
private val MoveSpec: AnimationSpec<Float>
    get() = tween(Motion.millis(PageTransitions.DURATION_SECONDS), easing = FastOutSlowInEasing)

/** The cross-fade under reduce motion: the surface role's reduced fade. */
private val FadeSpec: AnimationSpec<Float> get() = MotionRole.Surface.animation(reduceMotion = true)

/**
 * Whether Settings or What’s New covers Home completely and at rest, which pauses the running
 * animations beneath ([LocalScreenCovered]). Follows the presentation itself, not
 * `isSettingsShown`: uncovered the moment it moves (closing, a back swipe under the finger),
 * covered again only when a move that ends fully open lands (the push, a swipe springing back).
 */
class SettingsCover {
    private var coversState by mutableStateOf(false)

    /** Read in composition, so what's beneath follows it. */
    var covers: Boolean
        get() = coversState
        private set(value) {
            if (value == coversState) return
            coversState = value
            onChange(value)
        }

    /** Told each time [covers] changes. */
    var onChange: (Boolean) -> Unit = {}
    private var move = 0

    /** The presentation starts moving: uncovered until this move lands open. */
    fun moving(): Int {
        move += 1
        covers = false
        return move
    }

    /** [move] ended fully open; any move since then wins. */
    fun landedOpen(move: Int) {
        if (move == this.move) covers = true
    }
}
