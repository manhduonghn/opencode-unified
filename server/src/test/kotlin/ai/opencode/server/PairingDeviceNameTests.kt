package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.internal.WireJson
import ai.opencode.server.live.PairingSessionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The pairing response's optional `device: { name }` names a newly paired computer. */
class PairingDeviceNameTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun decode(json: String): PairingSessionPayload = PairingSessionPayload.decode(WireJson.parseToJsonElement(json))

    @Test
    fun readsTheDeviceNameWhenTheServerReportsOne() {
        assertEquals("Kit’s MacBook Pro", decode("""{"token":"t","device":{"name":"  Kit’s MacBook Pro "}}""").deviceName)
    }

    @Test
    fun todaysServersAndOddDevicesStillPair() {
        for (json in listOf(
            """{"token":"t"}""",
            """{"token":"t","device":null}""",
            """{"token":"t","device":{}}""",
            """{"token":"t","device":{"name":""}}""",
            """{"token":"t","device":{"name":42}}""",
            """{"token":"t","device":"mac"}""",
        )) {
            val payload = decode(json)
            assertEquals(json, "t", payload.token)
            assertNull(json, payload.deviceName)
        }
    }

    @Test
    fun aNameTheUserGaveWinsOverTheReportedOne() {
        val saved = OpenCodeServer(url = "https://abc.opentunnel.xyz", username = "opencode", password = "1780000000.old", name = "Desk")
        val paired = OpenCodeServer(url = saved.url, username = "opencode", password = "1790000000.new").copy(name = "Kit’s MacBook Pro")
        assertEquals("Desk", paired.keepingLocalSettings(listOf(saved)).name)
        val unnamed = saved.copy(name = null)
        assertEquals("Kit’s MacBook Pro", paired.keepingLocalSettings(listOf(unnamed)).name)
        assertEquals("Kit’s MacBook Pro", paired.keepingLocalSettings(emptyList()).name)
    }
}
