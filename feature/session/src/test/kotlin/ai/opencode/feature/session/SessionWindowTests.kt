package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The rendered window (`visibleLimit`) lets go of paged history once a reader is back at the end,
 * so later updates work on a screen or two of rows rather than everything paged through.
 */
class SessionWindowTests {
    private val session = testSession(id = "ses_window_reset", title = "Window", directory = "/p")

    private fun messages(count: Int): List<OpenCodeTranscriptItem> {
        val items = ArrayList<OpenCodeTranscriptItem>()
        for (index in 0 until count) items += OpenCodeTranscriptItem.UserText(id = "m$index", text = "Message $index")
        return items
    }

    private fun ids(range: IntRange): List<String> {
        val ids = ArrayList<String>()
        for (index in range) ids += "m$index"
        return ids
    }

    /** A reader who paged 200 rows in and browsed back to the end keeps every row through the glide, then settles to the base window. */
    private fun pagedState(h: SessionTestSupport.Harness, count: Int = 200): State = h.state {
        State.session(testServer, session, cachedItems = messages(count))
    }.copy(hasRenderedFirstContentFrame = true, visibleLimit = count, browsingWindowStartID = "m0")

    @Test fun returningToTheEndSettlesToTheBaseWindowAfterTheGlide() = sessionTest { h ->
        val clock = TestClock()
        val store = h.store(pagedState(h)) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(windowReset = true)
        }
        store.send(Action.TranscriptBrowsingChanged(false)) {
            // The glide keeps every row it passes: nothing leaves the top yet.
            it.copy(browsingWindowStartID = null)
        }
        assertEquals(200, store.state.visibleItems.size)
        clock.advance(SessionFeature.windowResetDelay - 1.milliseconds)
        clock.advance(1.milliseconds)
        store.receive(Action.RenderedWindowSettled::class) {
            it.copy(visibleLimit = SessionFeature.settledWindowEntries)
        }
        assertEquals(ids(200 - SessionFeature.settledWindowEntries until 200), store.state.visibleItems.map { it.id })
        assertTrue("The rows let go of page back in from memory", store.state.hasEarlierItems)
        store.finish()
    }

    @Test fun browsingAgainBeforeTheWindowSettlesKeepsItWhole() = sessionTest { h ->
        val clock = TestClock()
        val store = h.store(pagedState(h)) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(windowReset = true)
        }
        store.send(Action.TranscriptBrowsingChanged(false)) { it.copy(browsingWindowStartID = null) }
        clock.advance(1.seconds)
        store.send(Action.TranscriptBrowsingChanged(true)) { it.copy(browsingWindowStartID = "m0") }
        clock.advance(5.seconds)
        assertEquals(200, store.state.visibleLimit)
        store.finish()
    }

    @Test fun aSendFromHistorySettlesTheWindowToo() = sessionTest { h ->
        val clock = TestClock()
        val state = pagedState(h).withDraft("Hello")
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(windowReset = true)
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> delay(60.seconds) },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        assertNull(store.state.browsingWindowStartID)
        assertTrue(store.state.visibleLimit >= 200)
        clock.advance(SessionFeature.windowResetDelay)
        store.receive(Action.RenderedWindowSettled::class)
        assertEquals(SessionFeature.settledWindowEntries, store.state.visibleLimit)
        assertEquals(store.state.items.last().id, store.state.visibleItems.last().id)
        h.skipInFlightEffects()
    }

    @Test fun settledWindowKeepsWholeEntriesAndTheNewDivider() = sessionTest { h ->
        var state = h.state { State.session(testServer, session, cachedItems = messages(100)) }
            .copy(hasRenderedFirstContentFrame = true)
        assertEquals(SessionFeature.settledWindowEntries, state.settledVisibleLimit)
        // A tool group is one row however many tools it holds.
        val tools = ArrayList<OpenCodeTranscriptItem>()
        for (index in 0 until 50) {
            tools += OpenCodeTranscriptItem.Tool(
                id = "t$index",
                call = OpenCodeToolCall(
                    callID = "c$index",
                    name = "read",
                    status = OpenCodeToolCall.Status.Completed,
                    input = OpenCodeJSONValue.Object(emptyMap()),
                    output = emptyList(),
                    metadata = emptyMap(),
                ),
            )
        }
        state = state.copy(items = messages(60) + tools)
        assertEquals(50 + SessionFeature.settledWindowEntries - 1, state.settledVisibleLimit)
        assertEquals(
            state.items.size - state.settledVisibleLimit,
            TranscriptEntry.entryStart(state.items.size - state.settledVisibleLimit, state.items),
        )
        // The New divider and the row above it stay.
        state = state.copy(items = messages(100), newMessages = NewMessagesMark(itemID = "m10", generation = 1))
        assertEquals(91, state.settledVisibleLimit)
    }

    /** After Revert to Here the undone turns stay folded: the window keeps the boundary and spends its entries above it. */
    @Test fun settledWindowKeepsRevertToHeresBoundary() = sessionTest { h ->
        val reverted = session.copy(revertMessageID = "m80")
        var state = h.state { State.session(testServer, reverted, cachedItems = messages(100)) }
            .copy(hasRenderedFirstContentFrame = true)
        state = state.copy(visibleLimit = state.settledVisibleLimit)
        assertEquals(100 - (80 - SessionFeature.settledWindowEntries), state.visibleLimit)
        val last = state.visibleItems.last()
        assertTrue(
            "The undone turns came back: ${state.visibleItems.takeLast(3).map { it.id }}",
            last is OpenCodeTranscriptItem.Undone,
        )
        assertEquals("m79", state.visibleItems.dropLast(1).last().id)
    }

    /**
     * The reset never cuts the turn on screen: a reply's Copy and link previews are built from the
     * whole turn. A very long turn stays bounded.
     */
    @Test fun settledWindowKeepsTheBottomTurnWhole() = sessionTest { h ->
        fun replies(count: Int): List<OpenCodeTranscriptItem> {
            val items = ArrayList<OpenCodeTranscriptItem>()
            for (index in 0 until count) items += OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_r-text-$index", text = "")
            return items
        }
        val prompt = OpenCodeTranscriptItem.UserText(id = "p", text = "Go")
        var state = h.state { State.session(testServer, session, cachedItems = messages(10) + prompt + replies(60)) }
            .copy(hasRenderedFirstContentFrame = true)
        state = state.copy(visibleLimit = state.settledVisibleLimit)
        assertEquals("p", state.visibleItems.first().id)

        state = state.copy(items = listOf(prompt) + replies(200))
        assertEquals(SessionFeature.settledWindowCap, state.settledVisibleLimit)
    }

    /** Paging back in while the window would settle is browsing: it doesn't settle. */
    @Test fun pagingBackInCancelsTheSettle() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state { State.session(testServer, session, cachedItems = messages(200)) }
            .copy(hasRenderedFirstContentFrame = true, visibleLimit = 100, browsingWindowStartID = "m100")
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(windowReset = true)
        }
        store.send(Action.TranscriptBrowsingChanged(false)) { it.copy(browsingWindowStartID = null) }
        store.send(Action.ShowEarlierTapped) { it.copy(visibleLimit = 140) }
        clock.advance(10.seconds)
        store.finish()
    }

    @Test fun aWindowAlreadySmallIsLeftAlone() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state { State.session(testServer, session, cachedItems = messages(30)) }
            .copy(hasRenderedFirstContentFrame = true, browsingWindowStartID = "m10")
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(windowReset = true)
        }
        // At the base window, nothing is scheduled.
        store.send(Action.TranscriptBrowsingChanged(false)) { it.copy(browsingWindowStartID = null) }
        store.finish()
    }
}
