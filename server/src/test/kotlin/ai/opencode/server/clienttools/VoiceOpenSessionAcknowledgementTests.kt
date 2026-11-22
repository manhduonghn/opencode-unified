package ai.opencode.server.clienttools

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Its protocol tests. */
class VoiceOpenSessionAcknowledgementTests {
    @get:Rule val isolation = TestIsolationRule()

    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val call = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenSession,
        sessionID = "ses_voice", targetSessionID = "ses_target", toolCallID = "tool_open",
    )

    @Test fun wireRequestKeepsCallerSeparateFromDestination() {
        val wire = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"voice_open_session","arguments":{"session_id":"ses_target"},"_meta":{"ai.opencode/sessionID":"ses_voice"}}}"""
        val legacy = call.copy(toolCallID = null)
        assertEquals(ClientMcpProtocol.Step.Call(legacy), ClientMcpProtocol.handle(wire, connection, setOf(ClientTool.voiceOpenSession)))
    }

    @Test fun wireRequestPreservesTheServerToolIDForSSECorrelation() {
        val wire = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"voice_open_session","arguments":{"session_id":"ses_target","tool_call_id":"tool_open"},"_meta":{"ai.opencode/sessionID":"ses_voice"}}}"""
        assertEquals(ClientMcpProtocol.Step.Call(call), ClientMcpProtocol.handle(wire, connection, setOf(ClientTool.voiceOpenSession)))
    }
}
