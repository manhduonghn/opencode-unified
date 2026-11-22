package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class MarkdownBlockTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun markdownImageClientDecodesDataURIAndCachesInIsolatedTestScope() = runTest {
        val client = OpenCodeClient.testValue
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val dataURI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val key = OpenCodeClient.markdownImageKey(server, "/tmp", dataURI)
        assertEquals("${server.persistenceID}|/tmp|$dataURI", key)
        assertNull(client.cachedMarkdownImage(key))
        val loaded = client.markdownImage(server, "/tmp", dataURI, null)
        assertNotNull(loaded)
        assertSame(loaded, client.cachedMarkdownImage(key))
        assertNull("Each test client has its own cache", OpenCodeClient.testValue.cachedMarkdownImage(key))
    }
}
