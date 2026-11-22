package ai.opencode.feature.session.header

import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the session title animates while syncing or Auto Renaming. Only [Breathing] ships. */
enum class TitleLoadingStyle { Breathing, Shimmer, Phosphor, Wave }

/** Baked at their shipped defaults (tuning UI not ported). */
object TitleLoadingTunables {
    val STYLE = TitleLoadingStyle.Breathing
    const val MIN_OPACITY = 0.45
    const val SWEEP_PERIOD = 1.45
}

/** Monotonic seconds on the same base as Compose's frame clock (`System.nanoTime`). */
internal fun titleBreathNow(): Double = System.nanoTime() / 1e9

/**
 * How a title looks while it loads: its opacity breathes down to 45 % and back, and eases back to
 * full when loading ends. Under Reduce Motion it holds a steady dim. Each change of state starts
 * from the opacity and speed on screen, the difference decaying on a critically damped spring.
 * Times are seconds on any monotonic base ([titleBreathNow] in the app).
 */
data class TitleBreath(
    val isActive: Boolean = false,
    val reduceMotion: Boolean = false,
    /** When this curve began. */
    val start: Double = 0.0,
    /** Seconds into the breath at [start]. */
    val phase: Double = 0.0,
    /** What was on screen minus this curve's target at [start], and its rate. */
    val offset: Double = 0.0,
    val offsetVelocity: Double = 0.0,
) {
    /** An opacity and its rate (per second). */
    data class Sample(val opacity: Double, val velocity: Double)

    /** The opacity this curve heads for, and its rate, at [time]. */
    fun target(time: Double): Sample {
        if (!isActive) return Sample(1.0, 0.0)
        if (reduceMotion) return Sample(REDUCED_OPACITY, 0.0)
        val omega = 2 * PI / PERIOD
        val x = omega * (phase + time - start)
        return Sample(1 - DEPTH * (0.5 - 0.5 * cos(x)), -DEPTH * 0.5 * omega * sin(x))
    }

    /** The title's opacity, and its rate, at [time]. */
    fun value(time: Double): Sample {
        val (opacity, velocity) = target(time)
        val omega = 2 * PI / RESPONSE
        val t = max(0.0, time - start)
        val b = offsetVelocity + omega * offset
        val decay = exp(-omega * t)
        val error = (offset + b * t) * decay
        val errorVelocity = (b - omega * (offset + b * t)) * decay
        return Sample(min(1.0, max(0.0, opacity + error)), velocity + errorVelocity)
    }

    fun opacity(time: Double): Double = value(time).opacity

    /**
     * Whether the title has come to rest: a breath never does; a steady dim or full opacity does
     * once the spring has settled.
     */
    fun isSettled(time: Double): Boolean {
        if (isActive && !reduceMotion) return false
        return time - start >= SETTLE_SECONDS - 1e-9
    }

    /** Where a settled title rests: a steady dim, or full. */
    val restingOpacity: Double get() = if (isActive && reduceMotion) REDUCED_OPACITY else 1.0

    /**
     * How the tuned look drove a render-server animation. Ported for parity; Android's modifier
     * samples [opacity] per frame instead.
     */
    data class RenderServerPlan(
        val initialOpacity: Double,
        val leadInToTroughDuration: Double?,
        val repeatingHalfPeriod: Double?,
        val targetOpacity: Double,
        val springResponse: Double?,
    )

    fun renderServerPlan(time: Double): RenderServerPlan {
        val current = opacity(time)
        val trough = 1 - DEPTH
        if (!isActive) return RenderServerPlan(current, null, null, 1.0, RESPONSE)
        if (reduceMotion) return RenderServerPlan(current, null, null, REDUCED_OPACITY, RESPONSE)
        val half = PERIOD / 2
        val wrappedPhase = phase.rem(PERIOD)
        if (wrappedPhase > 0.01 && wrappedPhase < half - 0.01) {
            return RenderServerPlan(current, half - wrappedPhase, half, 1.0, null)
        }
        return RenderServerPlan(current, null, half, trough, null)
    }

    /** Smooth 0 ↔ 1 intensity envelope for the drawn (tuning) loading styles. */
    fun envelope(time: Double): Double {
        val span = max(1e-6, 1 - REDUCED_OPACITY)
        return min(1.0, max(0.0, (1 - opacity(time)) / span))
    }

    /**
     * The curve from [time] on, toward breathing or resting, carrying on from what's on screen. A
     * breath picks up where the title already is on its way down, so easing back in never
     * restarts it from full.
     */
    fun retargeted(active: Boolean, reduceMotion: Boolean, time: Double): TitleBreath {
        val (opacity, velocity) = value(time)
        var next = TitleBreath(isActive = active, reduceMotion = reduceMotion, start = time)
        if (active && !reduceMotion) {
            val dimmed = min(1.0, max(0.0, (1 - opacity) / DEPTH))
            next = next.copy(phase = acos(1 - 2 * dimmed) / (2 * PI) * PERIOD)
        }
        val target = next.target(time)
        return next.copy(offset = opacity - target.opacity, offsetVelocity = velocity - target.velocity)
    }

    companion object {
        /** Seconds per breath: the app's one breath ([AmbientClock]). */
        val PERIOD: Double get() = AmbientClock.breathPeriod

        /** How far a breath dims: full down to 45 %. */
        const val DEPTH = 1 - TitleLoadingTunables.MIN_OPACITY

        /** Reduce Motion's steady dim. */
        const val REDUCED_OPACITY = 0.5

        /** The spring's response as it eases in and out of a breath. */
        const val RESPONSE = 0.3

        /** How long the ease back to full takes, within half a percent. */
        const val SETTLE_SECONDS = 0.34

        /** The resting title: full opacity, still. */
        val rest = TitleBreath()
    }
}

/**
 * The plain object that owns a title's breathing motion: the current [TitleBreath] curve and the
 * opacity drawn this frame. Frames are sampled only while the opacity moves.
 */
@Stable
class TitleBreathMotion {
    var breath by mutableStateOf(TitleBreath.rest)
        private set

    /** Frames are drawn only while the opacity changes. */
    var isMoving by mutableStateOf(false)
        private set

    /** The opacity to draw; read it in a draw/layer block so frames skip recomposition. */
    var opacity by mutableFloatStateOf(1f)
        private set

    fun retarget(active: Boolean, reduceMotion: Boolean, now: Double = titleBreathNow()) {
        if (active == breath.isActive && reduceMotion == breath.reduceMotion) return
        breath = breath.retargeted(active, reduceMotion, now)
        isMoving = true
    }

    /** Samples [time]; returns false (and stops) once a fade has settled. A breath runs until it ends. */
    fun frame(time: Double): Boolean {
        val current = breath
        if (!isMoving) {
            opacity = current.restingOpacity.toFloat()
            return false
        }
        if (!(current.isActive && !current.reduceMotion) && time - current.start >= TitleBreath.SETTLE_SECONDS) {
            isMoving = false
            opacity = current.restingOpacity.toFloat()
            return false
        }
        opacity = current.opacity(time).toFloat()
        return true
    }
}

/**
 * The loading look of a title: while [active] its opacity breathes ([TitleBreath]), easing in and
 * out from whatever is on screen. Used while a session resyncs and while the server writes a title.
 * Respects Reduce Motion (a steady dim) and pauses frames while the app isn't resumed. Dims with a
 * layer alpha, so the title's own colour is untouched.
 *
 * @param active whether the title is loading (syncing or Auto Renaming).
 */
fun Modifier.titleBreathing(active: Boolean): Modifier = composed {
    val reduceMotion = rememberReduceMotion()
    val motion = remember { TitleBreathMotion() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(active, reduceMotion) { motion.retarget(active, reduceMotion) }
    LaunchedEffect(motion.breath, motion.isMoving, resumed) {
        if (!motion.isMoving) {
            motion.frame(titleBreathNow())
            return@LaunchedEffect
        }
        if (!resumed) return@LaunchedEffect
        while (withFrameNanos { motion.frame(it / 1e9) }) Unit
    }
    graphicsLayer { alpha = motion.opacity }
}

/**
 * Which title a header shows while titles change under a breath: a new title waits while the old
 * one breathes, and until that breath has eased back to full, so the swap never blurs out a dimmed
 * title. Another identity (a session switched in place) shows its own title at once.
 */
data class TitleHold<Identity>(
    val identity: Identity,
    val shown: String,
    private val latest: String,
    private val isBreathing: Boolean,
    /** When the last breath finishes easing back to full. */
    private val settlesAt: Double = Double.NEGATIVE_INFINITY,
    /** When a held title may show: look again then. */
    val wakeAt: Double? = null,
) {
    constructor(identity: Identity, title: String, isBreathing: Boolean) :
        this(identity = identity, shown = title, latest = title, isBreathing = isBreathing)

    /**
     * What to show for [identity] right now: before [update] has seen a new identity, its own
     * latest title, never the last one's.
     */
    fun title(identity: Identity, latest: String): String = if (identity == this.identity) shown else latest

    /**
     * Takes the inputs of one update together, so a title arriving in the same update its breath
     * ends still waits for the ease back.
     */
    fun update(identity: Identity, title: String, isBreathing: Boolean, now: Double): TitleHold<Identity> {
        if (identity != this.identity) return TitleHold(identity, title, isBreathing)
        val settles = if (this.isBreathing && !isBreathing) now + TitleBreath.SETTLE_SECONDS else settlesAt
        return copy(isBreathing = isBreathing, latest = title, settlesAt = settles).settle(now)
    }

    /** Shows the held title once nothing breathes and the ease back is over. */
    fun settle(now: Double): TitleHold<Identity> {
        val cleared = copy(wakeAt = null)
        if (latest == shown || isBreathing) return cleared
        // A wake that lands a hair early still counts: it never comes again.
        return if (now >= settlesAt - 0.02) cleared.copy(shown = latest) else cleared.copy(wakeAt = settlesAt)
    }
}

/**
 * Shows a title through [TitleHold].
 *
 * @param identity what the title belongs to (the session); a new identity swaps at once.
 * @param title the latest title.
 * @param isBreathing whether the title is breathing (pass the same flag as [titleBreathing]).
 * @param content draws the title to show; `animates` is false on the first frame of another
 *   identity, which swaps in place.
 */
@Composable
fun <Identity> BreathingTitleHold(
    identity: Identity,
    title: String,
    isBreathing: Boolean,
    content: @Composable (title: String, animates: Boolean) -> Unit,
) {
    val holder = remember { mutableStateOf<TitleHold<Identity>?>(null) }
    val hold = holder.value
    val isCurrent = hold?.identity == identity
    content(hold?.title(identity, title) ?: title, isCurrent)
    LaunchedEffect(identity, title, isBreathing) {
        val now = titleBreathNow()
        holder.value = holder.value?.update(identity, title, isBreathing, now)
            ?: TitleHold(identity, title, isBreathing)
    }
    val wakeAt = hold?.wakeAt
    LaunchedEffect(wakeAt) {
        if (wakeAt == null) return@LaunchedEffect
        val wait = wakeAt - titleBreathNow()
        if (wait > 0) delay((wait * 1000).toLong().coerceAtLeast(1))
        holder.value = holder.value?.settle(titleBreathNow())
    }
}
