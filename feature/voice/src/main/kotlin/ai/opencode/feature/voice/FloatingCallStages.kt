package ai.opencode.feature.voice

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionRubberBand
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.MotionTick
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.retarget
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableSpring
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeRadius
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// One Shape: the orb's own surface and dot field grow into a card above the composer, then into a
// sheet the height of the screen, on one vertical drag. One progress value runs 0 (orb), 1 (card)
// and 2 (full); the finger holds the shape's top edge, so every stage is scrubbable, and a release
// carries the finger's speed into the spring. Hanging up from any stage flies the shape into the
// composer's call button. Lengths in dp, times in seconds.

/** One Shape's tunables. */
object FloatingCallStageTunables {
    val cardWidth = Tunable("call.stage.cardWidth", "Card width", default = 416.0, range = 300.0..440.0, step = 2.0)
    val cardHeight = Tunable("call.stage.cardHeight", "Card height", default = 440.0, range = 300.0..620.0, step = 2.0)
    val fullTop = Tunable("call.stage.fullTop", "Full sheet top", default = 70.0, range = 44.0..160.0, step = 1.0)
    val margin = Tunable("call.stage.margin", "Card side margin", default = 12.0, range = 0.0..32.0, step = 1.0)

    /** The card takes over the screen's foot, the composer and its pills hidden under it, instead of standing above them. */
    val covers = Tunable("call.stage.covers", "Card covers the composer", default = true)
    val coverHeight = Tunable("call.stage.coverHeight", "Covering card height", default = 560.0, range = 300.0..720.0, step = 2.0)
    val coverInset = Tunable("call.stage.coverInset", "Covering card bottom margin", default = 12.0, range = 0.0..40.0, step = 1.0)
    // The design system's sheet radius.
    val cardRadius = Tunable("call.stage.cardRadius", "Card corner radius", default = OpenCodeRadius.sheet.value.toDouble(), range = 12.0..56.0, step = 1.0)
    val fullRadius = Tunable("call.stage.fullRadius", "Sheet corner radius", default = OpenCodeRadius.sheet.value.toDouble(), range = 12.0..56.0, step = 1.0)
    val cardBloomY = Tunable("call.stage.cardBloomY", "Card bloom centre", default = 166.0, range = 100.0..260.0, step = 1.0)
    val cardBloomRadius = Tunable("call.stage.cardBloomRadius", "Card bloom radius", default = 96.0, range = 40.0..160.0, step = 1.0)
    val fullBloomY = Tunable("call.stage.fullBloomY", "Sheet bloom centre", default = 150.0, range = 80.0..260.0, step = 1.0)
    val fullBloomRadius = Tunable("call.stage.fullBloomRadius", "Sheet bloom radius", default = 76.0, range = 30.0..160.0, step = 1.0)
    val cardFill = Tunable("call.stage.cardFill", "Card backing", default = 0.35, range = 0.0..1.0, step = 0.05)
    val sheetFill = Tunable("call.stage.sheetFill", "Sheet backing", default = 0.55, range = 0.0..1.0, step = 0.05)
    val dim = Tunable("call.stage.dim", "Dim behind the sheet", default = 0.4, range = 0.0..0.8, step = 0.05)
    val rubberBand = Tunable("call.stage.rubberBand", "Rubber band past the top", default = 0.55, range = 0.1..1.5, step = 0.05)
    val flickSpeed = Tunable("call.stage.flickSpeed", "Flick skips a stage above (pt/s)", default = 450.0, range = 150.0..1500.0, step = 25.0)
    val projection = Tunable("call.stage.projection", "Release projection (s)", default = 0.18, range = 0.0..0.5, step = 0.01)

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(covers)
        store.register(coverHeight, coverInset)
        store.register(cardWidth, cardHeight, fullTop, margin, cardRadius, fullRadius)
        store.register(cardBloomY, cardBloomRadius, fullBloomY, fullBloomRadius)
        store.register(cardFill, sheetFill, dim, rubberBand, flickSpeed, projection)
    }
}

/**
 * Lift and Spin Up: the composer's call button is where the orb starts. Pressed, it dips and its
 * phone glyph starts to spin; it lifts on a spring that starts moving up, turning into the dark Rim
 * Spinner as it grows, the spin speeding up until the call connects.
 */
object FloatingCallConnectTunables {
    val pressScale = Tunable("call.connect.pressScale", "Press scale", default = 0.88, range = 0.6..1.0, step = 0.01)
    val pressTime = Tunable("call.connect.pressTime", "Press before lifting (s)", default = 0.07, range = 0.0..0.3, step = 0.01)
    val liftKick = Tunable("call.connect.liftKick", "Lift kick (pt/s)", default = 380.0, range = 0.0..1200.0, step = 10.0)
    val arc = Tunable("call.connect.arc", "Sideways trails the lift", default = 0.35, range = 0.0..1.0, step = 0.05)
    val growDelay = Tunable("call.connect.growDelay", "Grow after lifting (s)", default = 0.12, range = 0.0..0.4, step = 0.01)
    val spinStart = Tunable("call.connect.spinStart", "Spin at the press (rev/s)", default = 0.55, range = 0.0..3.0, step = 0.05)
    val spinPeak = Tunable("call.connect.spinPeak", "Spin while connecting (rev/s)", default = 2.1, range = 0.2..4.0, step = 0.05)
    val spinRamp = Tunable("call.connect.spinRamp", "Spin speeds up over (s)", default = 0.9, range = 0.1..3.0, step = 0.05)
    val pop = Tunable("call.connect.pop", "Swell on connect", default = 0.07, range = 0.0..0.3, step = 0.01)
    val returnTurn = Tunable("call.connect.returnTurn", "Hang-up turns into the glyph", default = 0.5, range = 0.0..2.0, step = 0.05)
    val spinSettle = Tunable("call.connect.spinSettle", "Hang-up spin settles (s)", default = 0.5, range = 0.1..1.5, step = 0.05)

    val press = TunableSpring("call.connect.press", "Press spring", duration = 0.14, bounce = 0.0)
    val lift = TunableSpring("call.connect.lift", "Lift spring", duration = 0.52, bounce = 0.24)
    val grow = TunableSpring("call.connect.grow", "Grow spring", duration = 0.5, bounce = 0.22)

    /** The white button giving way to the dark spinner. */
    val reveal = TunableSpring("call.connect.reveal", "Button into the spinner", duration = 0.22, bounce = 0.0)

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(pressScale, liftKick, arc, pop)
        store.register(pressTime, growDelay, spinStart, spinPeak, spinRamp, returnTurn, spinSettle)
        store.register(press)
        store.register(lift)
        store.register(grow)
        store.register(reveal)
    }
}

/**
 * A plain motion object's frame callback: [start] asks for frames and [Drive], composed by the
 * surface that owns the object, steps it on Compose's frame clock until the callback returns
 * [MotionFrameAction.Stop]. Off screen (and in JVM tests) nothing drives it; tests step the object
 * by hand.
 */
class FloatingCallDisplayLink(val surface: String, private val onFrame: (MotionTick) -> MotionFrameAction) {
    /** Wants frames: [Drive] runs its loop while this is true. */
    var isRunning by mutableStateOf(false)
        private set

    fun start() {
        if (!isRunning) isRunning = true
    }

    fun stop() {
        if (isRunning) isRunning = false
    }

    internal fun frame(tick: MotionTick): MotionFrameAction {
        val action = if (isRunning) onFrame(tick) else MotionFrameAction.Stop
        if (action == MotionFrameAction.Stop) stop()
        return action
    }
}

/** Steps the link's object every frame while it runs; leaving composition stops it. */
@Composable
fun FloatingCallDisplayLink.Drive() {
    val running = isRunning
    val frameDuration = rememberFrameDurationSeconds()
    LaunchedEffect(this, running) {
        if (running) motionFrameLoop(frameDurationSeconds = frameDuration) { tick -> frame(tick) }
    }
}

/**
 * Lift and Spin Up's motion, stepped on the display link: the shape from the call button up into
 * the orb's rest, the white button giving way to the dark spinner, the swell on connect, and the
 * call's one spin, which the phone glyph shows while the button does. Events fire at their own time
 * within a frame, so it moves alike at any frame rate. A plain object: Compose observes its
 * published values, nothing else.
 */
class FloatingCallConnectMotion {
    /** The shape while it lifts out of the call button or swells on connect; null while it rests. */
    var frame by mutableStateOf<Rect?>(null)
        private set

    /** How much of the white call button still shows over the orb, 0 to 1. */
    var button by mutableDoubleStateOf(0.0)
        private set

    /** The call's spin: the glyph's, then the comet's, then the swirl's. */
    var spin by mutableStateOf<LivingOrbSpin?>(null)
        private set

    /** The phone glyph's turn, in radians: upright at whole turns. */
    var glyphAngle by mutableDoubleStateOf(0.0)
        private set

    /** Still lifting out of the button, rather than resting or swelling. */
    var isBreakingOut by mutableStateOf(false)
        private set

    private enum class Pending { lift, grow }

    private data class Event(val at: Double, val what: Pending)

    private var x = SpringSolver(FloatingCallConnectTunables.lift.spring)
    private var y = SpringSolver(FloatingCallConnectTunables.lift.spring)
    private var width = SpringSolver(FloatingCallConnectTunables.grow.spring)
    private var height = SpringSolver(FloatingCallConnectTunables.grow.spring)
    private var reveal = SpringSolver(FloatingCallConnectTunables.reveal.spring)
    private var pending: MutableList<Event> = mutableListOf()
    private var lifted = false
    private var grown = false
    private var connected = false
    private var returning = false

    /** Driving the shape's frame: lifting, or swelling on connect. */
    private var active = false
    private var rest: () -> Rect = { Rect.Zero }
    private var time = 0.0

    /** Steps on the display link, at [now]. The surface composes [FloatingCallDisplayLink.Drive]. */
    val link = FloatingCallDisplayLink("floating-call-connect") { step(now) }

    /**
     * The call button was pressed: the shape starts as it, exactly, and lifts into [rest], read
     * again every frame as the composer moves.
     */
    fun start(from: Rect, rest: () -> Rect, at: Double = now, runs: Boolean = true) {
        val t = FloatingCallConnectTunables
        this.rest = rest
        time = at
        lifted = false; grown = false; connected = false; returning = false
        active = true
        isBreakingOut = true
        val press = t.pressScale.value
        x = spring(from.center.x.toDouble())
        y = spring(from.center.y.toDouble())
        width = spring(from.width.toDouble())
        height = spring(from.height.toDouble())
        width.retarget(from.width * press, t.press.spring)
        height.retarget(from.height * press, t.press.spring)
        reveal = SpringSolver(t.reveal.spring, value = 1.0, target = 0.0, positionEpsilon = 0.002, velocityEpsilon = 0.02)
        pending = mutableListOf(Event(at + t.pressTime.value, Pending.lift), Event(at + t.pressTime.value + t.growDelay.value, Pending.grow))
        // The glyph starts turning under the finger, quickly up to its first speed.
        spin = LivingOrbSpin(time = at, mode = LivingOrbSpin.Mode.Ramp(rate = t.spinStart.value * 2 * PI, tau = 0.06))
        publish(at)
        if (runs) link.start()
    }

    /** The call connected: the spin dies away (time constant 0.8 s / 3) and the orb swells on the spring it's on. */
    fun connected(at: Double = now, reduceMotion: Boolean, runs: Boolean = true) {
        if (connected || returning) return
        connected = true
        advance(at)
        spin = spin?.ramping(rate = 0.0, tau = 0.8 / 3, at = at)
        val pop = FloatingCallConnectTunables.pop.value
        if (reduceMotion || pop <= 0) return
        val rest = rest()
        if (!active) {
            // Resting already: it swells where it is.
            if (rest.width <= 0) return
            x = spring(rest.center.x.toDouble()); y = spring(rest.center.y.toDouble())
            width = spring(rest.width.toDouble()); height = spring(rest.height.toDouble())
            reveal = SpringSolver(FloatingCallConnectTunables.reveal.spring, value = 0.0)
            lifted = true; grown = true; pending = mutableListOf()
            active = true
        }
        // An impulse on the spring it's on, not a new target.
        val duration = 0.42
        val kick = pop * (2 * PI / duration) * 1.6
        width.retarget(if (grown) rest.width.toDouble() else width.target, preservingVelocity = width.velocity + kick * rest.width, duration = duration, bounce = 0.3)
        height.retarget(if (grown) rest.height.toDouble() else height.target, preservingVelocity = height.velocity + kick * rest.height, duration = duration, bounce = 0.3)
        publish(at)
        if (runs) link.start()
    }

    /** Where the shape is and how fast it's moving, in (midX, midY, width, height), as a hang-up takes it. */
    data class Flight(val frame: Rect, val velocity: List<Double>)

    /**
     * Hung up: hands back where the shape is and how fast it's moving when it's still in flight,
     * and winds the spin down onto an upright glyph as the shape lands in the button.
     */
    fun hangUp(at: Double = now, reduceMotion: Boolean, runs: Boolean = true): Flight? {
        advance(at)
        val flight = frame?.let { Flight(it, listOf(x.velocity, y.velocity, width.velocity, height.velocity)) }
        pending = mutableListOf()
        isBreakingOut = false
        active = false
        frame = null
        if (reduceMotion) {
            spin = null
            return flight
        }
        val t = FloatingCallConnectTunables
        returning = true
        spin = (spin ?: LivingOrbSpin(time = at)).settling(omega = 2 * PI / max(0.05, t.spinSettle.value), turn = t.returnTurn.value * 2 * PI, at = at)
        publish(at)
        if (runs) link.start()
        return flight
    }

    /** A drag or a grow took the shape: it rests where the stages put it. */
    fun cancel() {
        pending = mutableListOf()
        active = false
        isBreakingOut = false
        if (frame != null) frame = null
        if (button != 0.0) button = 0.0
    }

    /** The surface went: stops stepping and lets go of its `rest`, which reads the surface's layout. */
    fun stop() {
        link.stop()
        rest = { Rect.Zero }
    }

    /** One frame at [now]; [MotionFrameAction.Stop] once nothing moves. */
    fun step(now: Double): MotionFrameAction {
        while (true) {
            val next = pending.firstOrNull() ?: break
            if (next.at > now) break
            pending.removeAt(0)
            advance(next.at)
            fire(next.what, next.at)
        }
        advance(now)
        if (active && pending.isEmpty() && lifted && grown && x.isSettled && y.isSettled && width.isSettled && height.isSettled && reveal.isSettled) {
            // Settled exactly on its rest: the resting orb takes over in place.
            active = false
            isBreakingOut = false
            frame = null
        }
        publish(now)
        val spin = spin
        if (returning && spin != null && spin.isSettled(now)) {
            returning = false
            val mode = spin.mode
            if (mode is LivingOrbSpin.Mode.Settle) glyphAngle = mode.angle
        }
        return if (active || returning) MotionFrameAction.Continue else MotionFrameAction.Stop
    }

    private fun fire(what: Pending, at: Double) {
        val t = FloatingCallConnectTunables
        val rest = rest()
        when (what) {
            Pending.lift -> {
                // Released moving up, it overshoots its rest a little; sideways it trails.
                y.retarget(rest.center.y.toDouble(), t.lift.spring, preservingVelocity = -t.liftKick.value)
                x.retarget(rest.center.x.toDouble(), duration = t.lift.duration.value * (1 + t.arc.value), bounce = t.lift.bounce.value * 0.5)
                if (!connected) spin = spin?.ramping(rate = t.spinPeak.value * 2 * PI, tau = t.spinRamp.value, at = at)
                lifted = true
            }
            Pending.grow -> {
                width.retarget(rest.width.toDouble(), t.grow.spring)
                height.retarget(rest.height.toDouble(), t.grow.spring)
                grown = true
            }
        }
    }

    private fun advance(to: Double) {
        val dt = to - time
        if (dt <= 0) return
        time = to
        val rest = rest()
        if (lifted && rest.width > 0) { x.target = rest.center.x.toDouble(); y.target = rest.center.y.toDouble() }
        if (grown && rest.width > 0) { width.target = rest.width.toDouble(); height.target = rest.height.toDouble() }
        x.step(dt)
        y.step(dt)
        width.step(dt)
        height.step(dt)
        reveal.step(dt)
    }

    private fun publish(now: Double) {
        if (active) {
            val w = max(0.0, width.value)
            val h = max(0.0, height.value)
            val next = rect(x.value - w / 2, y.value - h / 2, w, h)
            if (frame != next) frame = next
        }
        val shown = if (active) min(1.0, max(0.0, reveal.value)) else 0.0
        if (button != shown) button = shown
        val spin = spin
        if (spin != null && (button > 0 || returning)) {
            val angle = spin.sample(now).angle
            if (glyphAngle != angle) glyphAngle = angle
        }
    }

    companion object {
        /** Now, on the clock the display link and the orb's field step on. */
        val now: Double get() = AnimationTimeScale.shared.now()

        private fun spring(value: Double): SpringSolver = SpringSolver.of(duration = 0.5, value = value, positionEpsilon = 0.05, velocityEpsilon = 1.0)
    }
}

/** A rect from its origin and size, in doubles. */
internal fun rect(x: Double, y: Double, width: Double, height: Double): Rect =
    Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())

/** Where the shape is at each stage, for one screen and one resting orb. */
data class FloatingCallStageGeometry(
    val screen: Size,
    /** The orb at rest. */
    val rest: Rect,
    /** The card's bottom: near the screen's foot, over the composer, or the orb's usual rest bottom above it. */
    val low: Double,
    val trailing: Boolean = true,
    val card: Size = Size(FloatingCallStageTunables.cardWidth.value.toFloat(), FloatingCallStageTunables.cardHeight.value.toFloat()),
    val fullTop: Double = FloatingCallStageTunables.fullTop.value,
    /** How far the sheet runs on past the screen's bottom edge, so its rim and shadow never draw a line along the foot of the screen. */
    val foot: Double = 48.0,
    val margin: Double = FloatingCallStageTunables.margin.value,
    val rubberBand: Double = FloatingCallStageTunables.rubberBand.value,
    val cardRadius: Double = FloatingCallStageTunables.cardRadius.value,
    val fullRadius: Double = FloatingCallStageTunables.fullRadius.value,
    val cardBloom: Bloom = Bloom(FloatingCallStageTunables.cardBloomY.value, FloatingCallStageTunables.cardBloomRadius.value),
    val fullBloom: Bloom = Bloom(FloatingCallStageTunables.fullBloomY.value, FloatingCallStageTunables.fullBloomRadius.value),
    val flickSpeed: Double = FloatingCallStageTunables.flickSpeed.value,
    val projection: Double = FloatingCallStageTunables.projection.value,
) {
    data class Bloom(val y: Double, val radius: Double)

    /** The shape's top and bottom corner radii. */
    data class Radii(val top: Double, val bottom: Double)

    /** The dot field's bloom: its centre and radius. */
    data class BloomCircle(val centerX: Double, val centerY: Double, val radius: Double)

    /** A release's stage, and the speed it carries into the progress spring (stages a second). */
    data class Settled(val stage: FloatingCallStage, val velocity: Double)

    /** Orb to card and card to sheet. */
    data class Spans(val card: Double, val full: Double)

    private val screenWidth: Double get() = screen.width.toDouble()
    private val screenHeight: Double get() = screen.height.toDouble()

    /** How a drag past the sheet stretches. */
    private val stretch: MotionRubberBand get() = MotionRubberBand(dimension = 100.0, coefficient = rubberBand)

    /** The card, clamped to the screen: above the composer, on the orb's side. */
    val cardFrame: Rect
        get() {
            val width = max(rest.width.toDouble(), min(card.width.toDouble(), screenWidth - 2 * margin))
            val height = max(rest.height + 40.0, min(card.height.toDouble(), low - fullTop - 40))
            val x = if (trailing) screenWidth - margin - width else margin
            return rect(x, low - height, width, height)
        }

    /** The shape's top edge at each stage: the finger drags this edge. */
    val tops: List<Double> get() = listOf(rest.top.toDouble(), cardFrame.top.toDouble(), fullTop)

    /**
     * How far a finger travels up for each step, orb to card and card to sheet: the distance its
     * top edge moves, never less than [minSpan] (an orb carried up near or above the card's top
     * would otherwise flip the order and a drag would jump).
     */
    val spans: Spans
        get() {
            val tops = tops
            return Spans(max(minSpan, tops[0] - tops[1]), max(minSpan, tops[1] - tops[2]))
        }

    /**
     * How far up from the orb a drag has carried the shape at [progress]: the finger's travel.
     * With the orb resting below the card it's how far the top edge has risen, so the finger
     * holds the edge. Past the sheet it's the travel that stretches this far, so a finger
     * catching the shape on its way back holds it where it's drawn.
     */
    fun lift(progress: Double): Double {
        val spans = spans
        if (progress <= 1) return progress * spans.card
        if (progress <= 2) return spans.card + (progress - 1) * spans.full
        return spans.card + spans.full + stretch.excess(forStretch = (progress - 2) * spans.full)
    }

    /** The progress [lift] points of travel give, rubber-banding past the sheet. */
    fun progress(atLift: Double): Double {
        val spans = spans
        if (atLift <= spans.card) return max(0.0, atLift / spans.card)
        if (atLift <= spans.card + spans.full) return 1 + (atLift - spans.card) / spans.full
        return 2 + stretch.stretch(atLift - spans.card - spans.full) / spans.full
    }

    /**
     * The shape at [progress]. Growing from the orb, the width leads on the way up and trails on
     * the way down (one path both ways); past the card it widens to the screen's edges and its
     * top rises to the sheet's.
     */
    fun frame(progress: Double): Rect {
        val card = cardFrame
        if (progress <= 1) {
            val wide = 1 - (1 - progress) * (1 - progress)
            val width = max(0.0, lerp(rest.width.toDouble(), card.width.toDouble(), wide))
            val height = max(0.0, lerp(rest.height.toDouble(), card.height.toDouble(), progress))
            val bottom = lerp(rest.bottom.toDouble(), low, progress)
            val x = if (trailing) lerp(rest.right.toDouble(), card.right.toDouble(), wide) - width else lerp(rest.left.toDouble(), card.left.toDouble(), wide)
            return rect(x, bottom - height, width, height)
        }
        val k = progress - 1
        val edges = min(1.0, k)
        val minX = lerp(card.left.toDouble(), 0.0, edges)
        val maxX = lerp(card.right.toDouble(), screenWidth, edges)
        val top = lerp(card.top.toDouble(), fullTop, k)
        val bottom = lerp(low, screenHeight + foot, edges)
        return rect(minX, top, maxX - minX, bottom - top)
    }

    /** The part of [frame] on screen: what the card's and sheet's content lay out in. */
    fun visible(frame: Rect): Rect =
        rect(frame.left.toDouble(), frame.top.toDouble(), frame.width.toDouble(), max(0.0, min(frame.bottom.toDouble(), screenHeight) - frame.top))

    /** 0 at the card's height or less, 1 at the full sheet's. */
    fun fullness(frame: Rect): Double {
        val card = cardFrame.height.toDouble()
        return clamp((frame.height - card) / max(1.0, screenHeight + foot - fullTop - card))
    }

    /** 0 at the orb's height, 1 at the card's. */
    fun cardness(frame: Rect): Double =
        clamp((frame.height - rest.height.toDouble()) / max(1.0, (cardFrame.height - rest.height).toDouble()))

    /** Top and bottom corner radii: round as the orb, the card's corners, then square at the bottom as the sheet meets the screen's edge. */
    fun radii(frame: Rect): Radii {
        val short = min(frame.width, frame.height).toDouble()
        val round = max(0.0, short / 2)
        val squared = clamp((short - rest.height) / 40)
        val full = fullness(frame)
        return Radii(
            lerp(round, min(round, lerp(cardRadius, fullRadius, full)), squared),
            lerp(round, min(round, lerp(cardRadius, 0.0, full)), squared),
        )
    }

    /**
     * The card's bloom, shrunk and raised when the card is too short for it (landscape) so the
     * status, transcript and controls below it never overlap it.
     */
    val cardBloomFitted: Bloom get() = fitted(cardBloom, cardFrame.height.toDouble(), cardChromeBelow)

    /** The sheet's bloom, fitted to the sheet's height on screen the same way. */
    val fullBloomFitted: Bloom get() = fitted(fullBloom, screenHeight - fullTop, fullChromeBelow)

    private fun fitted(bloom: Bloom, height: Double, below: Double): Bloom {
        val room = height - below
        if (bloom.y + bloom.radius <= room) return bloom
        val radius = max(minBloomRadius, (room - headerBottom) / 2)
        return Bloom(y = max(headerBottom + radius, room - radius), radius = radius)
    }

    /** The dot field's bloom in the shape: filling the orb, centred near the card's top, then smaller and higher as the sheet fills. */
    fun bloom(frame: Rect): BloomCircle {
        val card = cardness(frame)
        val full = fullness(frame)
        val cardBloom = cardBloomFitted
        val fullBloom = fullBloomFitted
        val y = lerp(cardBloom.y, fullBloom.y, full)
        val radius = lerp(cardBloom.radius, fullBloom.radius, full)
        val cy = lerp(lerp(frame.center.y.toDouble(), frame.top + cardBloom.y, card), frame.top + y, full)
        val r = lerp(lerp(min(frame.width, frame.height) / 2.0, cardBloom.radius, card), radius, full)
        return BloomCircle(frame.center.x.toDouble(), cy, max(0.0, r))
    }

    /** The stage a release settles on with nothing carrying it. */
    fun nearest(progress: Double): FloatingCallStage =
        FloatingCallStage.entries.getOrNull(floor(clamp(progress, 0.0, 2.0) + 0.5).toInt()) ?: FloatingCallStage.orb

    /**
     * Where a release at [progress] lands, with the finger moving at [velocity] (dp a second,
     * down positive), and the speed it carries into the progress spring. A flick skips at least
     * to the next stage.
     */
    fun settle(progress: Double, velocity: Double): Settled {
        val spans = spans
        val lifts = listOf(0.0, spans.card, spans.card + spans.full)
        val projected = lift(progress) - max(-4000.0, min(4000.0, velocity)) * projection
        var near = 0
        for (index in 1..2) if (abs(lifts[index] - projected) < abs(lifts[near] - projected)) near = index
        if (abs(velocity) > flickSpeed) {
            near = if (velocity < 0) {
                max(near, min(2, floor(progress + 0.04).toInt() + 1))
            } else {
                min(near, max(0, ceil(progress - 0.04).toInt() - 1))
            }
        }
        val span = if (progress <= 1) spans.card else spans.full
        // Past the sheet the shape moves slower than the finger: it carries the speed it's
        // drawn moving.
        var drawn = velocity
        if (progress > 2) drawn = velocity * stretch.slope(stretch.excess(forStretch = (progress - 2) * spans.full))
        return Settled(FloatingCallStage.entries.getOrNull(near) ?: FloatingCallStage.orb, -drawn / span)
    }

    private fun clamp(value: Double, low: Double = 0.0, high: Double = 1.0): Double = min(high, max(low, value))

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    companion object {
        const val minSpan = 120.0

        // What `FloatingCallStageContent` lays out above and below the bloom: the header, then
        // the status, the transcript's top gap and the controls row with its margins.
        private const val headerBottom = 66.0
        private const val cardChromeBelow = 114.0
        private const val fullChromeBelow = 174.0
        private const val minBloomRadius = 28.0
    }
}

/**
 * The shape's motion between stages, stepped on the display link. The shape is the stage geometry
 * at [progress], plus an offset that decays to zero: a hang-up re-bases onto the call button and
 * keeps its position and speed. A plain object: Compose observes its published values; the frame
 * loop steps it, never a reducer.
 */
class FloatingCallStageMotion {
    var progress by mutableDoubleStateOf(0.0)
        private set
    var target by mutableStateOf(FloatingCallStage.orb)
        private set
    var isDragging by mutableStateOf(false)
        private set

    /** Where the shape lands once the call has ended. */
    var landing by mutableStateOf<Rect?>(null)
        private set

    /** The card and sheet's content, fading out as the shape leaves. */
    var content by mutableDoubleStateOf(1.0)
        private set

    /** The whole shape's opacity: Reduce Motion's fades, or a leave with nowhere to land. */
    var opacity by mutableDoubleStateOf(1.0)
        private set
    var hasLanded by mutableStateOf(false)
        private set

    /** The offset from the base shape, in (midX, midY, width, height). */
    var offset by mutableStateOf(listOf(0.0, 0.0, 0.0, 0.0))
        private set

    /** Landing in the call button, rather than fading where it rests. */
    val landsInButton: Boolean get() = landing != null && !fadesOnLanding

    /** Driving the shape: anywhere but resting as the orb. */
    val isActive: Boolean get() = progress != 0.0 || target != FloatingCallStage.orb || isDragging || landing != null || reduced != null

    private enum class Reduced { stage, end }

    private var reduced: Reduced? by mutableStateOf(null)
    private var reducedTime = 0.0
    private var spring = SpringSolver(Motion.surface, value = 0.0, target = 0.0, positionEpsilon = 0.0005, velocityEpsilon = 0.005)
    private val offsets = List(4) { SpringSolver(Motion.flight, value = 0.0, target = 0.0, positionEpsilon = 0.05, velocityEpsilon = 1.0) }
    private var fadesOnLanding by mutableStateOf(false)

    /** Where the landing is now: the call button, read each frame. */
    private var landingTarget: (() -> Rect?)? = null
    private var anchor: Pair<Double, Double>? = null

    /** Steps on the display link. The surface composes [FloatingCallDisplayLink.Drive]. */
    val link = FloatingCallDisplayLink("floating-call-stages") { tick -> step(tick.dt) }

    /** The shape now. */
    fun frame(geometry: FloatingCallStageGeometry): Rect {
        val base = landing ?: geometry.frame(progress)
        val offset = offset
        val width = max(0.0, base.width + offset[2])
        val height = max(0.0, base.height + offset[3])
        return rect(base.center.x + offset[0] - width / 2, base.center.y + offset[1] - height / 2, width, height)
    }

    /**
     * Grows on [Motion.surface] or shrinks on [Motion.panelClose] to [stage] (a let-go drag
     * passes [Motion.release]). Without a [velocity] it keeps the speed it has, so a retarget
     * mid-flight carries on from where it is.
     */
    fun animate(stage: FloatingCallStage, velocity: Double? = null, role: MotionRole? = null, reduceMotion: Boolean) {
        if (landing != null) return
        target = stage
        if (reduceMotion) {
            startReduced(Reduced.stage)
            return
        }
        val goal = stage.ordinal.toDouble()
        spring.value = progress
        spring.retarget(goal, role ?: (if (goal > progress) Motion.surface else Motion.panelClose), preservingVelocity = velocity)
        link.start()
    }

    /** Straight to [stage], no motion: the call carried over to a new screen. */
    fun jump(stage: FloatingCallStage) {
        if (landing != null) return
        target = stage
        updateProgress(stage.ordinal.toDouble())
        spring.value = progress
        spring.target = progress
        spring.velocity = 0.0
    }

    /** A finger took the shape, mid-flight or at rest: it holds where it is. */
    fun beginDrag(fingerY: Double, geometry: FloatingCallStageGeometry) {
        if (landing != null) return
        if (reduced == Reduced.stage) {
            reduced = null
            updateOpacity(1.0)
            updateProgress(target.ordinal.toDouble())
        }
        isDragging = true
        spring.value = progress
        spring.velocity = 0.0
        anchor = fingerY to geometry.lift(progress)
    }

    /** The finger holds the shape's top edge. */
    fun drag(fingerY: Double, geometry: FloatingCallStageGeometry) {
        val anchor = anchor
        if (!isDragging || anchor == null) return
        updateProgress(geometry.progress(atLift = anchor.second + anchor.first - fingerY))
        spring.value = progress
    }

    fun endDrag(stage: FloatingCallStage, velocity: Double, reduceMotion: Boolean) {
        if (!isDragging) return
        isDragging = false
        anchor = null
        animate(stage, velocity, role = Motion.release, reduceMotion = reduceMotion)
    }

    /**
     * The call ended: fly from [current] into [rect] (the call button, or half the orb where it
     * rests, fading, when there's no button to land in), keeping the shape's speed (or
     * [velocity], in (midX, midY, width, height), from a lift), while the content fades and any
     * sheet shrinks away. [target] is where the button is now, read every frame: a keyboard or
     * composer moving under it carries the landing along. It never passes the button, and
     * settles on it exactly.
     */
    fun land(
        rect: Rect,
        current: Rect,
        velocity: List<Double>? = null,
        fades: Boolean,
        target: (() -> Rect?)? = null,
        geometry: FloatingCallStageGeometry,
        reduceMotion: Boolean,
        runs: Boolean = true,
    ) {
        if (landing != null || hasLanded) return
        val carried = velocity ?: if (isActive && !isDragging) rectVelocity(geometry) else listOf(0.0, 0.0, 0.0, 0.0)
        isDragging = false
        anchor = null
        this.target = FloatingCallStage.orb
        landing = rect
        landingTarget = target
        val from = listOf(
            (current.center.x - rect.center.x).toDouble(),
            (current.center.y - rect.center.y).toDouble(),
            (current.width - rect.width).toDouble(),
            (current.height - rect.height).toDouble(),
        )
        offset = from
        if (reduceMotion) {
            // It fades where it is.
            startReduced(Reduced.end)
            return
        }
        fadesOnLanding = fades
        // Tuned or not, `flight`'s bounce stays at 0 or less here: the shape lands without
        // passing the button.
        val flight = Motion.flight.curve()
        val duration = flight.duration
        val bounce = min(0.0, flight.bounce)
        val omega = 2 * PI / max(0.01, duration)
        for (index in offsets.indices) {
            offsets[index].value = from[index]
            // Heading in faster than a critically damped spring can stop from here would carry
            // it past the button: it comes in no faster.
            var speed = carried[index]
            if (from[index] == 0.0) speed = 0.0 else if (speed / from[index] < -omega) speed = -omega * from[index]
            offsets[index].retarget(0.0, preservingVelocity = speed, duration = duration, bounce = bounce)
        }
        spring.value = progress
        spring.retarget(0.0, role = Motion.panelClose)
        if (runs) link.start()
    }

    fun stop() {
        link.stop()
        landingTarget = null
    }

    private fun startReduced(kind: Reduced) {
        reduced = kind
        reducedTime = 0.0
        link.start()
    }

    fun step(dt: Double): MotionFrameAction {
        var busy = false
        val reduced = reduced
        if (reduced != null) {
            // Reduce Motion: no travel. It fades out, changes while hidden, and fades back in; a
            // hang-up just fades.
            reducedTime = min(1.0, reducedTime + dt / max(0.01, Motion.reduced.curve().duration))
            if (reduced == Reduced.stage) {
                if (reducedTime >= 0.5) updateProgress(target.ordinal.toDouble())
                updateOpacity(abs(1 - 2 * reducedTime))
            } else {
                updateOpacity(1 - reducedTime)
                if (content != 0.0) content = 0.0
            }
            if (reducedTime >= 1) {
                this.reduced = null
                spring.value = progress
                spring.target = progress
                spring.velocity = 0.0
                if (reduced == Reduced.end) hasLanded = true
            } else {
                busy = true
            }
            return if (busy) MotionFrameAction.Continue else MotionFrameAction.Stop
        }
        if (!isDragging && !(spring.isSettled && spring.value == spring.target)) {
            val step = spring.step(dt)
            updateProgress(step.value)
            busy = busy || !step.isSettled
        }
        val landed = landing
        if (landed != null && !hasLanded) {
            val moved = landingTarget?.invoke()
            if (moved != null && moved != landed && moved.width > 0) {
                // The button moved: re-based on it, the shape stays where it is this frame and
                // its spring chases the button from there.
                val shift = listOf(
                    (moved.center.x - landed.center.x).toDouble(),
                    (moved.center.y - landed.center.y).toDouble(),
                    (moved.width - landed.width).toDouble(),
                    (moved.height - landed.height).toDouble(),
                )
                for (index in offsets.indices) offsets[index].value -= shift[index]
                landing = moved
            }
            var settled = true
            val next = offset.toMutableList()
            for (index in offsets.indices) {
                val before = offsets[index].value
                var step = offsets[index].step(dt)
                if (before != 0.0 && step.value != 0.0 && (before > 0) != (step.value > 0)) {
                    // Never past the button, even chasing one that moved.
                    offsets[index].value = 0.0
                    offsets[index].velocity = 0.0
                    step = SpringSolver.Step(value = 0.0, velocity = 0.0, isSettled = true)
                }
                next[index] = step.value
                settled = settled && step.isSettled
            }
            if (next != offset) offset = next
            val fade = max(0.0, content - dt / max(0.01, MotionTunables.swapOutDuration.value))
            if (fade != content) content = fade
            if (fadesOnLanding) {
                val duration = max(0.05, Motion.flight.curve().duration)
                updateOpacity(max(0.0, opacity - dt / duration))
            }
            if (settled) hasLanded = true else busy = true
        }
        return if (busy) MotionFrameAction.Continue else MotionFrameAction.Stop
    }

    /** How fast the shape is moving now, in (midX, midY, width, height). */
    private fun rectVelocity(geometry: FloatingCallStageGeometry): List<Double> {
        val dt = 1.0 / 240
        val a = geometry.frame(progress)
        val b = geometry.frame(progress + spring.velocity * dt)
        return listOf(
            (b.center.x - a.center.x) / dt,
            (b.center.y - a.center.y) / dt,
            (b.width - a.width) / dt,
            (b.height - a.height) / dt,
        )
    }

    private fun updateProgress(value: Double) {
        if (progress != value) progress = value
    }

    private fun updateOpacity(value: Double) {
        if (opacity != value) opacity = value
    }
}
