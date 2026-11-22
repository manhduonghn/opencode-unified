package ai.opencode.server

import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.logDescription
import ai.opencode.server.live.loggablePath
import ai.opencode.server.retry.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.SocketException
import kotlin.time.Duration.Companion.milliseconds

/**
 * What reaches the log about a failed request: statuses and error codes, never messages,
 * URLs, or pairing codes.
 */
class AppLogTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun errorsReadAsStatusesNeverMessages() {
        assertEquals("HTTP 503, Retry-After 2 s", OpenCodeClientError("Secret server text", statusCode = 503, retryAfter = 2.0).logSummary)
        val lost = OpenCodeClientError.unreachable(TransportFailure.Interrupted).copy(attempts = 3)
        assertEquals("transport interrupted, after 3 attempts", lost.logSummary)
        assertEquals("unclassified connection error", OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE).logSummary)
        assertEquals("client error", OpenCodeClientError("The transcript was unreadable").logSummary)
        assertEquals("320 ms", 320.milliseconds.logSummary)
        assertEquals("1.25 s", 1250.milliseconds.logSummary)
    }

    @Test
    fun aPairingCodeNeverReachesTheLog() {
        assertEquals("/auth/…", loggablePath("/auth/connect/abc-123"))
        assertEquals("/api/session/ses_1/prompt", loggablePath("/api/session/ses_1/prompt"))
        // Android: the URLError code the exception maps to (`UrlErrorCode`), its type, then its
        // cause's type; never the message that carries the URL.
        val error = SocketException("https://user:secret@example.ts.net/api").apply {
            initCause(java.io.IOException("connection reset by https://user:secret@example.ts.net"))
        }
        val description = logDescription(error)
        assertEquals("URLError -1005 (SocketException) (IOException)", description)
        assertFalse(description.contains("secret"))
        assertFalse(description.contains("example.ts.net"))
    }

    /** The message is a lambda: built at most once, and only while its level is collected. */
    @Test
    fun messagesAreBuiltAtMostOnce() {
        var built = 0
        fun message(): String {
            built += 1
            return "line $built"
        }
        val log = AppLog.app
        log.debug { message() }
        log.info { message() }
        log.notice { message() }
        log.error { message() }
        AppLog.perf.info { message() }
        assertTrue(built <= 5)
    }
}
