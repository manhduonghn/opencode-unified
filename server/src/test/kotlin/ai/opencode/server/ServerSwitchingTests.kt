package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerSwitchingTests {
    @get:Rule val isolation = TestIsolationRule()

    // MARK: Names

    @Test
    fun friendlyNamesNeverShowAnAddress() {
        fun server(url: String, others: List<String>) =
            OpenCodeServer(url = url, username = "opencode", password = "", alternateURLs = others).friendlyName
        // Tailnet and Bonjour names win over the address in use.
        assertEquals("Kits Mac", server("http://100.101.102.103:4096", listOf("http://kits-mac.tail1234.ts.net:4096")))
        assertEquals("My Mac", server("http://192.168.1.20:4096", listOf("http://my-mac.local:4096")))
        assertEquals("Devbox", server("https://devbox.example.com", emptyList()))
        // IPv4, with and without a port.
        assertEquals("Server 20", server("http://192.168.1.20:4096", emptyList()))
        assertEquals("Server 4", server("http://10.0.0.4", emptyList()))
        // IPv6, bracketed with a port, bracketed without, and with a zone.
        assertEquals("Server 1", server("http://[fd7a:115c:a1e0::1]:4096", emptyList()))
        assertEquals("Server 1", server("http://[::1]", emptyList()))
        assertEquals("Server 1c2d", friendlyServerName("fe80::1c2d%en0"))
        assertEquals("Server 5", friendlyServerName("[fd7a:115c::5]:4096"))
        assertEquals("Studio", friendlyServerName("studio:4096"))
        // The user's own name always wins.
        val named = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "").copy(name = "Kit’s Studio")
        assertEquals("Kit’s Studio", named.friendlyName)
    }

    @Test
    fun unnamedServersThatWouldShareANameAddTheirPort() {
        fun server(url: String, name: String? = null) = OpenCodeServer(url = url, username = "opencode", password = "", name = name)
        val first = server("http://10.0.2.2:4196")
        val second = server("http://10.0.2.2:4197")
        val saved = listOf(first, second, server("http://192.168.1.20:4096"))
        assertEquals("Server 2 · 4196", first.friendlyName(among = saved))
        assertEquals("Server 2 · 4197", second.friendlyName(among = saved))
        // No collision: the name is unchanged.
        assertEquals("Server 20", saved[2].friendlyName(among = saved))
        assertEquals("Server 2", first.friendlyName(among = listOf(first)))
        // The same address and port (another user) has nothing to add.
        assertEquals("Server 2", first.friendlyName(among = listOf(first, first.copy(username = "kit"))))
        // A chosen name stays as given; the unnamed one still says which it is.
        val named = server("http://10.0.2.2:4197", name = "Server 2")
        assertEquals("Server 2", named.friendlyName(among = listOf(first, named)))
        assertEquals("Server 2 · 4196", first.friendlyName(among = listOf(first, named)))
        // A port the address leaves out is its scheme's.
        assertEquals("Devbox · 443", server("https://devbox.example.com").friendlyName(among = listOf(server("https://devbox.example.com"), server("http://devbox.example.com:4096"))))
    }

    @Test
    fun defaultNameComesFromATailnetOrBonjourAddressBeforeTheOneInUse() {
        val paired = OpenCodeServer(
            url = "https://k7q2m9x4.opentunnel.xyz", username = "opencode", password = "",
            alternateURLs = listOf("http://192.168.1.20:4096", "http://basebook.tail1234.ts.net:4096"),
        )
        assertEquals("basebook", paired.defaultName)
        assertEquals("basebook", paired.displayName)

        val bonjour = OpenCodeServer(
            url = "http://10.0.0.4:4096", username = "opencode", password = "",
            alternateURLs = listOf("http://studio.local:4096"),
        )
        assertEquals("studio", bonjour.displayName)

        // Nothing better than the address in use: shortened like the Settings card always did.
        assertEquals("192.168.1.20:4096", OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "").displayName)
        assertEquals("devbox", OpenCodeServer(url = "https://devbox.example.com", username = "opencode", password = "").displayName)
    }

    @Test
    fun aChosenNameWinsAndABlankOneFallsBack() {
        var server = OpenCodeServer(url = "http://basebook.local:4096", username = "opencode", password = "", name = "  Work Mac ")
        assertEquals("Work Mac", server.displayName)
        server = server.copy(name = "   ")
        assertEquals("basebook", server.displayName)
    }

    @Test
    fun savedServersFromBeforeNamesAndHeadersStillDecode() {
        val old = """{"url":"http://10.0.0.4:4096","username":"opencode","password":"pw"}"""
        val server = Json.decodeFromString(OpenCodeServer.serializer(), old)
        assertNull(server.name)
        assertTrue(server.headers.isEmpty())
        assertTrue(server.alternateURLs.isEmpty())

        val named = server.copy(name = "Studio", headers = listOf(OpenCodeServer.Header("CF-Access-Client-Id", "id")))
        val roundTrip = Json.decodeFromString(OpenCodeServer.serializer(), Json.encodeToString(OpenCodeServer.serializer(), named))
        assertEquals(named, roundTrip)
        // Neither changes which saved entry this is.
        assertEquals(server.persistenceID, named.persistenceID)
    }

    @Test
    fun everyAddressTriedKeepsTheNameAndHeaders() {
        val server = OpenCodeServer(
            url = "http://a.example", username = "opencode", password = "", alternateURLs = listOf("http://b.example"),
            name = "Studio", headers = listOf(OpenCodeServer.Header("X-Proxy", "1")),
        )
        val moved = server.at("http://b.example")
        assertEquals("Studio", moved.name)
        assertEquals(server.headers, moved.headers)
    }

    // MARK: Headers

    @Test
    fun headersGoOutWithEveryRequestAndThePasswordSuppliesAuthorization() {
        val server = OpenCodeServer(
            url = "https://studio.example", username = "opencode", password = "pw",
            headers = listOf(
                OpenCodeServer.Header("CF-Access-Client-Id", "id"),
                OpenCodeServer.Header("Authorization", "Bearer proxy"),
                OpenCodeServer.Header("Bad Name", "skipped"),
            ),
        )
        val request = server.authorize(Request.Builder().url("https://studio.example/api/info")).build()
        assertEquals("id", request.header("CF-Access-Client-Id"))
        assertEquals(server.basicAuthorization, request.header("Authorization"))
        assertNull(request.header("Bad Name"))

        // File reads (transcript images, the file viewer) carry them too.
        val read = server.fileReadRequest(location = "/work", relativePath = "a.png")
        assertNotNull(read)
        assertEquals("id", read!!.header("CF-Access-Client-Id"))

        // Without a password, an Authorization header for a proxy goes through.
        val open = server.copy(password = "")
        val proxied = open.authorize(Request.Builder().url("https://studio.example/api/info")).build()
        assertEquals("Bearer proxy", proxied.header("Authorization"))
    }
}
