package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.PageTransitions
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A screen pushed over the session (a subagent, or a web page), keyed by its identity. The page's
 * UI comes from [SessionPageStack]'s `content` slot, not from this value.
 *
 * @property id the page's identity; a page keeps its composition (scroll, tasks) while its id stays.
 * @property paneTitle What TalkBack announces when the stack comes to rest with this page on top;
 *     null announces nothing.
 */
@Stable
class SessionStackPage(
    val id: Int,
    val paneTitle: String? = null,
)

/**
 * Subagent (and browser) screens pushed over the session, like a navigation stack. Pushes and pops
 * use Material's shared axis X ([PageTransitions]); system back previews leaving the top page as
 * Settings does, springing back on cancel. Under Reduce Motion they cross-fade.
 *
 * Covered screens stay composed (each in its own
 * [androidx.compose.runtime.saveable.SaveableStateHolder] entry), so a web page and a transcript
 * keep their state and going back is instant. They're hidden from TalkBack and their back handlers
 * parked, so only the top screen handles system back.
 *
 * @param pages the screens over the root, bottom first. New ids push (the last with the push
 * motion), missing ids pop (the top with the pop motion), and pages that stay take the new value. A
 * page removed while it animates out is still drawn through [content] until it has left, so the
 * host must be able to draw a page that's no longer in its list.
 * @param onPop the reader went back until `depth` pages remain; the host removes the pages above
 * `depth` from its state. The page has already left.
 * @param modifier the stack's frame (the session card); pages fill it and are clipped to it.
 * @param enabled whether system back may pop (not under the open drawer or Catch Up).
 * @param rootPaneTitle what TalkBack announces when the stack comes back to rest on the root.
 * @param onTopChanged a push or pop moved the screen on top.
 * @param root the screen at the bottom (the session), always composed.
 * @param content draws a pushed page.
 */
@Composable
fun SessionPageStack(
    pages: List<SessionStackPage>,
    onPop: (depth: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rootPaneTitle: String? = null,
    onTopChanged: () -> Unit = {},
    root: @Composable () -> Unit,
    content: @Composable (page: SessionStackPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()
    val currentOnPop by rememberUpdatedState(onPop)
    val currentOnTopChanged by rememberUpdatedState(onTopChanged)
    val stack = remember { PageStackState(scope, pages) }
    SideEffect {
        stack.reduceMotion = reduceMotion
        stack.onPopped = { currentOnPop(it) }
        stack.onTopChanged = { currentOnTopChanged() }
        stack.onRemoved = { holder.removeState(it) }
        stack.clearFocus = { focusManager.clearFocus() }
    }
    LaunchedEffect(pages) { stack.update(pages) }

    PredictiveBackHandler(enabled = enabled && stack.pages.isNotEmpty() && stack.leaving == null) { events ->
        stack.backStarted()
        try {
            events.collect { event -> stack.backMoved(event.progress, event.swipeEdge) }
            stack.popTop()
        } catch (cancelled: CancellationException) {
            stack.backCancelled()
            throw cancelled
        }
    }

    val realOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inertOwner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val density = LocalDensity.current
    val slidePx = with(density) { PageTransitions.SlideDistance.toPx() }
    val leanPx = with(density) { PageTransitions.BackPreviewShift.toPx() }
    val cornerPx = with(density) { PageTransitions.BackPreviewCorner.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val split = PageTransitions.FADE_SPLIT
    val canvas = OpenCodeTheme.colors.canvas

    Box(modifier.clipToBounds()) {
        val mounted = stack.pages
        val count = mounted.size + 1
        for (index in 0 until count) {
            val page = if (index == 0) null else mounted[index - 1]
            val stateKey: Any = page?.id ?: ROOT_KEY
            key(stateKey) {
                val isTop = index == count - 1
                val isBeneath = index == count - 2
                val semantics = when {
                    !isTop -> Modifier.clearAndSetSemantics {}
                    stack.atRest -> (page?.paneTitle ?: rootPaneTitle)?.let { title -> Modifier.semantics { paneTitle = title } } ?: Modifier
                    else -> Modifier
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val travel = stack.travel
                            val back = stack.back
                            val popping = stack.leaving != null
                            if (isTop) {
                                if (stack.reduceMotion) {
                                    alpha = 1f - travel
                                    return@graphicsLayer
                                }
                                val scale = 1f - (1f - PageTransitions.BACK_PREVIEW_SCALE) * back
                                scaleX = scale
                                scaleY = scale
                                val lean = if (stack.backEdge == BackEventCompat.EDGE_LEFT) leanPx else -leanPx
                                translationX = slidePx * travel * direction + lean * back
                                shape = RoundedCornerShape(cornerPx * back)
                                clip = back > 0f
                                val fade = if (popping) 1f - travel / split else (1f - travel - split) / (1f - split)
                                alpha = fade.coerceIn(0f, 1f)
                            } else if (isBeneath && !stack.atRest) {
                                if (stack.reduceMotion) return@graphicsLayer
                                if (back > 0f) {
                                    translationX = -slidePx * (1f - back) * (1f - travel) * direction
                                } else {
                                    translationX = -slidePx * (1f - travel) * direction
                                    val fade = if (popping) (travel - split) / (1f - split) else 1f - (1f - travel) / split
                                    alpha = fade.coerceIn(0f, 1f)
                                }
                            } else {
                                alpha = 0f
                            }
                        }
                        .then(if (page != null) Modifier.background(canvas) else Modifier)
                        // Takes the hit so touches never fall through to a covered screen.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .then(semantics),
                ) {
                    val owner = if (isTop) realOwner else inertOwner
                    val provided = if (owner != null) arrayOf(LocalOnBackPressedDispatcherOwner provides owner) else emptyArray()
                    CompositionLocalProvider(*provided) {
                        holder.SaveableStateProvider(stateKey) {
                            if (page == null) root() else content(page)
                        }
                    }
                }
            }
        }
    }
}

private const val ROOT_KEY = "ai.opencode.session.stack.root"

/** Material's shared-axis move (built per use so Developer Mode slow motion stretches it). */
private val MoveSpec: AnimationSpec<Float>
    get() = tween(Motion.millis(PageTransitions.DURATION_SECONDS), easing = FastOutSlowInEasing)

/**
 * Which pages [SessionPageStack] composes and how far the top one has moved. A push or pop runs
 * one animation; a newer one lands it first.
 */
@Stable
private class PageStackState(private val scope: CoroutineScope, initial: List<SessionStackPage>) {
    /** The composed pages, bottom first, including one animating out. */
    var pages by mutableStateOf(initial)
        private set

    /** The top page while it animates out. */
    var leaving by mutableStateOf<SessionStackPage?>(null)
        private set

    /** The top page's shared-axis travel: 0 in place, 1 gone. */
    var travel by mutableFloatStateOf(0f)
        private set

    /** How far system back has previewed leaving the top page. */
    var back by mutableFloatStateOf(0f)
        private set

    var backEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        private set

    /** Only the top screen shows; nothing moves. */
    var atRest by mutableStateOf(true)
        private set

    var reduceMotion = false
    var onPopped: (Int) -> Unit = {}
    var onTopChanged: () -> Unit = {}
    var onRemoved: (Int) -> Unit = {}
    var clearFocus: () -> Unit = {}

    /** Pages the reader went back from, until the host's list drops them too. */
    private val popped = mutableSetOf<Int>()
    private var job: Job? = null
    private var landing: (() -> Unit)? = null

    fun update(items: List<SessionStackPage>) {
        popped.retainAll(items.map { it.id }.toSet())
        val wanted = items.filter { it.id !in popped }
        val settled = pages.filter { it !== leaving }
        if (settled.map { it.id } == wanted.map { it.id }) {
            pages = wanted + listOfNotNull(leaving)
            return
        }
        finish()
        var common = 0
        while (common < pages.size && common < wanted.size && pages[common].id == wanted[common].id) common++
        val gone = pages.drop(common)
        val arriving = wanted.drop(common)
        if (arriving.isEmpty()) {
            // Pages below the top that are going too leave at once; the top animates out.
            for (page in gone.dropLast(1)) onRemoved(page.id)
            pages = wanted + gone.last()
            pop(notify = false)
            return
        }
        for (page in gone) onRemoved(page.id)
        clearFocus()
        pages = wanted
        onTopChanged()
        travel = 1f
        run { animate(1f, 0f, animationSpec = spec()) { value, _ -> travel = value } }
    }

    /** System back committed (a gesture, a button or TalkBack): the top page leaves. */
    fun popTop() {
        val top = pages.lastOrNull() ?: return
        if (leaving != null) return
        popped.add(top.id)
        pop(notify = true)
    }

    fun backStarted() {
        finish()
        clearFocus()
        atRest = false
    }

    fun backMoved(progress: Float, edge: Int) {
        backEdge = edge
        if (!reduceMotion) back = progress
    }

    fun backCancelled() {
        if (job != null) {
            back = 0f
            return
        }
        run { animate(back, 0f, animationSpec = MotionRole.Release.animation()) { value, _ -> back = value } }
    }

    private fun pop(notify: Boolean) {
        val top = pages.last()
        leaving = top
        run(
            then = {
                leaving = null
                onRemoved(top.id)
                pages = pages.filter { it !== top }
                onTopChanged()
                if (notify) onPopped(pages.size)
            },
        ) {
            animate(0f, 1f, animationSpec = spec()) { value, _ -> travel = value }
        }
    }

    private fun spec(): AnimationSpec<Float> = if (reduceMotion) MotionRole.Surface.animation(reduceMotion = true) else MoveSpec

    /** Runs [move]; when it ends (or a newer move lands it), rests on the top page and calls [then]. */
    private fun run(then: () -> Unit = {}, move: suspend () -> Unit) {
        finish()
        atRest = false
        landing = then
        job = scope.launch {
            move()
            job = null
            land()
        }
    }

    private fun finish() {
        if (job == null && landing == null) return
        job?.cancel()
        job = null
        land()
    }

    private fun land() {
        val then = landing
        landing = null
        travel = 0f
        back = 0f
        atRest = true
        then?.invoke()
    }
}
