package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** A pairing QR payload or deep link's optional `name` names the server it pairs. */
class PairingLinkNameTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun theQRPayloadAndDeepLinkCarryAName() {
        val qr = OpenCodePairingLink.decode("""{"code":"abc","urls":["https://box.example"],"name":" Anomaly Box "}""")!!
        assertEquals("Anomaly Box", qr.name)
        val deepLink = OpenCodePairingLink.decode("opencode-ios://pair?code=abc&url=https://box.example&name=Anomaly%20Box")
        assertEquals(qr, deepLink)
        assertEquals("Anomaly Box", qr.normalizedForRedemption.server.name)
        // Sharing keeps it.
        assertEquals(qr, OpenCodePairingLink.decode(qr.deepLinkURL))
    }

    @Test
    fun withoutANameNothingChanges() {
        for (text in listOf(
            """{"code":"abc","urls":["https://box.example"]}""",
            """{"code":"abc","urls":["https://box.example"],"name":"  "}""",
            "opencode-ios://pair?code=abc&url=https://box.example",
            "opencode-ios://pair?code=abc&url=https://box.example&name=",
        )) {
            val link = OpenCodePairingLink.decode(text)
            assertEquals(text, OpenCodePairingLink(origin = "https://box.example", code = "abc"), link)
            assertEquals(text, OpenCodePairingLink.decode("opencode-ios://pair?code=abc&url=https://box.example")!!.deepLinkURL, link!!.deepLinkURL)
        }
    }

    @Test
    fun otherAndRepeatedParametersAreStillRejected() {
        for (text in listOf(
            "opencode-ios://pair?code=abc&url=https://box.example&name=A&name=B",
            "opencode-ios://pair?code=abc&url=https://box.example&name=A&label=B",
        )) {
            assertNull(text, OpenCodePairingLink.decode(text))
        }
    }

    @Test
    fun theLinksNameWinsOverTheComputersButNotTheUsers() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /auth/connect/abc" -> Reply.Json(200, """{"token":"1790000000.synthetic_token_signature","device":{"name":"exe-vm"}}""")
                else -> Reply.status(404)
            }
        }
        try {
            val link = OpenCodePairingLink.decode("opencode-ios://pair?code=abc&url=${fixture.server.url}&name=Anomaly%20Box")!!
            val paired = OpenCodeClient.liveValue.redeemPairing(link)
            assertEquals("Anomaly Box", paired.name)
            assertEquals("Anomaly Box", paired.keepingLocalSettings(emptyList()).name)
            val renamed = OpenCodeServer(url = paired.url, username = "opencode", password = "1780000000.old", name = "Desk")
            assertEquals("Desk", paired.keepingLocalSettings(listOf(renamed)).name)
        } finally {
            fixture.close()
        }
    }
}
