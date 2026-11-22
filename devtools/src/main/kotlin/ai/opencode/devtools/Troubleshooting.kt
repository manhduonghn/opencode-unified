package ai.opencode.devtools

import ai.opencode.core.diagnostics.DiagnosticsEvent
import java.util.Locale

/** Summary, epochs and row text. */
object Troubleshooting {
    data class Group(val key: Int, val title: String, val events: List<DiagnosticsEvent>)

    /** Newest navigation first; epoch 0 is "App". */
    fun groupedEpochs(events: List<DiagnosticsEvent>): List<Group> =
        events.groupBy { it.navigationEpoch ?: 0 }.toSortedMap(compareByDescending { it }).map { (epoch, values) ->
            val session = values.firstNotNullOfOrNull { it.sessionID }
            Group(epoch, if (epoch == 0) "App" else "Navigation $epoch · ${session ?: "session"}", values)
        }

    /** Each request's network metrics. */
    fun metricsByRequest(events: List<DiagnosticsEvent>): Map<String, DiagnosticsEvent> =
        events.filter { it.kind == DiagnosticsEvent.Kind.NetworkMetrics && it.requestID != null }.associateBy { it.requestID!! }

    /** Last session open, tap → first frame / fresh content, SSE, last 60 seconds. */
    fun summary(events: List<DiagnosticsEvent>): List<Pair<String, String>> {
        val open = events.lastOrNull { it.name == "session_tap" || it.name == "session_navigation_started" }
        val frame = after(events, open, setOf("session_first_frame"))
        val fresh = after(events, open, setOf("fresh_content_laid_out", "snapshot_apply", "transcript_response"))
        val stream = events.lastOrNull { it.kind == DiagnosticsEvent.Kind.Stream }
        return listOf(
            "Last session open" to (open?.sessionID ?: "—"),
            "Tap → first frame" to duration(open, frame),
            "Tap → first fresh content" to duration(open, fresh),
            "SSE" to (stream?.name ?: "No event recorded"),
            "Address race" to (events.lastOrNull { it.name == "link_race" }?.reason ?: "None yet"),
            "Network path" to (events.lastOrNull { it.name == "path_change" }?.reason ?: "No change recorded"),
            "Stream watchdog" to watchdogSummary(events),
            "Last 60 seconds" to lastMinute(events),
        )
    }

    /** Stalled streams the watchdog ended, by which deadline passed. */
    fun watchdogSummary(events: List<DiagnosticsEvent>): String {
        var fired = 0
        var silent = 0
        for (event in events) {
            if (event.name != "sse_watchdog") continue
            fired += 1
            if (event.reason == "idle") silent += 1
        }
        if (fired == 0) return "Never fired"
        return "${fired - silent} didn’t open · $silent went silent"
    }

    fun lastMinute(events: List<DiagnosticsEvent>): String {
        val newest = events.lastOrNull()?.monotonicSeconds ?: 0.0
        val recent = events.filter { it.monotonicSeconds >= newest - 60 }
        val ends = recent.filter { it.kind == DiagnosticsEvent.Kind.RequestEnd }
        val wire = recent.mapNotNull { it.wireBytes }.sum()
        val decoded = ends.mapNotNull { it.decodedBytes }.sum()
        return "${ends.size} requests · ${bytes(wire)} wire → ${bytes(decoded)} decoded"
    }

    fun transferLine(event: DiagnosticsEvent, metrics: DiagnosticsEvent?): String =
        "${bytes(metrics?.wireBytes)} wire → ${bytes(event.decodedBytes)} decoded · ${event.contentEncoding ?: "identity"} · ${metrics?.protocolName ?: "protocol —"} · ${if (metrics?.connectionReused == true) "reused" else "new"}"

    fun phasesLine(metrics: DiagnosticsEvent): String =
        "DNS ${ms(metrics.dnsMilliseconds)} · connect ${ms(metrics.connectMilliseconds)} · TLS ${ms(metrics.tlsMilliseconds)} · first byte ${ms(metrics.firstByteMilliseconds)} · download ${ms(metrics.downloadMilliseconds)}"

    fun addressLine(metrics: DiagnosticsEvent): String =
        listOfNotNull(metrics.addressFamily?.name?.lowercase(Locale.ROOT), metrics.pathScope?.name?.lowercase(Locale.ROOT), metrics.interfaceKind?.name?.lowercase(Locale.ROOT))
            .ifEmpty { listOf("address —") }.joinToString(" · ") +
            " · headers ${bytes(metrics.requestHeaderBytes)} ↑ ${bytes(metrics.responseHeaderBytes)} ↓ · body ${bytes(metrics.requestBodyBytes)} ↑"

    fun relative(event: DiagnosticsEvent, origin: Double?): String =
        String.format(Locale.ROOT, "+%.1fs", event.monotonicSeconds - (origin ?: event.monotonicSeconds))

    fun ms(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "—"

    /** Decimal units, “Zero KB”, “1 byte”, KB whole, MB one decimal. */
    fun bytes(value: Long?): String = when {
        value == null -> "—"
        value == 0L -> "Zero KB"
        value == 1L -> "1 byte"
        value < 1000 -> "$value bytes"
        value < 1_000_000 -> String.format(Locale.ROOT, "%.0f KB", value / 1000.0)
        else -> String.format(Locale.ROOT, "%.1f MB", value / 1_000_000.0)
    }

    private fun after(events: List<DiagnosticsEvent>, start: DiagnosticsEvent?, names: Set<String>): DiagnosticsEvent? =
        start?.let { s -> events.firstOrNull { it.monotonicSeconds >= s.monotonicSeconds && it.name in names } }

    private fun duration(start: DiagnosticsEvent?, end: DiagnosticsEvent?): String =
        if (start == null || end == null) "Pending" else ms((end.monotonicSeconds - start.monotonicSeconds) * 1_000)
}
