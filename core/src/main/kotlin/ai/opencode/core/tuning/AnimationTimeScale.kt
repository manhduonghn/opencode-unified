package ai.opencode.core.tuning

import androidx.compose.runtime.mutableDoubleStateOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Slow motion from one virtual clock. Applied where motion is defined (`Motion.*`,
 * [MotionSpring.spec], `motionFrameLoop`), so a raw `tween(300)` doesn't slow.
 */
class AnimationTimeScale {
    private val lock = Any()
    @Volatile private var active = false
    @Volatile private var currentlyScaled = false
    @Volatile private var scaleValue = 1.0
    /** Virtual minus real time at `1×` after returning from slow motion (seconds). */
    @Volatile private var offset = 0.0
    private var hasScaled = false
    private var anchorReal = 0.0
    private var anchorVirtual = 0.0
    private val scaleState = mutableDoubleStateOf(1.0)

    /** True once slow motion has been engaged in this process. */
    val isActive: Boolean get() = active

    /** True when the current speed differs from `1×`. */
    val isScaled: Boolean get() = currentlyScaled

    /** Current scale (`1.0`, `0.5`, `0.25`, `0.1`). */
    val scale: Double get() = if (currentlyScaled) scaleValue else 1.0

    /** [scale], observed: a composable that builds animation specs from it recomposes when it changes. */
    val observedScale: Double get() = scaleState.doubleValue

    /** Scales a raw frame delta by [scale]. */
    fun scaledDelta(rawDt: Double): Double = if (currentlyScaled) rawDt * scaleValue else rawDt

    /** Monotonic virtual time (seconds, `System.nanoTime` domain) that advances at `scale × Δreal`, continuous across changes. */
    fun now(realSeconds: Double = System.nanoTime() / 1e9): Double {
        if (!active) return realSeconds
        if (!currentlyScaled) return realSeconds + offset
        return synchronized(lock) {
            if (!hasScaled) realSeconds else anchorVirtual + (realSeconds - anchorReal) * scaleValue
        }
    }

    /** A duration in virtual seconds as real seconds at the current scale (for `delay`). */
    fun wallSeconds(virtual: Double): Double = if (virtual <= 0) 0.0 else virtual / max(0.05, scale)

    /**
     * Sets the scale, re-anchoring the virtual clock so nothing jumps. Returns what was applied.
     */
    fun setScale(requested: Double, realSeconds: Double = System.nanoTime() / 1e9, bypassAccessCheck: Boolean = false): Double {
        val clamped = normalizeScale(requested)
        if (!bypassAccessCheck && !TuningAccess.isAvailable && clamped != 1.0) return 1.0
        val applied = synchronized(lock) {
            if (!hasScaled) {
                if (abs(clamped - 1.0) <= 1e-6) return 1.0
                hasScaled = true
                anchorReal = realSeconds
                anchorVirtual = realSeconds
            } else {
                anchorVirtual += (realSeconds - anchorReal) * scaleValue
                anchorReal = realSeconds
            }
            val isNonUnit = abs(clamped - 1.0) > 1e-6
            if (!isNonUnit) offset = anchorVirtual - anchorReal
            scaleValue = clamped
            currentlyScaled = isNonUnit
            active = true
            clamped
        }
        scaleState.doubleValue = applied
        return applied
    }

    /** Back to `1×`, keeping the virtual clock continuous. */
    fun reset(realSeconds: Double = System.nanoTime() / 1e9) {
        setScale(1.0, realSeconds, bypassAccessCheck = true)
    }

    companion object {
        val shared = AnimationTimeScale()
        val supportedScales = listOf(1.0, 0.5, 0.25, 0.1)

        fun normalizeScale(value: Double): Double {
            if (!value.isFinite()) return 1.0
            val clamped = min(max(value, 0.05), 2.0)
            supportedScales.firstOrNull { abs(it - clamped) < 0.02 }?.let { return it }
            return swiftRounded(clamped * 100) / 100
        }

        /** An intent extra or environment value, normalized. */
        fun requestedLabScale(argument: String?, environment: String? = System.getenv("OPENCODE_LAB_TIME_SCALE")): Double? {
            for (raw in listOf(argument, environment)) {
                val value = raw?.toDoubleOrNull() ?: continue
                if (value.isFinite() && value > 0) return normalizeScale(value)
            }
            return null
        }
    }
}
