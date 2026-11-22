package ai.opencode.server.clienttools

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.UUID

/** Its protocol and transport tests (MARK: Protocol, Transport). */
class ClientToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://studio.example.ts.net:4096", username = "opencode", password = "pw")
    private val screen = setOf(ClientTool.voiceRequestScreen)
    private val target get() = ClientToolsTarget(server = server, directory = "/work/app", tools = screen)
    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // Protocol

    @Test fun handshakeEchoesTheServersVersionAndListsTheScreenRequest() {
        val initialize = """{"method":"initialize","params":{"protocolVersion":"2025-06-18"},"jsonrpc":"2.0","id":0}"""
        val reply = ClientMcpProtocol.handle(initialize, connection, screen) as ClientMcpProtocol.Step.Send
        val result = json(reply.text)["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("android", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        assertEquals(
            ClientMcpProtocol.Step.Attached,
            ClientMcpProtocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", connection, screen),
        )

        val list = ClientMcpProtocol.handle("""{"method":"tools/list","jsonrpc":"2.0","id":1}""", connection, screen) as ClientMcpProtocol.Step.Send
        val tools = json(list.text)["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = mutableListOf<String>()
        for (tool in tools) names.add(tool.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("voice_request_screen"), names)
        val properties = tools[0].jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("string", properties["reason"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun toolCallsCarryTheCallingSessionAndReasonAndUnknownToolsAreRefusedOnTheSocket() {
        val call = """{"method":"tools/call","params":{"name":"voice_request_screen","arguments":{"reason":"  To check the layout. "},"_meta":{"ai.opencode/sessionID":"ses_agent"}},"jsonrpc":"2.0","id":"7"}"""
        assertEquals(
            ClientMcpProtocol.Step.Call(
                ClientToolCall(connection, JsonRpcID.Text("7"), ClientTool.voiceRequestScreen, sessionID = "ses_agent", reason = "To check the layout."),
            ),
            ClientMcpProtocol.handle(call, connection, screen),
        )
        val blank = """{"method":"tools/call","params":{"name":"voice_request_screen","arguments":{"reason":" "}},"jsonrpc":"2.0","id":8}"""
        assertEquals(
            ClientMcpProtocol.Step.Call(ClientToolCall(connection, JsonRpcID.Number(8), ClientTool.voiceRequestScreen)),
            ClientMcpProtocol.handle(blank, connection, screen),
        )

        val refusal = ClientMcpProtocol.handle("""{"method":"tools/call","params":{"name":"screenshot"},"jsonrpc":"2.0","id":3}""", connection, screen)
            as ClientMcpProtocol.Step.Send
        assertEquals(-32602, json(refusal.text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val pong = ClientMcpProtocol.handle("""{"method":"ping","jsonrpc":"2.0","id":4}""", connection, screen) as ClientMcpProtocol.Step.Send
        assertEquals(4, json(pong.text)["id"]!!.jsonPrimitive.int)
        val unknown = ClientMcpProtocol.handle("""{"method":"resources/list","jsonrpc":"2.0","id":5}""", connection, screen) as ClientMcpProtocol.Step.Send
        assertEquals(-32601, json(unknown.text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertNull(ClientMcpProtocol.handle("not json", connection, screen))
    }

    @Test fun repliesCarryTextAndBase64Images() {
        val bytes = byteArrayOf(1, 2, 3)
        val reply = ClientMcpProtocol.reply(
            JsonRpcID.Number(5),
            ClientToolResult(listOf(ClientToolResult.Content.Text("Screen"), ClientToolResult.Content.Jpeg(bytes))),
        )
        val obj = json(reply)
        assertEquals(5, obj["id"]!!.jsonPrimitive.int)
        val result = obj["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        val content = result["content"]!!.jsonArray
        assertEquals("Screen", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(bytes), content[1].jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", content[1].jsonObject["mimeType"]!!.jsonPrimitive.content)

        val refused = json(ClientMcpProtocol.reply(JsonRpcID.Text("x"), ClientToolResult.failure("No")))
        assertTrue(refused["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test fun theSocketRequestIsScopedToTheDirectoryAndAuthorized() {
        val request = ClientMcpProtocol.request(target)!!
        assertEquals("https", request.url.scheme)
        assertEquals("/api/experimental/client-mcp", request.url.encodedPath)
        assertEquals(listOf("location[directory]", "name"), request.url.queryParameterNames.toList())
        assertEquals("/work/app", request.url.queryParameter("location[directory]"))
        assertEquals("android", request.url.queryParameter("name"))
        assertEquals(server.basicAuthorization, request.header("Authorization"))

        val local = ClientToolsTarget(
            server = OpenCodeServer(url = "http://192.168.1.4:4096", username = "opencode", password = ""),
            directory = "/w",
            tools = screen,
        )
        assertEquals("http", ClientMcpProtocol.request(local)?.url?.scheme)
    }

    // Transport

    @Test fun everyCallGetsExactlyOneReply() {
        val sockets = ClientMcpSockets()
        val sent = mutableListOf<String>()
        sockets.add(connection) { text ->
            sent.add(text)
            true
        }
        val first = ClientToolCall(connection, JsonRpcID.Number(1), ClientTool.voiceRequestScreen)
        val unanswered = ClientToolCall(connection, JsonRpcID.Number(2), ClientTool.voiceRequestScreen)
        sockets.received(first)
        sockets.received(unanswered)

        assertTrue(sockets.reply(first, ClientToolResult.failure("no")))
        assertFalse(sockets.reply(first, ClientToolResult.failure("again")))
        // Closing refuses what's left, once; a late reply finds nothing.
        sockets.close(connection, refusal = "gone")
        assertFalse(sockets.reply(unanswered, ClientToolResult.failure("late")))
        sockets.close(connection, refusal = "gone")

        val ids = mutableListOf<Int>()
        val texts = mutableListOf<String>()
        for (text in sent) {
            val reply = json(text)
            ids.add(reply["id"]!!.jsonPrimitive.int)
            val content = reply["result"]!!.jsonObject["content"] as JsonArray
            texts.add(content[0].jsonObject["text"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf(1, 2), ids)
        assertEquals(listOf("no", "gone"), texts)
    }

    @Test fun audioToolsAreListedOnlyWhenOfferedAndCarryTheirArguments() {
        val audio = setOf(ClientTool.voicePlayAudio, ClientTool.voiceAudioControl)
        val list = ClientMcpProtocol.handle("""{"method":"tools/list","jsonrpc":"2.0","id":1}""", connection, audio) as ClientMcpProtocol.Step.Send
        val tools = json(list.text)["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = mutableListOf<String>()
        for (tool in tools) {
            names.add(tool.jsonObject["name"]!!.jsonPrimitive.content)
            assertFalse(tool.jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        }
        assertEquals(listOf("voice_play_audio", "voice_audio_control"), names)
        val required = tools[0].jsonObject["inputSchema"]!!.jsonObject["required"]!!.jsonArray
        assertEquals(listOf("url"), required.map { it.jsonPrimitive.content })

        val play = """{"method":"tools/call","params":{"name":"voice_play_audio","arguments":{"url":" https://example.com/song.mp3 ","title":"Song"}},"jsonrpc":"2.0","id":2}"""
        assertEquals(
            ClientMcpProtocol.Step.Call(
                ClientToolCall(connection, JsonRpcID.Number(2), ClientTool.voicePlayAudio, url = "https://example.com/song.mp3", title = "Song"),
            ),
            ClientMcpProtocol.handle(play, connection, audio),
        )
        val control = """{"method":"tools/call","params":{"name":"voice_audio_control","arguments":{"action":"pause"}},"jsonrpc":"2.0","id":3}"""
        assertEquals(
            ClientMcpProtocol.Step.Call(ClientToolCall(connection, JsonRpcID.Number(3), ClientTool.voiceAudioControl, action = "pause")),
            ClientMcpProtocol.handle(control, connection, audio),
        )
        // A tool whose experiment is off is unknown to the server.
        val screenCall = """{"method":"tools/call","params":{"name":"voice_request_screen"},"jsonrpc":"2.0","id":4}"""
        val refusal = ClientMcpProtocol.handle(screenCall, connection, audio) as ClientMcpProtocol.Step.Send
        assertEquals(-32602, json(refusal.text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
