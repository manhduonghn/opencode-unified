package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

/** `https://opencode.ai/pair#<base64url {"code","urls"}>`, the link a pairing QR opens. */
class PairPageLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun link(json: String, padded: Boolean = false): String {
        val encoder = if (padded) Base64.getUrlEncoder() else Base64.getUrlEncoder().withoutPadding()
        return "https://opencode.ai/pair#" + encoder.encodeToString(json.toByteArray())
    }

    @Test
    fun decodesThePayloadInTheFragment() {
        val json = """{"code":"Ab3_x-9Q","urls":["https://abc.opentunnel.xyz","http://192.168.1.10:4096"]}"""
        val expected = OpenCodePairingLink(
            origin = "https://abc.opentunnel.xyz", code = "Ab3_x-9Q", alternateOrigins = listOf("http://192.168.1.10:4096"),
        )
        assertEquals(expected, OpenCodePairingLink.decode(link(json)))
        assertEquals(expected, OpenCodePairingLink.decode(link(json, padded = true)))
        assertEquals(expected, OpenCodePairingLink.decode("  " + link(json).replace("/pair#", "/pair/#") + "\n"))
        assertTrue(OpenCodePairingLink.shouldHandleOpenURL(link(json)))
    }

    @Test
    fun payloadsWithURLSafeCharactersDecode() {
        // `?>` in JSON encodes to `/` and `+` in plain base64, `_` and `-` in base64url.
        val json = """{"code":"abc","urls":["https://box.example"],"note":"???>>>"}"""
        assertEquals("abc", OpenCodePairingLink.decode(link(json))?.code)
    }

    @Test
    fun badBase64OrJSONIsRejectedButStillRoutedAsPairing() {
        for (link in listOf(
            "https://opencode.ai/pair",
            "https://opencode.ai/pair#",
            "https://opencode.ai/pair#not*base64",
            "https://opencode.ai/pair#bm90IGpzb24",
        )) {
            assertNull(link, OpenCodePairingLink.decode(link))
            // Still a pairing link, so the app says it's bad instead of opening it elsewhere.
            assertTrue(link, OpenCodePairingLink.isPairingEndpoint(link))
        }
    }

    @Test
    fun missingOrBadFieldsAreRejected() {
        for (json in listOf(
            """{"urls":["https://box.example"]}""",
            """{"code":"abc"}""",
            """{"code":"abc","urls":[]}""",
            """{"code":"","urls":["https://box.example"]}""",
            """{"code":"abc","urls":["ftp://box.example"]}""",
        )) {
            assertNull(json, OpenCodePairingLink.decode(link(json)))
        }
    }

    @Test
    fun otherHostsPathsAndQueriesAreNotPairPages() {
        val good = link("""{"code":"abc","urls":["https://box.example"]}""")
        assertNull(OpenCodePairingLink.decode(good.replace("opencode.ai", "evil.example")))
        assertNull(OpenCodePairingLink.decode(good.replace("https://", "http://")))
        assertNull(OpenCodePairingLink.decode(good.replace("/pair#", "/pairing#")))
        assertNull(OpenCodePairingLink.decode(good.replace("/pair#", "/pair?x=1#")))
        assertFalse(OpenCodePairingLink.isPairPage("https://opencode.ai/download"))
    }

    @Test
    fun todaysCodesStillDecode() {
        assertEquals("https://box.example", OpenCodePairingLink.decode("""{"code":"abc","urls":["https://box.example"]}""")?.origin)
        assertEquals("code", OpenCodePairingLink.decode("https://abc.opentunnel.xyz/auth/connect/code")?.code)
    }
}
