package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.logDescription
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ConnectionErrorReportTests {
    @get:Rule val isolation = TestIsolationRule()

    private val timedOut get() = SocketTimeoutException("timeout")

    @Test
    fun cancelledLookupReturnsImmediately() = runBlocking {
        val started = System.nanoTime()
        var addresses: List<String>? = null
        launch {
            coroutineContext.job.cancel()
            addresses = ConnectionFailureDetail.resolve("100.64.0.1")
        }.join()
        assertEquals(emptyList<String>(), addresses)
        if (System.getenv("CI") != "true") {
            assertTrue((System.nanoTime() - started) < 500_000_000L)
        }
    }

    @Test
    fun mixedDNSAnswersDoNotProveThePublicAddressWasTried() {
        val detail = ConnectionFailureDetail(
            "https://studio.test.ts.net:4096".toHttpUrl(), timedOut, addresses = listOf("100.64.0.1", "34.117.59.81"),
        )
        assertFalse(detail.hasTailnetDNSMismatch)
        assertFalse(detail.text.contains("Use Tailscale DNS"))
    }

    @Test
    fun liveHealthFailureRetainsURLLoadingCodeWithoutRetrying() = runTest {
        val fixture = WireFixture { Reply.Failure(UrlErrorCode.TIMED_OUT) }
        try {
            val failure = try {
                OpenCodeClient.liveValue.health(fixture.server)
                null
            } catch (error: OpenCodeClientError) {
                error
            }
            assertNotNull(failure)
            val detail = failure!!.connectionFailures.first()
            assertEquals("https://${fixture.host}:443", detail.endpoint)
            assertEquals("URLError -1001 timedOut", detail.errorLabel)
            assertEquals(TransportFailure.TimedOut, failure.transport)
            assertEquals(1, fixture.requests.size)
            assertFalse(detail.logSummary.contains(fixture.host))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun httpFailuresStillIdentifyTheCandidate() {
        val detail = ConnectionFailureDetail(
            "https://box.example".toHttpUrl(),
            OpenCodeClientError("The server rejected these credentials.", statusCode = 401),
        )
        assertEquals("HTTP 401 · https://box.example:443\nResolved address: Unknown", detail.text)
        assertFalse(detail.hasTailnetDNSMismatch)
    }

    @Test
    fun tailnetRanges() {
        for (address in listOf(
            "100.64.0.0", "100.127.255.255", "100.100.100.100",
            "fd7a:115c:a1e0::1", "FD7A:115C:A1E0:ffff::1", "::ffff:100.64.0.1",
            "fd7a:115c:a1e0::1%utun3", "::ffff:100.64.0.1%utun3",
        )) {
            assertTrue(address, ConnectionFailureDetail.isTailnetAddress(address))
        }
    }

    @Test
    fun outsideTailnetRanges() {
        for (address in listOf(
            "100.63.255.255", "100.128.0.0", "34.117.59.81", "192.168.1.1",
            "fd7a:115c:a1df::1", "fd7a:115c:a1e1::1", "2606:4700::1", "::1",
            "::ffff:34.117.59.81", "::ffff:100.63.255.255", "::ffff:100.128.0.0",
        )) {
            assertFalse(address, ConnectionFailureDetail.isTailnetAddress(address))
            val detail = ConnectionFailureDetail("https://studio.test.ts.net:4096".toHttpUrl(), timedOut, addresses = listOf(address))
            assertTrue(address, detail.hasTailnetDNSMismatch)
        }
    }

    @Test
    fun otherHostsNeverGetTailnetAdvice() {
        for (host in listOf("example.com", "ts.net", "not-ts.net", "studio.ts.net.example.com")) {
            val detail = ConnectionFailureDetail("https://$host".toHttpUrl(), timedOut, addresses = listOf("34.117.59.81"))
            assertFalse(host, detail.hasTailnetDNSMismatch)
            assertFalse(host, detail.text.contains("Use Tailscale DNS"))
        }
    }

    @Test
    fun unknownAndTailnetResolutionsDoNotClaimDNSMismatch() {
        for (addresses in listOf(emptyList(), listOf("100.64.1.2", "fd7a:115c:a1e0::2"))) {
            val detail = ConnectionFailureDetail("https://studio.test.ts.net".toHttpUrl(), UnknownHostException("fixture"), addresses = addresses)
            assertFalse(detail.hasTailnetDNSMismatch)
            assertEquals("https://studio.test.ts.net:443", detail.endpoint)
            assertEquals("URLError -1003 cannotFindHost", detail.errorLabel)
        }
    }

    @Test
    fun shareableEvidenceAndLogProjectionKeepCredentialsOut() {
        val url = "https://user:password@studio.test.ts.net:4096/auth/connect/pair-secret?token=secret#fragment".toHttpUrl()
        // The failing URL rides along in the exception's message, as `NSURLErrorFailingURLErrorKey` does.
        val error = SocketTimeoutException("timeout")
        val detail = ConnectionFailureDetail(url, error, addresses = listOf("34.117.59.81"))
        assertEquals("URLError -1001 timedOut", detail.errorLabel)
        assertEquals("https://studio.test.ts.net:4096", detail.endpoint)
        assertTrue(detail.text.contains("34.117.59.81"))
        assertTrue(detail.text.contains("Use Tailscale DNS settings"))
        for (secret in listOf("user", "password", "pair-secret", "token=", "fragment")) {
            assertFalse(secret, detail.text.contains(secret))
        }
        assertEquals("code=-1001 tailnetAddress=false", detail.logSummary)
        val urlFailure = SocketTimeoutException(url.toString())
        assertTrue(logDescription(urlFailure).startsWith("URLError -1001"))
        for (privateValue in listOf("studio", "ts.net", "4096", "34.117.59.81", "password", "pair-secret")) {
            assertFalse(privateValue, detail.logSummary.contains(privateValue))
            assertFalse(privateValue, logDescription(urlFailure).contains(privateValue))
        }
    }
}
