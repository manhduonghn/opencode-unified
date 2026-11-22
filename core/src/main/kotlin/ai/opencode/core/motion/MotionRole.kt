package ai.opencode.core.motion

import ai.opencode.core.tuning.AnimationTimeScale
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/** Which half of a role's motion is playing: arriving or leaving. */
enum class MotionPhase { Enter, Exit }

/**
 * A curve one role resolves to: a spring, an ease-out or an ease-in-out. Compose specs
 * ([animation]) and [SpringSolver] read the same numbers.
 */
sealed interface MotionCurve {
    val duration: Double
    val bounce: Double get() = 0.0

    /** The `(ω, ζ)` a [SpringSolver] uses for this curve; an ease is treated as a critically damped spring of the same duration. */
    val springParameters: SpringSolver.Parameters get() = SpringSolver.parameters(duration, bounce)

    data class Spring(override val duration: Double, override val bounce: Double) : MotionCurve
    data class EaseOut(override val duration: Double) : MotionCurve
    data class EaseInOut(override val duration: Double) : MotionCurve

    /** The Compose spec for this curve, following Developer Mode slow motion. */
    fun <T> animation(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = when (this) {
        is Spring -> MotionSpring(duration, bounce).spec(visibilityThreshold)
        is EaseOut -> tween(durationMillis = Motion.millis(duration), easing = Motion.EaseOut)
        is EaseInOut -> tween(durationMillis = Motion.millis(duration), easing = Motion.EaseInOut)
    }
}

/**
 * One of the fourteen motion roles: the role picks the curve for each phase. Under Reduce Motion
 * every role is [Reduced]'s cross-fade.
 */
enum class MotionRole {
    Press, MicroIn, MicroOut, Swap, Layout, Collapse, PanelOpen, PanelClose, Surface, Release, Flight, Keyboard, Dim, Reduced;

    /** The role's curve for [phase], or [Reduced]'s under Reduce Motion. */
    fun curve(phase: MotionPhase = MotionPhase.Enter, reduceMotion: Boolean = false): MotionCurve {
        if (reduceMotion) return Reduced.curve()
        val enter = phase == MotionPhase.Enter
        return when (this) {
            Press -> if (enter) MotionCurve.EaseOut(MotionTunables.pressDuration.value) else springOf(MotionTunables.microInSpring.spring)
            MicroIn -> springOf(if (enter) MotionTunables.microInSpring.spring else MotionTunables.microOutSpring.spring)
            MicroOut -> springOf(MotionTunables.microOutSpring.spring)
            Swap -> MotionCurve.EaseOut(if (enter) MotionTunables.swapInDuration.value else MotionTunables.swapOutDuration.value)
            Layout -> springOf(if (enter) MotionTunables.layoutSpring.spring else MotionTunables.collapseSpring.spring)
            Collapse -> springOf(MotionTunables.collapseSpring.spring)
            PanelOpen -> springOf(if (enter) MotionTunables.panelOpenSpring.spring else MotionTunables.panelCloseSpring.spring)
            PanelClose -> springOf(MotionTunables.panelCloseSpring.spring)
            Surface -> springOf(MotionTunables.surfaceSpring.spring)
            Flight -> springOf(MotionTunables.flightSpring.spring)
            Release -> MotionCurve.Spring(2 * PI / max(MotionTunables.releaseOmega.value, 0.01), 0.0)
            Keyboard -> MotionCurve.Spring(MotionTunables.keyboardDuration.value, 0.0)
            Dim -> MotionCurve.EaseOut(MotionTunables.dimDuration.value)
            Reduced -> MotionCurve.EaseInOut(MotionTunables.reducedDuration.value)
        }
    }

    /**
     * The role's enter curve as a [MotionSpring] (an ease reads as a critically damped spring of
     * its duration).
     */
    val spring: MotionSpring
        get() {
            val curve = curve()
            return MotionSpring(curve.duration, curve.bounce)
        }

    /** The Compose spec for [phase]. The delay applies only when motion isn't reduced. */
    fun <T> animation(
        phase: MotionPhase = MotionPhase.Enter,
        reduceMotion: Boolean = false,
        delaySeconds: Double = 0.0,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> {
        val base = curve(phase, reduceMotion).animation(visibilityThreshold)
        if (reduceMotion || delaySeconds <= 0.0) return base
        return delayed(base, delaySeconds / AnimationTimeScale.shared.observedScale)
    }

    /** [animation] delayed by [staggerDelay] for item [index]. */
    fun <T> staggered(
        index: Int,
        phase: MotionPhase = MotionPhase.Enter,
        reduceMotion: Boolean = false,
        visibilityThreshold: T? = null,
    ): FiniteAnimationSpec<T> = animation(phase, reduceMotion, staggerDelay(index), visibilityThreshold)

    /** The velocity to hand `Animatable.animateTo`, zero under Reduce Motion. */
    fun initialVelocity(velocity: Float, reduceMotion: Boolean): Float = if (reduceMotion) 0f else velocity

    companion object {
        /** `index × staggerStep`, capped at `staggerCap`. */
        fun staggerDelay(index: Int): Double =
            min(max(index, 0) * MotionTunables.staggerStep.value, MotionTunables.staggerCap.value)

        private fun springOf(spring: MotionSpring): MotionCurve = MotionCurve.Spring(spring.duration, spring.bounce)
    }
}

/** A solver on [role]'s curve. Under Reduce Motion it runs the reduced curve from rest. */
fun SpringSolver(
    role: MotionRole,
    phase: MotionPhase = MotionPhase.Enter,
    value: Double,
    velocity: Double = 0.0,
    target: Double,
    reduceMotion: Boolean = false,
    positionEpsilon: Double = 0.25,
    velocityEpsilon: Double = 4.0,
): SpringSolver {
    val parameters = role.curve(phase, reduceMotion).springParameters
    return SpringSolver(
        value = value,
        velocity = if (reduceMotion) 0.0 else velocity,
        target = target,
        omega = parameters.omega,
        zeta = parameters.zeta,
        positionEpsilon = positionEpsilon,
        velocityEpsilon = velocityEpsilon,
    )
}

/**
 * Aims at [newTarget] on [role]'s curve, keeping position (and velocity unless motion is reduced).
 */
fun SpringSolver.retarget(
    newTarget: Double,
    role: MotionRole,
    phase: MotionPhase = MotionPhase.Enter,
    preservingVelocity: Double? = null,
    reduceMotion: Boolean = false,
) {
    val curve = role.curve(phase, reduceMotion)
    retarget(newTarget, preservingVelocity = preservingVelocity, duration = curve.duration, bounce = curve.bounce)
    if (reduceMotion) velocity = 0.0
}
