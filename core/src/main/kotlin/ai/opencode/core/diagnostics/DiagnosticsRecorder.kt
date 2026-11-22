package ai.opencode.core.diagnostics

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.tuning.InternalBuild
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.schedule
import kotlin.math.max

@Serializable(with = DiagnosticsErrorCategory.Serializer::class)
sealed interface DiagnosticsErrorCategory {
    data object Unauthorized : DiagnosticsErrorCategory
    data object NotFound : DiagnosticsErrorCategory
    data object Timeout : DiagnosticsErrorCategory
    data object Offline : DiagnosticsErrorCategory
    data object Cancelled : DiagnosticsErrorCategory
    data class Server(val status: Int) : DiagnosticsErrorCategory
    data object Transport : DiagnosticsErrorCategory

    val description: String
        get() = when (this) {
            Unauthorized -> "unauthorized"
            NotFound -> "notFound"
            Timeout -> "timeout"
            Offline -> "offline"
            Cancelled -> "cancelled"
            is Server -> "server($status)"
            Transport -> "transport"
        }

    companion object {
        fun of(status: Int): DiagnosticsErrorCategory = when (status) {
            401 -> Unauthorized
            404 -> NotFound
            else -> Server(status)
        }

        /**
         * Cancellation, timeouts and lost networks by type, else transport. A socket error's errno
         * is read from its message.
         */
        fun of(error: Throwable): DiagnosticsErrorCategory = when (error) {
            is kotlinx.coroutines.CancellationException -> Cancelled
            is java.net.SocketTimeoutException -> Timeout
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException -> Offline
            is java.net.SocketException -> when {
                error.message.orEmpty().contains("ETIMEDOUT", ignoreCase = true) -> Timeout
                offlineSocket.containsMatchIn(error.message.orEmpty()) -> Offline
                else -> Transport
            }
            is java.io.InterruptedIOException -> when {
                error.message.equals("timeout", ignoreCase = true) -> Timeout
                error.message.equals("Canceled", ignoreCase = true) -> Cancelled
                else -> Transport
            }
            else -> if (error.message.equals("Canceled", ignoreCase = true)) Cancelled else Transport
        }

        /** Persisted diagnostics keep their original NSError domains and codes. */
        fun of(domain: String, code: Int): DiagnosticsErrorCategory = when (domain) {
            "NSURLErrorDomain" -> when (code) {
                -1001 -> Timeout
                -1003, -1004, -1005, -1006, -1009, -1018, -1020 -> Offline
                -999 -> Cancelled
                else -> Transport
            }
            "NSPOSIXErrorDomain" -> when (code) {
                60 -> Timeout
                57, 54, 53, 32, 50, 51, 64, 65 -> Offline
                else -> Transport
            }
            else -> Transport
        }

        private val offlineSocket = Regex(
            "ENOTCONN|ECONNRESET|ECONNABORTED|EPIPE|ENETDOWN|ENETUNREACH|EHOSTDOWN|EHOSTUNREACH|" +
                "connection reset|connection aborted|broken pipe|not connected|network is (?:down|unreachable)|no route to host",
            RegexOption.IGNORE_CASE,
        )
    }

    object Serializer : KSerializer<DiagnosticsErrorCategory> {
        override val descriptor = PrimitiveSerialDescriptor("DiagnosticsErrorCategory", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: DiagnosticsErrorCategory) = encoder.encodeString(value.description)
        override fun deserialize(decoder: Decoder): DiagnosticsErrorCategory = when (val text = decoder.decodeString()) {
            "unauthorized" -> Unauthorized
            "notFound" -> NotFound
            "timeout" -> Timeout
            "offline" -> Offline
            "cancelled" -> Cancelled
            else -> text.removePrefix("server(").removeSuffix(")").toIntOrNull()?.let(::Server) ?: Transport
        }
    }
}

@Serializable
enum class DiagnosticsFailurePhase { @SerialName("dns") Dns, @SerialName("tcp") Tcp, @SerialName("tls") Tls, @SerialName("requestWrite") RequestWrite, @SerialName("ttfb") Ttfb, @SerialName("bodyRead") BodyRead, @SerialName("httpStatus") HttpStatus, @SerialName("decode") Decode, @SerialName("cancelled") Cancelled }

@Serializable
enum class DiagnosticsInterfaceKind { @SerialName("wifi") Wifi, @SerialName("cellular") Cellular, @SerialName("wired") Wired, @SerialName("loopback") Loopback, @SerialName("other") Other }

@Serializable
enum class DiagnosticsAddressFamily { @SerialName("ipv4") Ipv4, @SerialName("ipv6") Ipv6 }

@Serializable
enum class DiagnosticsPathScope { @SerialName("loopback") Loopback, @SerialName("tailnet") Tailnet, @SerialName("lan") Lan, @SerialName("wan") Wan }

enum class DiagnosticsStreamName(val rawValue: String) {
    ConnectStart("sse_connect_start"), Connected("sse_connected"), Disconnected("sse_disconnected"), Resumed("sse_resumed"), Stalled("sse_stalled"),
}

/** Reduces a socket address to enums immediately; never retains the input. */
data class DiagnosticsNetworkAddress(val family: DiagnosticsAddressFamily, val scope: DiagnosticsPathScope) {
    companion object {
        fun of(bytes: ByteArray?): DiagnosticsNetworkAddress? {
            bytes ?: return null
            if (bytes.size == 4) return DiagnosticsNetworkAddress(DiagnosticsAddressFamily.Ipv4, scope4(bytes.map { it.toInt() and 0xff }))
            if (bytes.size != 16) return null
            val b = bytes.map { it.toInt() and 0xff }
            val scope = when {
                b.take(10).all { it == 0 } && b[10] == 255 && b[11] == 255 -> scope4(b.takeLast(4))
                b.dropLast(1).all { it == 0 } && b[15] == 1 -> DiagnosticsPathScope.Loopback
                b.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0) -> DiagnosticsPathScope.Tailnet
                b[0] and 0xfe == 0xfc || (b[0] == 0xfe && b[1] and 0xc0 == 0x80) -> DiagnosticsPathScope.Lan
                else -> DiagnosticsPathScope.Wan
            }
            return DiagnosticsNetworkAddress(DiagnosticsAddressFamily.Ipv6, scope)
        }

        private fun scope4(b: List<Int>): DiagnosticsPathScope = when {
            b[0] == 127 -> DiagnosticsPathScope.Loopback
            b[0] == 100 && b[1] and 0xc0 == 64 -> DiagnosticsPathScope.Tailnet
            b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) -> DiagnosticsPathScope.Lan
            else -> DiagnosticsPathScope.Wan
        }
    }
}

/**
 * A missing phase stays unknown; an unfinished TLS handshake wins over unfinished TCP. Monotonic
 * timestamps.
 */
data class DiagnosticsNetworkPhases(
    var dnsStart: Long? = null, var dnsEnd: Long? = null,
    var connectStart: Long? = null, var connectEnd: Long? = null,
    var tlsStart: Long? = null, var tlsEnd: Long? = null,
    var requestStart: Long? = null, var requestEnd: Long? = null,
    var responseStart: Long? = null, var responseEnd: Long? = null,
) {
    val unfinished: DiagnosticsFailurePhase?
        get() = when {
            dnsStart != null && dnsEnd == null -> DiagnosticsFailurePhase.Dns
            tlsStart != null && tlsEnd == null -> DiagnosticsFailurePhase.Tls
            connectStart != null && connectEnd == null -> DiagnosticsFailurePhase.Tcp
            requestStart != null && requestEnd == null -> DiagnosticsFailurePhase.RequestWrite
            requestEnd != null && responseStart == null -> DiagnosticsFailurePhase.Ttfb
            responseStart != null && responseEnd == null -> DiagnosticsFailurePhase.BodyRead
            else -> null
        }
}

/** One request's transport timings. */
data class DiagnosticsNetworkMetrics(
    val wireBytes: Long? = null,
    val protocolName: String? = null,
    val connectionReused: Boolean? = null,
    val dnsMilliseconds: Double? = null,
    val connectMilliseconds: Double? = null,
    val tlsMilliseconds: Double? = null,
    val firstByteMilliseconds: Double? = null,
    val downloadMilliseconds: Double? = null,
    val uploadMilliseconds: Double? = null,
    val requestBodyBytes: Long? = null,
    val requestHeaderBytes: Long? = null,
    val responseHeaderBytes: Long? = null,
    val interfaceKind: DiagnosticsInterfaceKind? = null,
    val address: DiagnosticsNetworkAddress? = null,
)

/** Counts only observed line content of the event stream. */
data class DiagnosticsStreamProgress(
    val startedAt: Double,
    var connectedAt: Double? = null,
    var lastLineAt: Double? = null,
    var lineBytes: Long = 0,
    var lines: Int = 0,
) {
    /** Returns whether the stream resumed after 30 s of silence. */
    fun receiveLine(byteCount: Int, now: Double): Boolean {
        val resumed = lastLineAt?.let { now - it >= 30 } ?: false
        lineBytes += max(0, byteCount)
        lines += 1
        lastLineAt = now
        return resumed
    }
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Timing and routing metadata only (never headers, bodies, addresses or message text). */
@Serializable
data class DiagnosticsEvent(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val kind: Kind,
    val monotonicSeconds: Double,
    @Serializable(with = InstantSerializer::class) val wallDate: Instant,
    val name: String,
    val sessionID: String? = null,
    val navigationEpoch: Int? = null,
    val requestID: String? = null,
    val operationID: String? = null,
    val attempt: Int? = null,
    val inFlightRequests: Int? = null,
    val method: String? = null,
    val path: String? = null,
    val reason: String? = null,
    val statusCode: Int? = null,
    val errorCategory: DiagnosticsErrorCategory? = null,
    val durationMilliseconds: Double? = null,
    val wireBytes: Long? = null,
    val decodedBytes: Long? = null,
    val contentEncoding: String? = null,
    val protocolName: String? = null,
    val connectionReused: Boolean? = null,
    val dnsMilliseconds: Double? = null,
    val connectMilliseconds: Double? = null,
    val tlsMilliseconds: Double? = null,
    val firstByteMilliseconds: Double? = null,
    val downloadMilliseconds: Double? = null,
    val requestBodyBytes: Long? = null,
    val requestHeaderBytes: Long? = null,
    val responseHeaderBytes: Long? = null,
    val uploadMilliseconds: Double? = null,
    val decodeMilliseconds: Double? = null,
    val failurePhase: DiagnosticsFailurePhase? = null,
    val interfaceKind: DiagnosticsInterfaceKind? = null,
    val addressFamily: DiagnosticsAddressFamily? = null,
    val pathScope: DiagnosticsPathScope? = null,
    val isExpensive: Boolean? = null,
    val isConstrained: Boolean? = null,
    val streamLineBytes: Long? = null,
    val streamLines: Int? = null,
    val lastEventAgeMilliseconds: Double? = null,
    /** A short excerpt of what was said, kept only while Include Transcripts is on. */
    val excerpt: String? = null,
) {
    @Serializable
    enum class Kind { @SerialName("lifecycle") Lifecycle, @SerialName("navigation") Navigation, @SerialName("mark") Mark, @SerialName("requestStart") RequestStart, @SerialName("requestEnd") RequestEnd, @SerialName("networkMetrics") NetworkMetrics, @SerialName("stream") Stream, @SerialName("voice") Voice;

        val rawValue: String get() = name.replaceFirstChar { it.lowercase(Locale.ROOT) }
    }
}

object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase(Locale.ROOT))
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Where the recorder keeps its two switches. */
interface DiagnosticsSettings {
    fun bool(key: String): Boolean?
    fun set(key: String, value: Boolean)
}

/**
 * A bounded timeline (5,000 events, 15 minutes) of timing and routing metadata only; paths are
 * redacted. A voice excerpt is kept only while Include Transcripts is on.
 */
class DiagnosticsRecorder(
    enabled: Boolean? = null,
    private val maximumEvents: Int = 2_000,
    private val retentionSeconds: Double = DEFAULT_RETENTION_SECONDS,
    private val settings: DiagnosticsSettings? = null,
    defaultEnabled: Boolean = false,
    /** Persists every recorded event: internal builds keep the run before a crash. */
    private val journal: DiagnosticsJournal? = null,
    private val monotonicNow: () -> Double = { processElapsedSeconds() },
    private val wallNow: () -> Instant = Instant::now,
) {
    private class State(var enabled: Boolean, var includesTranscripts: Boolean) {
        var events = ArrayDeque<DiagnosticsEvent>()
        var navigationEpoch = 0
        var currentNavigationEpoch: Int? = null
        var currentSessionID: String? = null
        val requestStarts = HashMap<String, Double>()

        // When `requestStarts` was last swept, and how many events have come since.
        var requestPrunedAt = Double.NEGATIVE_INFINITY
        var eventsSinceRequestPrune = 0

        // Sweeps of `requestStarts`, so a test can count them: they are periodic.
        var requestPrunes = 0
    }

    private val lock = Any()
    private val state: State
    private val _changes = MutableStateFlow(0)
    @Volatile private var notifyPending = false

    /** What the journal held at launch: the tail of the previous run. */
    val previousRun: DiagnosticsJournal.PreviousRun?

    init {
        val stored = settings?.bool(DEFAULTS_KEY)
        val initial = enabled ?: (stored ?: defaultEnabled)
        val transcripts = settings?.bool(TRANSCRIPTS_DEFAULTS_KEY) ?: defaultEnabled
        state = State(initial, transcripts)
        previousRun = journal?.open(recording = initial)
    }


    private val unexpectedEndTaken = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once per launch when the previous run ended unexpectedly in the foreground. */
    fun takeUnexpectedEnd(): Boolean = previousRun?.endedUnexpectedly == true && !unexpectedEndTaken.getAndSet(true)

    /** The previous run's journaled events, oldest first; empty without a journal. */
    fun previousRunEvents(): List<DiagnosticsEvent> {
        val events = journal?.previousRunEvents().orEmpty()
        return if (includesTranscripts) events else withoutExcerpts(events)
    }

    /** Bumps (coalesced to every 0.25 s) when the timeline changes; screens reload on it. */
    val changes: StateFlow<Int> get() = _changes.asStateFlow()

    val isEnabled: Boolean get() = synchronized(lock) { state.enabled }

    val includesTranscripts: Boolean get() = synchronized(lock) { state.includesTranscripts }

    /** Turning transcripts off also forgets every excerpt already kept. */
    fun setIncludesTranscripts(value: Boolean) {
        settings?.set(TRANSCRIPTS_DEFAULTS_KEY, value)
        synchronized(lock) {
            state.includesTranscripts = value
            if (!value) state.events = ArrayDeque(withoutExcerpts(state.events))
        }
        if (!value) journal?.removeExcerpts()
        notifyChange()
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            state.enabled = enabled
            if (!enabled) state.requestStarts.clear()
        }
        settings?.set(DEFAULTS_KEY, enabled)
        // Turning recording off also forgets what this phone kept on disk.
        if (!enabled) journal?.clear()
        notifyChange()
    }

    fun clear() {
        synchronized(lock) {
            state.events.clear()
            state.requestStarts.clear()
            state.currentNavigationEpoch = null
            state.currentSessionID = null
        }
        journal?.clear()
        notifyChange()
    }

    /** The current run's retained window, oldest first; empty while off. */
    fun snapshot(): List<DiagnosticsEvent> = synchronized(lock) {
        if (!state.enabled) return emptyList()
        prune(monotonicNow())
        if (state.includesTranscripts) state.events.toList() else withoutExcerpts(state.events)
    }

    fun recordMark(name: String, sessionID: String?) = record { now, wall ->
        if (name == "session_tap" || (name == "session_navigation_started" && (state.currentNavigationEpoch == null || state.currentSessionID != sessionID))) {
            state.navigationEpoch += 1
            state.currentNavigationEpoch = state.navigationEpoch
            state.currentSessionID = sessionID
        }
        val kind = when {
            name == "session_tap" || name == "session_navigation_started" -> DiagnosticsEvent.Kind.Navigation
            name.startsWith("app_") -> DiagnosticsEvent.Kind.Lifecycle
            name.startsWith("sse_") || name.startsWith("stream_") || name.startsWith("voice_") -> DiagnosticsEvent.Kind.Stream
            else -> DiagnosticsEvent.Kind.Mark
        }
        DiagnosticsEvent(kind = kind, monotonicSeconds = now, wallDate = wall, name = name, sessionID = sessionID, navigationEpoch = state.currentNavigationEpoch)
    }

    /**
     * How the app reaches its server: a path change, an address race, a stalled event stream.
     * [detail] carries kinds, counts and timings only, never an address.
     */
    fun recordLink(name: String, detail: String) = record { now, wall ->
        DiagnosticsEvent(
            kind = DiagnosticsEvent.Kind.Stream, monotonicSeconds = now, wallDate = wall, name = name,
            sessionID = null, navigationEpoch = state.currentNavigationEpoch, reason = detail,
        )
    }

    /** One mark on a voice call's turn timeline; [excerpt] is dropped unless Include Transcripts is on. */
    fun recordVoice(name: String, sessionID: String?, detail: String? = null, excerpt: String? = null) {
        val cut = excerpt?.let(::excerpt)
        record { now, wall ->
            DiagnosticsEvent(
                kind = DiagnosticsEvent.Kind.Voice, monotonicSeconds = now, wallDate = wall, name = name,
                sessionID = sessionID ?: state.currentSessionID, navigationEpoch = state.currentNavigationEpoch,
                reason = detail, excerpt = if (state.includesTranscripts) cut else null,
            )
        }
    }

    /** A voice call's socket failed, closed or expired: only codes and the service's close reason. */
    fun recordVoiceSocketEvent(
        name: String, domain: String, code: Int, closeCode: Int,
        underlyingDomain: String? = null, underlyingCode: Int? = null, closeReason: String? = null,
        errorCategory: DiagnosticsErrorCategory? = DiagnosticsErrorCategory.Transport,
    ) {
        val parts = mutableListOf("domain=$domain", "code=$code", "closeCode=$closeCode")
        if (underlyingDomain != null && underlyingCode != null) parts += "underlying=$underlyingDomain:$underlyingCode"
        if (!closeReason.isNullOrEmpty()) parts += "closeReason=$closeReason"
        record { now, wall ->
            DiagnosticsEvent(
                kind = DiagnosticsEvent.Kind.Stream, monotonicSeconds = now, wallDate = wall, name = name,
                sessionID = state.currentSessionID, navigationEpoch = state.currentNavigationEpoch,
                reason = parts.joinToString(" "), statusCode = code, errorCategory = errorCategory,
            )
        }
    }

    fun requestStarted(
        id: String, method: String, url: String, reason: String?,
        operationID: String? = null, attempt: Int? = null, inFlightRequests: Int? = null,
    ) = record { now, wall ->
        state.requestStarts[id] = now
        DiagnosticsEvent(
            kind = DiagnosticsEvent.Kind.RequestStart, monotonicSeconds = now, wallDate = wall, name = "request_start",
            sessionID = sessionID(pathOf(url)), navigationEpoch = state.currentNavigationEpoch, requestID = id,
            operationID = operationID, attempt = attempt, inFlightRequests = inFlightRequests,
            method = method, path = redactedUrl(url), reason = reason?.let(::redactedPath),
        )
    }

    fun requestEnded(
        id: String, method: String, url: String, statusCode: Int?, errorCategory: DiagnosticsErrorCategory?,
        decodedBytes: Long?, contentEncoding: String?, operationID: String? = null, attempt: Int? = null,
        failurePhase: DiagnosticsFailurePhase? = null,
    ) = record { now, wall ->
        val start = state.requestStarts.remove(id)
        DiagnosticsEvent(
            kind = DiagnosticsEvent.Kind.RequestEnd, monotonicSeconds = now, wallDate = wall, name = "request_end",
            sessionID = sessionID(pathOf(url)), navigationEpoch = state.currentNavigationEpoch, requestID = id,
            operationID = operationID, attempt = attempt, method = method, path = redactedUrl(url), statusCode = statusCode,
            errorCategory = errorCategory, durationMilliseconds = start?.let { max(0.0, now - it) * 1_000 },
            decodedBytes = decodedBytes,
            contentEncoding = if (statusCode == null) null else contentEncoding?.takeIf { it.isNotEmpty() } ?: "identity",
            failurePhase = failurePhase,
        )
    }

    fun networkMetrics(requestID: String, path: String, metrics: DiagnosticsNetworkMetrics, operationID: String? = null, attempt: Int? = null) =
        record { now, wall ->
            DiagnosticsEvent(
                kind = DiagnosticsEvent.Kind.NetworkMetrics, monotonicSeconds = now, wallDate = wall, name = "network_metrics",
                navigationEpoch = state.currentNavigationEpoch, requestID = requestID, operationID = operationID,
                attempt = if (path == "/api/event") null else attempt, path = path,
                wireBytes = metrics.wireBytes, protocolName = metrics.protocolName, connectionReused = metrics.connectionReused,
                dnsMilliseconds = metrics.dnsMilliseconds, connectMilliseconds = metrics.connectMilliseconds,
                tlsMilliseconds = metrics.tlsMilliseconds, firstByteMilliseconds = metrics.firstByteMilliseconds,
                downloadMilliseconds = metrics.downloadMilliseconds, requestBodyBytes = metrics.requestBodyBytes,
                requestHeaderBytes = metrics.requestHeaderBytes, responseHeaderBytes = metrics.responseHeaderBytes,
                uploadMilliseconds = metrics.uploadMilliseconds,
                interfaceKind = metrics.interfaceKind ?: if (metrics.address?.scope == DiagnosticsPathScope.Loopback) DiagnosticsInterfaceKind.Loopback else null,
                addressFamily = metrics.address?.family, pathScope = metrics.address?.scope,
            )
        }

    fun decoded(requestID: String, operationID: String?, attempt: Int?, milliseconds: Double, failed: Boolean) = record { now, wall ->
        DiagnosticsEvent(
            kind = DiagnosticsEvent.Kind.Mark, monotonicSeconds = now, wallDate = wall, name = "response_decode",
            navigationEpoch = state.currentNavigationEpoch, requestID = requestID, operationID = operationID, attempt = attempt,
            decodeMilliseconds = milliseconds, failurePhase = if (failed) DiagnosticsFailurePhase.Decode else null,
        )
    }

    fun stream(
        name: DiagnosticsStreamName, requestID: String, operationID: String?, progress: DiagnosticsStreamProgress,
        statusCode: Int? = null, errorCategory: DiagnosticsErrorCategory? = null, failurePhase: DiagnosticsFailurePhase? = null,
    ) = record { now, wall ->
        val since = if (name == DiagnosticsStreamName.Connected) progress.startedAt else progress.connectedAt ?: progress.startedAt
        DiagnosticsEvent(
            kind = DiagnosticsEvent.Kind.Stream, monotonicSeconds = now, wallDate = wall, name = name.rawValue,
            navigationEpoch = state.currentNavigationEpoch, requestID = requestID, operationID = operationID,
            path = "/api/event", statusCode = statusCode, errorCategory = errorCategory,
            durationMilliseconds = max(0.0, now - since) * 1_000, failurePhase = failurePhase,
            streamLineBytes = progress.lineBytes, streamLines = progress.lines,
            lastEventAgeMilliseconds = progress.lastLineAt?.let { max(0.0, now - it) * 1_000 },
        )
    }

    /** Seconds since process start on the recorder's clock (for [DiagnosticsStreamProgress]). */
    fun now(): Double = monotonicNow()

    fun exportJSON(): String = exportJSON(snapshot())

    fun exportText(wallClock: Boolean = false): String = exportText(snapshot(), wallClock)

    private inline fun record(crossinline event: (now: Double, wall: Instant) -> DiagnosticsEvent) {
        if (!isEnabled) return
        val now = monotonicNow()
        val wall = wallNow()
        val inserted = synchronized(lock) {
            if (!state.enabled) return@synchronized null
            val value = event(now, wall)
            state.events.addLast(value)
            prune(now)
            while (state.events.size > maximumEvents) state.events.removeFirst()
            value
        } ?: return
        journal?.append(inserted)
        notifyChange()
    }

    private fun prune(now: Double) {
        val oldest = now - retentionSeconds
        // Sweeps run periodically, not per event: one walks every unfinished start.
        state.eventsSinceRequestPrune += 1
        if (state.eventsSinceRequestPrune >= REQUEST_PRUNE_EVERY_EVENTS || now - state.requestPrunedAt >= REQUEST_PRUNE_EVERY_SECONDS) {
            state.eventsSinceRequestPrune = 0
            state.requestPrunedAt = now
            state.requestStarts.entries.removeAll { it.value < oldest }
            state.requestPrunes += 1
        }
        // Unfinished timings stay bounded however long they wait for a sweep.
        if (state.requestStarts.size > maximumEvents) state.requestStarts.minByOrNull { it.value }?.key?.let(state.requestStarts::remove)
        while (state.events.isNotEmpty() && state.events.first().monotonicSeconds < oldest) state.events.removeFirst()
    }

    /** Sweeps of the request-start dictionary, for tests: never one per event. */
    val requestPruneCount: Int get() = synchronized(lock) { state.requestPrunes }

    private fun notifyChange() {
        if (notifyPending) return
        notifyPending = true
        timer.schedule((NOTIFY_INTERVAL * 1000).toLong()) {
            notifyPending = false
            _changes.value += 1
        }
    }

    companion object Key : DependencyKey<DiagnosticsRecorder> {
        const val DEFAULTS_KEY = "diagnostics.enabled"
        const val TRANSCRIPTS_DEFAULTS_KEY = "diagnostics.includeTranscripts"
        const val EXCERPT_LIMIT = 120
        const val NOTIFY_INTERVAL = 0.25

        /**
         * The request-start dictionary is swept every this many events, or after this long,
         * whichever comes first: the sweep walks it, and it runs for every recorded event.
         */
        const val REQUEST_PRUNE_EVERY_EVENTS = 64
        const val REQUEST_PRUNE_EVERY_SECONDS = 5.0
        const val DEFAULT_RETENTION_SECONDS = 600.0
        val retentionMinutes: Int = (DEFAULT_RETENTION_SECONDS / 60).toInt()
        const val TOUCH_PROBE_EVENT_NAME = "touch_probe"
        private val timer by lazy { Timer("diagnostics-notify", true) }

        /** The process-wide recorder. Internal builds record by default. */
        val shared: DiagnosticsRecorder by lazy {
            val context = runCatching { dependency(AppContext).applicationContext }.getOrNull()
                ?: return@lazy DiagnosticsRecorder()
            val settings = SharedPreferencesDiagnosticsSettings(context)
            if (InternalBuild.isEnabled) {
                DiagnosticsRecorder(maximumEvents = 5_000, retentionSeconds = 900.0, settings = settings, defaultEnabled = true, journal = DiagnosticsJournal.live(context))
            } else {
                DiagnosticsRecorder(settings = settings)
            }
        }

        override val liveValue: DiagnosticsRecorder get() = shared
        override val testValue: DiagnosticsRecorder get() = DiagnosticsRecorder(enabled = false)

        /** Seconds since this process started. */
        fun processElapsedSeconds(): Double = max(0.0, (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1_000.0)

        fun withoutExcerpts(events: Collection<DiagnosticsEvent>): List<DiagnosticsEvent> =
            if (events.none { it.excerpt != null }) events.toList() else events.map { it.copy(excerpt = null) }

        fun excerpt(text: String): String? {
            val flattened = text.lines().joinToString(" ").trim()
            if (flattened.isEmpty()) return null
            if (flattened.length <= EXCERPT_LIMIT) return flattened
            return flattened.take(EXCERPT_LIMIT - 1) + "…"
        }

        private val prettyJson = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }

        fun exportJSON(events: List<DiagnosticsEvent>): String =
            runCatching { prettyJson.encodeToString(ListSerializer(DiagnosticsEvent.serializer()), events) }.getOrDefault("[]")

        /** One line per event; [wallClock] adds UTC times for lining up with server logs. */
        fun exportText(values: List<DiagnosticsEvent>, wallClock: Boolean = false): String {
            val lines = mutableListOf<String>()
            val first = values.firstOrNull()
            if (first != null) {
                lines += listOf("OpenCode diagnostics", "Events: ${values.size}")
                if (wallClock) lines += "From ${DateTimeFormatter.ISO_INSTANT.format(first.wallDate.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))} to ${DateTimeFormatter.ISO_INSTANT.format(values.last().wallDate.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))}"
                lines += ""
                val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
                var origin = first.monotonicSeconds
                var previous = first.monotonicSeconds
                for (event in values) {
                    if (event.monotonicSeconds < previous) origin = event.monotonicSeconds
                    previous = event.monotonicSeconds
                    val relative = String.format(Locale.ROOT, "+%.3fs", max(0.0, event.monotonicSeconds - origin))
                    val pieces = if (wallClock) mutableListOf(relative, clock.format(event.wallDate)) else mutableListOf(relative)
                    pieces += listOf("[${event.kind.rawValue}]", event.name)
                    event.navigationEpoch?.let { pieces += "epoch=$it" }
                    event.method?.let { pieces += it }
                    event.path?.let { pieces += it }
                    event.durationMilliseconds?.let { pieces += String.format(Locale.ROOT, "%.0fms", it) }
                    event.statusCode?.let { pieces += "status=$it" }
                    event.errorCategory?.let { pieces += "error=${it.description}" }
                    if ((event.kind == DiagnosticsEvent.Kind.Stream || event.kind == DiagnosticsEvent.Kind.Voice || event.name == TOUCH_PROBE_EVENT_NAME) && event.reason != null) {
                        pieces += event.reason
                    }
                    event.excerpt?.let { pieces += "“$it”" }
                    event.wireBytes?.let { pieces += "wire=$it" }
                    event.decodedBytes?.let { pieces += "decoded=$it" }
                    event.contentEncoding?.let { pieces += "encoding=$it" }
                    event.protocolName?.let { pieces += "protocol=$it" }
                    event.connectionReused?.let { pieces += "reused=$it" }
                    lines += pieces.joinToString(" ")
                }
            } else {
                lines += listOf("OpenCode diagnostics", "(no events)")
            }
            return lines.joinToString("\n") + "\n"
        }

        /** A URL's redacted path plus its query names (`?directory=<redacted>`); never the host. */
        fun redactedUrl(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return redactedPath(url)
            val path = redactedPath(uri.rawPath ?: "")
            val names = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map { it.substringBefore('=') }.orEmpty()
            return path + if (names.isEmpty()) "" else "?" + names.joinToString("&") { "$it=<redacted>" }
        }

        /**
         * Path segments that carry credentials or file names: `/auth/<route>/…` (a pairing code is
         * the credential) and `/api/fs/read/<file>`. A query, if any, is dropped.
         */
        fun redactedPath(path: String): String {
            val bare = path.substringBefore('?')
            for (prefix in listOf("/auth/", "/api/fs/read/")) {
                if (!bare.startsWith(prefix)) continue
                val rest = bare.removePrefix(prefix)
                val kept = if (prefix == "/auth/") rest.split('/', limit = 2).firstOrNull()?.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: "" else ""
                return "$prefix$kept<redacted>"
            }
            return bare
        }

        private fun pathOf(url: String): String = runCatching { URI(url).rawPath }.getOrNull() ?: url

        private fun sessionID(path: String): String? {
            val parts = path.split('/').filter { it.isNotEmpty() }
            val index = parts.indexOf("session")
            return if (index >= 0 && index + 1 < parts.size) parts[index + 1] else null
        }
    }
}

private class SharedPreferencesDiagnosticsSettings(context: Context) : DiagnosticsSettings {
    private val preferences: SharedPreferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    override fun bool(key: String): Boolean? = if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    override fun set(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
}
