package ai.opencode.core.motion

import ai.opencode.core.tuning.AnimationTimeScale
import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.min

/** What a frame callback wants next. */
enum class MotionFrameAction { Continue, Stop }

/**
 * One frame of a [motionFrameLoop], in seconds. [dt] is capped at `maxFrames` frames, so a hitch
 * never teleports a spring, and scaled.
 */
data class MotionTick(val dt: Double, val now: Double, val rawFrameDuration: Double, val scale: Double)

/**
 * A per-frame loop on Compose's frame clock until [onTick] returns [MotionFrameAction.Stop] or the
 * coroutine is cancelled. Never read wall-clock time inside a step.
 */
suspend fun motionFrameLoop(
    frameDurationSeconds: Double = 1.0 / 60.0,
    maxFrames: Double = 2.0,
    timeScale: Double? = null,
    onTick: (MotionTick) -> MotionFrameAction,
) {
    val rawFrame = max(1.0 / 240.0, frameDurationSeconds)
    var previousNanos = 0L
    var virtualNow = 0.0
    while (true) {
        val action = withFrameNanos { frameNanos ->
            val rawDt = if (previousNanos == 0L) rawFrame else min(max((frameNanos - previousNanos) / 1e9, 0.0), maxFrames * rawFrame)
            previousNanos = frameNanos
            // Samples `AnimationTimeScale.shared.scale` every frame, so slow motion engaged
            // mid-flight applies at once.
            val scale = timeScale ?: AnimationTimeScale.shared.scale
            val dt = rawDt * scale
            virtualNow += dt
            onTick(MotionTick(dt = dt, now = virtualNow, rawFrameDuration = rawFrame, scale = scale))
        }
        if (action == MotionFrameAction.Stop) return
    }
}

/** The current display's frame duration in seconds (1/120 on a 120 Hz panel), for [motionFrameLoop]. */
@Composable
fun rememberFrameDurationSeconds(): Double {
    val view = LocalView.current
    val rate = view.display?.refreshRate?.takeIf { it > 0f } ?: 60f
    return remember(rate) { 1.0 / rate }
}

/** Android's Reduce Motion: Remove animations (animator duration scale 0). Live-updating. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val reduce = remember(context) { ReduceMotionSetting.state(context) }
    return reduce.value
}

/**
 * Remove animations for the whole app, kept current by one observer so rows don't each register
 * one. Main thread only.
 */
private object ReduceMotionSetting {
    private var resolver: ContentResolver? = null
    private var state: MutableState<Boolean>? = null

    fun state(context: Context): State<Boolean> {
        val appResolver = (context.applicationContext ?: context).contentResolver
        val current = state
        // A new application (each Robolectric test has its own) gets its own observer.
        if (current != null && resolver === appResolver) return current
        val created = mutableStateOf(read(appResolver))
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                created.value = read(appResolver)
            }
        }
        appResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver = appResolver
        state = created
        return created
    }

    private fun read(resolver: ContentResolver): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
