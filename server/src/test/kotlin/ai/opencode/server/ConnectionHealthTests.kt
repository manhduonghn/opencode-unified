package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionHealthTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun routes() {
        assertEquals(ConnectionRoute.Tailscale, ConnectionRoute.of("http://100.101.102.103:4096"))
        assertEquals(ConnectionRoute.Tailscale, ConnectionRoute.of("https://my-mac.tail1234.ts.net"))
        assertEquals(ConnectionRoute.LocalNetwork, ConnectionRoute.of("http://192.168.1.40:4096"))
        assertEquals(ConnectionRoute.LocalNetwork, ConnectionRoute.of("http://studio.local:4096"))
        assertEquals(ConnectionRoute.LocalNetwork, ConnectionRoute.of("http://[fd00::1]:4096"))
        assertEquals(ConnectionRoute.Loopback, ConnectionRoute.of("http://127.0.0.1:4096"))
        assertEquals(ConnectionRoute.Internet, ConnectionRoute.of("https://studio-q9m3.example.com"))
        assertEquals(ConnectionRoute.Internet, ConnectionRoute.of("https://fdroid.example.com"))
        assertEquals(0, OpenCodeServer.reachabilityRank("https://fdroid.example.com"))
        assertEquals(0, OpenCodeServer.reachabilityRank("https://fcm.example.com"))
        assertEquals(5, OpenCodeServer.reachabilityRank("http://[fd00::1]:4096"))
        assertEquals(3, OpenCodeServer.reachabilityRank("http://[fd7a:115c:a1e0::1]:4096"))
    }

    /** ULA and link-local prefixes count only for parsed IPv6 literals; names that happen to start with them are public. */
    @Test
    fun routesClassifyParsedAddressesNotSpellings() {
        val cases = listOf(
            "https://fd.example.com" to ConnectionRoute.Internet,
            "https://fc.example.com:8443" to ConnectionRoute.Internet,
            "https://fe80.example.com" to ConnectionRoute.Internet,
            "http://[fc00::1]:4096" to ConnectionRoute.LocalNetwork,
            "http://[fd12::1]:4096" to ConnectionRoute.LocalNetwork,
            "http://[FD12:3456::1]:4096" to ConnectionRoute.LocalNetwork,
            "http://[fe80::1%25en0]:4096" to ConnectionRoute.LocalNetwork,
            "http://[febf::1]:4096" to ConnectionRoute.LocalNetwork,
            "http://[fec0::1]:4096" to ConnectionRoute.Internet,
            "http://[fd7a:115c:a1e0::1]:4096" to ConnectionRoute.Tailscale,
            "http://[2606:4700:4700::1111]:4096" to ConnectionRoute.Internet,
            "http://100.64.0.1:4096" to ConnectionRoute.Tailscale,
            "http://100.101.102.103:4096" to ConnectionRoute.Tailscale,
            "http://100.128.0.1:4096" to ConnectionRoute.Internet,
            "https://box.tail1234.ts.net" to ConnectionRoute.Tailscale,
            "http://192.168.1.20:4096" to ConnectionRoute.LocalNetwork,
            "http://10.0.0.5:4096" to ConnectionRoute.LocalNetwork,
            "http://172.16.0.1:4096" to ConnectionRoute.LocalNetwork,
            "http://172.32.0.1:4096" to ConnectionRoute.Internet,
            "http://169.254.1.1:4096" to ConnectionRoute.LocalNetwork,
            "http://studio.local:4096" to ConnectionRoute.LocalNetwork,
            "http://localhost:4096" to ConnectionRoute.Loopback,
            "http://127.0.0.1:4096" to ConnectionRoute.Loopback,
            "http://[::1]:4096" to ConnectionRoute.Loopback,
            "http://127.0.0.2:4096" to ConnectionRoute.Loopback,
            "http://127.255.255.254:4096" to ConnectionRoute.Loopback,
            "http://[::ffff:127.0.0.1]:4096" to ConnectionRoute.Loopback,
            "http://[::ffff:192.168.1.20]:4096" to ConnectionRoute.LocalNetwork,
            "http://[::ffff:c0a8:114]:4096" to ConnectionRoute.LocalNetwork,
            "http://[::ffff:10.0.0.5]:4096" to ConnectionRoute.LocalNetwork,
            "http://[::ffff:100.64.0.1]:4096" to ConnectionRoute.Tailscale,
            "http://[::ffff:203.0.113.9]:4096" to ConnectionRoute.Internet,
            "http://[::ffff:0:192.168.1.20]:4096" to ConnectionRoute.Internet,
            "http://203.0.113.9:4096" to ConnectionRoute.Internet,
            "https://example.com" to ConnectionRoute.Internet,
            "http://10.0.0.5.example.com" to ConnectionRoute.Internet,
        )
        for ((url, route) in cases) {
            assertEquals(url, route, ConnectionRoute.of(url))
        }
        assertEquals(0, OpenCodeServer.reachabilityRank("https://fd.example.com"))
        assertEquals(5, OpenCodeServer.reachabilityRank("http://[fc00::1]:4096"))
        assertEquals(7, OpenCodeServer.reachabilityRank("http://localhost:4096"))
        assertEquals(7, OpenCodeServer.reachabilityRank("http://127.0.0.2:4096"))
        assertEquals(7, OpenCodeServer.reachabilityRank("http://[::ffff:127.0.0.1]:4096"))
        assertEquals(5, OpenCodeServer.reachabilityRank("http://[::ffff:192.168.1.20]:4096"))
        assertEquals(3, OpenCodeServer.reachabilityRank("http://[::ffff:100.64.0.1]:4096"))
        assertEquals(1, OpenCodeServer.reachabilityRank("http://[::ffff:203.0.113.9]:4096"))
        assertTrue(OpenCodeServer.isLoopback("http://127.0.0.2:4096"))
        assertEquals(8, OpenCodeServer.reachabilityRank("not a url"))
        assertTrue(OpenCodeServer.isLoopback("http://[::1]:4096"))
        assertFalse(OpenCodeServer.isLoopback("http://localhost.example.com"))
    }
}
