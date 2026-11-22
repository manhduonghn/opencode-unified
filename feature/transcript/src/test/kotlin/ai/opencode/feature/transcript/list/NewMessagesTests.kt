package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of iOS `OpenCodeTests/NewMessagesTests.swift`: the transcript half (`NewMessagesMark.firstUnseen`
 * and `NewMessagesPlacement.resolve`). The tests that drive `SessionFeature`/`HomeFeature` belong to
 * `:feature:session` and `:feature:drawer`. iOS `NewMessagesPlacement.resolve` is
 * [NewMessagesMark.resolve] here.
 *
 * Dropped with `TranscriptScrollBehavior` (deliberate Android divergence, see
 * docs/parity/transcript.md): `revealsBrowseFromTheDividerOrFollowAtTheEnd` and
 * `landingAtTheDividerDoesntPageInHistory`. The bottom-anchored list keeps both behaviours (the jump
 * button follows the scroll position; `TranscriptListScroller.holdsEarlierHistory`), but they need
 * a LazyColumn layout to test.
 */
class NewMessagesTests {
    private fun agent(id: String): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.UserText(id, id, source = OpenCodePromptSource.Session("ses_other"))

    private fun user(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id, id)

    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id, "Reply $id")

    @Test
    fun firstUnseenSkipsWhatWasOnScreenAndClientNotices() {
        val items = listOf(user("m1"), reply("m2"), OpenCodeTranscriptItem.Info("info-1", "Note"), reply("m3"), reply("m4"))
        assertEquals("m3", NewMessagesMark.firstUnseen(items, setOf("m1", "m2")))
        assertNull(NewMessagesMark.firstUnseen(items, setOf("m1", "m2", "m3", "m4")))
        // Nothing to compare with: no divider.
        assertNull(NewMessagesMark.firstUnseen(items, emptySet()))
    }

    /**
     * The reader's own messages never count, from this phone or another client, and sending one
     * reads everything above it; a message an agent sent into the session is news.
     */
    @Test
    fun ownMessagesNeverCountAndReadWhatsAboveThem() {
        val seen = setOf("m1", "m2")
        // Their own message (sent from the desktop while away), then its reply.
        assertEquals("m4", NewMessagesMark.firstUnseen(listOf(user("m1"), reply("m2"), user("m3"), reply("m4")), seen))
        // Only their own message: nothing new.
        assertNull(NewMessagesMark.firstUnseen(listOf(user("m1"), reply("m2"), user("m3")), seen))
        // A reply they answered from another client was read there.
        assertNull(NewMessagesMark.firstUnseen(listOf(user("m1"), reply("m2"), reply("m3"), user("m4")), seen))
        assertEquals("m5", NewMessagesMark.firstUnseen(listOf(user("m1"), reply("m2"), reply("m3"), user("m4"), reply("m5")), seen))
        // Images and files sent with their message are theirs too.
        val images = OpenCodeTranscriptItem.UserImages("m3-images", emptyList())
        assertNull(NewMessagesMark.firstUnseen(listOf(user("m1"), reply("m2"), user("m3"), images), seen))
        // Another session's agent forwarding a prompt is news.
        assertEquals("m3", NewMessagesMark.firstUnseen(listOf(user("m1"), reply("m2"), agent("m3"), reply("m4")), seen))
    }

    /** When the mark's row isn't among the entries, the divider sits above the next entry that holds something new. */
    @Test
    fun placementFallsToTheNextEntryWithNewItems() {
        val tool = OpenCodeTranscriptItem.Notice("n3", "Shell finished")
        val items = listOf(user("m1"), reply("m2"), tool, reply("m4"))
        val entries = listOf(
            TranscriptEntry.UserGroup("m1", listOf(user("m1")), startsTurn = true),
            TranscriptEntry.Single(reply("m2")),
            TranscriptEntry.Single(reply("m4")),
        )
        val placement = NewMessagesMark.resolve(NewMessagesMark(itemID = "n3", generation = 3), items, entries)
        assertEquals(NewMessagesPlacement(entryID = "m4", generation = 3), placement)
        assertNull(NewMessagesMark.resolve(null, items, entries))
    }

    @Test
    fun olderRowsPagedInAboveWhatWasSeenArentNew() {
        val items = listOf(user("old1"), reply("old2"), user("m1"), reply("m2"), reply("m3"))
        assertEquals("m3", NewMessagesMark.firstUnseen(items, setOf("m1", "m2")))
        assertNull(NewMessagesMark.firstUnseen(items.take(4), setOf("m1", "m2")))
    }

    /** A group or joined reply the reader already had stays above the divider, and Messages' time stays with the message it dates. */
    @Test
    fun placementSkipsRowsTheReaderHadAndKeepsATimeWithItsMessage() {
        val t1 = OpenCodeTranscriptItem.Notice("t1", "Shell finished")
        val t2 = OpenCodeTranscriptItem.Notice("t2", "Shell finished")
        val items = listOf(user("m1"), t1, t2, reply("m3"))
        val entries = listOf(
            TranscriptEntry.UserGroup("m1", listOf(user("m1")), startsTurn = true),
            TranscriptEntry.ToolGroup("t1", listOf(t1, t2)),
            TranscriptEntry.Single(reply("m3")),
        )
        assertEquals("m3", NewMessagesMark.resolve(NewMessagesMark(itemID = "t2", generation = 1), items, entries)?.entryID)
        val stamp = TranscriptEntry.Single(OpenCodeTranscriptItem.Info(TranscriptTimeStamps.PREFIX + "m4", "9:41"))
        val dated = listOf(TranscriptEntry.Single(reply("m3")), stamp, TranscriptEntry.UserGroup("m4", listOf(user("m4")), startsTurn = true))
        assertEquals(
            stamp.id,
            NewMessagesMark.resolve(NewMessagesMark(itemID = "m4", generation = 1), listOf(reply("m3"), user("m4")), dated)?.entryID,
        )
    }
}
