package ai.opencode.feature.transcript

import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.feature.transcript.rows.InlineToolGroupHeader
import ai.opencode.feature.transcript.rows.ToolGroupLabel
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.TranscriptNoticeKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

private fun cards(blocks: List<OpenCodeMarkdownBlock>): List<List<String>> =
    blocks.mapNotNull { (it.kind as? Kind.Files)?.files?.map(AgentFileReference::path) }

class AgentFileMarkdownTests {
    @Test fun pathsInCodeAndLocalLinksBecomeFileLinks() {
        val blocks = OpenCodeMarkdownBlock.blocks("See `OpenCode/SessionView.swift:12`, [the spec](docs/spec.pdf), and [the site](https://opencode.ai).")
        val text = (blocks.firstOrNull()?.kind as? Kind.Paragraph)?.text ?: return fail("Expected a paragraph")
        // Equal neighbouring links coalesce into one run.
        val urls = mutableListOf<String>()
        var previous: String? = null
        for (run in text.runs) {
            val link = run.link
            if (link != null && link != previous) urls += link
            previous = link
        }
        assertEquals(3, urls.size)
        assertEquals(AgentFileReference(path = "OpenCode/SessionView.swift", line = 12), AgentFileReference.fromLinkURL(urls[0]))
        assertEquals("docs/spec.pdf", AgentFileReference.fromLinkURL(urls[1])?.path)
        assertEquals("https://opencode.ai", urls[2])
    }

    @Test fun mediaAndDocumentsGetOneCardRowAfterTheirFirstMention() {
        val blocks = OpenCodeMarkdownBlock.blocks(
            """
            The recording is `out/demo.mp4` and the code is `src/app.ts`.

            - Spec: [spec](docs/spec.pdf)
            - Again: `out/demo.mp4`

            ```sh
            ls out/demo.mp4
            ```
            """.trimIndent(),
        )
        assertEquals(listOf(listOf("out/demo.mp4"), listOf("docs/spec.pdf")), cards(blocks))
        assertEquals("Ids stay sequential after inserting cards", blocks.indices.toList(), blocks.map { it.id })
    }

    @Test fun markdownImagesOfMediaBecomeCards() {
        val blocks = OpenCodeMarkdownBlock.blocks("![demo](recordings/demo.mov)\n\n![shot](shot.png)")
        val files = (blocks.firstOrNull()?.kind as? Kind.Files)?.files ?: return fail("Expected a card")
        assertEquals(listOf("recordings/demo.mov"), files.map { it.path })
        assertTrue("Expected an image", blocks.last().kind is Kind.Image)
    }

    @Test fun aFileMentionedTwiceGetsOneCard() {
        val blocks = OpenCodeMarkdownBlock.blocks("Rendered `out/demo.mp4`.\n\n![demo](out/demo.mp4)\n\n![code](src/app.ts)")
        assertEquals(listOf(listOf("out/demo.mp4")), cards(blocks))
    }

    /**
     * Android's transcript snapshot keeps the reply's raw Markdown
     * ([OpenCodeTranscriptItem.AssistantMarkdown]) and parses it on display, so the snapshot is the
     * item: its cards come back after the round trip.
     */
    @Test fun cardsSurviveTheTranscriptSnapshot() {
        val item: OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown("a", "Rendered `out/demo.mp4`.")
        val decoded = Json.decodeFromString(OpenCodeTranscriptItem.serializer(), Json.encodeToString(OpenCodeTranscriptItem.serializer(), item))
        val text = (decoded as OpenCodeTranscriptItem.AssistantMarkdown).text
        val blocks = OpenCodeMarkdownBlock.blocks((item as OpenCodeTranscriptItem.AssistantMarkdown).text)
        assertEquals(blocks.map { it.kind }, OpenCodeMarkdownBlock.blocks(text).map { it.kind })
        assertEquals(listOf(listOf("out/demo.mp4")), cards(blocks))
    }
}

class ToolSummaryTests {
    private fun tool(name: String, status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.Tool(
            UUID.randomUUID().toString(),
            OpenCodeToolCall(
                callID = "c", name = name, status = status, input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(),
                error = if (status == OpenCodeToolCall.Status.Error) "failed" else null, metadata = emptyMap(),
            ),
        )

    /**
     * Nine reads, two shells, seven image edits and more, one of which failed, count the tools and
     * mark the failure.
     */
    @Test fun longFailedMixCountsToolsAndFailures() {
        val tools = List(9) { tool("read") } + listOf(tool("shell"), tool("shell")) +
            List(6) { tool("opencode_image_edit") } + listOf(tool("opencode_image_edit", OpenCodeToolCall.Status.Error), tool("grep"))
        val header = InlineToolGroupHeader(tools)
        assertEquals("Used 19 tools", header.title)
        assertEquals(1, header.failed)
        assertNull(header.cue)
        assertNull(ToolGroupLabel.failedNotices(tools))
        assertEquals("Image generate", OpenCodeToolCall.displayName("opencode_image_generate"))
        assertEquals("Other MCP servers keep their name", "Slack send", OpenCodeToolCall.displayName("slack_send"))
    }

    /**
     * Like the TUI's grouped rows, no tool call's failure reaches the collapsed row, recovered or
     * not, final or not.
     */
    @Test fun toolCallFailuresNeverCueTheCollapsedRow() {
        val error = OpenCodeToolCall.Status.Error
        assertNull(ToolGroupLabel.failedNotices(listOf(tool("read", error), tool("read"))))
        assertNull("The final patch failed", ToolGroupLabel.failedNotices(listOf(tool("read"), tool("patch", error))))
        assertNull(ToolGroupLabel.failedNotices(listOf(tool("shell", error), tool("shell"), tool("subagent", error))))
    }

    /**
     * A failed background shell or subagent notice keeps a quiet cue naming
     * what failed, as the TUI and desktop keep a failed completion in view.
     */
    @Test fun failedNoticesKeepAQuietCue() {
        val shell = OpenCodeTranscriptItem.Notice("n1", "Shell failed · swift test", isError = true)
        val agent = OpenCodeTranscriptItem.Notice("n2", "Subagent failed · Review", isError = true)
        val finished = OpenCodeTranscriptItem.Notice("n3", "Shell finished · git status")
        val again = OpenCodeTranscriptItem.Notice("n4", "Shell failed · swift build", isError = true)
        assertNull(ToolGroupLabel.failedNotices(listOf(tool("read", OpenCodeToolCall.Status.Error), finished)))
        assertEquals("Shell failed", ToolGroupLabel.failedNotices(listOf(tool("read"), shell)))
        assertEquals("Shell failed, Subagent failed", ToolGroupLabel.failedNotices(listOf(shell, tool("shell"), agent, again)))
    }

    /** The collapsed row as it reads: label, then any quiet cue. */
    private fun label(tools: List<OpenCodeTranscriptItem>): String {
        val (text, cue) = ToolGroupLabel.of(tools)
        return cue?.let { "$text · $it" } ?: text
    }

    @Test fun severalNoticesOfOneKindAreCounted() {
        val finished = TranscriptNoticeKind(TranscriptNoticeKind.Source.Subagent, TranscriptNoticeKind.Outcome.Finished)
        val subagents = listOf(
            OpenCodeTranscriptItem.Notice("s1", "Explore finished · Find the header", kind = finished),
            OpenCodeTranscriptItem.Notice("s2", "General finished · Rename the test", kind = finished),
        )
        assertEquals("2 subagents finished", label(subagents))
        val failed = (1..3).map {
            OpenCodeTranscriptItem.Notice(
                "f$it", "Shell failed · Run $it", isError = true,
                kind = TranscriptNoticeKind(TranscriptNoticeKind.Source.Shell, TranscriptNoticeKind.Outcome.Failed),
            )
        }
        assertEquals("3 shells failed", label(failed))
    }

    @Test fun mixedNoticesReadUpdatesAndKeepTheirFailureCue() {
        val shellFinished = TranscriptNoticeKind(TranscriptNoticeKind.Source.Shell, TranscriptNoticeKind.Outcome.Finished)
        val finished = OpenCodeTranscriptItem.Notice("n1", "Shell finished · git status", kind = shellFinished)
        val failed = OpenCodeTranscriptItem.Notice(
            "n2", "Explore failed · Review", isError = true,
            kind = TranscriptNoticeKind(TranscriptNoticeKind.Source.Subagent, TranscriptNoticeKind.Outcome.Failed),
        )
        val other = OpenCodeTranscriptItem.Notice("n3", "Shell finished · swift build", kind = shellFinished)
        assertEquals("Updates · Explore failed", label(listOf(finished, failed, other)))
        val updates = (1..2).map { OpenCodeTranscriptItem.Notice("u$it", "Session update · Rebased") }
        assertEquals("Only shells and subagents are counted", "Updates", label(updates))
    }

    @Test fun groupsWithToolsKeepTheUsedWording() {
        val notice = OpenCodeTranscriptItem.Notice("n", "Shell failed · swift test", isError = true)
        assertEquals("Used 1 tool · Shell failed", label(listOf(tool("shell"), notice)))
        assertEquals("Used 2 tools", label(listOf(tool("read"), tool("read"))))
    }

    /** Like the TUI and desktop, a subagent's notice names its agent. */
    @Test fun noticeHeadingsNameTheSubagent() {
        val finished = TranscriptNoticeKind(TranscriptNoticeKind.Source.Subagent, TranscriptNoticeKind.Outcome.Finished)
        assertEquals("Explore finished", finished.heading(agent = "explore"))
        assertEquals("Code-Reviewer finished", finished.heading(agent = "code-reviewer"))
        assertEquals("Subagent finished", finished.heading(agent = null))
        assertEquals("Subagent finished", finished.heading(agent = " "))
        assertEquals("Shell failed", TranscriptNoticeKind(TranscriptNoticeKind.Source.Shell, TranscriptNoticeKind.Outcome.Failed).heading(agent = "explore"))
        assertEquals("1 shell interrupted", TranscriptNoticeKind(TranscriptNoticeKind.Source.Shell, TranscriptNoticeKind.Outcome.Cancelled).count(1))
        assertEquals("My_agent V2", AgentDisplayName.of("my_agent v2"))
    }

    /**
     * A turn that ends in an error keeps that error in view as its own row
     * after the Used N row, whatever the tools did.
     */
    @Test fun aTurnThatEndedInErrorKeepsItsOwnRow() {
        val runError = OpenCodeTranscriptItem.Info("msg-error", "Provider error")
        val entries = TranscriptEntry.build(listOf(tool("read", OpenCodeToolCall.Status.Error), tool("read"), runError))
        assertEquals(2, entries.size)
        val group = entries[0] as? TranscriptEntry.ToolGroup
        if (group == null || entries[1] != TranscriptEntry.Single(runError)) return fail("Expected the group, then the error row: $entries")
        assertNull(ToolGroupLabel.failedNotices(group.tools))
    }
}
