package ai.opencode.core.log

import android.os.Trace
import android.util.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration

/**
 * The app's only log, to logcat (`OpenCode/<category>`) and a ring buffer for reports. Log IDs,
 * counts, lengths, statuses and timings only; never credentials, addresses or content.
 */
class AppLog private constructor(val category: String, subsystem: String = SUBSYSTEM, private val traces: Boolean = false) {
    /** Reads only the app's own subsystem, so `opencode.perf` marks stay out of reports. */
    private val recorded = subsystem == SUBSYSTEM
    private val tag = if (subsystem == SUBSYSTEM) "OpenCode/$category" else "$subsystem/$category"

    fun debug(message: () -> String) = log(Log.DEBUG, "debug", message)
    fun info(message: () -> String) = log(Log.INFO, "info", message)
    fun notice(message: () -> String) = log(Log.INFO, "notice", message)
    fun error(message: () -> String) = log(Log.ERROR, "error", message)

    fun debug(message: String) = debug { message }
    fun info(message: String) = info { message }
    fun notice(message: String) = notice { message }
    fun error(message: String) = error { message }

    private inline fun log(priority: Int, level: String, message: () -> String) {
        if (priority < minimumPriority) return
        val text = message().replace('\r', ' ').replace('\n', ' ')
        Log.println(priority, tag, text)
        // `opencode.perf` lines double as Perfetto instants named by their first word.
        if (traces && Trace.isEnabled()) {
            Trace.beginSection("oc:" + text.substringBefore(' ').take(117))
            Trace.endSection()
        }
        if (recorded) Ring.append("${DateTimeFormatter.ISO_INSTANT.format(Instant.now())} $level $category: $text")
    }

    companion object {
        const val SUBSYSTEM = "ai.opencode.mobilecode"

        /** Lowest priority collected; debug builds set [Log.DEBUG] at startup. */
        @Volatile
        var minimumPriority: Int = Log.INFO

        val send = AppLog("send")
        val network = AppLog("network")
        val stream = AppLog("stream")
        val voice = AppLog("voice")
        val app = AppLog("app")

        /** The responsiveness gauntlet's timing marks (`LabMarks`), under the `opencode.perf` subsystem. */
        val perf = AppLog("session", subsystem = "opencode.perf", traces = true)

        /** Recent lines from this process (not `opencode.perf`), oldest first. */
        fun recentLines(since: Instant): List<String> = Ring.since(since)
    }

    private object Ring {
        private const val CAPACITY = 4_000
        private val lines = ArrayDeque<Pair<Instant, String>>()

        fun append(line: String) = synchronized(lines) {
            if (lines.size == CAPACITY) lines.removeFirst()
            lines.addLast(Instant.now() to line)
        }

        fun since(date: Instant): List<String> = synchronized(lines) {
            lines.filter { !it.first.isBefore(date) }.map { it.second }
        }
    }
}

/** Throwable metadata only: messages, file names and suppressed exceptions may contain private data. */
internal val Throwable.logSummary: String
    get() {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val classes = ArrayList<String>()
        var error: Throwable? = this
        while (error != null && seen.add(error)) {
            classes += error.javaClass.simpleName
            error = error.cause
        }
        val frame = stackTrace.firstOrNull()
        return classes.joinToString(" <- ") +
            (frame?.let { " at ${it.className}.${it.methodName}:${it.lineNumber}" } ?: "")
    }

/** "1.25 s", "320 ms". */
val Duration.logSummary: String
    get() {
        val milliseconds = inWholeNanoseconds / 1_000_000.0
        if (milliseconds < 1_000) return "${Math.round(milliseconds)} ms"
        // Half-even from the shortest decimal form.
        val text = BigDecimal.valueOf(milliseconds / 1_000).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
        return "$text s"
    }
