package ai.opencode.core.diagnostics

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How late each frame callback came for the frame it was due to prepare, so a refresh-rate change
 * costs nothing. Seconds.
 */
class PerfFrameClock {
    data class Frame(
        /** Time between the two callbacks. */
        val intervalMs: Double,
        /** The period the previous callback was scheduled for. */
        val periodMs: Double,
        /** How far past its deadline this callback came. */
        val lateMs: Double,
    )

    private var deadline: Double? = null
    private var period = 0.0
    private var last = 0.0

    /** One callback for the frame at [timestamp], preparing the one at [target]. Null for the first. */
    fun tick(timestamp: Double, target: Double): Frame? {
        val deadline = deadline
        val period = period
        val last = last
        this.deadline = target
        this.period = target - timestamp
        this.last = timestamp
        if (deadline == null || period <= 0) return null
        // Display timestamps are vsync times; a hair of float noise isn't lateness.
        val late = timestamp - deadline
        return Frame(
            intervalMs = (timestamp - last) * 1_000,
            periodMs = period * 1_000,
            lateMs = if (late > period * 0.01) late * 1_000 else 0.0,
        )
    }
}

/**
 * Callback lateness summed over a window: long frames, the hitch time they add, the frames they
 * drop, and main-thread stalls. Pure.
 */
class PerfFrameWindow {
    var frames = 0
    /** Time the window covers, callback to callback. */
    var spanMs = 0.0
    /** Callbacks half a period or more past their deadline. */
    var long = 0
    /** Their lateness, added up. */
    var hitchMs = 0.0
    /** Refreshes no frame was prepared for. */
    var dropped = 0
    /** Callbacks past their deadline by each of [stallThresholds]. */
    val stalls = IntArray(3)
    /** The latest callback. */
    var maxLateMs = 0.0

    /** True when the frame was a stall. */
    fun add(frame: PerfFrameClock.Frame): Boolean {
        frames += 1
        spanMs += frame.intervalMs
        maxLateMs = max(maxLateMs, frame.lateMs)
        if (frame.lateMs >= frame.periodMs * 0.5) {
            long += 1
            hitchMs += frame.lateMs
            dropped += max(1, (frame.lateMs / frame.periodMs).roundToInt())
        }
        for ((index, threshold) in stallThresholds.withIndex()) if (frame.lateMs >= threshold) stalls[index] += 1
        return frame.lateMs >= stallThresholds[0]
    }

    companion object {
        /** A callback this many milliseconds past its deadline is a main-thread stall. */
        val stallThresholds: List<Double> = listOf(50.0, 100.0, 250.0)
    }
}

/**
 * During a `perf-frames` lab run, posts `frames_window` every half second and `frame_stall` per
 * stall. The prepared frame is `frameTimeNanos` + the current refresh period, re-read every frame.
 */
object PerfFrameSampler {
    /** The `perf-frames` launch extra; set by `DevTools.handleLaunchIntent`. */
    @Volatile var framesRequested: Boolean = false

    internal val isEnabled: Boolean get() = LabMarks.isEnabled && framesRequested

    /** Main thread. */
    fun startIfRequested() {
        if (!isEnabled) return
        PerfFrameLink.start()
    }
}

/** The display's current refresh period in seconds (60 Hz when unknown). */
object PerfDisplay {
    private val display: Display? by lazy {
        runCatching { dependency(AppContext).getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
    }

    fun periodSeconds(): Double {
        val rate = display?.refreshRate ?: 60f
        return 1.0 / (if (rate > 1f) rate else 60f)
    }
}

private object PerfFrameLink : Choreographer.FrameCallback {
    private const val WINDOW_MS = 500.0

    private var running = false
    private val clock = PerfFrameClock()
    private var window = PerfFrameWindow()
    private var windowStart = 0L

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        val wall = System.currentTimeMillis()
        val timestamp = frameTimeNanos / 1e9
        val frame = clock.tick(timestamp, timestamp + PerfDisplay.periodSeconds())
        if (frame == null) {
            windowStart = wall
            return
        }
        if (window.add(frame)) {
            val late = frame.lateMs.roundToInt()
            LabMarks.post("frame_stall", mapOf("stallMs" to late, "since" to wall - late))
        }
        if (window.spanMs < WINDOW_MS) return
        LabMarks.post(
            "frames_window",
            mapOf(
                "since" to windowStart, "frames" to window.frames, "spanMs" to window.spanMs.roundToInt(),
                "long" to window.long, "hitchMs" to window.hitchMs.roundToInt(), "dropped" to window.dropped,
                "stall50" to window.stalls[0], "stall100" to window.stalls[1], "stall250" to window.stalls[2],
                "maxMs" to window.maxLateMs.roundToInt(),
            ),
        )
        window = PerfFrameWindow()
        windowStart = wall
    }
}

/**
 * Main-thread latency from a named moment to the frame showing its result, posted as a
 * `perf_<name>` mark. One at a time, each name at most every 250 ms.
 */
object PerfFrameLatency {
    val isEnabled: Boolean get() = PerfFrameSampler.isEnabled

    fun begin(name: String, counts: Map<String, Int> = emptyMap()) {
        if (!isEnabled) return
        val start = System.nanoTime()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            PerfLatencyLink.begin(name, start, counts)
        } else {
            main.post { PerfLatencyLink.begin(name, start, counts) }
        }
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
}

/** Main thread. */
private object PerfLatencyLink : Choreographer.FrameCallback {
    private const val MINIMUM_INTERVAL_NANOS = 250_000_000L

    private class Pending(val name: String, val start: Long, val counts: Map<String, Int>)

    private var pending: Pending? = null
    private val lastStart = HashMap<String, Long>()

    fun begin(name: String, start: Long, counts: Map<String, Int>) {
        if (pending != null) return
        val last = lastStart[name]
        if (last != null && start - last < MINIMUM_INTERVAL_NANOS) return
        pending = Pending(name, start, counts)
        lastStart[name] = start
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val pending = pending ?: return
        this.pending = null
        // `frameTimeNanos` and `System.nanoTime` share the monotonic clock.
        val target = frameTimeNanos + (PerfDisplay.periodSeconds() * 1e9).toLong()
        val ms = ((target - pending.start) / 1e6).roundToInt()
        LabMarks.post("perf_${pending.name}", pending.counts + ("ms" to ms))
    }
}
