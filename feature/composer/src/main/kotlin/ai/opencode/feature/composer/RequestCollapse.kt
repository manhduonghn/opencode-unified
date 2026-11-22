package ai.opencode.feature.composer

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.motion.Motion
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object RequestCollapseTunables {
    val threshold = Tunable("requests.collapse.threshold", "Collapse past", default = 0.3, range = 0.1..0.8, step = 0.05)
    /** dp per second. */
    val flick = Tunable("requests.collapse.flick", "Flick speed", default = 700.0, range = 200.0..2000.0, step = 50.0)
}

/**
 * A docked question dragged down to its bar (0 open, 1 down to the bar), as pure arithmetic for the
 * dock and its tests.
 */
object RequestCollapse {
    /** Where a drag that started at [start] and has moved [translation] (down is positive) holds the fold, over [travel]. */
    fun progress(start: Float, translation: Float, travel: Float): Float {
        if (travel <= 1f) return start
        return min(max(start + translation / travel, 0f), 1f)
    }

    /** The finger has gone far enough that letting go there changes it. */
    fun isPast(progress: Float, startedCollapsed: Boolean, threshold: Float): Boolean =
        if (startedCollapsed) progress < 1f - threshold else progress > threshold

    /** Where a let-go settles: a flick goes its way; otherwise past the threshold changes it and short of it snaps back. */
    fun settlesCollapsed(progress: Float, velocity: Float, startedCollapsed: Boolean, threshold: Float, flick: Float): Boolean {
        if (velocity > flick) return true
        if (velocity < -flick) return false
        return isPast(progress, startedCollapsed, threshold) != startedCollapsed
    }

    /**
     * A settle's spring carrying on at the finger's speed, as a fraction of the distance left
     * per second, clamped. The same value moves the panel and its glass, so they stay together.
     */
    fun settleVelocity(velocity: Float, from: Float, to: Float, travel: Float): Float {
        val distance = (to - from) * travel
        if (abs(distance) <= 1f) return 0f
        return min(max(velocity / distance, -12f), 12f)
    }

    /** “2 questions left”: the active fields without an answer. */
    fun remaining(form: OpenCodeFormRequest, answers: Map<String, OpenCodeFormValue>): Int {
        var count = 0
        for (field in form.activeFields(answers)) {
            if (!RequestDockAnswer.hasAnswer(answers[field.key])) count += 1
        }
        return count
    }
}

/**
 * What the dock's fold tells the session's surface, kept without being observed: while the finger
 * holds the panel, and while it springs to rest after, the panel's height moves frame by frame and
 * the surface follows it frame by frame (no easing of its own), so the two never ride separate
 * curves.
 */
class RequestPanelGesture {
    /** A finger holds the panel. Cleared by [reset] too, should the drag's end never arrive. */
    var isTracking = false
    /** The fold as last drawn (mid-spring too), so a finger catching it takes it from where it shows. */
    var presented = 0f
    /** Pixels from open to the bar. */
    var travel = 0f
    private var settleTarget: Float? = null
    private var settleReached = false
    private var settleUntilNanos = 0L

    /** The fold is springing to neither end: a finger may catch it either way. */
    val isMidway: Boolean get() = presented > 0.01f && presented < 0.99f

    /** The docked request changed or went: no drag or settle carries over. */
    fun reset() {
        isTracking = false
        settleTarget = null
    }

    /** The fold is springing to [target]; a deadline ends it should the last frame never come. */
    fun settling(target: Float) {
        settleTarget = target
        settleReached = false
        settleUntilNanos = System.nanoTime() + SETTLE_DEADLINE_NANOS
    }

    /** The layout drew the fold at [progress]. */
    fun drew(progress: Float) {
        presented = progress
        if (progress == settleTarget) settleReached = true
    }

    /**
     * The surface follows the panel's height as it is, without easing: the fold is moving it.
     * The settle's last frame is followed too, then a plain change eases as before.
     */
    fun takeFollows(): Boolean {
        if (isTracking) return true
        if (settleTarget == null) return false
        val now = System.nanoTime()
        if (settleReached || now > settleUntilNanos) settleTarget = null
        return now <= settleUntilNanos
    }

    private companion object {
        const val SETTLE_DEADLINE_NANOS = 1_200_000_000L
    }
}

/** The open panel and its bar cross-fade over the middle of the fold. */
object RequestCollapseFade {
    fun opacity(progress: Float, isBar: Boolean, window: ClosedFloatingPointRange<Float> = 0.2f..0.8f): Float {
        val clamped = min(max(progress, 0f), 1f)
        val span = max(window.endInclusive - window.start, 0.01f)
        val bar = min(max((clamped - window.start) / span, 0f), 1f)
        return if (isBar) bar else 1f - bar
    }
}

/**
 * Lays the open panel out whole and reports a height between it and its [bar]'s ([progress] 0 to
 * 1), both top-aligned so the header stays in place as it folds. The progress is read in layout, so
 * a drag or settle moves no composition. Clipped to its height, and at the sides only at the
 * glass's edges ([RequestDockMetrics.edge]), where × and the scrolling bands reach. The side the
 * fold rests on ([collapsed]) is placed on top, so only it takes touches.
 */
@Composable
internal fun RequestCollapseLayout(
    progress: () -> Float,
    gesture: RequestPanelGesture,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    open: @Composable () -> Unit,
    bar: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(open, bar),
        modifier = modifier.drawWithContent {
            val overhang = RequestDockMetrics.edge.toPx()
            clipRect(left = -overhang, top = 0f, right = size.width + overhang, bottom = size.height) {
                this@drawWithContent.drawContent()
            }
        },
    ) { (openMeasurables, barMeasurables), constraints ->
        val width = constraints.maxWidth
        val opens = openMeasurables.map { it.measure(Constraints(maxWidth = width, maxHeight = constraints.maxHeight)) }
        val bars = barMeasurables.map { it.measure(Constraints(maxWidth = width)) }
        var full = 0
        for (placeable in opens) full = max(full, placeable.height)
        if (constraints.hasBoundedHeight) full = min(full, constraints.maxHeight)
        var barHeight = full
        if (bars.isNotEmpty()) {
            barHeight = 0
            for (placeable in bars) barHeight = max(barHeight, placeable.height)
        }
        val shown = min(max(progress(), 0f), 1f)
        gesture.travel = max(full - barHeight, 0).toFloat()
        gesture.drew(shown)
        val height = max((full - shown * (full - barHeight)).roundToInt(), 0)
        // The side it rests on is on top, so it alone takes touches.
        val openZ = if (collapsed) 0f else 1f
        layout(width, height) {
            for (placeable in opens) placeable.place(0, 0, zIndex = openZ)
            for (placeable in bars) placeable.place(0, 0, zIndex = 1f - openZ)
        }
    }
}

/**
 * The docked question's fold as it moves: a vertical drag on the panel follows the finger, and a
 * downward drag in a list already at its top folds it too (nested scroll, as Material's sheets do);
 * let go, it settles on the spring, carrying the finger's speed, folded or open, and tells the
 * reducer.
 */
internal class RequestFold(initial: Float) {
    val progress = Animatable(initial)
    var drag: CollapseDrag? = null
    private var translation = 0f
    private var gesture = RequestPanelGesture()
    private var scope: CoroutineScope? = null
    private var haptics: HapticsClient? = null
    private var density = 1f
    private var reducedMotion = false
    private var send: (RequestDockFeature.Action) -> Unit = {}
    private var collapsedIDs: () -> Set<String> = { emptySet() }

    class CollapseDrag(val start: Float, val startedCollapsed: Boolean) {
        var isPast = false
    }

    /** What the fold reads and tells, as of this composition. */
    fun bind(
        gesture: RequestPanelGesture,
        scope: CoroutineScope,
        haptics: HapticsClient,
        density: Float,
        reducedMotion: Boolean,
        send: (RequestDockFeature.Action) -> Unit,
        collapsedIDs: () -> Set<String>,
    ) {
        this.gesture = gesture
        this.scope = scope
        this.haptics = haptics
        this.density = density
        this.reducedMotion = reducedMotion
        this.send = send
        this.collapsedIDs = collapsedIDs
    }

    /** A finger on the panel moved [delta] pixels (down is positive): the fold follows it. */
    fun dragged(formID: String, delta: Float) {
        var current = drag
        if (current == null) {
            current = CollapseDrag(start = gesture.presented, startedCollapsed = formID in collapsedIDs())
            drag = current
            translation = 0f
            gesture.isTracking = true
        }
        translation += delta
        val next = RequestCollapse.progress(current.start, translation, gesture.travel)
        val past = RequestCollapse.isPast(next, current.startedCollapsed, RequestCollapseTunables.threshold.value.toFloat())
        if (past != current.isPast) {
            current.isPast = past
            haptics?.play(Haptic.threshold)
        }
        scope?.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(next) }
    }

    /** The finger let go at [velocity] pixels per second: the fold settles where it points. */
    fun ended(formID: String, velocity: Float) {
        val current = drag ?: return
        val collapsed = RequestCollapse.settlesCollapsed(
            progress = progress.value,
            velocity = velocity / density,
            startedCollapsed = current.startedCollapsed,
            threshold = RequestCollapseTunables.threshold.value.toFloat(),
            flick = RequestCollapseTunables.flick.value.toFloat(),
        )
        drag = null
        settle(formID, collapsed, velocity)
    }

    /** Folds or opens the docked question on the release spring, which the session's surface rides too. */
    fun settle(formID: String, collapsed: Boolean, velocity: Float = 0f) {
        gesture.isTracking = false
        animate(if (collapsed) 1f else 0f, velocity)
        send(RequestDockFeature.Action.CollapseChanged(requestID = formID, collapsed = collapsed))
    }

    /** The reducer folded or opened it (a failed send opens it): it springs there unless a finger holds it. */
    fun follow(collapsed: Boolean) {
        if (drag != null) return
        val target = if (collapsed) 1f else 0f
        if (progress.targetValue == target) return
        animate(target, 0f)
    }

    private fun animate(target: Float, velocity: Float) {
        val scope = scope ?: return
        val from = progress.value
        if (from != target) gesture.settling(target)
        // Let go, it carries the finger's speed on the release spring: a fraction of the
        // distance per second, back in progress per second for the Animatable.
        val fraction = Motion.release.initialVelocity(RequestCollapse.settleVelocity(velocity, from, target, gesture.travel), reducedMotion)
        val initialVelocity = fraction * (target - from)
        scope.launch {
            progress.animateTo(target, Motion.release.animation(reduceMotion = reducedMotion), initialVelocity = initialVelocity)
        }
    }

    /** The panel's drag, and a list's overscroll at its top, folding it. */
    @Composable
    fun dragModifier(formID: String, enabled: Boolean): Modifier {
        val state = rememberDraggableState { delta -> dragged(formID, delta) }
        val connection = remember(formID) { FoldScroll(formID) }
        return Modifier
            .nestedScroll(connection)
            .draggable(
                state,
                Orientation.Vertical,
                enabled = enabled,
                startDragImmediately = progress.isRunning,
                onDragStopped = { velocity -> ended(formID, velocity) },
            )
    }

    private inner class FoldScroll(val formID: String) : NestedScrollConnection {
        var nested = false

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!nested || source != NestedScrollSource.UserInput) return Offset.Zero
            dragged(formID, available.y)
            return Offset(0f, available.y)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (nested || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            if (formID in collapsedIDs()) return Offset.Zero
            nested = true
            dragged(formID, available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!nested) return Velocity.Zero
            nested = false
            ended(formID, available.y)
            return available
        }
    }
}
