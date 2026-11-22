package ai.opencode.server.clienttools

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Its protocol test (MARK: Protocol). */
class VoiceOpenLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val both = setOf(ClientTool.voiceRequestScreen, ClientTool.voiceOpenURL)
    private val links = setOf(ClientTool.voiceOpenURL)

    private fun list(tools: Set<ClientTool>): List<String> {
        val step = ClientMcpProtocol.handle("""{"method":"tools/list","jsonrpc":"2.0","id":1}""", connection, tools)
        val send = step as ClientMcpProtocol.Step.Send
        val listed = Json.parseToJsonElement(send.text).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = mutableListOf<String>()
        for (tool in listed) {
            val name = tool.jsonObject["name"]!!.jsonPrimitive.content
            if (name == "voice_open_url") {
                val required = tool.jsonObject["inputSchema"]!!.jsonObject["required"]!!.jsonArray
                assertEquals(listOf("url"), required.map { it.jsonPrimitive.content })
                assertFalse(tool.jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
            }
            names.add(name)
        }
        return names
    }

    @Test fun theSocketListsOnlyWhatsOnAndParsesTheLink() {
        assertEquals(listOf("voice_request_screen", "voice_open_url"), list(both))
        assertEquals(listOf("voice_open_url"), list(links))

        val text = """{"method":"tools/call","params":{"name":"voice_open_url","arguments":{"url":" https://m.uber.com/ul/ ","title":"Uber"},"_meta":{"ai.opencode/sessionID":"ses_agent"}},"jsonrpc":"2.0","id":5}"""
        assertEquals(
            ClientMcpProtocol.Step.Call(
                ClientToolCall(
                    connection = connection, id = JsonRpcID.Number(5), tool = ClientTool.voiceOpenURL,
                    sessionID = "ses_agent", url = "https://m.uber.com/ul/", title = "Uber",
                ),
            ),
            ClientMcpProtocol.handle(text, connection, links),
        )
        // A tool whose experiment is off is unknown to the socket.
        assertTrue(ClientMcpProtocol.handle(text, connection, setOf(ClientTool.voiceRequestScreen)) is ClientMcpProtocol.Step.Send)
    }
}
