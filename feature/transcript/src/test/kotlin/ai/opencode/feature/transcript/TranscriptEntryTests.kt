package ai.opencode.feature.transcript

import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEntryTests {
    @Test
    fun snapshotTraceDistinguishesFlickerFromNavigationAndOldHistory() {
        val trace = TranscriptSnapshotTrace()
        assertTrue(trace.record("s1", listOf("text", "tool"), 0.0).second.isEmpty())
        assertEquals(listOf("tool"), trace.record("s1", listOf("text"), 1.0).first)
        assertEquals(listOf("tool"), trace.record("s1", listOf("text", "tool"), 2.0).second)
        trace.record("s1", listOf("text"), 3.0)
        assertTrue(trace.record("s1", listOf("text", "tool"), 9.0).second.isEmpty())
        assertTrue(trace.record("s2", listOf("other"), 10.0).first.isEmpty())
    }

    @Test
    fun completionNoticesCollapseWithToolsWithoutCrossingAnAnswer() {
        val notice = OpenCodeTranscriptItem.Notice("done", "Shell finished · Build app")
        val entries = TranscriptEntry.build(
            listOf(
                tool("build", name = "shell"), notice, tool("read"),
                OpenCodeTranscriptItem.AssistantMarkdown("answer", ""),
                OpenCodeTranscriptItem.Notice("later", "Subagent finished · Review"),
            ),
        )
        assertEquals(listOf("group-build", "answer", "group-later"), entries.map { it.id })
        assertEquals(listOf("build", "done", "read"), entries[0].itemIDs)
    }

    @Test
    fun groupsConsecutiveUserAndToolItems() {
        val items = listOf(
            OpenCodeTranscriptItem.UserImages("text-images", emptyList()),
            OpenCodeTranscriptItem.UserText("text", "Hello"),
            tool("read", name = "read"),
            tool("shell", name = "shell"),
            OpenCodeTranscriptItem.AssistantMarkdown("answer", ""),
        )

        val entries = TranscriptEntry.build(items)

        assertEquals(3, entries.size)
        assertEquals("user-text-images", entries[0].id)
        assertEquals(listOf("text-images", "text"), entries[0].itemIDs)
        assertEquals("group-read", entries[1].id)
        assertEquals(listOf("read", "shell"), entries[1].itemIDs)
        assertEquals("answer", entries[2].id)
    }

    @Test
    fun questionToolBreaksToolRuns() {
        val entries = TranscriptEntry.build(listOf(tool("one"), question("question"), tool("two")))

        assertEquals(listOf("group-one", "question", "group-two"), entries.map { it.id })
    }

    @Test
    fun findsContainingEntry() {
        val entries = TranscriptEntry.build(
            listOf(
                OpenCodeTranscriptItem.UserText("first", "One"),
                OpenCodeTranscriptItem.UserText("second", "Two"),
            ),
        )

        assertEquals("user-second", TranscriptEntry.entryID("second", entries))
        assertNull(TranscriptEntry.entryID("missing", entries))
    }

    @Test
    fun adjacentPromptsKeepSeparateUserGroups() {
        val entries = TranscriptEntry.build(
            listOf(
                OpenCodeTranscriptItem.UserText("msg_first", "First"),
                OpenCodeTranscriptItem.UserText("msg_second", "Second"),
                OpenCodeTranscriptItem.UserImages("msg_third-images", emptyList()),
                OpenCodeTranscriptItem.UserText("msg_third", "Third"),
            ),
        )

        assertEquals(listOf("user-msg_first", "user-msg_second", "user-msg_third-images"), entries.map { it.id })
        assertEquals(listOf("msg_third-images", "msg_third"), entries.last().itemIDs)
    }

    @Test
    fun identityPlanRetainsToolRowWhenLeadingMemberMoves() {
        val previous = TranscriptEntry.ToolGroup("tool-b", listOf(tool("tool-b"), tool("tool-c")))
        val grown = TranscriptEntry.ToolGroup("tool-a", listOf(tool("tool-a"), tool("tool-b"), tool("tool-c")))
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(previous),
            previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(previous.id to "stable-tools"),
            previousQueuedStableIDs = emptyMap(),
            entries = listOf(grown),
            queued = emptyList(),
        )

        assertEquals("stable-tools", plan.entryStableIDs[grown.id])
    }

    @Test
    fun identityPlanResolvesGroupSplitAndMergeDeterministically() {
        val a = tool("a")
        val b = tool("b")
        val c = tool("c")
        val d = tool("d")
        val combined = TranscriptEntry.ToolGroup("a", listOf(a, b, c, d))
        val leading = TranscriptEntry.ToolGroup("a", listOf(a, b))
        val trailing = TranscriptEntry.ToolGroup("c", listOf(c, d))
        val split = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(combined),
            previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(combined.id to "stable-combined"),
            previousQueuedStableIDs = emptyMap(),
            entries = listOf(leading, trailing),
            queued = emptyList(),
        )
        assertEquals("stable-combined", split.entryStableIDs[leading.id])
        assertEquals(trailing.id, split.entryStableIDs[trailing.id])

        val merged = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(leading, trailing),
            previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(leading.id to "stable-leading", trailing.id to "stable-trailing"),
            previousQueuedStableIDs = emptyMap(),
            entries = listOf(combined),
            queued = emptyList(),
        )
        assertEquals("stable-leading", merged.entryStableIDs[combined.id])
    }

    /**
     * A notice shown after a read joins the read's group ahead of it once history has it, so the
     * merged group is named for the notice. The read's row, on screen above, keeps its identity
     * and reconfigures; the notice's own row is the one that leaves.
     */
    @Test
    fun identityPlanKeepsTheEarlierRowWhenAGroupTakesAnotherRowsName() {
        val read = tool("read")
        val notice = OpenCodeTranscriptItem.Notice("notice", "Shell finished · swift build")
        val text = OpenCodeTranscriptItem.AssistantMarkdown("text", "Done.")
        val previous = TranscriptEntry.build(listOf(read, text, notice), isRunning = true)
        assertEquals(listOf("group-read", "text", "group-notice"), previous.map { it.id })
        val settled = TranscriptEntry.build(listOf(notice, read, text))
        assertEquals(listOf("group-notice", "text", "reply-end-text"), settled.map { it.id })
        val stable = previous.associate { it.id to "stable-${it.id}" }
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = previous,
            previousQueued = emptyList(),
            previousEntryStableIDs = stable,
            previousQueuedStableIDs = emptyMap(),
            entries = settled,
            queued = emptyList(),
        )
        assertEquals("The read's row keeps its identity", "stable-group-read", plan.entryStableIDs["group-notice"])
        assertEquals("stable-text", plan.entryStableIDs["text"])
        assertFalse("The notice's own row leaves", plan.entryStableIDs.values.contains("stable-group-notice"))
    }

    /** Plans [entries] after [previous], whose rows had [stable] identities. */
    private fun plan(
        previous: List<TranscriptEntry>,
        stable: Map<String, String>,
        entries: List<TranscriptEntry>,
    ): TranscriptRowIdentityPlan = TranscriptRowIdentityPlan.make(
        previousEntries = previous, previousQueued = emptyList(), previousEntryStableIDs = stable,
        previousQueuedStableIDs = emptyMap(), entries = entries, queued = emptyList(),
    )

    private fun group(vararg members: OpenCodeTranscriptItem): TranscriptEntry =
        TranscriptEntry.ToolGroup(members[0].id, members.toList())

    private fun notice(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Notice(id, "Shell finished · $id")

    private fun expectUnique(plan: TranscriptRowIdentityPlan) {
        val ids = plan.entryStableIDs.values.toList()
        assertEquals("Duplicate stable IDs: ${plan.entryStableIDs}", ids.size, ids.toSet().size)
    }

    /**
     * Members regroup so that one new row would fall back to an identity another row still holds:
     * every row keeps a distinct identity.
     */
    @Test
    fun identityPlanNeverHandsOutATakenStableID() {
        val a = tool("a")
        val b = tool("b")
        val c = tool("c")
        val d = tool("d")
        val e = tool("e")
        val previous = listOf(group(a, b, c), group(d, e))
        val regrouped = plan(previous, mapOf("group-a" to "group-b", "group-d" to "group-d"), listOf(group(d), group(a, e), group(b, c)))
        expectUnique(regrouped)
        assertEquals("The row that kept its first member keeps its identity", "group-b", regrouped.entryStableIDs["group-a"])
        assertEquals("group-d", regrouped.entryStableIDs["group-d"])
        assertEquals("A fresh identity, not one in use", "group-b#2", regrouped.entryStableIDs["group-b"])
    }

    /**
     * A notice merges into the read's group ahead of it, then they split again: the read keeps its
     * identity throughout and no two rows share one.
     */
    @Test
    fun identityPlanSurvivesAMergeThenASplit() {
        val read = tool("read")
        val n = notice("n")
        val first = listOf(group(read), group(n))
        val merged = listOf(group(n, read))
        val step = plan(first, mapOf("group-read" to "group-read", "group-n" to "group-n"), merged)
        expectUnique(step)
        assertEquals("group-read", step.entryStableIDs["group-n"])
        val split = plan(merged, step.entryStableIDs, listOf(group(n), group(read)))
        expectUnique(split)
        assertEquals("Identity follows the read", "group-read", split.entryStableIDs["group-read"])
        assertEquals("group-n", split.entryStableIDs["group-n"])
    }

    /** Two notices ahead of the read in history: the read's row still keeps its identity; notices don't outvote its tool. */
    @Test
    fun identityPlanLetsToolsNotNoticesDecide() {
        val read = tool("read")
        val n1 = notice("n1")
        val n2 = notice("n2")
        val text = OpenCodeTranscriptItem.AssistantMarkdown("text", "Done.")
        val previous = TranscriptEntry.build(listOf(read, text, n1, n2), isRunning = true)
        val stable = previous.associate { it.id to "stable-${it.id}" }
        val settled = plan(previous, stable, TranscriptEntry.build(listOf(n1, n2, read, text)))
        expectUnique(settled)
        assertEquals("stable-group-read", settled.entryStableIDs["group-n1"])
        assertFalse(settled.entryStableIDs.values.contains("stable-group-n1"))
    }

    /** A group that keeps its first member keeps its identity when a later row's members join it. */
    @Test
    fun identityPlanKeepsTheNameWhenLaterRowsJoin() {
        val read = tool("read")
        val n1 = notice("n1")
        val n2 = notice("n2")
        val notices = plan(listOf(group(read), group(n1, n2)), mapOf("group-read" to "s-read", "group-n1" to "s-n1"), listOf(group(read, n1, n2)))
        assertEquals("s-read", notices.entryStableIDs["group-read"])
        val a = tool("a")
        val b = tool("b")
        val c = tool("c")
        val tools = plan(listOf(group(a), group(b, c)), mapOf("group-a" to "s-a", "group-b" to "s-b"), listOf(group(a, b, c)))
        assertEquals("s-a", tools.entryStableIDs["group-a"])
    }

    @Test
    fun tailIncludesTheReplyBehindItsActions() {
        val user = TranscriptEntry.Single(OpenCodeTranscriptItem.UserText("u", "Hi"))
        val reply = TranscriptEntry.Single(OpenCodeTranscriptItem.AssistantMarkdown("r", ""))
        val end = TranscriptEntry.ReplyEnd("reply-end-r", emptyList())
        assertEquals(listOf(end.id, reply.id), TranscriptEntry.tail(listOf(user, reply, end)).map { it.id })
        assertEquals(listOf(reply.id), TranscriptEntry.tail(listOf(user, reply)).map { it.id })
        assertTrue(TranscriptEntry.tail(emptyList()).isEmpty())
    }

    @Test
    fun queuedInputKeepsItsIdentityWhenEchoedThenPromoted() {
        val local = OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_queued", text = "Steer here"))
        val inbox = OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_queued", text = "Steer here"))
        val echoed = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(local),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = mapOf(local.id to local.id),
            entries = emptyList(),
            queued = listOf(inbox),
        )

        assertEquals(local.id, echoed.queuedStableIDs[inbox.id])
        assertTrue(echoed.promotedEntryIDs.isEmpty())
        assertTrue(echoed.supersededQueuedIDs.isEmpty())

        val entry = TranscriptEntry.build(listOf(OpenCodeTranscriptItem.UserText("msg_queued", "Steer here"))).first()
        val promoted = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(inbox),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = echoed.queuedStableIDs,
            entries = listOf(entry),
            queued = emptyList(),
        )

        assertEquals(local.id, promoted.entryStableIDs[entry.id])
        assertEquals(setOf(entry.id), promoted.promotedEntryIDs)
        assertTrue(promoted.supersededQueuedIDs.isEmpty())
    }

    @Test
    fun promotedEntrySupersedesAStaleInboxSnapshot() {
        val inbox = OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_queued", text = "Steer here"))
        val entry = TranscriptEntry.build(listOf(OpenCodeTranscriptItem.UserText("msg_queued", "Steer here"))).first()

        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(inbox),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = mapOf(inbox.id to "local-queued"),
            entries = listOf(entry),
            queued = listOf(inbox),
        )

        assertEquals("local-queued", plan.entryStableIDs[entry.id])
        assertEquals("local-queued", plan.queuedStableIDs[inbox.id])
        assertEquals(setOf(entry.id), plan.promotedEntryIDs)
        assertEquals(setOf(inbox.id), plan.supersededQueuedIDs)

        val repeated = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(entry),
            previousQueued = listOf(inbox),
            previousEntryStableIDs = plan.entryStableIDs,
            previousQueuedStableIDs = plan.queuedStableIDs,
            entries = listOf(entry),
            queued = listOf(inbox),
        )
        assertEquals(setOf(inbox.id), repeated.supersededQueuedIDs)
    }

    /**
     * A bubble already in the transcript (an idle send ahead of its echo) hides an inbox listing of
     * the same message, in every later snapshot too.
     */
    @Test
    fun pendingRowNeverShowsBesideABubbleThatCameFirst() {
        val entry = TranscriptEntry.build(listOf(OpenCodeTranscriptItem.UserText("msg_sent", "Check it"))).first()
        val inbox = OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_sent", text = "Check it", delivery = OpenCodeDelivery.Steer))
        val listed = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(entry), previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(entry.id to entry.id), previousQueuedStableIDs = emptyMap(),
            entries = listOf(entry), queued = listOf(inbox),
        )
        assertEquals(setOf(inbox.id), listed.supersededQueuedIDs)
        assertEquals(entry.id, listed.entryStableIDs[entry.id])
        val again = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(entry), previousQueued = listOf(inbox),
            previousEntryStableIDs = listed.entryStableIDs, previousQueuedStableIDs = listed.queuedStableIDs,
            entries = listOf(entry), queued = listOf(inbox),
        )
        assertEquals(setOf(inbox.id), again.supersededQueuedIDs)
    }

    @Test
    fun identicalQueuedTextPromotesEachPromptExactlyOnce() {
        val first = OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_first", text = "Again"))
        val second = OpenCodeQueuedInput(OpenCodePendingInput(id = "msg_second", text = "Again"))
        val entries = TranscriptEntry.build(
            listOf(
                OpenCodeTranscriptItem.UserText("msg_first", "Again"),
                OpenCodeTranscriptItem.AssistantMarkdown("reply", ""),
                OpenCodeTranscriptItem.UserText("msg_second", "Again"),
            ),
        )

        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(first, second),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = mapOf(first.id to first.id, second.id to second.id),
            entries = entries,
            queued = emptyList(),
        )

        assertEquals(setOf("user-msg_first", "user-msg_second"), plan.promotedEntryIDs)
        assertEquals("msg_first", plan.entryStableIDs["user-msg_first"])
        assertEquals("msg_second", plan.entryStableIDs["user-msg_second"])
    }

    @Test
    fun entryStartMovesBackToTheStartOfAToolGroupOrASend() {
        val items = listOf(
            reply("r0"), tool("t0"), OpenCodeTranscriptItem.Notice("n0", "Shell finished · Build"), tool("t1"),
            OpenCodeTranscriptItem.UserImages("u1-images", emptyList()), OpenCodeTranscriptItem.UserText("u1", "Look"), reply("r1"),
        )
        assertEquals(1, TranscriptEntry.entryStart(3, items))
        assertEquals(1, TranscriptEntry.entryStart(2, items))
        assertEquals(1, TranscriptEntry.entryStart(1, items))
        assertEquals(4, TranscriptEntry.entryStart(5, items))
        assertEquals(6, TranscriptEntry.entryStart(6, items))
        assertEquals(0, TranscriptEntry.entryStart(0, items))
    }

    @Test
    fun entryStartStopsAtWhatBreaksAGroup() {
        val items = listOf(
            tool("t0"), question("q"), tool("t1"), tool("t2"),
            OpenCodeTranscriptItem.UserText("u1", "One"), OpenCodeTranscriptItem.UserText("u2", "Two"),
        )
        assertEquals(2, TranscriptEntry.entryStart(3, items))
        // Adjacent sends are separate entries.
        assertEquals(5, TranscriptEntry.entryStart(5, items))
    }

    // `ReplyEndTests`.

    /** The reply's Markdown as Copy serializes it (`ReplyActions`). */
    private fun replyEnds(entries: List<TranscriptEntry>): List<String> = entries.mapNotNull { entry ->
        (entry as? TranscriptEntry.ReplyEnd)?.let { end -> OpenCodeMarkdownBlock.markdown(end.texts.flatMap { OpenCodeMarkdownBlock.blocks(it) }) }
    }

    @Test
    fun eachFinishedReplyEndsWithItsWholeText() {
        val items = listOf(
            OpenCodeTranscriptItem.UserText("u1", "Why?"),
            OpenCodeTranscriptItem.AssistantMarkdown("a1", "First part."),
            OpenCodeTranscriptItem.Tool("t1", testCall(callID = "c1", name = "bash")),
            OpenCodeTranscriptItem.AssistantMarkdown("a2", "Second part."),
            OpenCodeTranscriptItem.UserText("u2", "And now?"),
            OpenCodeTranscriptItem.AssistantMarkdown("a3", "Done."),
        )
        val entries = TranscriptEntry.build(items)
        assertEquals(listOf("First part.\n\nSecond part.", "Done."), replyEnds(entries))
        // The first reply's actions sit right before the next question.
        val index = entries.indexOfFirst { it.id == "reply-end-a2" }
        assertEquals("u2", entries[index + 1].userMessageID)
    }

    @Test
    fun aReplyStillRunningHasNoActionsYet() {
        val items = listOf(OpenCodeTranscriptItem.UserText("u1", "Why?"), OpenCodeTranscriptItem.AssistantMarkdown("a1", "Working on it"))
        assertTrue(replyEnds(TranscriptEntry.build(items, isRunning = true)).isEmpty())
        assertEquals(listOf("Working on it"), replyEnds(TranscriptEntry.build(items, isRunning = false)))
    }

    @Test
    fun copiedRepliesKeepTheirMarkdown() {
        val source = """
            |## Fix
            |
            |The **order** of `rules` matters, see [docs](https://example.com).
            |
            |1. First
            |2. Second
            |   - nested
            |
            |```swift
            |let x = 1
            |```
            |
            || A | B |
            || --- | --- |
            || 1 | 2 |
        """.trimMargin()
        val markdown = replyEnds(TranscriptEntry.build(listOf(OpenCodeTranscriptItem.AssistantMarkdown("a1", source)))).firstOrNull()
        assertEquals(
            """
                |## Fix
                |
                |The **order** of `rules` matters, see [docs](https://example.com).
                |
                |1. First
                |2. Second
                |  - nested
                |
                |```swift
                |let x = 1
                |```
                |
                || A | B |
                || --- | --- |
                || 1 | 2 |
            """.trimMargin(),
            markdown,
        )
    }

    @Test
    fun taskItemsTakeTheirBoxAsTheMarker() {
        val blocks = OpenCodeMarkdownBlock.blocks(
            """
                |- [ ] Book dinner
                |- [x] Reply to Dmitriy
                |  - [X] nested done
                |- plain bullet
                |1. [ ] numbered stays numbered
            """.trimMargin(),
        )
        val markers = blocks.mapNotNull { block ->
            (block.kind as? OpenCodeMarkdownBlock.Kind.ListItem)?.let { "${it.marker} ${it.text.text}" }
        }
        assertEquals(listOf("[ ] Book dinner", "[x] Reply to Dmitriy", "[x] nested done", "• plain bullet"), markers)
        assertEquals(
            "- [ ] Book dinner\n- [x] Reply to Dmitriy\n  - [x] nested done\n- plain bullet\n1. [ ] numbered stays numbered",
            OpenCodeMarkdownBlock.markdown(blocks),
        )
    }

    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id, "Reply $id")

    private fun question(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id,
        OpenCodeToolCall(
            callID = "call-$id",
            name = "question",
            status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(
                mapOf(
                    "questions" to OpenCodeJSONValue.Array(
                        listOf(OpenCodeJSONValue.Object(mapOf("question" to OpenCodeJSONValue.Str("Continue?")))),
                    ),
                ),
            ),
            output = emptyList(),
            error = null,
            metadata = emptyMap(),
        ),
    )

    private fun tool(id: String, name: String = "read"): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.Tool(id, testCall(callID = "call-$id", name = name))

    private fun testCall(
        callID: String? = null,
        name: String,
        status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed,
        input: OpenCodeJSONValue = OpenCodeJSONValue.Null,
    ): OpenCodeToolCall = OpenCodeToolCall(
        callID = callID ?: name, name = name, status = status, input = input, output = emptyList(), error = null, metadata = emptyMap(),
    )
}
