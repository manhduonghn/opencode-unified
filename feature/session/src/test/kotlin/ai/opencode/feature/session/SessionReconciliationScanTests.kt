package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.feature.transcript.list.TranscriptScan
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refresh, a settlement, an older page and a session switch work on the rows they touch, not on
 * every row of a long session. Each test counts the rows walked (`TranscriptScan`) over an
 * 800-row transcript.
 */
class SessionReconciliationScanTests {
    private val session = testSession(id = "ses_scan", title = "Scan", directory = "/p")

    private fun history(count: Int): List<OpenCodeTranscriptItem> {
        val items = ArrayList<OpenCodeTranscriptItem>()
        for (index in 0 until count) {
            items += OpenCodeTranscriptItem.UserText(id = "msg_%04d".format(index), text = "Message $index")
        }
        return items
    }

    private fun ids(items: List<OpenCodeTranscriptItem>): List<String> = items.map { it.id }

    private fun <T> counting(body: () -> T): Pair<T, Int> {
        val tally = NewMessagesMark.Companion.Tally()
        val result = TranscriptScan.withTally(tally) { body() }
        return result to tally.count
    }

    /** A refresh while a reply streams keeps the streamed part (absent from REST) in place, linking only the window it overlaps. */
    @Test fun mergeWithAStreamedPartLinksOnlyTheWindow() {
        val older = history(800)
        val window = older.takeLast(29) + OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_live-text-0", text = "Settled")
        val streamed = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_live-text-1", text = "Streaming")
        val tail = OpenCodeTranscriptItem.UserText(id = "msg_steer", text = "Steer")
        val existing = older + window.last() + streamed + tail
        val fetched = window + tail
        val (merged, linked) = counting {
            SessionFeature.mergeTranscript(fetched = fetched, existing = existing, projectedMessageIDs = setOf("msg_live"))
        }
        assertEquals(ids(older) + listOf("msg_live-text-0", "msg_live-text-1", "msg_steer"), ids(merged))
        assertTrue("Linked $linked rows of ${existing.size}", linked <= 3 * (fetched.size + 2))
    }

    /** A window that doesn't overlap still places a held part, after the row it followed. */
    @Test fun mergeWithNoOverlapStillPlacesHeldParts() {
        val existing = history(5) + OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_live-text-0", text = "")
        val fetched = listOf(OpenCodeTranscriptItem.UserText(id = "msg_new", text = "New"))
        val merged = SessionFeature.mergeTranscript(fetched = fetched, existing = existing, projectedMessageIDs = setOf("msg_live"))
        assertEquals(ids(history(5)) + listOf("msg_live-text-0", "msg_new"), ids(merged))
    }

    /** A held part whose message has rows only above the window goes after them, above the window: the span links never drop it. */
    @Test fun mergePlacesAHeldPartAfterItsMessagesOlderRows() {
        val first = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a-text-0", text = "")
        val other = OpenCodeTranscriptItem.UserText(id = "msg_b", text = "B")
        val window = OpenCodeTranscriptItem.UserText(id = "msg_w", text = "W")
        val held = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a-text-1", text = "")
        val merged = SessionFeature.mergeTranscript(
            fetched = listOf(window),
            existing = listOf(first, other, window, held),
            projectedMessageIDs = setOf("msg_a"),
        )
        assertEquals(listOf("msg_a-text-0", "msg_a-text-1", "msg_b", "msg_w"), ids(merged))
    }

    /** The same newest window again reads only the rows up to the first older one, and the window's first row from the end. */
    @Test fun anUnchangedRefreshOfALongSessionScansAFewRows() = sessionTest { h ->
        val items = history(800)
        val state = h.state { State.session(testServer, session, cachedItems = items) }.copy(hasRenderedFirstContentFrame = true)
        val store = h.store(state)
        store.exhaustive = false
        val tally = NewMessagesMark.Companion.Tally()
        TranscriptScan.withTally(tally) {
            store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(items.takeLast(30))))
        }
        assertEquals(items, store.state.items)
        assertTrue("Scanned ${tally.count} rows of 800", tally.count <= 40)
        store.skipReceivedActions()
    }

    @Test fun firstUnseenReadsOnlyTheTailAfterTheLastSeenRow() {
        val newRows = ArrayList<OpenCodeTranscriptItem>()
        for (index in 0 until 3) newRows += OpenCodeTranscriptItem.UserText(id = "msg_new$index", text = "New")
        val items = history(800) + newRows
        val seen = ids(history(800)).toSet()
        val (first, scanned) = counting { NewMessagesMark.firstUnseen(items, seen = seen, unread = false) }
        assertNull("The reader's own messages never count", first)
        assertTrue(scanned <= 4)
        val reply = history(800) + OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "")
        val (unseen, tailScan) = counting { NewMessagesMark.firstUnseen(reply, seen = seen) }
        assertEquals("msg_reply-text-0", unseen)
        assertTrue(tailScan <= 2)
        // A viewed time alone, with nothing after it, marks nothing.
        assertNull(NewMessagesMark.firstUnseen(reply, seen = emptySet(), viewedMilliseconds = Long.MAX_VALUE / 2))
    }

    /** Leaving a long session keeps the newest seen rows, enough to reopen it on the right divider, rather than a set of every row. */
    @Test fun switchSnapshotKeepsOnlyTheNewestSeenRows() = sessionTest { h ->
        val state = h.state { State.session(testServer, session, cachedItems = history(800)) }.copy(hasRenderedFirstContentFrame = true)
        val snapshot = SessionFeature.SwitchSnapshot.from(state)!!
        assertEquals(SessionFeature.SwitchSnapshot.seenTailLimit, snapshot.seenItemIDs.size)
        assertTrue(snapshot.seenItemIDs.contains("msg_0799"))
        assertFalse(snapshot.seenItemIDs.contains("msg_0000"))

        // Reopened on its newest window with a reply that arrived meanwhile.
        val reply = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "")
        val reopened = h.state { State.session(testServer, session, cachedItems = history(800).takeLast(30) + reply) }
            .noteOpening(seen = snapshot.seenItemIDs)
        assertEquals(reply.id, reopened.newMessages?.itemID)
    }

    /** An older page lands above Home's cached newest rows and the divider: nothing Home keeps changed, so it isn't told. */
    @Test fun anOlderPageAboveTheNewestRowsIsNotPublished() = sessionTest { h ->
        val newest = history(100).takeLast(40)
        val state = h.state { State.session(testServer, session, cachedItems = newest) }.copy(
            hasRenderedFirstContentFrame = true,
            earlierHistory = State.EarlierHistory.Loading(cursor = "c1", reachedLoadedItems = true),
        )
        val page = OpenCodeTranscriptPage(items = history(100).take(60), next = "c2")
        val store = h.store(state)
        store.send(Action.EarlierHistoryResponse(sessionID = session.id, generation = 0, cursor = "c1", result = Result.success(page))) {
            it.copy(
                items = history(100),
                earlierHistory = State.EarlierHistory.Paging(cursor = "c2"),
                earlierHistoryInsertionID = "msg_0000",
            )
        }
        store.finish()
    }

    /** A short session's page does reach Home's cached rows. */
    @Test fun anOlderPageUnderTheCachedRowsIsPublished() = sessionTest { h ->
        val newest = history(20).takeLast(10)
        val state = h.state { State.session(testServer, session, cachedItems = newest) }.copy(
            hasRenderedFirstContentFrame = true,
            earlierHistory = State.EarlierHistory.Loading(cursor = "c1", reachedLoadedItems = true),
        )
        val page = OpenCodeTranscriptPage(items = history(20).take(10), next = null)
        val store = h.store(state)
        store.send(Action.EarlierHistoryResponse(sessionID = session.id, generation = 0, cursor = "c1", result = Result.success(page))) {
            it.copy(
                items = history(20),
                earlierHistory = State.EarlierHistory.Exhausted,
                earlierHistoryInsertionID = "msg_0000",
            )
        }
        store.receive(Action.AcceptedTranscriptUpdated::class)
        store.finish()
    }

    @Test fun messageRowsMatchesWhatBelongsToTheMessage() {
        val rows = MessageRows("msg_a")
        assertTrue(rows.contains(OpenCodeTranscriptItem.UserText(id = "msg_a", text = "")))
        assertTrue(rows.contains(OpenCodeTranscriptItem.UserImages(id = "msg_a-images", images = emptyList())))
        assertFalse(rows.contains(OpenCodeTranscriptItem.UserImages(id = "msg_ab-images", images = emptyList())))
        assertTrue(rows.contains(OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a-text-0", text = "")))
        assertFalse(rows.contains(OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_ab-text-0", text = "")))
        val call = OpenCodeToolCall(
            callID = "c",
            messageID = "msg_a",
            name = "read",
            status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(emptyMap()),
            output = emptyList(),
            metadata = emptyMap(),
        )
        assertTrue(rows.contains(OpenCodeTranscriptItem.Tool(id = "other", call = call)))
        assertTrue(SessionFeature.containsMessage("msg_a", history(50) + OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a-text-0", text = "")))
        assertFalse(SessionFeature.containsMessage("msg_a", history(50)))
    }
}
