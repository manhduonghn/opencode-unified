package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEntryTests {
    private fun tool(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id,
        OpenCodeToolCall(callID = "call-$id", name = "read", status = OpenCodeToolCall.Status.Completed, input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), metadata = emptyMap()),
    )

    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id, "Reply $id")

    private fun user(id: String, text: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id, text)

    private fun toolGroups(items: List<OpenCodeTranscriptItem>, isRunning: Boolean = false): List<List<String>> =
        TranscriptEntry.build(items, isRunning = isRunning).mapNotNull { (it as? TranscriptEntry.ToolGroup)?.tools?.map { tool -> tool.id } }

    private fun replyEnds(items: List<OpenCodeTranscriptItem>, isRunning: Boolean = false): List<String> =
        TranscriptEntry.build(items, isRunning = isRunning).mapNotNull { if (it is TranscriptEntry.ReplyEnd) it.id else null }

    private fun state(items: List<OpenCodeTranscriptItem>, visibleLimit: Int, rendered: Boolean = false): State =
        State.session(testServer, testSession(), cachedItems = items).copy(visibleLimit = visibleLimit, hasRenderedFirstContentFrame = rendered)

    /**
     * The FailedToolMix shape: a 19-tool group just past a 20-row window shows
     * as one group of 19, never 18 with one left above.
     */
    @Test fun aGroupJustPastTheWindowShowsWhole() {
        val tools = (0 until 19).map { tool("mix-$it") }
        val items = listOf(user("u", "Go")) + tools + listOf(reply("r"), tool("one"))
        val state = state(items, visibleLimit = 20)
        assertEquals(1, state.windowStart)
        assertEquals("mix-0", state.visibleItems.first().id)
        assertEquals(listOf(tools.map { it.id }, listOf("one")), toolGroups(state.visibleItems))
        assertEquals("mix-0", state.firstVisibleItemID)
    }

    /**
     * A group the window has to cut hides whole once the tail moves past it,
     * at every step between.
     */
    @Test fun aSlidingWindowNeverSplitsAGroup() {
        val tools = (0 until 10).map { tool("t$it") }
        val items = (listOf(user("u", "Go")) + tools).toMutableList()
        var state = state(items, visibleLimit = 5)
        for (index in 0 until 12) {
            items += reply("r$index")
            state = state.copy(items = items.toList())
            val groups = toolGroups(state.visibleItems)
            assertTrue("step $index: $groups", groups.isEmpty() || groups == listOf(tools.map { it.id }))
        }
    }

    /**
     * Once drawn, rows arriving at the end keep the window's first row, and that row is a whole
     * group's first.
     */
    @Test fun retainingTheRenderedStartKeepsTheGroupWhole() {
        val tools = (0 until 10).map { tool("t$it") }
        val replies = (0 until 15).map { reply("r$it") }
        var state = state(listOf(user("u", "Go")) + tools + replies, visibleLimit = 20, rendered = true)
        assertEquals("t0", state.visibleItems.first().id)
        for (index in 0 until 8) {
            val previousFirstID = state.visibleItems.firstOrNull()?.id
            state = state.copy(items = state.items + reply("late$index")).retainRenderedWindowStart(previousFirstID)
            assertEquals("after ${index + 1} arrivals", "t0", state.visibleItems.first().id)
            assertEquals(listOf(tools.map { it.id }), toolGroups(state.visibleItems))
        }
        assertEquals(listOf("reply-end-late7"), replyEnds(state.visibleItems))
    }

    /**
     * The WorkingLeaves shape: a row added and taken away again below the
     * window's first row must not pull the reply above it into view, which
     * would show that reply's end as new.
     */
    @Test fun aRowRemovedBelowTheStartNeverRevealsAnEarlierReplyEnd() {
        val items = mutableListOf(user("u0", "Start"))
        for (index in 1..12) items += listOf(user("u$index", "Q$index"), reply("r$index"))
        var state = state(items, visibleLimit = 20, rendered = true)
        assertEquals("u3", state.visibleItems.first().id)
        val before = replyEnds(state.visibleItems)
        assertFalse(before.contains("reply-end-r2"))

        var previousFirstID = state.visibleItems.firstOrNull()?.id
        state = state.copy(items = state.items + user("sent", "Check the run's end")).retainRenderedWindowStart(previousFirstID)
        previousFirstID = state.visibleItems.firstOrNull()?.id
        state = state.copy(items = state.items.filterNot { it.id == "sent" }).retainRenderedWindowStart(previousFirstID)
        assertEquals("u3", state.visibleItems.first().id)
        previousFirstID = state.visibleItems.firstOrNull()?.id
        state = state.copy(items = state.items + user("sent", "Check the run's end") + reply("streamed")).retainRenderedWindowStart(previousFirstID)
        assertEquals("u3", state.visibleItems.first().id)
        assertEquals(setOf("reply-end-streamed"), replyEnds(state.visibleItems).toSet() - before.toSet())
    }

    /**
     * Before the first frame nothing is retained: the window keeps its size,
     * and moves past a group without splitting it.
     */
    @Test fun beforeTheFirstFrameTheWindowSlidesPastAGroupWhole() {
        val tools = (0 until 4).map { tool("t$it") }
        var state = state(listOf(user("u", "Go")) + tools + listOf(reply("r0")), visibleLimit = 4)
        val previousFirstID = state.visibleItems.firstOrNull()?.id
        state = state.copy(items = state.items + reply("r1") + reply("r2")).retainRenderedWindowStart(previousFirstID)
        assertEquals("t0", state.visibleItems.first().id)
        state = state.copy(items = state.items + reply("r3") + reply("r4") + reply("r5"))
        assertEquals(listOf("r2", "r3", "r4", "r5"), state.visibleItems.map { it.id })
    }
}
