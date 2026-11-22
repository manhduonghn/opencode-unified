package ai.opencode.core.motion

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableSpring
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningStore
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * ω = 2π / duration, ζ = 1 − bounce, so [spec] is `spring(dampingRatio = ζ, stiffness = ω²)`.
 * [fromResponse] takes the legacy `response:dampingFraction:` form.
 */
data class MotionSpring(val duration: Double, val bounce: Double = 0.0) {
    /** Angular frequency ω (rad/s). */
    val omega: Double get() = SpringSolver.parameters(duration, bounce).omega

    /** Damping ratio ζ. */
    val dampingRatio: Float get() = SpringSolver.parameters(duration, bounce).zeta.toFloat()

    /** Compose stiffness (mass 1) = ω². */
    val stiffness: Float get() = (omega * omega).toFloat()

    /** The exact Compose spring. Pass a [visibilityThreshold] for Dp/Offset/Int values. */
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> {
        // Slow motion scales time: ω × s, so stiffness × s² (damping ratio is unchanged).
        val scale = AnimationTimeScale.shared.observedScale.toFloat()
        return SpringSpec(dampingRatio = dampingRatio, stiffness = stiffness * scale * scale, visibilityThreshold = visibilityThreshold)
    }

    /** The spring, or [Motion.reducedFadeSpec] under Reduce Motion. */
    fun <T> animation(reduceMotion: Boolean, delaySeconds: Double = 0.0, visibilityThreshold: T? = null): FiniteAnimationSpec<T> {
        if (reduceMotion) return Motion.reducedFadeSpec(delaySeconds)
        val base = spec(visibilityThreshold)
        return if (delaySeconds > 0) delayed(base, delaySeconds / AnimationTimeScale.shared.observedScale) else base
    }

    companion object {
        fun fromResponse(response: Double, dampingFraction: Double): MotionSpring =
            MotionSpring(response, if (dampingFraction <= 1) 1 - dampingFraction else 1 / dampingFraction - 1)
    }
}

/**
 * The app's motion vocabulary: fourteen [MotionRole]s plus shared geometry, stagger and ambient
 * values, backed by live [MotionTunables].
 */
object Motion {
    /** The Motion group's sections, in order. */
    val sectionTitles = listOf("Springs", "Fades", "Geometry", "Ambient")

    // Roles

    val press = MotionRole.Press
    val microIn = MotionRole.MicroIn
    val microOut = MotionRole.MicroOut
    val swap = MotionRole.Swap
    val layout = MotionRole.Layout
    val collapse = MotionRole.Collapse
    val panelOpen = MotionRole.PanelOpen
    val panelClose = MotionRole.PanelClose
    val surface = MotionRole.Surface
    val release = MotionRole.Release
    val flight = MotionRole.Flight
    val keyboard = MotionRole.Keyboard
    val dim = MotionRole.Dim
    val reduced = MotionRole.Reduced

    // Shared values, live from [MotionTunables]

    /** One beat after a surface begins expanding before interior content reveals (0.05 s). */
    val contentDelay: Double get() = MotionTunables.contentDelay.value
    /** Per-item cascade delay across a staggered group (0.02 s). */
    val staggerStep: Double get() = MotionTunables.staggerStep.value

    // Blur radii & entrance rises

    /** Subtle blur for compact capsules (3). */
    val blurSubtle: Dp get() = MotionTunables.blurSubtle.value
    /** Standard blur for morphing panels and docks (8). */
    val blurPanel: Dp get() = MotionTunables.blurPanel.value
    /** Deep arrival blur for full sheets (14). */
    val blurSheet: Dp get() = MotionTunables.blurSheet.value
    /** Progressive backdrop blur behind expanded sheets (18). Android: use a scrim, not a blur. */
    val blurBackdrop: Dp get() = MotionTunables.blurBackdrop.value
    /** Subtle vertical rise for arriving rows and labels (6). */
    val entranceRise: Dp get() = MotionTunables.entranceRise.value
    /** Vertical rise for expanding panel content (10). */
    val panelRise: Dp get() = MotionTunables.panelRise.value

    /** [MotionTunables.register]. */
    fun register(store: TuningStore = TuningStore.shared) = MotionTunables.register(store)

    /**
     * The component-named keys the roles replaced (`motion.morphOpen.duration`,
     * `motion.reducedFade`, …).
     */
    fun isRetiredKey(key: String): Boolean {
        val parts = key.split('.')
        if (parts.size < 2 || parts[0] != "motion") return false
        return parts[1] in retiredNames
    }

    private val retiredNames = setOf(
        "morphOpen", "morphClose", "panelOpen", "settle", "disclosureOpen", "disclosureClose", "microPop", "microExit", "drawerSlide",
        "reducedFade", "fadeFast", "fadeStandard", "fadeSlow", "contentDelay", "handoffDelay", "staggerStep",
        "foldHandoverRatio", "blurSubtle", "blurPanel", "blurSheet", "blurBackdrop", "entranceRise", "panelRise",
    )

    // Deprecated: fixed at today's numbers until their call sites move to a role.

    /** Primary sheet / morph fold (0.34 s, critically damped). */
    @Deprecated("Use Motion.panelClose")
    val morphClose = MotionSpring(0.34, 0.0)
    /** Critically damped layout resize, row promotion, or card settle (0.40 s). */
    @Deprecated("Use Motion.layout or Motion.surface")
    val settle = MotionSpring(0.40, 0.0)
    /** Small badge, pill, or glyph entrance pop (0.26 s, 0.22 bounce). */
    @Deprecated("Use Motion.microIn")
    val microPop = MotionSpring(0.26, 0.22)
    @Deprecated("Use Motion.release")
    val drawerSlide: MotionSpring get() = release.spring

    /** Cross-fade used under Reduce Motion (0.20 s, ease-in-out). */
    @Deprecated("Use MotionTunables.reducedDuration", ReplaceWith("MotionTunables.reducedDuration.value"))
    val reducedFade: Double get() = MotionTunables.reducedDuration.value
    /** Fast outgoing content / press / chrome fade (0.14 s, ease-out). */
    @Deprecated("Use MotionTunables.swapOutDuration", ReplaceWith("MotionTunables.swapOutDuration.value"))
    val fadeFast: Double get() = MotionTunables.swapOutDuration.value
    /** Standard incoming content / row fade (0.22 s, ease-out). */
    @Deprecated("Use MotionTunables.swapInDuration", ReplaceWith("MotionTunables.swapInDuration.value"))
    val fadeStandard: Double get() = MotionTunables.swapInDuration.value

    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** [Motion.reduced]'s `.easeInOut(duration: reducedDuration)`. */
    fun <T> reducedFadeSpec(delaySeconds: Double = 0.0): FiniteAnimationSpec<T> =
        tween(durationMillis = millis(MotionTunables.reducedDuration.value), delayMillis = millis(delaySeconds), easing = EaseInOut)

    fun <T> easeOut(seconds: Double, delaySeconds: Double = 0.0, reduceMotion: Boolean = false): FiniteAnimationSpec<T> =
        if (reduceMotion) reducedFadeSpec(delaySeconds)
        else tween(durationMillis = millis(seconds), delayMillis = millis(delaySeconds), easing = EaseOut)

    fun <T> easeInOut(seconds: Double, delaySeconds: Double = 0.0, reduceMotion: Boolean = false): FiniteAnimationSpec<T> =
        if (reduceMotion) reducedFadeSpec(delaySeconds)
        else tween(durationMillis = millis(seconds), delayMillis = millis(delaySeconds), easing = EaseInOut)

    /** Seconds → milliseconds, stretched by Developer Mode slow motion ([AnimationTimeScale]). */
    fun millis(seconds: Double): Int = (seconds * 1000 / AnimationTimeScale.shared.observedScale).roundToInt()
}

/** Delays any finite spec. */
internal fun <T> delayed(spec: FiniteAnimationSpec<T>, delaySeconds: Double): FiniteAnimationSpec<T> =
    DelayedSpec(spec, (delaySeconds * 1_000_000_000L).toLong())

private class DelayedSpec<T>(private val base: FiniteAnimationSpec<T>, private val delayNanos: Long) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        DelayedVectorizedSpec(base.vectorize(converter), delayNanos)

    override fun equals(other: Any?) = other is DelayedSpec<*> && other.base == base && other.delayNanos == delayNanos
    override fun hashCode() = base.hashCode() * 31 + delayNanos.hashCode()
}

private class DelayedVectorizedSpec<V : AnimationVector>(
    private val base: VectorizedFiniteAnimationSpec<V>,
    private val delayNanos: Long,
) : VectorizedFiniteAnimationSpec<V> {
    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        if (playTimeNanos < delayNanos) initialValue
        else base.getValueFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        if (playTimeNanos < delayNanos) initialVelocity
        else base.getVelocityFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        delayNanos + base.getDurationNanos(initialValue, targetValue, initialVelocity)
}

/**
 * The [Motion] vocabulary as live tunables, same keys and defaults. Keep each declaration on one
 * line: `scripts/tune.py bake` rewrites its default.
 */
object MotionTunables {
    // Springs (duration s, bounce)

    val microInSpring = TunableSpring("motion.springs.microIn", "Micro in", duration = 0.26, bounce = 0.15)
    val microOutSpring = TunableSpring("motion.springs.microOut", "Micro out", duration = 0.18, bounce = 0.0)
    val layoutSpring = TunableSpring("motion.springs.layout", "Layout", duration = 0.34, bounce = 0.0)
    val collapseSpring = TunableSpring("motion.springs.collapse", "Collapse", duration = 0.26, bounce = 0.0)
    val panelOpenSpring = TunableSpring("motion.springs.panelOpen", "Panel open", duration = 0.40, bounce = 0.10)
    val panelCloseSpring = TunableSpring("motion.springs.panelClose", "Panel close", duration = 0.30, bounce = 0.0)
    val surfaceSpring = TunableSpring("motion.springs.surface", "Surface", duration = 0.44, bounce = 0.0)
    val flightSpring = TunableSpring("motion.springs.flight", "Flight", duration = 0.38, bounce = 0.0)
    /** Critically damped release stiffness (ω, rad/s): `2π / ω` ≈ 0.22 s. */
    val releaseOmega = Tunable("motion.springs.releaseOmega", "Release ω", default = 28.5, range = 12.0..60.0, step = 0.5)
    /** Keyboard-riding motion when the system's own inset animation isn't available. */
    val keyboardDuration = Tunable("motion.springs.keyboard", "Keyboard fallback (s)", default = 0.267, range = 0.15..0.5, step = 0.005)

    // Fades & eases (seconds)

    val pressDuration = Tunable("motion.fades.press", "Press down (s)", default = 0.10, range = 0.04..0.25, step = 0.01)
    val swapInDuration = Tunable("motion.fades.swapIn", "Swap in (s)", default = 0.22, range = 0.08..0.5, step = 0.01)
    val swapOutDuration = Tunable("motion.fades.swapOut", "Swap out (s)", default = 0.14, range = 0.06..0.4, step = 0.01)
    val dimDuration = Tunable("motion.fades.dim", "Dim (s)", default = 0.32, range = 0.1..0.8, step = 0.01)
    val reducedDuration = Tunable("motion.fades.reduced", "Reduce Motion fade (s)", default = 0.20, range = 0.05..0.5, step = 0.01)
    /** One beat after glass starts growing before its content arrives. */
    val contentDelay = Tunable("motion.fades.contentDelay", "Content delay (s)", default = 0.05, range = 0.0..0.2, step = 0.01)
    val staggerStep = Tunable("motion.fades.staggerStep", "Stagger step (s)", default = 0.02, range = 0.0..0.08, step = 0.005)
    val staggerCap = Tunable("motion.fades.staggerCap", "Stagger cap (s)", default = 0.10, range = 0.02..0.3, step = 0.01)

    // Geometry & thresholds

    val pressScale = Tunable("motion.geometry.pressScale", "Press scale", default = 0.97f, range = 0.90..1.0, step = 0.005)
    val microInScale = Tunable("motion.geometry.microInScale", "Micro in scale", default = 0.85f, range = 0.5..1.0, step = 0.01)
    val swapScale = Tunable("motion.geometry.swapScale", "Swap & exit scale", default = 0.96f, range = 0.85..1.0, step = 0.01)
    val rollDistance = Tunable("motion.geometry.rollDistance", "Roll (pt)", default = 5.dp, range = 0.0..16.0, step = 0.5)
    val blurSubtle = Tunable("motion.geometry.blurSubtle", "Subtle blur (pt)", default = 3.dp, range = 0.0..12.0, step = 0.5)
    val blurPanel = Tunable("motion.geometry.blurPanel", "Panel blur (pt)", default = 8.dp, range = 0.0..24.0, step = 0.5)
    val blurSheet = Tunable("motion.geometry.blurSheet", "Sheet blur (pt)", default = 14.dp, range = 0.0..30.0, step = 0.5)
    val blurBackdrop = Tunable("motion.geometry.blurBackdrop", "Backdrop blur (pt)", default = 18.dp, range = 0.0..40.0, step = 0.5)
    val entranceRise = Tunable("motion.geometry.entranceRise", "Entrance rise (pt)", default = 6.dp, range = 0.0..20.0, step = 1.0)
    val panelRise = Tunable("motion.geometry.panelRise", "Panel rise (pt)", default = 10.dp, range = 0.0..28.0, step = 1.0)
    val widthLead = Tunable("motion.geometry.widthLead", "Flight width lead (×)", default = 1.65, range = 1.0..2.5, step = 0.05)
    /** Dp per second. */
    val flickVelocity = Tunable("motion.geometry.flickVelocity", "Flick (pt/s)", default = 250.0, range = 100.0..900.0, step = 25.0)

    // Ambient clock (seconds)

    val breathPeriod = Tunable("motion.ambient.breathPeriod", "Breath period (s)", default = 1.70, range = 0.8..3.5, step = 0.05)
    val sweepPeriod = Tunable("motion.ambient.sweepPeriod", "Sweep period (s)", default = 1.60, range = 0.6..3.5, step = 0.05)

    /**
     * Lists every role's tunables in the panel before anything has animated, in section order, and
     * drops overrides left on keys this vocabulary retired.
     */
    fun register(store: TuningStore = TuningStore.shared) {
        store.register(microInSpring)
        store.register(microOutSpring)
        store.register(layoutSpring)
        store.register(collapseSpring)
        store.register(panelOpenSpring)
        store.register(panelCloseSpring)
        store.register(surfaceSpring)
        store.register(flightSpring)
        store.register(releaseOmega)
        store.register(keyboardDuration)
        store.register(pressDuration, swapInDuration, swapOutDuration, dimDuration, reducedDuration, contentDelay, staggerStep, staggerCap)
        store.register(pressScale, microInScale, swapScale, rollDistance, blurSubtle, blurPanel, blurSheet, blurBackdrop, entranceRise, panelRise, widthLead, flickVelocity)
        store.register(breathPeriod, sweepPeriod)
        val stale = HashMap<String, TuneValue?>()
        for (key in store.overrides.keys) {
            if (Motion.isRetiredKey(key)) stale[key] = null
        }
        if (stale.isNotEmpty()) store.set(stale)
    }
}
