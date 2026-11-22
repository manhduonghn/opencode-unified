package ai.opencode.core.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One axis of an analytical spring, stepped in closed form for any `dt`. [retarget] keeps position
 * and velocity continuous. Mutable; [copy] gives an independent value.
 */
class SpringSolver(
    var value: Double = 0.0,
    var velocity: Double = 0.0,
    var target: Double = value,
    omega: Double,
    zeta: Double = 1.0,
    var positionEpsilon: Double = 0.25,
    var velocityEpsilon: Double = 4.0,
) {
    var omega: Double = max(omega, 0.01)
    var zeta: Double = max(zeta, 0.01)

    data class Step(val value: Double, val velocity: Double, val isSettled: Boolean)

    data class Parameters(val omega: Double, val zeta: Double)

    constructor(
        spring: MotionSpring,
        value: Double = 0.0,
        velocity: Double = 0.0,
        target: Double = value,
        positionEpsilon: Double = 0.25,
        velocityEpsilon: Double = 4.0,
    ) : this(
        value,
        velocity,
        target,
        parameters(spring.duration, spring.bounce).omega,
        parameters(spring.duration, spring.bounce).zeta,
        positionEpsilon,
        velocityEpsilon,
    )

    val isSettled: Boolean
        get() = abs(value - target) <= positionEpsilon && abs(velocity) <= velocityEpsilon

    /** Retargets while preserving in-flight velocity (or seeding a gesture velocity). */
    fun retarget(newTarget: Double, preservingVelocity: Double? = null, duration: Double? = null, bounce: Double? = null) {
        target = newTarget
        if (preservingVelocity != null) velocity = preservingVelocity
        if (duration != null) {
            val params = parameters(duration, bounce ?: 0.0)
            omega = params.omega
            zeta = params.zeta
        }
    }

    /** Retargets using a [MotionSpring]'s `(duration, bounce)`. */
    fun retarget(newTarget: Double, spring: MotionSpring, preservingVelocity: Double? = null) =
        retarget(newTarget, preservingVelocity, spring.duration, spring.bounce)

    /** Steps forward by [dt] seconds toward [target]. */
    fun step(dt: Double, snapWhenSettled: Boolean = true): Step =
        step(target, omega, zeta, dt, snapWhenSettled)

    /** Steps forward by [dt] seconds toward an explicit target and `(omega, zeta)`. */
    fun step(toward: Double, omega: Double, zeta: Double, dt: Double, snapWhenSettled: Boolean = false): Step {
        target = toward
        this.omega = omega
        this.zeta = zeta
        val (nextValue, nextVelocity) = evaluate(from = value, to = toward, initialVelocity = velocity, omega = omega, zeta = zeta, elapsed = dt)
        value = nextValue
        velocity = nextVelocity
        val settled = isSettled
        if (snapWhenSettled && settled) {
            value = toward
            velocity = 0.0
        }
        return Step(value, velocity, settled)
    }

    fun copy(): SpringSolver =
        SpringSolver(value, velocity, target, omega, zeta, positionEpsilon, velocityEpsilon)

    override fun equals(other: Any?): Boolean = other is SpringSolver &&
        value == other.value && velocity == other.velocity && target == other.target &&
        omega == other.omega && zeta == other.zeta &&
        positionEpsilon == other.positionEpsilon && velocityEpsilon == other.velocityEpsilon

    override fun hashCode(): Int = listOf(value, velocity, target, omega, zeta).hashCode()

    override fun toString(): String = "SpringSolver(value=$value, velocity=$velocity, target=$target, ω=$omega, ζ=$zeta)"

    companion object {
        fun of(
            duration: Double,
            bounce: Double = 0.0,
            value: Double = 0.0,
            velocity: Double = 0.0,
            target: Double = value,
            positionEpsilon: Double = 0.25,
            velocityEpsilon: Double = 4.0,
        ): SpringSolver = SpringSolver(MotionSpring(duration, bounce), value, velocity, target, positionEpsilon, velocityEpsilon)

        /** ω = 2π / duration, ζ = 1 − bounce (or 1 / (1 + bounce) for negative bounce). */
        fun parameters(duration: Double, bounce: Double): Parameters {
            val omega = 2 * PI / max(duration, 0.01)
            val zeta = if (bounce >= 0) 1 - bounce else 1 / (1 + bounce)
            return Parameters(omega, max(zeta, 0.01))
        }

        /**
         * Closed-form solution at [elapsed] seconds from `(from, initialVelocity)` toward [to]
         * for critically damped (ζ = 1), underdamped (ζ < 1) and overdamped (ζ > 1) springs.
         */
        fun evaluate(
            from: Double,
            to: Double,
            initialVelocity: Double = 0.0,
            omega: Double,
            zeta: Double = 1.0,
            elapsed: Double,
        ): Pair<Double, Double> {
            val v0 = initialVelocity
            val dt = elapsed
            if (dt <= 0) return from to v0
            val x0 = from - to
            return if (abs(zeta - 1) < 1e-4) {
                val decay = exp(-omega * dt)
                val a = v0 + omega * x0
                (to + (x0 + a * dt) * decay) to ((v0 - omega * a * dt) * decay)
            } else if (zeta < 1) {
                val wd = omega * sqrt(1 - zeta * zeta)
                val decay = exp(-zeta * omega * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                val nextValue = to + decay * (x0 * c + (v0 + zeta * omega * x0) / wd * s)
                val nextVelocity = decay * (v0 * c - (zeta * omega * v0 + omega * omega * x0) / wd * s)
                nextValue to nextVelocity
            } else {
                val root = sqrt(zeta * zeta - 1)
                val r1 = -omega * (zeta - root)
                val r2 = -omega * (zeta + root)
                val c1 = (v0 - r2 * x0) / (r1 - r2)
                val c2 = x0 - c1
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                (to + c1 * e1 + c2 * e2) to (c1 * r1 * e1 + c2 * r2 * e2)
            }
        }

        /** Closed-form evaluation parameterized by a `(duration, bounce)` spring. */
        fun evaluate(
            from: Double,
            to: Double,
            initialVelocity: Double = 0.0,
            spring: MotionSpring,
            elapsed: Double,
        ): Pair<Double, Double> {
            val params = parameters(spring.duration, spring.bounce)
            return evaluate(from = from, to = to, initialVelocity = initialVelocity, omega = params.omega, zeta = params.zeta, elapsed = elapsed)
        }
    }
}
