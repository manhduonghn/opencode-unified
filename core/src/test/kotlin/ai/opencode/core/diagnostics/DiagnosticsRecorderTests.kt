package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID

/**
 * Android takes a socket address as bytes (OkHttp hands over an `InetAddress`), so the address test
 * parses literals ([addressBytes]) before classifying.
 */
class DiagnosticsRecorderTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Clock {
        var value = 100.0
        var calls = 0
        fun now(): Double {
            calls += 1
            return value
        }
    }

    private val url = "https://example.invalid/api/info"

    @Test
    fun unfinishedRequestTimingIsForgottenOnDisableAgeAndCount() {
        val clock = Clock()
        val recorder = DiagnosticsRecorder(enabled = true, maximumEvents = 1, retentionSeconds = 5.0, monotonicNow = clock::now)
        fun start(id: String) = recorder.requestStarted(id = id, method = "GET", url = url, reason = null)
        fun end(id: String) = recorder.requestEnded(
            id = id, method = "GET", url = url, statusCode = 200, errorCategory = null, decodedBytes = 0, contentEncoding = null,
        )
        start("disabled")
        recorder.setEnabled(false)
        recorder.setEnabled(true)
        end("disabled")
        assertNull(recorder.snapshot().last().durationMilliseconds)
        start("old")
        clock.value += 6
        recorder.snapshot()
        end("old")
        assertNull(recorder.snapshot().last().durationMilliseconds)
        start("first")
        clock.value += 1
        start("second")
        end("first")
        assertNull(recorder.snapshot().last().durationMilliseconds)
    }

    @Test
    fun unfinishedRequestTimingsAreSweptPeriodicallyNotPerEvent() {
        val clock = Clock()
        val recorder = DiagnosticsRecorder(enabled = true, maximumEvents = 5_000, retentionSeconds = 900.0, monotonicNow = clock::now)
        val events = DiagnosticsRecorder.REQUEST_PRUNE_EVERY_EVENTS * 4
        for (index in 0 until events) {
            recorder.requestStarted(id = "r$index", method = "GET", url = url, reason = null)
        }
        // One sweep at the first event (nothing had run yet), then one every 64
        // events: four for 256, not one per event.
        assertEquals(4, recorder.requestPruneCount)
    }

    @Test
    fun streamProgressCountsLinesNotWireBytesAndOnlyMarksLongGaps() {
        val progress = DiagnosticsStreamProgress(startedAt = 1.0)
        val first = progress.receiveLine(byteCount = 12, now = 2.0)
        val heartbeat = progress.receiveLine(byteCount = 0, now = 3.0)
        val resumed = progress.receiveLine(byteCount = 18, now = 35.0)
        assertTrue(!first && !heartbeat && resumed)
        val recorder = DiagnosticsRecorder(enabled = true, monotonicNow = { 40.0 })
        recorder.stream(DiagnosticsStreamName.Disconnected, requestID = "request", operationID = null, progress = progress)
        val event = recorder.snapshot().first()
        assertTrue(event.streamLineBytes == 30L && event.streamLines == 3)
        assertTrue(event.wireBytes == null && event.decodedBytes == null)
        assertEquals(5_000.0, event.lastEventAgeMilliseconds!!, 0.0)
    }

    @Test
    fun classifiesAddressesWithoutRetainingThemOrGuessingInterfaces() {
        for ((address, scope) in listOf(
            "127.12.0.1" to DiagnosticsPathScope.Loopback, "100.64.0.1" to DiagnosticsPathScope.Tailnet,
            "100.127.255.254" to DiagnosticsPathScope.Tailnet, "100.128.0.1" to DiagnosticsPathScope.Wan,
            "172.16.0.1" to DiagnosticsPathScope.Lan, "172.32.0.1" to DiagnosticsPathScope.Wan, "169.254.1.2" to DiagnosticsPathScope.Lan,
            "::1" to DiagnosticsPathScope.Loopback, "fd7a:115c:a1e0::12" to DiagnosticsPathScope.Tailnet,
            "fe80::123%en0" to DiagnosticsPathScope.Lan, "::ffff:100.64.1.2" to DiagnosticsPathScope.Tailnet,
            "2606:4700::1111" to DiagnosticsPathScope.Wan,
        )) {
            assertEquals(address, scope, DiagnosticsNetworkAddress.of(addressBytes(address))?.scope)
        }
        assertEquals(DiagnosticsAddressFamily.Ipv4, DiagnosticsNetworkAddress.of(addressBytes("100.64.0.1"))?.family)
        assertEquals(DiagnosticsAddressFamily.Ipv6, DiagnosticsNetworkAddress.of(addressBytes("::ffff:100.64.0.1"))?.family)
        for (address in listOf(null, "", "peer.ts.net", "secret/path", "999.1.1.1")) {
            assertNull(address, DiagnosticsNetworkAddress.of(addressBytes(address)))
        }
    }

    @Test
    fun failurePhaseUsesOnlyUnfinishedTimestampEvidence() {
        val date = 1L
        val phases = DiagnosticsNetworkPhases()
        assertNull(phases.unfinished)
        phases.dnsStart = date
        assertEquals(DiagnosticsFailurePhase.Dns, phases.unfinished)
        phases.dnsEnd = date
        phases.connectStart = date
        assertEquals(DiagnosticsFailurePhase.Tcp, phases.unfinished)
        phases.tlsStart = date
        assertEquals("TLS is nested in connect", DiagnosticsFailurePhase.Tls, phases.unfinished)
        phases.tlsEnd = date
        phases.connectEnd = date
        phases.requestStart = date
        assertEquals(DiagnosticsFailurePhase.RequestWrite, phases.unfinished)
        phases.requestEnd = date
        assertEquals(DiagnosticsFailurePhase.Ttfb, phases.unfinished)
        phases.responseStart = date
        assertEquals(DiagnosticsFailurePhase.BodyRead, phases.unfinished)
        phases.responseEnd = date
        assertNull(phases.unfinished)
    }

    @Test
    fun missingResponseBytesStayUnknownAndDecodeIsAnIndependentMeasurement() {
        val recorder = DiagnosticsRecorder(enabled = true)
        recorder.requestEnded(
            id = "failed", method = "GET", url = url, statusCode = null, errorCategory = DiagnosticsErrorCategory.Timeout,
            decodedBytes = null, contentEncoding = null, failurePhase = DiagnosticsFailurePhase.Ttfb,
        )
        recorder.decoded(requestID = "other", operationID = null, attempt = 2, milliseconds = 13.0, failed = true)
        val events = recorder.snapshot()
        assertTrue(events[0].decodedBytes == null && events[0].contentEncoding == null)
        assertEquals(DiagnosticsFailurePhase.Ttfb, events[0].failurePhase)
        assertTrue(events[1].decodeMilliseconds == 13.0 && events[1].failurePhase == DiagnosticsFailurePhase.Decode)
        assertEquals(2, events[1].attempt)
    }

    @Test
    fun boundsByCountAndAgeAndUsesMonotonicDurations() {
        val clock = Clock()
        val recorder = DiagnosticsRecorder(
            enabled = true, maximumEvents = 3, retentionSeconds = 10.0,
            monotonicNow = clock::now, wallNow = { Instant.ofEpochSecond(1_000) },
        )
        val url = "https://example.test/api/session/ses_safe/message?password=hunter2"
        recorder.requestStarted(id = "one", method = "GET", url = url, reason = "refresh")
        clock.value = 101.25
        recorder.requestEnded(
            id = "one", method = "GET", url = url, statusCode = 200, errorCategory = null, decodedBytes = 1_024, contentEncoding = "gzip",
        )
        recorder.recordMark("snapshot_apply", sessionID = "ses_safe")
        recorder.recordMark("fresh_content_laid_out", sessionID = "ses_safe")

        val bounded = recorder.snapshot()
        assertEquals(3, bounded.size)
        assertEquals(1_250.0, bounded.first().durationMilliseconds!!, 1e-9)
        assertEquals("/api/session/ses_safe/message?password=<redacted>", bounded.first().path)

        clock.value = 112.0
        recorder.recordMark("after_retention", sessionID = null)
        assertEquals(listOf("after_retention"), recorder.snapshot().map { it.name })
    }

    /** Android reads a socket error's errno from its message. */
    @Test
    fun socketErrnosClassifyAsOfflineOrTimeout() {
        fun posix(name: String) = DiagnosticsErrorCategory.of(SocketException("sendto failed: $name"))
        for (code in listOf("ENOTCONN", "ECONNRESET", "ECONNABORTED", "EPIPE", "ENETDOWN", "ENETUNREACH", "EHOSTDOWN", "EHOSTUNREACH")) {
            assertEquals(code, DiagnosticsErrorCategory.Offline, posix(code))
        }
        assertEquals(DiagnosticsErrorCategory.Timeout, posix("ETIMEDOUT"))
        assertEquals(DiagnosticsErrorCategory.Transport, posix("EACCES"))
        assertEquals(DiagnosticsErrorCategory.Offline, DiagnosticsErrorCategory.of("NSURLErrorDomain", -1005))
        assertEquals(DiagnosticsErrorCategory.Transport, DiagnosticsErrorCategory.of("NSCocoaErrorDomain", 57))
        for (code in listOf(57, 54, 53, 32, 50, 51, 64, 65)) {
            assertEquals(DiagnosticsErrorCategory.Offline, DiagnosticsErrorCategory.of("NSPOSIXErrorDomain", code))
        }
        assertEquals(DiagnosticsErrorCategory.Timeout, DiagnosticsErrorCategory.of("NSPOSIXErrorDomain", 60))
        assertEquals(DiagnosticsErrorCategory.Transport, DiagnosticsErrorCategory.of("NSPOSIXErrorDomain", 13))
    }

    @Test
    fun voiceSocketEventsAreStreamEventsWithTheirCodes() {
        val recorder = DiagnosticsRecorder(enabled = true)
        recorder.recordVoiceSocketEvent(
            "voice_socket_dropped",
            domain = "NSURLErrorDomain",
            code = -1,
            closeCode = 1006,
            underlyingDomain = "NSPOSIXErrorDomain",
            underlyingCode = 57,
            closeReason = "going away",
            errorCategory = DiagnosticsErrorCategory.Offline,
        )
        recorder.recordMark("voice_socket_reconnected", sessionID = null)

        val events = recorder.snapshot()
        assertEquals(listOf(DiagnosticsEvent.Kind.Stream, DiagnosticsEvent.Kind.Stream), events.map { it.kind })
        val dropped = events.first()
        assertEquals("domain=NSURLErrorDomain code=-1 closeCode=1006 underlying=NSPOSIXErrorDomain:57 closeReason=going away", dropped.reason)
        assertEquals(-1, dropped.statusCode)
        assertTrue(recorder.exportText().contains("voice_socket_dropped status=-1 error=offline domain=NSURLErrorDomain"))
    }

    @Test
    fun disabledPathRecordsNothing() {
        val clock = Clock()
        val recorder = DiagnosticsRecorder(enabled = false, monotonicNow = clock::now)
        recorder.recordMark("secret prompt body", sessionID = "ses_private")
        assertTrue(recorder.snapshot().isEmpty())
        assertEquals(0, clock.calls)
    }

    @Test
    fun exportsNeverContainQueryValuesOrAuthorizationHeaders() {
        val recorder = DiagnosticsRecorder(enabled = true)
        val url = "https://user:password@example.test/api/session/ses_123?password=super-secret&token=private"
        recorder.requestStarted(id = "request", method = "GET", url = url, reason = "test")
        recorder.requestEnded(
            id = "request", method = "GET", url = url, statusCode = 200, errorCategory = null, decodedBytes = 20, contentEncoding = "gzip",
        )
        val export = recorder.exportJSON()
        assertTrue(export.contains("ses_123"))
        assertTrue(export.contains("password=<redacted>"))
        assertFalse(export.contains("super-secret"))
        assertFalse(export.contains("private"))
        assertFalse(export.contains("Authorization", ignoreCase = true))
        assertFalse(export.contains("user:password"))
    }

    @Test
    fun textExportSnapshot() {
        val event = DiagnosticsEvent(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            kind = DiagnosticsEvent.Kind.RequestEnd,
            monotonicSeconds = 42.0,
            wallDate = Instant.ofEpochSecond(0),
            name = "request_end",
            navigationEpoch = 2,
            method = "GET",
            path = "/api/session/ses_example/message?limit=<redacted>",
            statusCode = 200,
            durationMilliseconds = 125.0,
            decodedBytes = 326_000,
            contentEncoding = "gzip",
        )
        assertEquals(
            """
            OpenCode diagnostics
            Events: 1

            +0.000s [requestEnd] request_end epoch=2 GET /api/session/ses_example/message?limit=<redacted> 125ms status=200 decoded=326000 encoding=gzip
            """.trimIndent() + "\n",
            DiagnosticsRecorder.exportText(listOf(event)),
        )
    }

    @Test
    fun classifiesTransportAndHTTPFailures() {
        assertEquals(DiagnosticsErrorCategory.Timeout, DiagnosticsErrorCategory.of(SocketTimeoutException("timeout")))
        assertEquals(DiagnosticsErrorCategory.Offline, DiagnosticsErrorCategory.of(java.net.UnknownHostException("host")))
        assertEquals(DiagnosticsErrorCategory.Cancelled, DiagnosticsErrorCategory.of(java.io.IOException("Canceled")))
        assertEquals(DiagnosticsErrorCategory.Unauthorized, DiagnosticsErrorCategory.of(401))
        assertEquals(DiagnosticsErrorCategory.NotFound, DiagnosticsErrorCategory.of(404))
        assertEquals(DiagnosticsErrorCategory.Server(503), DiagnosticsErrorCategory.of(503))
    }
}

class PrivacyManifestTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun diagnosticsRecorderExportsElapsedTimeRelativeToAppLaunchInsteadOfRawUptime() {
        val recorder = DiagnosticsRecorder(enabled = true)
        recorder.recordMark("app_active", sessionID = null)
        val event = recorder.snapshot().first()
        assertTrue(event.monotonicSeconds >= 0)

        val run1 = DiagnosticsEvent(kind = DiagnosticsEvent.Kind.Lifecycle, monotonicSeconds = 45.0, wallDate = Instant.ofEpochSecond(1_000), name = "app_background")
        val run2 = DiagnosticsEvent(kind = DiagnosticsEvent.Kind.Lifecycle, monotonicSeconds = 0.25, wallDate = Instant.ofEpochSecond(1_010), name = "app_active")
        val text = DiagnosticsRecorder.exportText(listOf(run1, run2), wallClock = true)
        assertFalse("Resetting monotonicSeconds on a new launch must not produce negative relative times", text.contains("+-"))
        assertTrue(text.contains("+0.000s"))
    }
}

/** IPv4 dotted quads and IPv6 (zone stripped). */
private fun addressBytes(address: String?): ByteArray? {
    val host = address?.substringBefore('%') ?: return null
    parseV4(host)?.let { return it }
    if (!host.contains(':')) return null
    val (head, tail) = if ("::" in host) host.substringBefore("::") to host.substringAfter("::") else host to null
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        val pieces = part.split(':')
        for ((index, piece) in pieces.withIndex()) {
            if (index == pieces.lastIndex && '.' in piece) {
                val v4 = parseV4(piece) ?: return null
                result += ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
                result += ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
            } else {
                result += piece.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null
            }
        }
        return result
    }
    val first = groups(head) ?: return null
    val last = tail?.let { groups(it) ?: return null }
    val words = if (last == null) first.takeIf { it.size == 8 } ?: return null
    else (first + List(8 - first.size - last.size) { 0 } + last).takeIf { first.size + last.size < 8 } ?: return null
    return ByteArray(16) { (if (it % 2 == 0) words[it / 2] shr 8 else words[it / 2] and 0xff).toByte() }
}

private fun parseV4(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val values = parts.map { part -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
    return ByteArray(4) { values[it].toByte() }
}
