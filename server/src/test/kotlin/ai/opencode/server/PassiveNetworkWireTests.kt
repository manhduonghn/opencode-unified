package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.retry.ServerContact
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** These catch missing identity on the transport paths that bypass perform. */
class PassiveNetworkWireTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun finalImageResponseOriginNeverEstablishesUnrelatedServerContact() = runTest {
        for (serverRead in listOf(true, false)) {
            val final = "http://redirect-${UUID.randomUUID().toString().lowercase()}.invalid/image.png".toHttpUrl()
            val fixture = WireFixture(scheme = "http", responseURL = final) { Reply.Json(200, "not an image") }
            try {
                val original = "http://${fixture.host}/api/fs/read/image.png".toHttpUrl()
                assertFalse(ServerContact.shared.hasAnsweredHTTPOrigin(original))
                assertFalse(ServerContact.shared.hasAnsweredHTTPOrigin(final))
                OpenCodeClient.liveValue.markdownImage(
                    fixture.server, if (serverRead) "/project" else null, if (serverRead) "image.png" else original.toString(), null,
                )
                assertEquals(1, fixture.requests.size)
                assertEquals(serverRead, ServerContact.shared.hasAnsweredHTTPOrigin(original))
                assertFalse("A final response URL is never remembered as first-party contact", ServerContact.shared.hasAnsweredHTTPOrigin(final))
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun explicitImageURLsStayExternalEvenOnTheServerOrigin() = runTest {
        for (scheme in listOf("https", "HTTPS")) {
            val fixture = WireFixture { Reply.Json(200, "not an image") }
            try {
                OpenCodeClient.liveValue.markdownImage(fixture.server, null, "$scheme://${fixture.host}/api/fs/read/private.png", null)
                val request = fixture.requests.first()
                assertTrue(request.header("X-OpenCode-Op") == null && request.header("X-OpenCode-Req") == null)
                assertNull(request.header("Authorization"))
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun serverMarkdownReadsCarryTaskIdentity() = runTest {
        val fixture = WireFixture { Reply.Json(200, "not an image") }
        try {
            OpenCodeClient.liveValue.markdownImage(fixture.server, "/test", "picture.png", null)
            val request = fixture.requests.first()
            assertNotNull(UUID.fromString(request.header("X-OpenCode-Op")!!))
            assertNotNull(UUID.fromString(request.header("X-OpenCode-Req")!!))
            assertEquals("1", request.header("X-OpenCode-Attempt"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun streamConnectionsHaveIndependentIdentityNotInventedRetryNumbers() = runTest {
        val fixture = WireFixture { Reply.Json(200, "data: {\"type\":\"server.connected\"}\n\n") }
        try {
            repeat(2) {
                try {
                    OpenCodeClient.liveValue.events(fixture.server).collect()
                } catch (_: OpenCodeClientError) {
                    // EOF is the stream's expected terminal error.
                }
            }
            val requests = fixture.requests
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.header("X-OpenCode-Attempt") == null })
            assertNotEquals(requests[0].header("X-OpenCode-Op"), requests[1].header("X-OpenCode-Op"))
            assertNotEquals(requests[0].header("X-OpenCode-Req"), requests[1].header("X-OpenCode-Req"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anExternalImageWithAServerShapedPathGetsNoCorrelationHeaders() = runTest {
        val server = WireFixture { Reply.status(404) }
        val external = WireFixture { Reply.Json(200, "not an image") }
        try {
            OpenCodeClient.liveValue.markdownImage(server.server, null, "https://${external.host}/api/fs/read/private.png", null)
            val request = external.requests.first()
            assertTrue(request.header("X-OpenCode-Op") == null && request.header("X-OpenCode-Req") == null)
            assertNull(request.header("Authorization"))
        } finally {
            server.close()
            external.close()
        }
    }
}
