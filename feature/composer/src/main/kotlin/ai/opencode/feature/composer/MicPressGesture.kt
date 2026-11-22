package ai.opencode.feature.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private typealias HoldArm = DictationFeature.Hold.Arm

/**
 * Where a held mic's finger has gone. Sliding up arms lock and sliding left arms cancel, measured
 * from where the finger is now, so one drag can swing between them. A target arms once its progress
 * reaches 1 and leads the other's, and stays armed until the finger is [hysteresis] short of its
 * line or the other leads by [dominanceMargin]. Distances in dp.
 */
class DictationHoldTracker {
    /** Progress toward each target, 1 at its line. */
    data class Progress(val lock: Float = 0f, val cancel: Float = 0f) {
        companion object {
            fun of(translation: Offset) = Progress(
                lock = max(0f, -translation.y) / lockDistance,
                cancel = max(0f, -translation.x) / cancelDistance,
            )
        }
    }

    var armed: DictationFeature.Hold.Arm? = null
        private set
    var progress = Progress()
    var maxDistance = 0f
        private set

    fun update(translation: Offset) {
        maxDistance = max(maxDistance, hypot(translation.x, translation.y))
        progress = Progress.of(translation)
        armed?.let { if (holds(it)) return }
        armed = listOf(HoldArm.Lock, HoldArm.Cancel).filter(::arms).maxByOrNull(::progress)
    }

    private fun progress(arm: HoldArm): Float = if (arm == HoldArm.Lock) progress.lock else progress.cancel

    private fun distance(arm: HoldArm): Float = if (arm == HoldArm.Lock) lockDistance else cancelDistance

    private fun other(arm: HoldArm): HoldArm = if (arm == HoldArm.Lock) HoldArm.Cancel else HoldArm.Lock

    /** Reached its line and leads. */
    private fun arms(arm: HoldArm): Boolean = progress(arm) >= 1 && progress(arm) > progress(other(arm))

    /** Still near enough its line and not overtaken. */
    private fun holds(arm: HoldArm): Boolean =
        progress(arm) * distance(arm) >= distance(arm) - hysteresis &&
            progress(arm) + dominanceMargin >= progress(other(arm))

    fun isTap(afterSeconds: Double): Boolean = armed == null && afterSeconds < tapDuration && maxDistance < tapSlop

    enum class Ending {
        /** Short and still: locks hands-free. */
        Tap,

        /** Let go: sends, or commits what's armed. */
        Release,

        /** The system took the touch: never a send. */
        Interrupted,
    }

    fun ending(afterSeconds: Double, cancelled: Boolean): Ending = when {
        cancelled -> Ending.Interrupted
        isTap(afterSeconds) -> Ending.Tap
        else -> Ending.Release
    }

    companion object {
        const val lockDistance = 56f
        const val cancelDistance = 96f
        const val hysteresis = 9f

        /** How far an armed target's progress may trail the other's before it hands over. */
        const val dominanceMargin = 0.15f

        /** Shorter, stiller presses are taps. */
        const val tapDuration = 0.3
        const val tapSlop = 10f

        /**
         * How much each target's feedback shows for a finger at [translation]: all
         * lock straight up, all cancel straight left, blended between 30° and 60°
         * so a diagonal never switches the picture at once.
         */
        fun weights(translation: Offset): Progress {
            val up = max(0f, -translation.y)
            val left = max(0f, -translation.x)
            if (up + left <= 0f) return Progress(lock = 0.5f, cancel = 0.5f)
            val angle = Math.toDegrees(atan2(up, left).toDouble()).toFloat()
            val t = min(1f, max(0f, (angle - 30f) / 30f))
            val lock = t * t * (3 - 2 * t)
            return Progress(lock = lock, cancel = 1 - lock)
        }
    }
}

/** The finger's travel while holding the mic, in dp, for the bar to follow. */
data class DictationDrag(val translation: Offset = Offset.Zero)

/** What the mic's press reports. */
class MicPressCallbacks(
    val onBegan: () -> Unit,
    val onDrag: (DictationDrag) -> Unit,
    val onArmed: (DictationFeature.Hold.Arm?) -> Unit,
    val onReleased: () -> Unit,
    val onLocked: () -> Unit,
    /** The system took the touch, or the mic left the screen mid-press. */
    val onInterrupted: () -> Unit,
)

/**
 * One press at a time, from touch-down to its end: tap, release, or
 * interruption, reported exactly once. A press still down when the mic goes
 * away (the composer swapping it out) ends as an interruption.
 */
class MicPress(var callbacks: MicPressCallbacks) {
    private var tracker = DictationHoldTracker()
    var isPressing = false
        private set

    /** The finger landed at [uptimeMillis] (the pointer event's clock). */
    fun began(uptimeMillis: Long) {
        DictationLatency.touchDown(uptimeMillis)
        isPressing = true
        tracker = DictationHoldTracker()
        callbacks.onBegan()
    }

    fun moved(translation: Offset) {
        if (!isPressing) return
        val wasArmed = tracker.armed
        tracker.update(translation)
        callbacks.onDrag(DictationDrag(translation))
        if (tracker.armed != wasArmed) callbacks.onArmed(tracker.armed)
    }

    fun ended(afterSeconds: Double, cancelled: Boolean) {
        if (!isPressing) return
        end(tracker.ending(afterSeconds, cancelled))
    }

    /** The mic left the screen. */
    fun dispose() {
        if (isPressing) end(DictationHoldTracker.Ending.Interrupted)
    }

    private fun end(ending: DictationHoldTracker.Ending) {
        isPressing = false
        tracker = DictationHoldTracker()
        callbacks.onDrag(DictationDrag())
        when (ending) {
            DictationHoldTracker.Ending.Tap -> callbacks.onLocked()
            DictationHoldTracker.Ending.Release -> callbacks.onReleased()
            DictationHoldTracker.Ending.Interrupted -> callbacks.onInterrupted()
        }
    }
}

/**
 * The mic's press: it begins at touch-down with the touch's timestamp, so recording starts the
 * moment the finger lands; tap or hold is decided at the lift. Only the first finger counts, and a
 * touch a parent takes over or the system cancels ends as an interruption, never a send.
 * [isEnabled] is off while a recording is hands-free.
 */
@Composable
fun Modifier.micPressGesture(isEnabled: Boolean, callbacks: MicPressCallbacks): Modifier {
    val press = remember { MicPress(callbacks) }
    press.callbacks = callbacks
    DisposableEffect(press) { onDispose { press.dispose() } }
    return pointerInput(isEnabled) {
        if (!isEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            var cancelled = true
            var upTime = down.uptimeMillis
            try {
                press.began(down.uptimeMillis)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        upTime = change.uptimeMillis
                        val delta = (change.position - start) / density
                        press.moved(delta)
                        cancelled = false
                        break
                    }
                    // Someone else (a scroll) took the touch.
                    if (change.isConsumed) break
                    upTime = change.uptimeMillis
                    if (change.position != change.previousPosition) press.moved((change.position - start) / density)
                }
            } finally {
                press.ended((upTime - down.uptimeMillis) / 1000.0, cancelled)
            }
        }
    }
}
