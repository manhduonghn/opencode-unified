package ai.opencode.server.live

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeAccount
import ai.opencode.server.model.OpenCodeMCPServer
import ai.opencode.server.retry.RequestIdempotency
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ServerDetailsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun decodesEveryMCPStatus() {
        val json = """
        {"location":{"directory":"/Users/kit"},"data":[
          {"name":"a","status":{"status":"connected"}},
          {"name":"b","status":{"status":"pending"}},
          {"name":"c","status":{"status":"disabled"}},
          {"name":"d","status":{"status":"failed","error":"exited"}},
          {"name":"e","status":{"status":"needs_auth","error":"Sign in"},"integrationID":"linear"},
          {"name":"f","status":{"status":"later"}}
        ]}
        """.trimIndent()
        val payload = decodedAs<LocatedPayload<List<MCPServerPayload>>>(json.toByteArray())
        assertEquals("/Users/kit", payload.location?.directory)
        assertEquals(
            listOf(
                OpenCodeMCPServer.Status.Connected, OpenCodeMCPServer.Status.Pending, OpenCodeMCPServer.Status.Disabled,
                OpenCodeMCPServer.Status.Failed("exited"), OpenCodeMCPServer.Status.NeedsAuth("Sign in"),
                OpenCodeMCPServer.Status.Unknown("later"),
            ),
            payload.data.map { it.server().status },
        )
        assertEquals("linear", payload.data[4].server().integrationID)
    }

    @Test
    fun decodesCredentialAndEnvironmentAccounts() {
        val json = """
        {"location":{"directory":"/Users/kit"},"data":[
          {"id":"opencode","name":"OpenCode Console","methods":[],"connections":[
            {"type":"credential","id":"cred_1","label":"Anomaly","method":"oauth"}]},
          {"id":"openai","name":"OpenAI","methods":[],"connections":[
            {"type":"credential","id":"cred_2","label":"default","method":"oauth"},
            {"type":"env","name":"OPENAI_API_KEY"}]},
          {"id":"linear","name":"Linear","methods":[],"connections":[]}
        ]}
        """.trimIndent()
        val payload = decodedAs<LocatedPayload<List<IntegrationPayload>>>(json.toByteArray())
        val accounts = payload.data.flatMap { it.accounts() }
        assertEquals(listOf("OpenCode Console", "OpenAI", "OpenAI"), accounts.map { it.provider })
        assertEquals(
            listOf(OpenCodeAccount.Method.OAuth, OpenCodeAccount.Method.OAuth, OpenCodeAccount.Method.Environment),
            accounts.map { it.method },
        )
        assertEquals(listOf("Anomaly", "default", "OPENAI_API_KEY"), accounts.map { it.label })
    }

    @Test
    fun onlyMCPConnectIsRetriedWhenUnsent() {
        assertEquals(RequestIdempotency.Unsafe, mcpConnectionIdempotency(connected = true))
        assertEquals(RequestIdempotency.Idempotent, mcpConnectionIdempotency(connected = false))
    }
}
