package ai.opencode.feature.voice

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the call is doing, as the orb shows it. */
enum class FloatingCallMood { connecting, live, thinking, needsInput, ended }

/** Playground scenes: they stand in for the call's voices and state. */
enum class FloatingCallScene { idle, listening, agentSpeaking, bothSpeaking, thinking, needsInput }

/**
 * The call button's spin (Lift and Spin Up), in closed form: the phone glyph starts it on the
 * press, it carries on while the call connects and dies away on connect. Hanging up winds it
 * down onto an upright glyph. Radians and seconds; the glyph is upright at whole turns.
 */
data class LivingOrbSpin(
    val time: Double,
    val angle: Double = 0.0,
    val rate: Double = 0.0,
    val mode: Mode = Mode.Ramp(rate = 0.0, tau = 1.0),
) {
    sealed interface Mode {
        /** The speed eases toward [rate] (rad/s) with time constant [tau]. */
        data class Ramp(val rate: Double, val tau: Double) : Mode

        /** Critically damped onto [angle], never turning back. */
        data class Settle(val angle: Double, val omega: Double) : Mode
    }

    data class Sample(val angle: Double, val rate: Double)

    fun sample(at: Double): Sample {
        val dt = max(0.0, at - time)
        return when (val mode = mode) {
            is Mode.Ramp -> {
                val tau = max(0.01, mode.tau)
                val fall = exp(-dt / tau)
                Sample(angle + mode.rate * dt + (rate - mode.rate) * tau * (1 - fall), mode.rate + (rate - mode.rate) * fall)
            }
            is Mode.Settle -> {
                val x = angle - mode.angle
                val c = rate + mode.omega * x
                val e = exp(-mode.omega * dt)
                Sample(mode.angle + (x + c * dt) * e, (c - mode.omega * (x + c * dt)) * e)
            }
        }
    }

    /** From where it is at [at], its speed easing toward [rate]. */
    fun ramping(rate: Double, tau: Double, at: Double): LivingOrbSpin {
        val now = sample(at)
        return LivingOrbSpin(time = at, angle = now.angle, rate = now.rate, mode = Mode.Ramp(rate = rate, tau = tau))
    }

    /**
     * From where it is at [at], onto the next upright angle: spinning, the first one a
     * critically damped spring at [omega] can stop on without passing it (it needs
     * `rate / omega`), so it spins down; nearly still, at least [turn] on, so it turns into
     * place. An upright further on than the spin would coast to pulls it forward, by no more
     * than [acceleration] (rad/s²) so it never whips: the spring slows to suit.
     */
    fun settling(omega: Double, turn: Double, acceleration: Double = 600.0, at: Double): LivingOrbSpin {
        val now = sample(at)
        val rate = max(0.0, now.rate)
        val ahead = now.angle + maxOf(if (rate > 1) 0.0 else turn, rate / omega, 1e-6)
        val goal = ceil(ahead / (2 * Math.PI)) * 2 * Math.PI
        // Critically damped from `distance` behind at `rate`, it starts speeding up at ω(ω·distance − 2·rate).
        val distance = goal - now.angle
        val gentle = (rate + sqrt(rate * rate + distance * acceleration)) / distance
        return LivingOrbSpin(time = at, angle = now.angle, rate = now.rate, mode = Mode.Settle(angle = goal, omega = min(omega, gentle)))
    }

    /** Settling, it's upright and still to well under a point at the glyph's edge. */
    fun isSettled(at: Double): Boolean {
        val mode = mode as? Mode.Settle ?: return false
        val now = sample(at)
        return abs(now.angle - mode.angle) < 0.005 && abs(now.rate) < 0.05
    }
}
