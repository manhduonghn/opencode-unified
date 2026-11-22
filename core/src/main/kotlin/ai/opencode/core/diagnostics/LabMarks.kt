package ai.opencode.core.diagnostics

import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.InternalBuild
import android.os.Trace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of iOS `LabMarks`: shared-clock timing marks for the responsiveness gauntlet and the
 * diagnostics timeline. Every mark is an `AppLog.perf` line (and so a Perfetto instant, see
 * [PerfTrace]); when a debug build is launched with a marks endpoint ([endpoint], the intent
 * extra `lab-marks-url`, iOS's `OPENCODE_LAB_MARKS_URL`), the same mark is posted to the fixture
 * so its choreography timeline and the app's state changes pair up. Wall clock, not monotonic.
 *
 * Content-free: names, session IDs and integer values only.
 */
object LabMarks {
    /** The fixture's marks URL; set once at launch in debug builds. */
    @Volatile var endpoint: URL? = null
        set(value) {
            field = if (InternalBuild.isDebug) value else null
        }

    val isEnabled: Boolean get() = endpoint != null

    val needsNetworkMetrics: Boolean get() = endpoint != null || DiagnosticsRecorder.shared.isEnabled

    fun mark(name: String, sessionID: String? = null, values: Map<String, Int> = emptyMap()) {
        if (needsNetworkMetrics) {
            if (values.isEmpty()) {
                AppLog.perf.info { "$name session=${sessionID ?: "-"}" }
            } else {
                val detail = values.keys.sorted().joinToString(" ") { "$it=${values[it] ?: 0}" }
                AppLog.perf.info { "$name session=${sessionID ?: "-"} $detail" }
            }
        } else {
            PerfTrace.instant(name)
        }
        DiagnosticsRecorder.shared.recordMark(name, sessionID)
        endpoint ?: return
        val body = LinkedHashMap<String, Any>()
        body["name"] = name
        body["t"] = System.currentTimeMillis()
        sessionID?.let { body["sessionID"] = it }
        body.putAll(values)
        if (name == "session_first_frame" && "footprintKB" !in body) body["footprintKB"] = physicalFootprintKB()
        post(body)
    }

    /** iOS `physicalFootprintKB`: the process's PSS in KB. */
    fun physicalFootprintKB(): Int = runCatching {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        info.totalPss
    }.getOrDefault(0)

    /**
     * iOS `networkTask`: records the task's transport metrics in the diagnostics timeline and, on a
     * lab run, posts `network_task` (path, connection reuse, protocol, connect and TLS times).
     */
    fun networkTask(requestID: String, path: String, metrics: DiagnosticsNetworkMetrics, operationID: String? = null, attempt: Int? = null) {
        DiagnosticsRecorder.shared.networkMetrics(requestID, path, metrics, operationID, attempt)
        endpoint ?: return
        val body = LinkedHashMap<String, Any>()
        body["name"] = "network_task"
        body["t"] = System.currentTimeMillis()
        body["path"] = path
        body["reused"] = metrics.connectionReused ?: false
        metrics.protocolName?.let { body["protocol"] = it }
        metrics.connectMilliseconds?.let { body["connectMs"] = it.toInt() }
        metrics.tlsMilliseconds?.let { body["tlsMs"] = it.toInt() }
        post(body)
    }

    /** A lab-only event with scalar fields (counts, kinds, never content): the smoothness suite's frame windows, stalls and motion findings. */
    fun post(event: String, fields: Map<String, Any>) {
        endpoint ?: return
        post(fields + mapOf("name" to event, "t" to System.currentTimeMillis()))
    }

    private val poster = Executors.newSingleThreadExecutor { Thread(it, "lab-marks").apply { isDaemon = true } }

    private fun post(body: Map<String, Any>) {
        endpoint ?: return
        send(encode(body))
    }

    /** iOS `JSONSerialization`: numbers, booleans, strings, lists and maps (nested). */
    private fun encode(body: Map<String, Any>): String = json(body).toString()

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
        is Iterable<*> -> JsonArray(value.map(::json))
        is DoubleArray -> JsonArray(value.map(::JsonPrimitive))
        is IntArray -> JsonArray(value.map(::JsonPrimitive))
        is Array<*> -> JsonArray(value.map(::json))
        else -> JsonPrimitive(value.toString())
    }

    private fun send(json: String) {
        val endpoint = endpoint ?: return
        poster.execute {
            runCatching {
                val connection = endpoint.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toByteArray()) }
                connection.responseCode
                connection.disconnect()
            }
        }
    }
}

/**
 * Perfetto / systrace sections (iOS `os_signpost` through `AppLog.perf.signposter`). Shows up in
 * a Perfetto or Android Studio system trace under the app's process. Cheap when tracing is off.
 *
 * - [instant]: a zero-length marker (iOS `emitEvent`).
 * - [section]: a synchronous interval on the calling thread.
 * - [begin]/[end]: an async interval (iOS `beginInterval`/`endInterval`), e.g. Session Switch.
 */
object PerfTrace {
    private val cookies = AtomicInteger(0)

    fun instant(name: String) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(sectionName(name))
        Trace.endSection()
    }

    inline fun <T> section(name: String, block: () -> T): T {
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginSection(sectionName(name))
        try {
            return block()
        } finally {
            if (tracing) Trace.endSection()
        }
    }

    /** Begins an async interval; pass the returned cookie to [end]. */
    fun begin(name: String): Int {
        val cookie = cookies.incrementAndGet()
        if (Trace.isEnabled()) Trace.beginAsyncSection(sectionName(name), cookie)
        return cookie
    }

    fun end(name: String, cookie: Int) {
        if (Trace.isEnabled()) Trace.endAsyncSection(sectionName(name), cookie)
    }

    /** A counter track (frame stats, in-flight requests). */
    fun counter(name: String, value: Long) {
        if (Trace.isEnabled()) Trace.setCounter(sectionName(name), value)
    }

    /** Trace names are capped at 127 characters. */
    fun sectionName(name: String): String = if (name.length <= 120) "oc:$name" else "oc:${name.take(117)}"
}
