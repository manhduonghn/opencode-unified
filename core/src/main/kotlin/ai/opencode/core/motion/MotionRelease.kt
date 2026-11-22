package ai.opencode.core.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The release rule: commits on a flick toward the target, never away, else on passing halfway.
 * Velocities in dp/s.
 */
object MotionRelease {
    const val commitProgress: Double = 0.5

    fun shouldCommit(progress: Double, velocity: Double, flickVelocity: Double = MotionTunables.flickVelocity.value): Boolean {
        if (velocity >= flickVelocity) return true
        if (velocity <= -flickVelocity) return false
        return progress >= commitProgress
    }

    fun shouldCommit(translation: Double, velocity: Double, distance: Double, flickVelocity: Double = MotionTunables.flickVelocity.value): Boolean =
        shouldCommit(progress = if (distance > 0) translation / distance else 0.0, velocity = velocity, flickVelocity = flickVelocity)

    /** A gesture's velocity as a fraction of the distance left per second, for a spring's initial velocity. */
    fun relativeVelocity(velocity: Double, from: Double, to: Double): Double {
        val distance = to - from
        if (abs(distance) <= 0.5) return 0.0
        return velocity / distance
    }
}

/** An overscroll band with an exact inverse, so a grab mid-band resumes without a jump. */
class MotionRubberBand(dimension: Double, coefficient: Double = 0.55) {
    val dimension: Double = max(dimension, 1.0)
    val coefficient: Double = max(coefficient, 0.01)

    fun stretch(excess: Double): Double {
        val magnitude = (1 - 1 / (abs(excess) * coefficient / dimension + 1)) * dimension
        return if (excess < 0) -magnitude else magnitude
    }

    fun excess(forStretch: Double): Double {
        val drawn = min(abs(forStretch), dimension - 0.01)
        val magnitude = drawn * dimension / (coefficient * (dimension - drawn))
        return if (forStretch < 0) -magnitude else magnitude
    }

    /** d stretch / d excess: [coefficient] at the edge, falling off smoothly. */
    fun slope(excess: Double): Double {
        val base = abs(excess) * coefficient / dimension + 1
        return coefficient / (base * base)
    }

    fun band(value: Double, bounds: ClosedFloatingPointRange<Double>): Double {
        if (value < bounds.start) return bounds.start + stretch(value - bounds.start)
        if (value > bounds.endInclusive) return bounds.endInclusive + stretch(value - bounds.endInclusive)
        return value
    }

    fun unband(position: Double, bounds: ClosedFloatingPointRange<Double>): Double {
        if (position < bounds.start) return bounds.start + excess(position - bounds.start)
        if (position > bounds.endInclusive) return bounds.endInclusive + excess(position - bounds.endInclusive)
        return position
    }

    override fun equals(other: Any?): Boolean =
        other is MotionRubberBand && other.dimension == dimension && other.coefficient == coefficient

    override fun hashCode(): Int = dimension.hashCode() * 31 + coefficient.hashCode()
}
