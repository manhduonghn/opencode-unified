package ai.opencode.core.motion

import ai.opencode.core.tuning.AnimationTimeScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * One clock for every ambient loop, so loops on screen stay in step and a remounted loop resumes in
 * phase.
 */
object AmbientClock {
    val breathPeriod: Double get() = max(0.2, MotionTunables.breathPeriod.value)
    val sweepPeriod: Double get() = max(0.2, MotionTunables.sweepPeriod.value)

    /** Where a breath rests when the clock is paused (Reduce Motion): halfway. */
    const val settledBreath: Double = 0.5

    /** Seconds on the shared clock, following Developer Mode slow motion. */
    fun mediaTime(realSeconds: Double = System.nanoTime() / 1e9): Double = AnimationTimeScale.shared.now(realSeconds)

    /** Fraction `0 ..< 1` of the way through a [period] at [mediaTime]. */
    fun phase(mediaTime: Double, period: Double): Double {
        val length = max(period, 0.01)
        val phase = (mediaTime % length) / length
        return if (phase < 0) phase + 1 else phase
    }

    /** `0 → 1 → 0` over one breath, starting and ending at rest. */
    fun breath(mediaTime: Double): Double = 0.5 - 0.5 * cos(2 * PI * phase(mediaTime, breathPeriod))

    /** `0 → 1` over one sweep (or [fraction] of one). */
    fun sweep(mediaTime: Double, fraction: Double = 1.0): Double = phase(mediaTime, sweepPeriod * fraction)

    data class Sample(val breath: Double, val sweep: Double, val isSettled: Boolean) {
        companion object {
            val settled = Sample(breath = settledBreath, sweep = 0.0, isSettled = true)
        }
    }

    fun sample(realSeconds: Double = System.nanoTime() / 1e9): Sample {
        val time = mediaTime(realSeconds)
        return Sample(breath = breath(time), sweep = sweep(time), isSettled = false)
    }
}

/** How often an ambient loop redraws: full rate in front, half behind. */
enum class AmbientRate {
    Foreground, Background;

    val minimumInterval: Double
        get() = when (this) {
            Foreground -> 1.0 / 60.0
            Background -> 1.0 / 30.0
        }
}

/**
 * Draws [content] each frame (at most [rate]); settled under Reduce Motion, when not [isActive], or
 * while not resumed.
 */
@Composable
fun AmbientTimeline(
    rate: AmbientRate = AmbientRate.Foreground,
    isActive: Boolean = true,
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable (AmbientClock.Sample) -> Unit,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val paused = reduceMotion || !isActive || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val sample = remember { mutableStateOf(AmbientClock.Sample.settled) }
    LaunchedEffect(paused, rate) {
        if (paused) {
            sample.value = AmbientClock.Sample.settled
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L || (now - last) / 1e9 >= rate.minimumInterval - 0.001) {
                    last = now
                    sample.value = AmbientClock.sample()
                }
            }
        }
    }
    content(if (reduceMotion) AmbientClock.Sample.settled else sample.value)
}
