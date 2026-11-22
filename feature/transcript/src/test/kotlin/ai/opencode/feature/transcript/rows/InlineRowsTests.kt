package ai.opencode.feature.transcript.rows

import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeQuestion
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.TranscriptNoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

private fun call(
    name: String,
    input: Map<String, OpenCodeJSONValue> = emptyMap(),
    status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed,
    startedAt: Instant? = null,
    completedAt: Instant? = null,
) = OpenCodeToolCall(
    callID = UUID.randomUUID().toString(), name = name, status = status, input = OpenCodeJSONValue.Object(input), output = emptyList(),
    error = if (status == OpenCodeToolCall.Status.Error) "exit 101" else null, metadata = emptyMap(),
    startedAt = startedAt, completedAt = completedAt,
)

private fun tool(id: String, call: OpenCodeToolCall): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(id, call)

private fun read(id: String, path: String, status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed) =
    tool(id, call("read", mapOf("path" to OpenCodeJSONValue.Str(path)), status = status))

private fun str(value: String) = OpenCodeJSONValue.Str(value)

class InlineRowsTests {
    @Test fun consecutiveReadsMergeIntoOneRow() {
        val rows = InlineToolRow.rows(listOf(
            tool("t1", call("shell", mapOf("command" to str("rg -l approvedChannels")))),
            read("r1", "~/.config"),
            read("r2", "~/code/chat-tools/README.md"),
            read("r3", "~/code"),
            tool("t2", call("shell", mapOf("command" to str("cargo test --locked")))),
            read("r4", "src/main.rs"),
        ))
        assertEquals(listOf("t1", "r1", "t2", "r4"), rows.map { it.id })
        assertEquals("Read", rows[1].name)
        assertEquals(".config, README.md, code", rows[1].detail)
        assertEquals(listOf("r1", "r2", "r3"), rows[1].memberIDs)
        assertEquals("A lone read stays a plain row", "main.rs", rows[3].detail)
        assertTrue("Only commands are mono", rows[0].detailIsCommand && !rows[1].detailIsCommand)
    }

    @Test fun failedOrRunningReadsKeepTheirOwnRows() {
        val rows = InlineToolRow.rows(listOf(
            read("r1", "a.md"),
            read("r2", "missing.md", status = OpenCodeToolCall.Status.Error),
            read("r3", "b.md"),
            read("r4", "c.md", status = OpenCodeToolCall.Status.Running),
        ))
        assertEquals(listOf("r1", "r2", "r3", "r4"), rows.map { it.id })
        assertTrue(rows[1].isFailed && !rows[1].isRunning)
        assertTrue(rows[3].isRunning && !rows[3].isFailed)
    }

    @Test fun noticesStayInPlaceBetweenTools() {
        val rows = InlineToolRow.rows(listOf(
            read("r1", "a.md"),
            OpenCodeTranscriptItem.Notice(
                id = "n1", text = "Explore finished · Found the config",
                kind = TranscriptNoticeKind(TranscriptNoticeKind.Source.Subagent, TranscriptNoticeKind.Outcome.Finished),
            ),
            read("r2", "b.md"),
        ))
        assertEquals("A notice breaks a run of reads", listOf("r1", "n1", "r2"), rows.map { it.id })
        assertEquals("Explore finished", rows[1].name)
        assertEquals("Found the config", rows[1].detail)
    }

    @Test fun executeNamesTheToolsItsScriptCalled() {
        val send = call("execute", mapOf("code" to str("return await tools.slack.send({ channel: '#people-ops', text })")))
        assertEquals("slack.send", InlineToolRow.detail(send))
        val both = call("execute", mapOf("code" to str("await tools[\"slack\"].search({}); return tools.slack.send({})")))
        assertEquals("slack.search, slack.send", InlineToolRow.detail(both))
    }

    @Test fun executeNamesItsCatalogSearchNeverItsCode() {
        val namespace = call("execute", mapOf("code" to str("return search({ namespace: 'slack', query: 'reply in thread' })")))
        assertEquals("search slack", InlineToolRow.detail(namespace))
        val query = call("execute", mapOf("code" to str("return search({query: \"slack approved channels\"})")))
        assertEquals("search “slack approved channels”", InlineToolRow.detail(query))
        val plain = call("execute", mapOf("code" to str("const x = 1 + 1\nreturn x")))
        assertNull(InlineToolRow.detail(plain))
    }

    @Test fun skillNamesTheSkill() {
        assertEquals("chat-ops", InlineToolRow.detail(call("skill", mapOf("id" to str("chat-ops")))))
        assertEquals("release-notes", InlineToolRow.detail(call("skill", mapOf("name" to str("release-notes")))))
        val rows = InlineToolRow.rows(listOf(tool("s1", call("skill", mapOf("id" to str("chat-ops"))))))
        assertTrue(rows[0].name == "Skill" && rows[0].detail == "chat-ops")
    }

    @Test fun questionDraftLosesTheStrayQuestionMark() {
        val draft = "Send this in her thread: \"Signed the contractor agreement and sent my new address to Payroll yesterday.\"?"
        assertEquals("Signed the contractor agreement and sent my new address to Payroll yesterday.", InlineQuestion.quote(draft))
        val curly = "Post this? “Thanks, everyone, for a great launch week!” ?"
        assertEquals("Thanks, everyone, for a great launch week!", InlineQuestion.quote(curly))
        assertEquals("Which notes should go with the ETA?", InlineQuestion.quote("Which notes should go with the ETA?"))
        assertEquals("A short quote is part of the question", "Keep \"Tabs\" or \"Spaces\"?", InlineQuestion.quote("Keep \"Tabs\" or \"Spaces\"?"))
    }

    @Test fun questionListsOptionsWithYoursChosen() {
        val question = OpenCodeQuestion(question = "Send this in her thread: \"Signed the contractor agreement yesterday.\"?", header = "Reply to Maya")
        val lines = InlineQuestion.lines(
            questions = listOf(question), options = listOf(listOf("Send it", "Edit first", "Don’t send")),
            answers = listOf(listOf("Send it")), dismissed = false, reason = null,
        )
        assertEquals(
            listOf(
                InlineQuestion.Line("Signed the contractor agreement yesterday.", InlineQuestion.Line.Kind.Prompt),
                InlineQuestion.Line("Send it", InlineQuestion.Line.Kind.Chosen),
                InlineQuestion.Line("Edit first", InlineQuestion.Line.Kind.Unchosen),
                InlineQuestion.Line("Don’t send", InlineQuestion.Line.Kind.Unchosen),
            ),
            lines,
        )
        val typed = InlineQuestion.lines(
            questions = listOf(OpenCodeQuestion(question = "How should I sign off?", header = "Sign-off")),
            options = listOf(listOf("Thanks!", "Cheers")), answers = listOf(listOf("Talk soon, Kit")), dismissed = false, reason = null,
        )
        assertEquals(
            "A typed answer follows the options, quoted",
            InlineQuestion.Line("“Talk soon, Kit”", InlineQuestion.Line.Kind.Chosen), typed.last(),
        )
        val dismissed = InlineQuestion.lines(
            questions = listOf(OpenCodeQuestion(question = "Add a reminder?", header = "Payroll")),
            options = listOf(listOf("Add reminder", "Not now")), answers = emptyList(), dismissed = true, reason = "Already on my list",
        )
        assertEquals(
            listOf(
                InlineQuestion.Line.Kind.Prompt, InlineQuestion.Line.Kind.Unchosen, InlineQuestion.Line.Kind.Unchosen,
                InlineQuestion.Line.Kind.Decision, InlineQuestion.Line.Kind.Note,
            ),
            dismissed.map { it.kind },
        )
    }

    @Test fun optionsAlignWithTheDecodedQuestions() {
        fun obj(vararg pairs: Pair<String, OpenCodeJSONValue>) = OpenCodeJSONValue.Object(mapOf(*pairs))
        fun arr(vararg values: OpenCodeJSONValue) = OpenCodeJSONValue.Array(values.toList())
        val form = call("question", mapOf("questions" to arr(
            obj("question" to str("Which channel?"), "header" to str("Channel"),
                "options" to arr(obj("label" to str("#people-ops")), obj("label" to str("#general")))),
            obj("question" to str("  "), "options" to arr(obj("label" to str("Skipped")))),
            obj("question" to str("Anything else?"), "header" to str("")),
        )))
        assertEquals(listOf(listOf("#people-ops", "#general"), emptyList()), InlineQuestion.options(form))
        assertEquals(2, form.questions?.size)
    }

    @Test fun headerCountsToolsAndFailures() {
        val start = Instant.ofEpochSecond(1_000)
        val shellFinished = OpenCodeTranscriptItem.Notice(
            id = "n1", text = "Shell finished",
            kind = TranscriptNoticeKind(TranscriptNoticeKind.Source.Shell, TranscriptNoticeKind.Outcome.Finished),
        )
        val items = listOf(
            tool("t1", call("shell", status = OpenCodeToolCall.Status.Completed, startedAt = start, completedAt = start.plusSeconds(20))),
            read("r1", "a.md"),
            shellFinished,
            tool("t2", call("shell", status = OpenCodeToolCall.Status.Completed, startedAt = start.plusSeconds(30), completedAt = start.plusSeconds(108))),
        )
        val header = InlineToolGroupHeader(items)
        assertEquals("Notices don't count", "Used 3 tools", header.title)
        assertEquals(0, header.failed)
        assertEquals("No elapsed duration in the header", "Used 3 tools", header.spoken)

        val failed = InlineToolGroupHeader(items + tool("t3", call("shell", status = OpenCodeToolCall.Status.Error)))
        assertEquals("Used 4 tools", failed.title)
        assertEquals(1, failed.failed)
        assertEquals("Used 4 tools, 1 failed", failed.spoken)

        assertEquals("Used 1 tool", InlineToolGroupHeader(listOf(tool("t1", call("shell", status = OpenCodeToolCall.Status.Running, startedAt = start)))).spoken)
        assertEquals("Used 1 tool", InlineToolGroupHeader(listOf(read("r1", "a.md"))).title)
        assertEquals("Shell finished", InlineToolGroupHeader(listOf(shellFinished)).title)
    }

    @Test fun declinedCallsAreNotFailures() {
        val denied = call("shell", status = OpenCodeToolCall.Status.Error).copy(errorType = "permission.rejected")
        val header = InlineToolGroupHeader(listOf(tool("t1", denied)))
        assertEquals(0, header.failed)
        assertEquals("Denied", header.cue)
        assertEquals("Used 1 tool, Denied", header.spoken)
    }

    // aLostEdgeCountsFromTheRequest: dropped. It probes the CALayer edge band a reused or
    // scrolled-away cell can lose mid-motion (`attachEdge`, `motion(_:)`); Android's disclosure is the
    // row's own height spring and clip (no cell reuse, no edge layer), see docs/parity/transcript.md.
}
