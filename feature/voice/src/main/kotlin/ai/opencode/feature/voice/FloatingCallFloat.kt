package ai.opencode.feature.voice

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Floaty: the resting orb's place, what a touch on it was, the space it may rest in, and its drift
// after a throw. Lengths in dp, speeds in dp a second, times in seconds. Plain values and objects,
// so the physics runs in JVM tests.

/** Where the orb's centre rests: null [x] is the trailing side, null [y] just above the composer, following it as the keyboard raises it. */
data class FloatingCallPlace(val x: Float? = null, val y: Float? = null)

/**
 * The floating call's screen, safe area and resting size, for working out where its orb rests from
 * outside its overlay.
 */
data class FloatingCallOrbLayout(val screen: Size, val safeArea: FloatingCallEdgeInsets, val size: Size)

/** The screen's safe area in dp, by side. */
data class FloatingCallEdgeInsets(val top: Float = 0f, val leading: Float = 0f, val bottom: Float = 0f, val trailing: Float = 0f)

/**
 * Where the orb may be on one screen: where its centre may rest (inside the side keep-outs and safe
 * areas, below the header, above the composer and what stands on it), and how far past that a throw
 * may carry it with the whole orb still on screen.
 */
data class FloatingCallRestArea(
    val screen: Size,
    val safeArea: FloatingCallEdgeInsets,
    /** The top of the composer and what stands above it, once laid out. */
    val composerTop: Float?,
    /** The resting orb's (or capsule's) size. */
    val size: Size,
    /**
     * The top of a keyboard the composer doesn't ride (the drawer's search, a sheet's field), in
     * this screen's coordinates: the orb rests above it.
     */
    val keyboardTop: Float? = null,
) {
    /** The bottom of the space the orb may rest in. */
    val bottom: Float
        get() {
            val composer = composerTop ?: (screen.height - 34 - ComposerMetrics.barHeight)
            return keyboardTop?.let { min(composer, it) } ?: composer
        }

    val bounds: Rect
        get() {
            val inset = FloatingCallTunables.sideInset.value.toFloat()
            val minX = max(inset, safeArea.leading) + size.width / 2
            val maxX = screen.width - max(inset, safeArea.trailing) - size.width / 2
            val minY = max(FloatingCallTunables.topLimit.value.toFloat(), safeArea.top) + size.height / 2
            val maxY = bottom - FloatingCallTunables.composerGap.value.toFloat() - size.height / 2
            val top = min(minY, maxY)
            return Rect(minX, top, minX + max(0f, maxX - minX), top + max(0f, maxY - minY))
        }

    companion object {
        /**
         * How far a session raises its transcript's end so the orb resting at [orb] covers none
         * of it: from the orb's top down to the composer's (`size + composerGap` at its rest).
         * None when the orb stands beside the composer's column (a wide pane) or well up over the
         * transcript, dragged there, where it rests over the text by choice.
         */
        fun clearance(orb: Rect, composer: Rect, gap: Float): Float {
            if (orb.width <= 0f || composer.width <= 0f) return 0f
            if (orb.right <= composer.left || orb.left >= composer.right) return 0f
            if (orb.bottom <= composer.top - gap - orb.height) return 0f
            return max(0f, composer.top - orb.top)
        }
    }

    val reach: Rect
        get() {
            val over = FloatingCallTunables.overshoot.value.toFloat()
            val screenArea = Rect(size.width / 2, size.height / 2, screen.width - size.width / 2, screen.height - size.height / 2)
            return bounds.inflate(over).intersect(screenArea)
        }

    fun frame(place: FloatingCallPlace): Rect {
        val area = bounds
        val x = FloatingCallDrift.clamp((place.x ?: area.right).toDouble(), area.left.toDouble(), area.right.toDouble())
        val y = FloatingCallDrift.clamp((place.y ?: area.bottom).toDouble(), area.top.toDouble(), area.bottom.toDouble())
        return frame(Offset(x.toFloat(), y.toFloat()))
    }

    fun frame(centeredAt: Offset): Rect =
        Rect(centeredAt.x - size.width / 2, centeredAt.y - size.height / 2, centeredAt.x + size.width / 2, centeredAt.y + size.height / 2)
}

/** The smallest rect holding both (`CGRect.union`). */
internal fun Rect.union(other: Rect): Rect =
    Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

/**
 * The floating call's screen and safe area as last laid out. A drift or a lift outlives the layout
 * pass that started it, so it reads its bounds here every frame: a rotation or resize mid-flight
 * moves them along.
 */
class FloatingCallLayout(var screen: Size = Size.Zero, var safeArea: FloatingCallEdgeInsets = FloatingCallEdgeInsets()) {
    /** The window's keyboard top, while one is up ([FloatingCallRestArea.keyboardTop]). */
    var keyboardTop: Float? = null
}

/**
 * What a touch on the resting orb was, judged when the finger lifts: the orb is under the finger
 * from touch-down, so a drag always moves it, and only a tap expands it. A tap on an orb still
 * drifting only catches it.
 */
@Suppress("EnumEntryName")
enum class FloatingCallTouch {
    tap, caught, drag;

    companion object {
        operator fun invoke(
            travel: Double,
            duration: Double,
            wasMoving: Boolean,
            slop: Double = FloatingCallTunables.tapSlop.value,
            time: Double = FloatingCallTunables.tapTime.value,
        ): FloatingCallTouch {
            if (!(travel < slop && duration <= time)) return drag
            return if (wasMoving) caught else tap
        }
    }
}

/**
 * Floaty: the orb glides on with a throw's speed and slows with friction; outside `bounds` (where
 * its centre may rest) a soft spring eases it back in, carrying the speed it has rather than
 * stopping hard. It rests where it slows. A hard throw runs on past the keep-out no further than
 * `reach` (the screen's edge): heading out, it's held to the speed the soft spring can stop within
 * what's left.
 */
class FloatingCallDrift(
    position: Offset,
    velocity: Offset = Offset.Zero,
    var friction: Double = FloatingCallTunables.friction.value,
    var edgeDuration: Double = Motion.layout.curve().duration,
    var edgeBounce: Double = Motion.layout.curve().bounce,
) {
    private var x = position.x.toDouble()
    private var y = position.y.toDouble()
    private var vx = velocity.x.toDouble()
    private var vy = velocity.y.toDouble()

    var position: Offset
        get() = Offset(x.toFloat(), y.toFloat())
        set(value) { x = value.x.toDouble(); y = value.y.toDouble() }

    var velocity: Offset
        get() = Offset(vx.toFloat(), vy.toFloat())
        set(value) { vx = value.x.toDouble(); vy = value.y.toDouble() }

    val speed: Double get() = hypot(vx, vy)

    /** Advances [dt] seconds in small fixed steps; true once it rests inside [bounds]. */
    fun step(dt: Double, bounds: Rect, reach: Rect? = null): Boolean {
        val limit = reach?.union(bounds)
        var left = dt
        while (left > 1e-6) {
            val h = min(1.0 / 480, left)
            left -= h
            substep(h, bounds, limit)
        }
        val inside = abs(x - clamp(x, bounds.left.toDouble(), bounds.right.toDouble())) < 0.05 &&
            abs(y - clamp(y, bounds.top.toDouble(), bounds.bottom.toDouble())) < 0.05
        if (!(speed < 1.5 && inside)) return false
        vx = 0.0
        vy = 0.0
        x = clamp(x, bounds.left.toDouble(), bounds.right.toDouble())
        y = clamp(y, bounds.top.toDouble(), bounds.bottom.toDouble())
        return true
    }

    private fun substep(dt: Double, bounds: Rect, reach: Rect?) {
        val k = exp(-friction * dt)
        vx *= k
        vy *= k
        val omega = 2 * PI / max(0.03, edgeDuration)
        val zeta = 1 - edgeBounce
        val cx = clamp(x, bounds.left.toDouble(), bounds.right.toDouble())
        val cy = clamp(y, bounds.top.toDouble(), bounds.bottom.toDouble())
        if (cx != x) vx += (-omega * omega * (x - cx) - 2 * zeta * omega * vx) * dt
        if (cy != y) vy += (-omega * omega * (y - cy) - 2 * zeta * omega * vy) * dt
        if (reach != null) {
            // A critically damped spring entering at v runs on v / (ω e) at most.
            val stoppable = omega * E
            if (vx < 0) vx = max(vx, -max(0.0, x - reach.left) * stoppable)
            if (vx > 0) vx = min(vx, max(0.0, reach.right - x) * stoppable)
            if (vy < 0) vy = max(vy, -max(0.0, y - reach.top) * stoppable)
            if (vy > 0) vy = min(vy, max(0.0, reach.bottom - y) * stoppable)
        }
        x += vx * dt
        y += vy * dt
    }

    companion object {
        fun clamp(value: Double, low: Double, high: Double): Double = min(max(value, low), max(low, high))
    }
}

/**
 * The orb off its rest: held under a finger, or drifting after a throw. A plain object stepping at
 * the display's rate ([link]); it tells the surface where the orb came to rest and lets go.
 */
class FloatingCallFloatMotion {
    /** Where it may rest and how far a throw may carry it. */
    data class Bounds(val rest: Rect, val reach: Rect)

    /** The orb's centre while it's held or drifting; null at rest. */
    var position by mutableStateOf<Offset?>(null)
        private set
    var isHeld by mutableStateOf(false)
        private set
    private var drift = FloatingCallDrift(Offset.Zero)
    private var bounds: (() -> Bounds)? = null
    private var rested: ((Offset) -> Unit)? = null

    /** Steps on the display link. The surface composes [FloatingCallDisplayLink.Drive]. */
    val link = FloatingCallDisplayLink("floating-call-float") { tick -> step(tick.dt) }

    /** How fast it drifts now, in dp a second. */
    val velocity: Offset get() = if (position == null || isHeld) Offset.Zero else drift.velocity

    /** A finger took it, at rest or mid-drift: it stops under the finger. */
    fun hold(at: Offset) {
        link.stop()
        drift.velocity = Offset.Zero
        drift.position = at
        isHeld = true
        updatePosition(at)
    }

    fun move(to: Offset) {
        if (!isHeld) return
        drift.position = to
        updatePosition(to)
    }

    /**
     * Let go with the throw's [velocity]: it drifts on until it rests inside [bounds] (read every
     * frame, so a keyboard moving the composer moves the keep-out along), then [rested] places it
     * there.
     */
    fun release(velocity: Offset, bounds: () -> Bounds, reduceMotion: Boolean, rested: (Offset) -> Unit) {
        if (!isHeld) return
        isHeld = false
        this.bounds = bounds
        this.rested = rested
        drift = FloatingCallDrift(drift.position, velocity)
        if (reduceMotion) {
            // No travel: it rests where it was let go, inside the keep-outs.
            val area = bounds().rest
            val point = drift.position
            settle(
                Offset(
                    FloatingCallDrift.clamp(point.x.toDouble(), area.left.toDouble(), area.right.toDouble()).toFloat(),
                    FloatingCallDrift.clamp(point.y.toDouble(), area.top.toDouble(), area.bottom.toDouble()).toFloat(),
                ),
            )
            return
        }
        link.start()
    }

    /** The screen changed under it (a rotation, a resize): drifting, it carries on from inside [area], never from off the screen it's on now. */
    fun clamp(into: Rect) {
        if (position == null || isHeld || into.width < 0 || into.height < 0) return
        val now = drift.position
        val point = Offset(
            FloatingCallDrift.clamp(now.x.toDouble(), into.left.toDouble(), into.right.toDouble()).toFloat(),
            FloatingCallDrift.clamp(now.y.toDouble(), into.top.toDouble(), into.bottom.toDouble()).toFloat(),
        )
        if (point == now) return
        drift.position = point
        updatePosition(point)
    }

    /** Stops it where it is now (a stage change, a hang-up, the screen going): where it was, or null at rest. */
    fun stop(): Offset? {
        link.stop()
        val point = position
        isHeld = false
        rested = null
        bounds = null
        updatePosition(null)
        return point
    }

    fun step(dt: Double): MotionFrameAction {
        val bounds = bounds
        if (position == null || isHeld || bounds == null) return MotionFrameAction.Stop
        val area = bounds()
        val resting = drift.step(dt, area.rest, area.reach)
        if (resting) {
            settle(drift.position)
            return MotionFrameAction.Stop
        }
        updatePosition(drift.position)
        return MotionFrameAction.Continue
    }

    private fun settle(point: Offset) {
        val rested = rested
        this.rested = null
        bounds = null
        rested?.invoke(point)
        updatePosition(null)
    }

    private fun updatePosition(point: Offset?) {
        if (position != point) position = point
    }
}
