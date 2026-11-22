package ai.opencode.core.diagnostics

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Where the app was and what it recorded, under 2 MB. No credentials or server addresses: the
 * server goes by a short hash.
 */
@Serializable
data class ProblemReportContext(
    val sessionID: String? = null,
    val serverID: String = "",
    val screen: String = "",
    val project: String? = null,
    val connection: String = "",
    val call: String? = null,
)

@Serializable
data class ProblemReportDevice(
    val appVersion: String,
    val build: String,
    /** "Debug", "Internal" or "Play Store". */
    val distribution: String,
    val system: String,
    val model: String,
    val liveActivities: String? = null,
)

@Serializable
data class ProblemReport(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val note: String = "",
    val crashedLastRun: Boolean = false,
    val device: ProblemReportDevice,
    val context: ProblemReportContext,
    /** The last few telling timeline lines, for the readable summary. */
    val highlights: List<String> = emptyList(),
) {
    val title: String get() = if (crashedLastRun) "Problem report (after crash)" else "Problem report"

    /** The ID the sheet shows and copies. */
    val shortID: String get() = shortID(id)

    companion object {
        fun shortID(id: UUID): String = id.toString().take(8).lowercase(Locale.ROOT)
    }
}

/** A report and its files, each already within its share of the size budget. */
data class ProblemReportBundle(val report: ProblemReport, val files: Map<String, ByteArray>) {
    object File {
        const val TIMELINE_TEXT = "timeline.txt"
        const val TIMELINE_JSON = "timeline.json"
        const val APP_LOG = "app-log.txt"
        const val PREVIOUS_RUN = "previous-run.txt"
        const val SCREENSHOT = "screenshot.jpg"
    }

    override fun equals(other: Any?): Boolean =
        other is ProblemReportBundle && other.report == report && other.files.keys == files.keys && files.all { (k, v) -> other.files[k].contentEquals(v) }

    override fun hashCode(): Int = report.hashCode() * 31 + files.keys.hashCode()
}

object ProblemReportBuilder {
    /** The whole bundle stays under 2 MB. */
    const val SCREENSHOT_LIMIT = 600_000
    const val TIMELINE_JSON_LIMIT = 700_000
    const val TIMELINE_TEXT_LIMIT = 300_000
    const val APP_LOG_LIMIT = 300_000
    const val PREVIOUS_RUN_LIMIT = 150_000
    /** The previous run is attached when it ended this recently, or crashed. */
    const val PREVIOUS_RUN_WINDOW_SECONDS = 900L

    fun make(
        id: UUID,
        note: String = "",
        crashedLastRun: Boolean = false,
        context: ProblemReportContext,
        device: ProblemReportDevice,
        now: Instant,
        events: List<DiagnosticsEvent>,
        appLog: List<String> = emptyList(),
        previousRun: List<DiagnosticsEvent> = emptyList(),
        screenshot: ByteArray? = null,
    ): ProblemReportBundle {
        val safeContext = context.copy(serverID = safeServerID(context.serverID))
        val recorded = if (crashedLastRun && previousRun.isNotEmpty()) previousRun + events else events
        val report = ProblemReport(
            id = id, createdAt = now, note = note, crashedLastRun = crashedLastRun,
            device = device, context = safeContext, highlights = highlights(recorded),
        )
        val files = LinkedHashMap<String, ByteArray>()
        files[ProblemReportBundle.File.TIMELINE_TEXT] = tail(DiagnosticsRecorder.exportText(recorded, wallClock = true), TIMELINE_TEXT_LIMIT).toByteArray()
        files[ProblemReportBundle.File.TIMELINE_JSON] = boundedJSON(recorded, TIMELINE_JSON_LIMIT)
        if (appLog.isNotEmpty()) files[ProblemReportBundle.File.APP_LOG] = tail(appLog.joinToString("\n") + "\n", APP_LOG_LIMIT).toByteArray()
        val last = previousRun.lastOrNull()
        if (last != null && (crashedLastRun || now.epochSecond - last.wallDate.epochSecond <= PREVIOUS_RUN_WINDOW_SECONDS)) {
            files[ProblemReportBundle.File.PREVIOUS_RUN] = tail(DiagnosticsRecorder.exportText(previousRun, wallClock = true), PREVIOUS_RUN_LIMIT).toByteArray()
        }
        if (screenshot != null && screenshot.size <= SCREENSHOT_LIMIT) files[ProblemReportBundle.File.SCREENSHOT] = screenshot
        return ProblemReportBundle(report, files)
    }

    /** Voice turns, streams, lifecycle, navigation and failed requests: what a reader scans first. Newest last. */
    fun highlights(events: List<DiagnosticsEvent>, count: Int = 15): List<String> {
        val telling = events.filter { event ->
            when (event.kind) {
                DiagnosticsEvent.Kind.Voice, DiagnosticsEvent.Kind.Stream, DiagnosticsEvent.Kind.Lifecycle, DiagnosticsEvent.Kind.Navigation -> true
                DiagnosticsEvent.Kind.RequestEnd -> (event.errorCategory != null && event.errorCategory != DiagnosticsErrorCategory.Cancelled) || (event.statusCode ?: 200) >= 400
                DiagnosticsEvent.Kind.Mark, DiagnosticsEvent.Kind.RequestStart, DiagnosticsEvent.Kind.NetworkMetrics -> false
            }
        }
        val text = DiagnosticsRecorder.exportText(telling.takeLast(count), wallClock = true)
        return text.split("\n").drop(4).filter { it.isNotEmpty() }
    }

    /** The readable summary uploaded with a trace. */
    fun summary(report: ProblemReport, hasScreenshot: Boolean): String {
        val device = report.device
        val context = report.context
        val lines = mutableListOf("## OpenCode Android: ${report.title}", "")
        val note = report.note.trim()
        if (note.isNotEmpty()) {
            lines += note.split("\n").map { "> $it" }
            lines += ""
        }
        var screen = context.screen
        context.sessionID?.let { screen += " `$it`" }
        context.project?.let { screen += " in $it" }
        lines += listOf(
            "- Reported: ${DateTimeFormatter.ISO_INSTANT.format(report.createdAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))}",
            "- App: ${device.appVersion} (${device.build}), ${device.distribution}",
            "- Device: ${device.model}, ${device.system}",
        )
        context.sessionID?.let { lines += "- Session: `$it`" }
        if (context.serverID.isNotEmpty()) lines += "- Server ID: `${safeServerID(context.serverID)}`"
        lines += listOf("- Screen: $screen", "- Connection: ${context.connection}")
        if (report.crashedLastRun) lines += "- Previous run: ended unexpectedly"
        context.call?.let { lines += "- Call: $it" }
        device.liveActivities?.let { lines += "- Live Activities: $it" }
        if (!hasScreenshot) lines += "- Screenshot: none"
        if (report.highlights.isNotEmpty()) {
            lines += listOf("", if (report.crashedLastRun) "Recent timeline (including previous run):" else "Recent timeline:", "```text")
            lines += report.highlights
            lines += "```"
        }
        return lines.joinToString("\n")
    }

    /**
     * What a report sends into its session: a header line like a Scrawl capture's, so the agent
     * knows what it is, then the uploaded summary. [traceURL] is null when the upload failed.
     */
    fun sessionMessage(bundle: ProblemReportBundle, traceURL: String?): String {
        val hasScreenshot = bundle.files[ProblemReportBundle.File.SCREENSHOT] != null
        val header = sessionMessageHeader(bundle, traceURL, failedFallback = "The trace upload failed, so only this summary arrived.")
        return (header + listOf("", summary(bundle.report, hasScreenshot))).joinToString("\n")
    }

    private fun sessionMessageHeader(bundle: ProblemReportBundle, traceURL: String?, failedFallback: String): List<String> {
        val report = bundle.report
        val device = report.device
        val hasScreenshot = bundle.files[ProblemReportBundle.File.SCREENSHOT] != null
        val traceLine = traceURL?.let { "Trace (timeline, app log${if (hasScreenshot) ", screenshot" else ""}): $it" } ?: failedFallback
        return listOf(
            "[problem report ${report.shortID} · ${device.model} · OpenCode Android ${device.appVersion} (${device.build})]",
            "Sent from Report a Problem in the OpenCode Android app (Developer Mode). Report ID: ${report.id.toString().uppercase(Locale.ROOT)}",
            traceLine,
        )
    }

    /** The session message's ID, made from the report's, so a retry of the same message is admitted once. */
    fun sessionMessageID(id: UUID, followUp: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("problem-report:${id.toString().uppercase(Locale.ROOT)}:${if (followUp) "link" else "summary"}".toByteArray())
        return "msg_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** The short message that carries the attachment: header, note, report ID and trace link. */
    fun sessionMessageWithAttachment(bundle: ProblemReportBundle, traceURL: String?): String {
        val report = bundle.report
        val lines = sessionMessageHeader(bundle, traceURL, failedFallback = "The trace upload failed; the attached report is the only copy.").toMutableList()
        val note = report.note.trim()
        if (note.isNotEmpty()) {
            lines += ""
            lines += note.split("\n").map { "> $it" }
        }
        lines += listOf("", "The full report (device, build, screen, timeline and app log) is attached as ${attachmentName(report)}; read it with your file tools.")
        return lines.joinToString("\n")
    }

    /** Sent after a retry uploads a report whose summary already reached the session. */
    fun sessionTraceFollowUp(report: ProblemReport, traceURL: String): String =
        "[problem report ${report.shortID} · trace uploaded]\nThe retry uploaded the trace for report ${report.id.toString().uppercase(Locale.ROOT)}: $traceURL"

    /** The report as one Markdown file: the summary, then the timeline, app log and previous run it carries. */
    fun attachment(bundle: ProblemReportBundle): String {
        val sections = mutableListOf(summary(bundle.report, bundle.files[ProblemReportBundle.File.SCREENSHOT] != null))
        for ((title, name) in listOf(
            "Timeline" to ProblemReportBundle.File.TIMELINE_TEXT,
            "App log" to ProblemReportBundle.File.APP_LOG,
            "Previous run" to ProblemReportBundle.File.PREVIOUS_RUN,
        )) {
            val data = bundle.files[name]?.takeIf { it.isNotEmpty() } ?: continue
            sections += "## $title\n\n```text\n${String(data).trim('\n')}\n```"
        }
        return sections.joinToString("\n\n") + "\n"
    }

    fun attachmentName(report: ProblemReport): String = "report-${report.shortID}.md"

    /** A persistence ID ("user|https://host") becomes its short hash, so no address or username leaves the phone. */
    fun safeServerID(serverID: String): String = if ("|" in serverID || "://" in serverID) serverKey(serverID) else serverID

    /** The first 6 bytes of SHA-256, hex. */
    fun serverKey(persistenceID: String): String =
        MessageDigest.getInstance("SHA-256").digest(persistenceID.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** The end of [text] within [limit] UTF-8 bytes, cut at a line. */
    fun tail(text: String, limit: Int): String {
        val bytes = text.toByteArray()
        if (bytes.size <= limit) return text
        val suffix = bytes.copyOfRange(bytes.size - limit, bytes.size)
        val newline = suffix.indexOf('\n'.code.toByte())
        val start = if (newline >= 0) newline + 1 else 0
        return "… (earlier lines omitted)\n" + String(suffix, start, suffix.size - start)
    }

    /** The newest events whose JSON fits [limit]. */
    fun boundedJSON(events: List<DiagnosticsEvent>, limit: Int): ByteArray {
        var kept = events
        var data = DiagnosticsRecorder.exportJSON(kept).toByteArray()
        while (data.size > limit && kept.isNotEmpty()) {
            kept = kept.drop(max(1, kept.size / 4))
            data = DiagnosticsRecorder.exportJSON(kept).toByteArray()
        }
        return data
    }
}
