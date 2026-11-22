package ai.opencode.server.testing

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.testing.TestIsolationGuard
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeTransport
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ServerTestIsolationTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun blocksUnmockedNetworkRequestsWithHostOnlyAndAllowsWireFixtures() = runTest {
        val violations = TestIsolationGuard.capturingViolations("unmockedNetworkTest()") {
            val request = Request.Builder().url("https://secret-host.example.com:8443/api/session?token=super-secret-value").build()
            try {
                dependency(OpenCodeTransport).request.newCall(request).execute().close()
                fail("Expected network request to be blocked")
            } catch (_: IOException) {
                // Expected: ConnectException from TestNetworkBlock.
            }
        }
        assertEquals(violations.toString(), 1, violations.size)
        val message = violations.first()
        assertTrue(message.contains("unmockedNetworkTest()"))
        assertTrue(message.contains("secret-host.example.com"))
        assertFalse("Must never log query strings or secrets", message.contains("super-secret-value"))
        assertFalse("Must log host only, never full path", message.contains("/api/session"))

        val fixture = WireFixture { WireFixture.Reply.Json(200, """{"ok":true}""") }
        try {
            val response = dependency(OpenCodeTransport).request.newCall(Request.Builder().url("${fixture.server.url}/api/info").build()).execute()
            response.use { assertEquals(200, it.code) }
        } finally {
            fixture.close()
        }
    }
}
