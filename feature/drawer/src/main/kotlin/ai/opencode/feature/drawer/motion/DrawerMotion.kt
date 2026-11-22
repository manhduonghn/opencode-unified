package ai.opencode.feature.drawer.motion

import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionRelease
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.feature.drawer.list.DrawerListShortcuts
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Port of `OpenCode/Drawer/DrawerMotion.swift`: the card that recedes. Plain state holders own
 * every per-frame value (iOS keeps them in UIKit outside SwiftUI); Compose reads them only in
 * layout/draw lambdas, so a drag never recomposes the session or the drawer list. The reducer hears only settled
 * open/shut ([DrawerMotionState.Callbacks.onArrived]).
 *
 * Units: iOS points map 1:1 to dp. Every distance in this file is in dp, velocities in dp/s.
 */

/**
 * The drawer's motion tunables (iOS `DrawerTunables` in `DrawerMotion.swift`, Developer Mode →
 * Tuning Panel). Baked iOS defaults; the Tuning Panel backing is `:devtools`. Named apart from
 * the `DrawerTunables` of `DrawerStyle.swift` (metrics), which another file owns.
 */
object DrawerMotionTunables {
    /** List recede scale (iOS `drawer.depth.recedeScale`, 0.96). */
    const val RECEDE_SCALE = 0.96f
    /** List dim, dark (iOS `drawer.dim.dark`, 0.7). */
    const val DIM_DARK = 0.7f
    /** List dim, light (iOS `drawer.dim.light`, 0.6). */
    const val DIM_LIGHT = 0.6f
    /** List dim falloff power (iOS `drawer.dim.power`, 1.3). */
    const val DIM_POWER = 1.3f
    /** Shortcut exit scale (iOS `drawer.shortcuts.scale`, 0.96). */
    const val SHORTCUT_SCALE = 0.96f
    /** Shortcut exit delay after a page close (iOS `drawer.shortcuts.closeDelay`, 0.12 s). */
    const val SHORTCUT_CLOSE_DELAY = 0.12
    /**
     * Android only: how much of the drawer's width the card travels back toward shut while a
     * predictive back gesture is held (at full progress). The commit then closes it the rest
     * of the way; a cancel springs it open again.
     */
    const val BACK_PREVIEW_TRAVEL = 0.25f
}

/**
 * What the Catch Up row keeps while it folds away: the count it showed, and when the drawer first
 * showed Catch Up finished.
 */
@Stable
class DrawerShortcutMotion {
    var displayedCatchUpCount: Int = 1
        private set
    var displayedCanMarkAllRead: Boolean = false
        private set

    /**
     * When the drawer first showed Catch Up finished (seconds on the `System.nanoTime()` clock,
     * the frame clock's base): the row's clock, kept until new unread puts the count back.
     */
    var finishStart: Double? by mutableStateOf(null)
        private set

    /** The finish the row has played; search, a hidden drawer or a fold resume its clock, never restart it. */
    private var playedFinish: Int? = null

    fun sync(shortcuts: DrawerListShortcuts, searchIsEmpty: Boolean, isDrawerVisible: Boolean) {
        if (shortcuts.catchUpCount > 0) {
            displayedCatchUpCount = shortcuts.catchUpCount
            displayedCanMarkAllRead = shortcuts.canMarkAllRead
        } else {
            displayedCanMarkAllRead = false
        }
        if (shortcuts.catchUpCount > 0) finishStart = null
        val finish = shortcuts.catchUpFinish ?: return
        if (finish == playedFinish || !searchIsEmpty || !isDrawerVisible) return
        playedFinish = finish
        finishStart = System.nanoTime() / 1e9
    }
}

@Composable
fun rememberDrawerShortcutMotion(): DrawerShortcutMotion = remember { DrawerShortcutMotion() }

/**
 * The drawer's settle (iOS `DrawerSpring`): a critically damped spring (no bounce) stepped each
 * frame from the finger's release velocity. It arrives (within 1 dp of the edge) about 0.28 s
 * from rest across the whole drawer and sooner from a flick or a short way, then settles a few
 * frames later. Positions in dp, velocity in dp/s.
 */
class DrawerSpring(position: Double, var velocity: Double, target: Double, val bounds: ClosedFloatingPointRange<Double>) {
    enum class Event {
        /** First within [ARRIVAL_TOLERANCE] of the edge: what the eye reads as shut or open. Haptics play earlier, at the release. */
        Arrived,
        /** At rest on the edge; the spring is done. */
        Settled,
    }

    var position: Double = position.coerceIn(bounds.start, bounds.endInclusive)
    val target: Double = target.coerceIn(bounds.start, bounds.endInclusive)
    var hasArrived: Boolean = false
        private set

    /** Advances [dt] seconds with the exact critically damped solution; stops on the edge rather than passing it. */
    fun step(dt: Double): List<Event> {
        val events = ArrayList<Event>(2)
        val x0 = position - target
        val (value, nextVelocity) = SpringSolver.evaluate(
            from = position,
            to = target,
            initialVelocity = velocity,
            omega = OMEGA,
            zeta = 1.0,
            elapsed = dt,
        )
        var x = value - target
        var v = nextVelocity
        if ((x0 != 0.0 && x * x0 < 0) || (target + x) !in bounds) {
            // A hard flick reaches the edge before the spring would, or a release right at the
            // edge still moves outwards: stop on the edge.
            x = 0.0
            v = 0.0
        }
        position = target + x
        velocity = v
        if (!hasArrived && abs(x) <= ARRIVAL_TOLERANCE) {
            hasArrived = true
            events += Event.Arrived
        }
        if (hasArrived && abs(x) < 0.25 && abs(v) < 8) {
            position = target
            velocity = 0.0
            events += Event.Settled
        }
        return events
    }

    companion object {
        /** `MotionRole.Release`'s natural frequency (`releaseOmega`): from rest, 330 dp arrives in about 0.28 s. */
        val OMEGA: Double get() = MotionRole.Release.curve().springParameters.omega
        const val ARRIVAL_TOLERANCE = 1.0
    }
}

/** The drawer list under the card is darker the more shut it is, clearing a little faster than linearly (iOS `DrawerDim`). */
object DrawerDim {
    fun alpha(progress: Float, maximum: Float): Float {
        val closed = 1 - progress.coerceIn(0f, 1f)
        return maximum * closed.toDouble().pow(DrawerMotionTunables.DIM_POWER.toDouble()).toFloat()
    }

    /** Black over the list when the drawer is shut. A black dark-mode list only shows it in its text, so dark goes deeper. */
    val color: Color = Color.Black

    fun maximum(isDark: Boolean): Float = if (isDark) DrawerMotionTunables.DIM_DARK else DrawerMotionTunables.DIM_LIGHT
}

/** The list recedes a little behind the closing card, on the same progress as the finger and spring (iOS `DrawerDepth`). */
object DrawerDepth {
    fun scale(progress: Float, reducedMotion: Boolean): Float {
        if (reducedMotion) return 1f
        val closedScale = DrawerMotionTunables.RECEDE_SCALE
        return closedScale + (1 - closedScale) * progress.coerceIn(0f, 1f)
    }
}

/**
 * The session card's corners while the drawer moves (iOS `DrawerCorner`): the display's own
 * corner radius from the first frame of a drag or an opening until the card settles shut, so the
 * card reads as the screen sliding aside.
 *
 * iOS looks the radius up from a table of iPhone screen sizes; Android reports it
 * ([RoundedCorner], API 31+), so [displayRadius] reads the window's bottom corners.
 */
object DrawerCorner {
    fun radius(isMoving: Boolean, offset: Float, display: Float): Float =
        if (isMoving || offset > 0) display else 0f

    /** The window's bottom corner radius in px (0 on a square display). */
    fun displayRadiusPx(view: android.view.View): Int {
        val insets = view.rootWindowInsets ?: return 0
        val left = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        val right = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
        return max(left, right)
    }
}

/** The display's corner radius in dp ([DrawerCorner.displayRadiusPx]). */
@Composable
fun rememberDisplayCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) { with(density) { DrawerCorner.displayRadiusPx(view).toDp() } }
}

/**
 * The card's offset and the drawer's open state, owned outside composition (iOS
 * `SessionDrawerViewController`'s drag, settle and `apply(offset:)`).
 *
 * - The pan feeds [beginDrag] / [drag] / [endDrag] / [cancelDrag].
 * - The drawer button calls [setPresented]; a tap on the card or Escape calls [close].
 * - Release: one commit rule (`MotionRelease.shouldCommit`): a flick decides, otherwise the
 *   halfway mark; the card then
 *   rides [DrawerSpring] from the finger's velocity. Reduce Motion places it without moving.
 * - [Callbacks.onCommitted] fires when a release or tap commits to the other side (play
 *   `Haptic.snap`); programmatic closes (a row, New Session) stay quiet, as on iOS.
 * - [Callbacks.onArrived] fires the frame the card first lands on the far edge (send the
 *   reducer `sessionDrawerArrived(isOpen:)`); a reversal before then never arrives.
 *
 * Everything per frame ([offset], [progress], [isCardMoving]) is snapshot state read in
 * layout/draw lambdas, so dragging never recomposes the card's content. Distances in dp.
 */
@Stable
class DrawerMotionState(
    private val scope: CoroutineScope,
    initiallyPresented: Boolean = false,
    drawerWidth: Float = 0f,
) {
    /** What the container hears (iOS `SessionDrawerContainer`'s closures). */
    class Callbacks(
        /** The drawer visually arrived open (`true`) or shut, away from where it last rested. */
        val onArrived: (Boolean) -> Unit = {},
        /** A release or tap commits to the other side: play `Haptic.snap` now. */
        val onCommitted: () -> Unit = {},
        /** A touch that may move the drawer began: `haptics.prepare(.snap)`. */
        val onTouchDown: () -> Unit = {},
        /** The open/shut decision changed (iOS `onPresentationChanged`, the `isPresented` binding). */
        val onPresentationChanged: (Boolean) -> Unit = {},
        /** How far the drawer is open (0..1), each frame the card moves (the Call Tray follows it). */
        val onProgress: (Float) -> Unit = {},
        /**
         * The card starts moving away from shut or rests open: it stops taking touches (iOS
         * `suspendContent`: end the drawer's search, drop focus unless held).
         */
        val onSuspendContent: () -> Unit = {},
        /** The card settled shut (`true`: takes touches again, restore or let go of the keyboard). */
        val onContentInteractive: (Boolean) -> Unit = {},
    )

    var callbacks: Callbacks = Callbacks()

    /** Optional keyboard hold driven from the same points iOS drives it. */
    var keyboardHold: DrawerKeyboardHold? = null

    /** Whether a shut card about to move may hold its keyboard (iOS `holdKeyboardIfShut`'s guards: phone, no Catch Up, no pushed subagent). */
    var canHoldKeyboard: () -> Boolean = { true }

    /** Reduce Motion: settles place the card without moving, and the list doesn't recede. */
    var reducedMotion: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // iOS `update(...)`: a spring in flight lands at once.
            if (value && job != null) settle(if (isPresented) width else 0f, animated = false)
        }

    /** Only a phone's drawer recedes (iOS `recedes`). */
    var recedes: Boolean = true

    var frameDurationSeconds: Double = 1.0 / 60.0

    private val offsetState = mutableFloatStateOf(if (initiallyPresented) drawerWidth else 0f)
    private val widthState = mutableFloatStateOf(drawerWidth)
    private val movingState = mutableStateOf(false)
    private val draggingState = mutableStateOf(false)
    private val presentedState = mutableStateOf(initiallyPresented)
    private val drawerAvailableState = mutableStateOf(initiallyPresented)
    private val contentSuspendedState = mutableStateOf(initiallyPresented)

    /** The card's leading offset in dp (0 shut, [width] open). */
    val offset: Float get() = offsetState.floatValue

    /** The drawer's width in dp. */
    val width: Float get() = widthState.floatValue

    /** How far the drawer is open (0..1). */
    val progress: Float get() = if (width > 0) (offset / width).coerceIn(0f, 1f) else 0f

    /** The card is under the finger or on its spring (corners rounded). */
    val isCardMoving: Boolean get() = movingState.value

    /** iOS `isCardMoving`'s `didSet`: Session Switch waits for the card to rest. */
    private var cardMoving: Boolean
        get() = movingState.value
        set(value) {
            if (value == movingState.value) return
            movingState.value = value
            PerfTimeline.cardMoving(value)
        }

    /** Where the drawer is headed (iOS `isPresented`). */
    val isPresented: Boolean get() = presentedState.value

    /** The drawer is composed and takes touches (iOS `setDrawerAvailable`); compose it lazily while false. */
    val isDrawerAvailable: Boolean get() = drawerAvailableState.value

    /** The card doesn't take touches (moving or resting open). */
    val isContentSuspended: Boolean get() = contentSuspendedState.value

    /** The scrim over the card takes taps (iOS `dimmingView.isUserInteractionEnabled`). Changes only at the edges. */
    val isScrimInteractive: State<Boolean> = derivedStateOf { progress > 0 }

    /** The card's content is hidden from TalkBack while the drawer is open or being dragged open. */
    val isContentHiddenFromAccessibility: State<Boolean> = derivedStateOf {
        if (draggingState.value) progress > 0f else presentedState.value
    }

    /** The drawer is hidden from TalkBack while shut or closing. */
    val isDrawerHiddenFromAccessibility: State<Boolean> = derivedStateOf {
        if (draggingState.value) progress == 0f else !presentedState.value
    }

    /** The open drawer is modal for TalkBack (iOS `accessibilityViewIsModal`). */
    val isModal: State<Boolean> = derivedStateOf { progress >= 1f }

    private var restsOpen = initiallyPresented
    private var dragStartOffset = 0f
    private var spring: DrawerSpring? = null
    private var job: Job? = null
    private var isDragging: Boolean
        get() = draggingState.value
        set(value) {
            draggingState.value = value
        }
    private var ignoresDragUntilItEnds = false

    /**
     * The width changed (rotation, window resize): keep the progress (iOS `update(width:)`).
     * [catchingUp]: Catch Up owns the card's pose, so a card at rest isn't resettled.
     */
    fun updateWidth(newWidth: Float, catchingUp: Boolean = false) {
        if (newWidth == width) return
        val hadWidth = width > 0
        val wasAnimating = spring != null
        val currentOffset = stopAnimationAtCurrentOffset()
        val progress = if (width > 0) (currentOffset / width).coerceIn(0f, 1f) else 0f
        widthState.floatValue = newWidth
        dragStartOffset = newWidth * progress
        apply(dragStartOffset)
        val target = if (isPresented) newWidth else 0f
        if (isDragging) {
            // iOS re-enables the recognizer, which cancels the pan.
            ignoresDragUntilItEnds = true
            isDragging = false
            cancelDrag()
        } else if (wasAnimating) {
            settle(target, animated = true)
        } else if (catchingUp) {
            // The first width while catching up: the card takes the model's pose, and Catch Up
            // keeps its touches.
            if (!hadWidth) apply(target)
        } else if (abs(dragStartOffset - target) > 0.5f) {
            // At rest the card sits where the model says: a drawer opened before it had a width
            // (none to scale from) lands open now.
            val open = isPresented
            AppLog.app.notice { "drawer_pose_resettled reason=width_changed open=$open" }
            settle(target, animated = false)
        }
    }

    /** The pan's touch-down (warm the snap haptic). */
    fun touchDown() = callbacks.onTouchDown()

    fun beginDrag() {
        ignoresDragUntilItEnds = false
        isDragging = true
        dragStartOffset = stopAnimationAtCurrentOffset()
        cardMoving = true
        holdKeyboardIfShut()
        // The card moves again: the keys come back up with it.
        keyboardHold?.bringKeysBack()
        suspendContent()
    }

    /** The card stays under the finger: the same distance, the same frame. [translation] in dp since touch-down. */
    fun drag(translation: Float) {
        if (ignoresDragUntilItEnds || !isDragging) return
        apply((dragStartOffset + translation).coerceIn(0f, width))
    }

    /** Release at [velocity] dp/s (positive toward open). */
    fun endDrag(velocity: Float) {
        if (consumeIgnoredEnd()) return
        isDragging = false
        val presented = MotionRelease.shouldCommit(translation = offset.toDouble(), velocity = velocity.toDouble(), distance = width.toDouble())
        val changed = presented != isPresented
        presentedState.value = presented
        if (changed) callbacks.onCommitted()
        callbacks.onPresentationChanged(presented)
        settle(if (presented) width else 0f, animated = true, velocity = velocity)
    }

    fun cancelDrag() {
        if (consumeIgnoredEnd()) return
        isDragging = false
        settle(if (isPresented) width else 0f, animated = true)
    }

    /**
     * A predictive back gesture committed: the card closes from wherever the gesture left it,
     * however far that was (Android only; a finger pan decides with [endDrag]).
     */
    fun endBackDrag() {
        if (consumeIgnoredEnd()) return
        isDragging = false
        close()
    }

    /**
     * A predictive back gesture on the shut card committed (Android only): the drawer
     * opens from wherever the gesture left the card, as the drawer button opens it.
     */
    fun endBackOpen() {
        if (consumeIgnoredEnd()) return
        isDragging = false
        val changed = !isPresented
        presentedState.value = true
        if (changed) callbacks.onCommitted()
        callbacks.onPresentationChanged(true)
        settle(width, animated = true)
    }

    /**
     * The drawer button opens it (plays [Callbacks.onCommitted]); a programmatic close is a row,
     * New Session, Catch Up or What’s New taking the card back, and stays quiet.
     */
    fun setPresented(presented: Boolean, animated: Boolean = true) {
        if (presented == isPresented) return
        presentedState.value = presented
        if (presented) callbacks.onCommitted()
        if (!presented) keyboardHold?.setDrawerSearching(false)
        settle(if (presented) width else 0f, animated)
    }

    /** Closing by hand: a tap on the card, Escape, back (iOS `closeDrawer`). Returns whether it closed anything. */
    fun close(): Boolean {
        if (!isPresented && offset <= 0f) return false
        val changed = isPresented
        presentedState.value = false
        if (changed) callbacks.onCommitted()
        callbacks.onPresentationChanged(false)
        settle(0f, animated = true)
        return true
    }

    /** Hands the card from the finger (or a tap) to the spring, which keeps the finger's velocity. */
    fun settle(target: Float, animated: Boolean, velocity: Float = 0f) {
        stopAnimationAtCurrentOffset()
        if (target > 0) holdKeyboardIfShut() else keyboardHold?.bringKeysBack()
        if (!animated || reducedMotion) {
            cardMoving = false
            apply(target)
            setContentInteractive(target == 0f)
            setDrawerAvailable(target > 0)
            arrive(target > 0)
            if (target > 0) keyboardHold?.sendKeysAway()
            return
        }
        if (target > 0) {
            suspendContent()
            setDrawerAvailable(true)
        }
        cardMoving = true
        // Rounded from this frame, before the spring's first step.
        apply(offset)
        val drawerSpring = DrawerSpring(offset.toDouble(), velocity.toDouble(), target.toDouble(), 0.0..max(width, 0f).toDouble())
        spring = drawerSpring
        job = scope.launch {
            motionFrameLoop(frameDurationSeconds) { tick ->
                val events = drawerSpring.step(tick.dt)
                val open = drawerSpring.target > 0
                val settled = Event.Settled in events
                if (settled) cardMoving = false
                apply(drawerSpring.position.toFloat().coerceIn(0f, width))
                if (Event.Arrived in events) arrive(open)
                // Resting open, the keys step out of the drawer's way.
                if (open && (Event.Arrived in events || settled)) keyboardHold?.sendKeysAway()
                if (settled) {
                    spring = null
                    job = null
                    setContentInteractive(!open)
                    setDrawerAvailable(open)
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    private fun consumeIgnoredEnd(): Boolean {
        if (!ignoresDragUntilItEnds) return false
        ignoresDragUntilItEnds = false
        return true
    }

    private fun holdKeyboardIfShut() {
        val hold = keyboardHold ?: return
        if (!recedes || offset != 0f || !canHoldKeyboard() || isContentSuspended) return
        hold.begin()
    }

    private fun suspendContent() {
        // A drag closing the drawer takes the keyboard from its search too.
        keyboardHold?.setDrawerSearching(false)
        contentSuspendedState.value = true
        callbacks.onSuspendContent()
    }

    private fun setContentInteractive(interactive: Boolean) {
        if (!interactive) return suspendContent()
        keyboardHold?.endDrawerSearch()
        contentSuspendedState.value = false
        callbacks.onContentInteractive(true)
    }

    private fun setDrawerAvailable(available: Boolean) {
        if (drawerAvailableState.value != available) drawerAvailableState.value = available
    }

    private fun arrive(open: Boolean) {
        if (open == restsOpen) return
        restsOpen = open
        callbacks.onArrived(open)
    }

    private fun stopAnimationAtCurrentOffset(): Float {
        job?.cancel()
        job = null
        spring = null
        return offset
    }

    private fun apply(newOffset: Float) {
        val progress = if (width > 0) (newOffset / width).coerceIn(0f, 1f) else 0f
        if (progress > 0) setDrawerAvailable(true)
        offsetState.floatValue = newOffset
        callbacks.onProgress(progress)
        keyboardHold?.follow(progress)
    }
}

private typealias Event = DrawerSpring.Event

/** A [DrawerMotionState] on this composition's frame clock. Set [DrawerMotionState.updateWidth] from layout. */
@Composable
fun rememberDrawerMotionState(initiallyPresented: Boolean = false): DrawerMotionState {
    val scope = rememberCoroutineScope()
    val frame = rememberFrameDurationSeconds()
    return remember(scope) { DrawerMotionState(scope, initiallyPresented) }.also { it.frameDurationSeconds = frame }
}
