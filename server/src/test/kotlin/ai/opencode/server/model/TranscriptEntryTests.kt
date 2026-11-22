package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TaskListAndExecuteTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun execute(code: String) = OpenCodeToolCall(
        callID = "c", name = "execute", status = OpenCodeToolCall.Status.Running,
        input = OpenCodeJSONValue.Object(mapOf("code" to OpenCodeJSONValue.Str(code))),
        output = emptyList(), metadata = emptyMap(),
    )

    @Test
    fun executeRowsNameTheToolsTheirScriptCalls() {
        val code = """
            const all = JSON.parse(await tools.slack.channels({}) || "[]")
            for (const id of all) await tools.slack.search({ query: "dinner" })
            await tools["browser-control"].status()
            await tools.slack.search({ query: "again" })
            devtools.open()
        """.trimIndent()
        assertEquals(listOf("slack.channels", "slack.search", "browser-control.status"), OpenCodeToolCall.calledTools(code))
        assertEquals("slack.channels, slack.search, browser-control.status", execute(code).subtitle)
        assertEquals("return 1 + 1", execute("return 1 + 1").subtitle)
    }
}
