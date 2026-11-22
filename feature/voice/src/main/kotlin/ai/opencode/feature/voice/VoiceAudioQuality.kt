package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Five-second windows of arrivals, mid-speech starvation and capture/send pacing. */
class VoiceAudioQuality(private val clock: Clock = dependency(Clock)) {
    private var windowStart = clock.now()
    private var received = 0
    private var receivedMilliseconds = 0.0
    private var lastArrival: Duration? = null
    private val arrivalGaps = mutableListOf<Double>()
    private var dropouts = 0
    private var dropoutMilliseconds = 0.0
    private var queuedMilliseconds = 0.0
    private var prerollMilliseconds: Double? = null
    private var captured = 0
    private var captureDropped = 0
    private var lastCapture: Duration? = null
    private val captureGaps = mutableListOf<Double>()
    private val sendMilliseconds = mutableListOf<Double>()
    private var totalDropouts = 0
    private var totalCaptureDropped = 0
    @Synchronized fun assistantArrived(milliseconds: Double, queuedBefore: Double) {
        val now = clock.now(); lastArrival?.let { arrivalGaps.add(ms(now - it)) }; lastArrival = now
        received++; receivedMilliseconds += milliseconds; queuedMilliseconds = queuedBefore + milliseconds
    }
    @Synchronized fun assistantStarved(silentMilliseconds: Double, prerollMilliseconds: Double) {
        dropouts++; totalDropouts++; dropoutMilliseconds += silentMilliseconds; this.prerollMilliseconds = prerollMilliseconds
    }
    @Synchronized fun setPreroll(milliseconds: Double) { prerollMilliseconds = milliseconds }
    @Synchronized fun assistantFlushed() { lastArrival = null }
    @Synchronized fun captured(dropped: Boolean) {
        val now = clock.now(); lastCapture?.let { captureGaps.add(ms(now - it)) }; lastCapture = now
        captured++; if (dropped) { captureDropped++; totalCaptureDropped++ }
    }
    @Synchronized fun sent(after: Duration) { sendMilliseconds.add(ms(after)) }
    data class Window(val text: String, val problem: Boolean)
    @Synchronized fun takeWindow(): Window? {
        val result = if (received == 0 && captured == 0) null else {
            val parts = mutableListOf<String>()
            if (received > 0) {
                parts.add("voice in: $received chunks (${receivedMilliseconds.toInt()} ms audio)")
                parts.add("arrival gap p50/p95/max ${pct(arrivalGaps, 0.5)}/${pct(arrivalGaps, 0.95)}/${pct(arrivalGaps, 1.0)} ms")
            }
            parts.add("dropouts $dropouts" + if (dropouts > 0) " (${dropoutMilliseconds.toInt()} ms silent)" else "")
            if (received > 0) prerollMilliseconds?.let { parts.add("pre-roll ${it.toInt()} ms") }
            if (captured > 0) parts.add("mic out: $captured/${(ms(clock.now() - windowStart) / 100).toInt()} chunks, dropped $captureDropped, gap p95/max ${pct(captureGaps, 0.95)}/${pct(captureGaps, 1.0)} ms")
            if (sendMilliseconds.isNotEmpty()) parts.add("send p95/max ${pct(sendMilliseconds, 0.95)}/${pct(sendMilliseconds, 1.0)} ms")
            Window(parts.joinToString(" · "), dropouts > 0 || captureDropped > 0 || pct(arrivalGaps, 0.95) > 250)
        }
        windowStart = clock.now(); received = 0; receivedMilliseconds = 0.0; arrivalGaps.clear()
        dropouts = 0; dropoutMilliseconds = 0.0; queuedMilliseconds = 0.0
        captured = 0; captureDropped = 0; captureGaps.clear(); sendMilliseconds.clear()
        return result
    }
    val hasProblems get() = synchronized(this) { totalDropouts > 0 || totalCaptureDropped > 0 }
    companion object {
        val window = 5.seconds
        private fun ms(duration: Duration) = duration.inWholeNanoseconds / 1e6
        private fun pct(values: List<Double>, p: Double): Int = if (values.isEmpty()) 0 else values.sorted()[((values.size - 1) * p).toInt()].roundToInt()
        fun route(manager: AudioManager = dependency(AppContext).getSystemService(AudioManager::class.java)): String {
            val device = manager.communicationDevice
            // Device product names can expose personal names: record only type and rates.
            return listOfNotNull("route in/out: ${device?.type ?: "none"}", "hw ${device?.sampleRates?.joinToString() ?: "unknown"} Hz",
                "io buffer ${manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "unknown"} frames", "mode ${manager.mode}",
                if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) "Bluetooth hands-free (narrowband)" else null).joinToString(" · ")
        }
    }
}
