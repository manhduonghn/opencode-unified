package ai.opencode.feature.transcript

import ai.opencode.feature.transcript.rows.QuestionRowSummary
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestReviewTests {
    private fun call(json: String): OpenCodeToolCall {
        val items = OpenCodeClient.decodeTranscript("""{"data":[{"id":"msg_a","type":"assistant","content":[$json]}]}""".toByteArray())
        return (items.firstOrNull() as? OpenCodeTranscriptItem.Tool)?.call ?: throw AssertionError("Not a tool call: $items")
    }

    @Test
    fun settledQuestionsReadAsOneQuietRow() {
        val question = """{"question":"May I bring Slack forward?","header":"Send in Slack","options":[]}"""
        val answered = call("""{"type":"tool","id":"c1","name":"question","state":{"status":"completed","input":{"questions":[$question]},"metadata":{"answers":[["Bring Slack forward"]]}}}""")
        val summary = QuestionRowSummary(questions = requireNotNull(answered.questions), call = answered)
        assertEquals("Send in Slack, Bring Slack forward", summary.spoken)
        assertEquals("Bring Slack forward", summary.answer(0))

        val dismissed = call("""{"type":"tool","id":"c2","name":"question","state":{"status":"error","input":{"questions":[$question,$question]},"error":{"type":"tool.execution","message":"Draft it instead"}}}""")
        val declined = QuestionRowSummary(questions = requireNotNull(dismissed.questions), call = dismissed)
        assertEquals("2 questions, Dismissed", declined.spoken)
        assertEquals("Draft it instead", declined.answer(1))

        val pending = call("""{"type":"tool","id":"c3","name":"question","state":{"status":"running","input":{"questions":[$question]}}}""")
        val entries = TranscriptEntry.build(listOf(OpenCodeTranscriptItem.Tool(id = "t3", call = pending), OpenCodeTranscriptItem.Tool(id = "t1", call = answered)))
        assertEquals(listOf("t1"), entries.map { it.id })
    }
}
