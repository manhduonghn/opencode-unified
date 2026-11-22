package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.NewMessagesFold
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Port of the `SessionFeature` half of iOS `NewMessagesTests.swift`.
 *
 * The New divider (an experiment): what arrived while the reader was away,
 * shown only when it doesn't fit on screen, and the reads that bring it
 * before they're back.
 *
 * Not ported here (:feature:transcript, `NewMessagesMark` / `NewMessagesPlacement` alone):
 * `firstUnseenSkipsWhatWasOnScreenAndClientNotices`, `ownMessagesNeverCountAndReadWhatsAboveThem`,
 * `placementFallsToTheNextEntryWithNewItems`, `olderRowsPagedInAboveWhatWasSeenArentNew`,
 * `placementSkipsRowsTheReaderHadAndKeepsATimeWithItsMessage`.
 * Not ported here (:feature:drawer, `HomeFeature`): `switchingBackMarksWhatArrivedSince`,
 * `theDrawerReadsLikelySessionsBeforeATap`, `leavingTheAppHoldsTheStreamUntilTheReturn`.
 */
class NewMessagesTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")

    private fun session(id: String) = OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10))
    private fun user(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id = id, text = id)
    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = "Reply $id")

    private fun Harness.sessionState(id: String, items: List<OpenCodeTranscriptItem>): SessionFeature.State =
        state { SessionFeature.State.session(server = server, session = session(id), cachedItems = items) }

    private fun Harness.resumeStore(
        state: SessionFeature.State,
        pendingRequests: suspend () -> OpenCodePendingRequests = { throw OpenCodeClientError(message = "Offline") },
        transcript: () -> List<OpenCodeTranscriptItem>,
    ): TestStore<SessionFeature.State, Action> {
        val store = store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> transcript() },
                getSession = { _, id -> session(id) },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> pendingRequests() },
                pendingInputs = { _, _ -> emptyList() },
                contextUsage = { _, _ -> OpenCodeContextReading.Inconclusive },
                viewSession = { _, _, _ -> },
            )
        }
        store.exhaustive = false
        return store
    }

    /**
     * Back in the app: the read after the return puts the divider above the
     * first row that wasn't on screen, and a later return with nothing new
     * takes it away.
     */
    @Test fun theReadAfterAReturnPlacesTheDividerThenClearsIt() = sessionTest { h ->
        val away = listOf(user("m1"), reply("m2"))
        val back = away + listOf(reply("m3"), reply("m4"))
        val reads = LockIsolated(listOf(away, back, back))
        val state = h.sessionState("ses_new", away).copy(hasRenderedFirstContentFrame = true, initialTranscriptIsFresh = true, isVisible = true)
        val store = h.resumeStore(state) {
            reads.withValue { if (it.size > 1) it.drop(1) to it[0] else it to it[0] }
        }

        // A read while the reader is still here changes nothing.
        store.send(Action.RefreshRequested(RefreshReason.Invalidation))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertNull(store.state.newMessages)

        store.send(Action.VisibilityChanged(false))
        assertEquals(setOf("m1", "m2"), store.state.newMessagesBaseline)
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertEquals(NewMessagesMark(itemID = "m3", lastItemID = "m4", generation = 1), store.state.newMessages)
        assertNull(store.state.newMessagesBaseline)

        // Once read past, a quick trip away with nothing new shows nothing.
        store.send(Action.NewMessagesPassed(generation = 1))
        store.send(Action.VisibilityChanged(false))
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshResponse::class, timeout = 1.seconds)
        assertNull(store.state.newMessages)
        h.skipInFlightEffects()
    }

    /**
     * Several messages that fit on screen show at the bottom with no divider:
     * in front of the reader they're read, so only what comes after them can
     * make one.
     */
    @Test fun messagesThatFitAreReadOnceShown() = sessionTest { h ->
        val state = h.state {
            h.sessionState("ses_fit", listOf(user("m1"), reply("m2"), reply("m3"), reply("m4"), reply("m5")))
                .copy(isVisible = true)
                .noteOpening(seen = setOf("m1", "m2"))
        }
        assertEquals("m3", state.newMessages?.itemID)
        val generation = state.newMessages?.generation ?: 0
        val store = h.store(state)
        store.exhaustive = false
        // An older mark's answer changes nothing.
        store.send(Action.NewMessagesFit(generation = generation - 1))
        assertNotNull(store.state.newMessages)
        store.send(Action.NewMessagesFit(generation = generation))
        assertNull(store.state.newMessages)
        assertEquals(setOf("m1", "m2", "m3", "m4", "m5"), store.state.newMessagesBaseline)
        assertNull(store.state.newMessagesFitThrough)

        var after = store.state
        h.state {
            after = after.updateNewMessagesMark(settles = false)
            assertNull("nothing new since what fit", after.newMessages)
            after = after.copy(items = after.items + reply("m6")).updateNewMessagesMark(settles = true)
        }
        assertEquals("m6", after.newMessages?.itemID)
    }

    /**
     * Decided as the reader arrives, the divider stays as decided while the
     * reply streams on in front of them: what fit is read and nothing that
     * streams in after it makes a mark, and a divider that didn't fit keeps
     * its place. (iOS `@Test(arguments: [true, false])`.)
     */
    @Test fun theArrivalsDecisionHoldsWhileStreaming() {
        for (fits in listOf(true, false)) sessionTest { h ->
            val state = h.state {
                h.sessionState("ses_decided", listOf(user("m1"), reply("m2"), reply("m3")))
                    .copy(isVisible = true, hasRenderedFirstContentFrame = true)
                    .noteOpening(seen = setOf("m1", "m2"))
            }
            val mark = state.newMessages!!
            assertEquals(NewMessagesMark(itemID = "m3", lastItemID = "m3", generation = 1), mark)
            val store = h.store(state) {
                this[Clock] = ImmediateClock()
            }
            if (fits) {
                // The list measured it all on screen.
                store.send(Action.NewMessagesFit(generation = mark.generation)) {
                    it.copy(newMessages = null, newMessagesBaseline = setOf("m1", "m2", "m3"))
                }
            }
            // An older mark's answer changes nothing.
            store.send(Action.NewMessagesFit(generation = mark.generation - 1))
            store.send(
                Action.ContentBatch(
                    sessionID = "ses_decided",
                    events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = "Streaming reply")),
                ),
            ) {
                it.copy(
                    isExternallyActive = true,
                    workingRun = WorkingRun(counter = 1, startID = 1, key = "ses_decided", sawStart = true, startTurn = "m1", baseline = "msg_live-text-0"),
                    streamIsHealthy = true,
                    streamSupportsProjection = true,
                    projectedMessageIDs = setOf("msg_live"),
                    lastProjectedMessageID = "msg_live",
                    liveText = mapOf("msg_live-text-0" to "Streaming reply"),
                    items = it.items + OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_live-text-0", text = "Streaming reply", inProgress = true),
                    // In front of the reader: seen, whatever the read after it brings.
                    newMessagesWatched = setOf("msg_live-text-0"),
                )
            }
            assertEquals("fits=$fits", if (fits) null else mark, store.state.newMessages)
            h.skipInFlightEffects()
        }
    }

    /**
     * Rows the stream brings while away are measured as a whole each time:
     * rows that each fit can add up to a backlog that doesn't. What fit is
     * read only once the reader is back in front of it.
     */
    @Test fun whatFitsWhileAwayIsMeasuredAgainAsMoreArrives() = sessionTest { h ->
        val state = h.state { h.sessionState("ses_away", listOf(user("m1"), reply("m2"), reply("m3"))).noteOpening(seen = setOf("m1", "m2")) }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false
        store.send(Action.NewMessagesFit(generation = store.state.newMessages?.generation ?: 0))
        assertNull(store.state.newMessages)
        assertEquals("m3", store.state.newMessagesFitThrough)
        assertEquals("not read while away", setOf("m1", "m2"), store.state.newMessagesBaseline)

        val away = h.state { store.state.copy(items = store.state.items + reply("m4")).updateNewMessagesMark(settles = false) }
        assertEquals("the whole span, from the first row they missed", "m3", away.newMessages?.itemID)
        assertNull(away.newMessagesFitThrough)

        // Back in front of a span that fit: it's read.
        store.send(Action.VisibilityChanged(true))
        assertEquals(setOf("m1", "m2", "m3"), store.state.newMessagesBaseline)
        assertNull(store.state.newMessagesFitThrough)
        h.skipInFlightEffects()
    }

    /**
     * A mark placed early (the drawer's copy, or the stream while away) is
     * placed again when the read after the return brings more below it, so
     * the list brings the reader to those rows too; leaving drops it.
     */
    @Test fun moreRowsUnderAnEarlyMarkMakeANewMark() = sessionTest { h ->
        var state = h.state { h.sessionState("ses_more", listOf(user("m1"), reply("m2"), reply("m3"))).noteOpening(seen = setOf("m1", "m2")) }
        val early = state.newMessages
        assertEquals("m3", early?.itemID)
        state = h.state { state.copy(items = state.items + reply("m4")).updateNewMessagesMark(settles = true) }
        assertEquals("m3", state.newMessages?.itemID)
        assertNotEquals(early?.generation, state.newMessages?.generation)
        state = h.state { state.noteLeaving() }
        assertNull(state.newMessages)
        assertEquals(setOf("m1", "m2"), state.newMessagesBaseline)
    }

    /**
     * The divider's window keeps rows above it, short of every loaded row (a
     * window over all of them asks for older history).
     */
    @Test fun theDividersWindowStopsShortOfEveryLoadedRow() = sessionTest { h ->
        val items = (0 until 30).map { reply("m$it") }
        val state = h.state { h.sessionState("ses_window", items).noteOpening(seen = (0 until 12).map { "m$it" }.toSet()) }
        assertEquals("m12", state.newMessages?.itemID)
        assertEquals(29, state.visibleLimit)
    }

    /**
     * Back while a message's read started in the background is still out:
     * the return's page read follows it, and a failed one forgets the baseline.
     */
    @Test fun theReturnAlwaysReadsThePage() = sessionTest { h ->
        val state = h.sessionState("ses_settle", listOf(user("m1"))).copy(
            hasRenderedFirstContentFrame = true,
            newMessagesBaseline = setOf("m1"),
            nextRefreshID = 1,
            refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "m2")),
        )
        val store = h.resumeStore(state) { listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "m1")) }
        store.send(Action.VisibilityChanged(true))
        store.receive(Action.RefreshRequested::class)
        assertEquals(RefreshReason.Resume, store.state.refresh?.followup)

        store.send(Action.RefreshResponse(sessionID = "ses_settle", requestID = 1, result = Result.failure(OpenCodeClientError(message = "offline"))))
        assertEquals("the page read still follows", setOf("m1"), store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    @Test fun aFailedReadAfterTheReturnForgetsTheBaseline() = sessionTest { h ->
        val state = h.sessionState("ses_fail", listOf(user("m1"))).copy(
            isVisible = true,
            newMessagesBaseline = setOf("m1"),
            refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Resume),
        )
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.RefreshResponse(sessionID = "ses_fail", requestID = 1, result = Result.failure(OpenCodeClientError(message = "offline"))))
        assertNull(store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    @Test fun aGlanceAtControlCenterIsntLeaving() = sessionTest { h ->
        val state = h.sessionState("ses_glance", listOf(user("m1"))).copy(hasRenderedFirstContentFrame = true, isVisible = true)
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.SceneActiveChanged(false))
        store.send(Action.VisibilityChanged(false))
        assertNull(store.state.newMessagesBaseline)
        store.send(Action.SceneEnteredBackground)
        assertEquals(setOf("m1"), store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    /**
     * A send from this phone reads everything above it, after the return's
     * read settled the divider too: the divider goes, and nothing waiting to
     * be settled counts as new.
     */
    @Test fun sendingClearsTheDivider() = sessionTest { h ->
        val state = h.sessionState("ses_send", listOf(user("m1"), reply("m2"), reply("m3"))).copy(
            newMessages = NewMessagesMark(itemID = "m3", generation = 1),
            newMessagesBaseline = setOf("m1", "m2"),
        ).withDraft("Thanks")
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> awaitCancellation() },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        assertNull(store.state.newMessages)
        assertNull(store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    @Test fun passingTheDividerClearsOnlyItsOwnMark() = sessionTest { h ->
        val state = h.sessionState("ses_pass", listOf(user("m1"))).copy(newMessages = NewMessagesMark(itemID = "m1", generation = 2))
        val store = h.store(state)
        store.send(Action.NewMessagesPassed(generation = 1))
        store.send(Action.NewMessagesPassed(generation = 2)) { it.copy(newMessages = null) }
        store.finish()
    }

    /**
     * A session opened again from a copy that already has what arrived since:
     * its first frame carries the divider, and enough rows to show it.
     */
    @Test fun openingFromAFresherCopyPlacesTheDividerInTheFirstFrame() = sessionTest { h ->
        val seen = (0 until 4).map { "m$it" }.toSet()
        val items = listOf(user("m0")) + (1 until 30).map { reply("m$it") }
        val detail = h.state { h.sessionState("ses_open", items).noteOpening(seen = seen) }
        assertEquals("m4", detail.newMessages?.itemID)
        assertTrue(detail.visibleItems.any { it.id == "m4" })
        // With rows above it, so landing there doesn't page in more history.
        assertEquals("m1", detail.visibleItems.firstOrNull()?.id)
        // A copy this run hasn't shown is taken as seen.
        val fresh = h.state { h.sessionState("ses_fresh", items).noteOpening(seen = null) }
        assertNull(fresh.newMessages)
    }

    // region Read receipts and the watermark

    private val liveID = "msg_live-text-0"
    private val liveRow: OpenCodeTranscriptItem get() = OpenCodeTranscriptItem.AssistantMarkdown(id = liveID, text = "Streaming reply", inProgress = true)
    private val liveDelta: Action
        get() = Action.ContentBatch(
            sessionID = "ses_live",
            events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = "Streaming reply")),
        )

    private fun pageRead(items: List<OpenCodeTranscriptItem>, id: Int = 1): Action =
        Action.RefreshResponse(
            sessionID = "ses_live",
            requestID = id,
            result = Result.success(
                SessionFeature.AuthoritativeRefresh(observed = session("ses_live"), items = items, scope = SessionFeature.AuthoritativeRefresh.Scope.Page),
            ),
        )

    /** On screen, back in a session whose return read ([reason]) is still out. */
    private fun Harness.watching(items: List<OpenCodeTranscriptItem>, reason: RefreshReason = RefreshReason.Resume): SessionFeature.State =
        sessionState("ses_live", items).copy(
            isVisible = true,
            hasRenderedFirstContentFrame = true,
            nextRefreshID = 1,
            refresh = SessionFeature.State.Refresh(id = 1, reason = reason),
        )

    @Test fun theDividersWindowReachesTheFirstRowOnceHistoryIsExhausted() = sessionTest { h ->
        val items = (0 until 30).map { reply("m$it") }
        val state = h.state {
            h.sessionState("ses_window_all", items).copy(earlierHistory = SessionFeature.State.EarlierHistory.Exhausted)
                .noteOpening(seen = (0 until 12).map { "m$it" }.toSet())
        }
        assertEquals("m12", state.newMessages?.itemID)
        assertEquals(30, state.visibleLimit)
        assertEquals("m0", state.visibleItems.firstOrNull()?.id)
    }

    /**
     * Rows that stream in front of the reader while the read
     * after their return is out are seen, so that read never marks them;
     * a row it brings from while they were away still is.
     */
    @Test fun liveStreamedRowsWhileOpenRefreshInFlightAreNotMarkedNew() = sessionTest { h ->
        val store = h.resumeStore(h.watching(listOf(user("m1"), reply("m2"))).copy(newMessagesBaseline = setOf("m1", "m2"))) { emptyList() }
        store.send(liveDelta)
        assertEquals(liveID, store.state.items.lastOrNull()?.id)
        assertNull(store.state.newMessages)
        store.send(pageRead(listOf(user("m1"), reply("m2"), liveRow)))
        assertNull("streamed in front of them", store.state.newMessages)
        assertNull(store.state.newMessagesBaseline)

        val awayStore = h.resumeStore(h.watching(listOf(user("m1"), reply("m2"))).copy(newMessagesBaseline = setOf("m1", "m2"))) { emptyList() }
        awayStore.send(liveDelta)
        awayStore.send(pageRead(listOf(user("m1"), reply("m2"), reply("m3"), liveRow)))
        assertEquals("arrived while away, above what streamed", "m3", awayStore.state.newMessages?.itemID)
        h.skipInFlightEffects()
    }

    /**
     * A message's read already out with the return's page read queued after
     * it: another message's read never takes the page read's place, so the
     * return still settles.
     */
    @Test fun settlementRefreshDoesNotClobberPendingResumePageRefresh() = sessionTest { h ->
        val watched = h.watching(listOf(user("m1"), reply("m2")), reason = RefreshReason.Settlement(messageID = "m2"))
        val state = watched.copy(refresh = watched.refresh?.copy(followup = RefreshReason.Resume), newMessagesBaseline = setOf("m1"))
        val store = h.resumeStore(state) { emptyList() }
        store.send(Action.RefreshRequested(RefreshReason.Settlement(messageID = "m3")))
        assertEquals(RefreshReason.Resume, store.state.refresh?.followup)
        h.skipInFlightEffects()
    }

    /**
     * What fit as the reader arrived is read; a reply that streams in under
     * it afterwards is in front of them, so the read that follows doesn't
     * put a divider above it.
     */
    @Test fun fittingMarkAbsorbedInFrontOfReaderDoesNotReappearOnSubsequentStreamRead() = sessionTest { h ->
        val state = h.state { h.watching(listOf(user("m1"), reply("m2"), reply("r1")), reason = RefreshReason.Open).noteOpening(seen = setOf("m1", "m2")) }
        val mark = checkNotNull(state.newMessages)
        val store = h.resumeStore(state) { emptyList() }
        store.send(Action.NewMessagesFit(generation = mark.generation))
        assertNull(store.state.newMessages)
        assertEquals(setOf("m1", "m2", "r1"), store.state.newMessagesBaseline)
        store.send(liveDelta)
        store.send(pageRead(listOf(user("m1"), reply("m2"), reply("r1"), liveRow)))
        assertNull(store.state.newMessages)
        assertNull(store.state.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    private fun tool(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id = id,
        call = OpenCodeToolCall(callID = id, name = "read", status = OpenCodeToolCall.Status.Completed, input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), metadata = emptyMap()),
    )

    private fun SessionFeature.State.applyLive(
        key: String = "ses_live",
        turn: String = "m1",
        message: String = "msg_live",
        baseline: String? = null,
    ): SessionFeature.State = copy(
        isExternallyActive = true,
        workingRun = WorkingRun(counter = 1, startID = 1, key = key, sawStart = true, startTurn = turn, baseline = baseline ?: liveID),
        streamIsHealthy = true,
        streamSupportsProjection = true,
        projectedMessageIDs = setOf(message),
        lastProjectedMessageID = message,
    )

    /**
     * Everything new joined a tool group the reader had on screen: there's
     * no row to put the divider above, and no mark is left behind to wake on
     * the reply that streams in next.
     */
    @Test fun midActivityGroupUnseenSettledDoesNotTriggerDividerOnNextLiveReply() = sessionTest { h ->
        var state = h.sessionState("ses_group", listOf(user("u1"), tool("t1"), tool("t2"))).copy(newMessagesBaseline = setOf("u1", "t1"))
        state = h.state { state.updateNewMessagesMark(settles = true) }
        assertNull(state.newMessages)
        state = state.copy(items = state.items + reply("a1"))
        val entries = TranscriptEntry.build(state.items)
        assertNull(NewMessagesMark.resolve(state.newMessages, state.items, entries))
    }

    private fun liveTool(toolID: String, events: (String) -> List<OpenCodeLiveContentEvent>): Action =
        Action.ContentBatch(sessionID = "ses_away", events = events(toolID))

    private fun toolStarted(toolID: String): Action =
        liveTool(toolID) { listOf(OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_a", toolID = it, name = "read")) }

    private fun toolTyped(toolID: String): Action =
        liveTool(toolID) { listOf(OpenCodeLiveContentEvent.ToolInputDelta(messageID = "msg_a", toolID = it, delta = "x")) }

    private fun streamingTool(toolID: String, input: String = ""): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id = "msg_a-tool-$toolID",
        call = OpenCodeToolCall(
            callID = toolID, messageID = "msg_a", name = "read", status = OpenCodeToolCall.Status.Streaming,
            input = OpenCodeJSONValue.Str(input), output = emptyList(), metadata = emptyMap(),
        ),
    )

    private fun List<OpenCodeTranscriptItem>.replacing(index: Int, item: OpenCodeTranscriptItem) =
        toMutableList().also { it[index] = item }

    /**
     * Away, the stream marks what it brings: the first new row places the
     * mark, rows after it only move its end, and a delta that adds no row
     * changes nothing. Rows that join a group already on screen mark nothing.
     */
    @Test fun streamingWhileAwayMovesTheMarksEndAndFoldsIntoAGroupOnScreen() = sessionTest { h ->
        val state = h.state { h.sessionState("ses_away", listOf(user("u1"), tool("t1"), reply("a1"))).noteOpening(seen = setOf("u1", "t1", "a1")) }
        assertNull(state.newMessages)
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
        }
        store.send(toolStarted("x1")) {
            it.applyLive(key = "ses_away", turn = "u1", message = "msg_a", baseline = "msg_a-tool-x1").copy(
                items = it.items + streamingTool("x1"),
                newMessagesGeneration = 1,
                newMessages = NewMessagesMark(itemID = "msg_a-tool-x1", lastItemID = "msg_a-tool-x1", generation = 1),
            )
        }
        store.send(toolStarted("x2")) {
            it.copy(
                items = it.items + streamingTool("x2"),
                newMessagesGeneration = 2,
                newMessages = NewMessagesMark(itemID = "msg_a-tool-x1", lastItemID = "msg_a-tool-x2", generation = 2),
            )
        }
        store.send(toolTyped("x2")) { it.copy(items = it.items.replacing(4, streamingTool("x2", input = "x"))) }

        // New tools under the reader's own tool group: no row of their own.
        val folding = h.state { h.sessionState("ses_away", listOf(user("u1"), tool("t1"))).noteOpening(seen = setOf("u1", "t1")) }
        val foldStore = h.store(folding) {
            this[Clock] = ImmediateClock()
        }
        foldStore.send(toolStarted("x1")) {
            it.applyLive(key = "ses_away", turn = "u1", message = "msg_a", baseline = "msg_a-tool-x1").copy(
                items = it.items + streamingTool("x1"),
                newMessagesFolded = NewMessagesFold(itemID = "msg_a-tool-x1", lastItemID = "msg_a-tool-x1", count = 3),
            )
        }
        foldStore.send(toolTyped("x1")) { it.copy(items = it.items.replacing(2, streamingTool("x1", input = "x"))) }
        h.skipInFlightEffects()
    }

    @Test fun aQuestionAnsweredWhileAwayIsMarkedOnceItJoinsTheTranscript() = sessionTest { h ->
        fun question(status: OpenCodeToolCall.Status): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
            id = "msg_a-tool-q1",
            call = OpenCodeToolCall(
                callID = "q1", messageID = "msg_a", name = "question", status = status,
                input = OpenCodeJSONValue.Object(
                    mapOf("questions" to OpenCodeJSONValue.Array(listOf(OpenCodeJSONValue.Object(mapOf("question" to OpenCodeJSONValue.Str("Continue?")))))),
                ),
                output = emptyList(), metadata = emptyMap(),
            ),
        )
        val state = h.state {
            h.sessionState("ses_away", listOf(user("u1"), tool("t1"), question(OpenCodeToolCall.Status.Running))).noteOpening(seen = setOf("u1", "t1"))
        }
        assertNull(state.newMessages)
        assertEquals(NewMessagesFold(itemID = "msg_a-tool-q1", lastItemID = "msg_a-tool-q1", count = 3), state.newMessagesFolded)
        val store = h.store(state) {
            this[Clock] = TestClock()
        }
        store.send(Action.ContentBatch(sessionID = "ses_away", events = listOf(OpenCodeLiveContentEvent.ReasoningDelta(messageID = "msg_a", ordinal = 0)))) {
            it.copy(
                isExternallyActive = true, streamIsHealthy = true, streamSupportsProjection = true,
                workingRun = WorkingRun(counter = 1, startID = 1, key = "ses_away", sawStart = true, startTurn = "u1", baseline = "msg_a-tool-q1"),
            )
        }
        store.send(
            Action.ContentBatch(
                sessionID = "ses_away",
                events = listOf(OpenCodeLiveContentEvent.ToolSucceeded(messageID = "msg_a", toolID = "q1", output = emptyList(), metadata = emptyMap())),
            ),
        ) {
            it.copy(
                projectedMessageIDs = setOf("msg_a"), lastProjectedMessageID = "msg_a",
                items = it.items.replacing(2, question(OpenCodeToolCall.Status.Completed)),
                newMessagesFolded = null, newMessagesGeneration = 1,
                newMessages = NewMessagesMark(itemID = "msg_a-tool-q1", lastItemID = "msg_a-tool-q1", generation = 1),
            )
        }
        store.receive(Action.RefreshRequested::class) {
            it.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_a")))
        }
        h.skipInFlightEffects()
    }

    /**
     * Away through a long turn, a delta reads none of the transcript again:
     * a row joining the marked span rebuilds no turn, a delta that adds no
     * row rebuilds nothing even when the rows fold into a group.
     */
    @Test fun streamingWhileAwayReadsNoTurnPerDelta() = sessionTest { h ->
        val turn = listOf(user("u1")) + (0 until 300).map { tool("t$it") }
        val marked = NewMessagesMark.Companion.Tally()
        val state = h.state { h.sessionState("ses_away", turn + reply("a1")).let { s -> s.noteOpening(seen = s.items.map { it.id }.toSet()) } }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
        }
        store.exhaustive = false
        NewMessagesMark.withTally(marked) {
            store.send(toolStarted("x0"))
            val placed = marked.count
            assertEquals("placing the mark builds its turn once", turn.size + 2, placed)
            for (index in 1 until 50) {
                store.send(toolStarted("x$index"))
                store.send(toolTyped("x$index"))
            }
            assertEquals("rows under the mark read nothing", placed, marked.count)
        }
        assertEquals("msg_a-tool-x0", store.state.newMessages?.itemID)
        assertEquals("msg_a-tool-x49", store.state.newMessages?.lastItemID)

        val folded = NewMessagesMark.Companion.Tally()
        val folding = h.state { h.sessionState("ses_away", turn).let { s -> s.noteOpening(seen = s.items.map { it.id }.toSet()) } }
        val foldStore = h.store(folding) {
            this[Clock] = ImmediateClock()
        }
        foldStore.exhaustive = false
        NewMessagesMark.withTally(folded) {
            foldStore.send(toolStarted("x0"))
            val found = folded.count
            assertEquals(turn.size + 1, found)
            repeat(50) { foldStore.send(toolTyped("x0")) }
            assertEquals("a delta that adds no row rebuilds nothing", found, folded.count)
        }
        assertNull(foldStore.state.newMessages)
        h.skipInFlightEffects()
    }

    /** Fix 1: An inactive foreground scene (`!isSceneActive`) does not absorb streamed rows as seen. */
    @Test fun inactiveSceneDoesNotAbsorbStreamedRowsAsSeen() = sessionTest { h ->
        val m1 = user("m1")
        val m2 = reply("m2")
        val live = liveRow
        // The return restarts the pending-work poll; its request read stays out.
        val held: suspend () -> OpenCodePendingRequests = { awaitCancellation() }
        val store = h.resumeStore(h.watching(listOf(m1, m2)), pendingRequests = held) { listOf(m1, m2, live) }
        store.exhaustive = true
        store.send(Action.SceneActiveChanged(false)) { it.copy(isSceneActive = false) }
        store.send(liveDelta) {
            it.applyLive().copy(
                liveText = mapOf(liveID to "Streaming reply"),
                items = it.items + live,
                newMessagesBaseline = setOf("m1", "m2"),
                newMessagesGeneration = 1,
                newMessages = NewMessagesMark(itemID = liveID, lastItemID = liveID, generation = 1),
            )
        }
        store.send(Action.SceneActiveChanged(true)) { it.copy(isSceneActive = true) }
        store.send(pageRead(listOf(m1, m2, live))) {
            it.copy(refresh = null, initialTranscriptIsFresh = true, newMessagesBaseline = null)
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_live", items = listOf(m1, m2, live)))
        h.skipInFlightEffects()
    }

    /**
     * The reader's own message, spoken on a call or sent from another
     * client, reads what's above it: the divider goes, as after a send here.
     */
    @Test fun voicePromptAdmittedClearsNewMessagesDivider() = sessionTest { h ->
        val state = h.watching(listOf(user("m1"), reply("m2"), reply("m3")))
            .copy(refresh = null, newMessages = NewMessagesMark(itemID = "m3", lastItemID = "m3", generation = 1))
        val store = h.resumeStore(state) { emptyList() }
        store.send(Action.VoicePromptAdmitted(sessionID = "ses_live"))
        assertNull(store.state.newMessages)

        val remote = h.watching(listOf(user("m1"), reply("m2"), reply("m3")))
            .copy(newMessages = NewMessagesMark(itemID = "m3", lastItemID = "m3", generation = 1))
        val remoteStore = h.resumeStore(remote) { emptyList() }
        remoteStore.send(pageRead(listOf(user("m1"), reply("m2"), reply("m3"), user("m4"))))
        assertNull("their prompt from the desktop", remoteStore.state.newMessages)
        h.skipInFlightEffects()
    }

    /** Truth table #4: Backgrounding freezes the watermark so background streaming is marked New on return. */
    @Test fun backgroundingFreezesWatermarkForForegroundReturn() = sessionTest { h ->
        val m1 = user("m1")
        val m2 = reply("m2")
        val live = liveRow
        // The return restarts the pending-work poll; its request read stays out.
        val held: suspend () -> OpenCodePendingRequests = { awaitCancellation() }
        val store = h.resumeStore(h.watching(listOf(m1, m2)), pendingRequests = held) { listOf(m1, m2, live) }
        store.exhaustive = true
        store.send(Action.SceneActiveChanged(false)) { it.copy(isSceneActive = false) }
        store.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true, newMessagesBaseline = setOf("m1", "m2")) }
        store.send(liveDelta) {
            it.applyLive().copy(
                liveText = mapOf(liveID to "Streaming reply"),
                items = it.items + live,
                newMessagesGeneration = 1,
                newMessages = NewMessagesMark(itemID = liveID, lastItemID = liveID, generation = 1),
            )
        }
        store.send(Action.SceneActiveChanged(true)) { it.copy(isSceneActive = true, isBackgrounded = false, pendingWorkPollSeconds = 12) }
        store.receive(Action.PendingInputsRequested::class)
        store.receive(Action.PendingInputsResponse::class)
        store.send(pageRead(listOf(m1, m2, live))) {
            it.copy(refresh = null, initialTranscriptIsFresh = true, newMessagesBaseline = null)
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_live", items = listOf(m1, m2, live)))
        h.skipInFlightEffects()
    }

    /** Truth table #15: When >80 unseen items overflow the tail window, paging in earlier history recomputes the mark. */
    @Test fun earlierHistoryResponseRecomputesMarkWhenSeenBoundaryPagesIn() = sessionTest { h ->
        val seenItems = listOf(user("m1"), reply("m2"))
        val earlyUnseen = listOf(reply("early1"), reply("early2"))
        val tailWindow = (0 until 80).map { reply("r$it") }
        val olderPage = seenItems + earlyUnseen + reply("r0")
        val merged = seenItems + earlyUnseen + tailWindow
        val state = h.state {
            h.sessionState("ses_overflow", tailWindow).copy(
                isVisible = true,
                hasRenderedFirstContentFrame = true,
                initialTranscriptIsFresh = true,
                earlierHistory = SessionFeature.State.EarlierHistory.Paging(cursor = "cursor_1"),
                earlierHistoryInsertionID = "r0",
                newMessagesBaseline = setOf("m1", "m2"),
            ).updateNewMessagesMark(settles = true)
        }
        assertEquals(NewMessagesMark(itemID = "r0", lastItemID = "r79", generation = 1), state.newMessages)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(historyPage = { _, _, _ -> OpenCodeTranscriptPage(items = olderPage, next = null) })
        }
        store.send(Action.ShowEarlierTapped) {
            it.copy(visibleLimit = it.visibleLimit + 40, earlierHistory = SessionFeature.State.EarlierHistory.Loading(cursor = "cursor_1", reachedLoadedItems = true))
        }
        store.receive(
            Action.EarlierHistoryResponse(
                sessionID = "ses_overflow", generation = 0, cursor = "cursor_1",
                result = Result.success(OpenCodeTranscriptPage(items = olderPage, next = null)),
            ),
        ) {
            it.copy(
                items = merged, earlierHistoryInsertionID = "m1", earlierHistory = SessionFeature.State.EarlierHistory.Exhausted,
                newMessagesGeneration = 2, newMessages = NewMessagesMark(itemID = "early1", lastItemID = "r79", generation = 2),
                newMessagesEarlierBaseline = null,
            )
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_overflow", items = merged))
    }

    /** Fix 6: Streaming batches while waiting on the return snapshot record only the batch's inserted item IDs in O(batch). */
    @Test fun streamingWhileWaitingForReturnSnapshotTracksWatchedInOBatch() = sessionTest { h ->
        val m1 = user("m1")
        val m2 = reply("m2")
        val state = h.watching(listOf(m1, m2)).copy(newMessagesBaseline = setOf("m1", "m2"))
        val batch = listOf(
            OpenCodeLiveContentEvent.TextStarted(messageID = "msg_live", ordinal = 0),
            OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = "Hello"),
            OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = " world"),
            OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_live", toolID = "t1", name = "bash"),
            OpenCodeLiveContentEvent.ToolInputDelta(messageID = "msg_live", toolID = "t1", delta = "ls"),
        )
        val expectedText = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_live-text-0", text = "Hello world", inProgress = true)
        fun bash(input: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
            id = "msg_live-tool-t1",
            call = OpenCodeToolCall(
                callID = "t1", messageID = "msg_live", name = "bash", status = OpenCodeToolCall.Status.Streaming,
                input = OpenCodeJSONValue.Str(input), output = emptyList(), metadata = emptyMap(),
            ),
        )
        val startedTool = bash("")
        val expectedTool = bash("ls")
        val store = h.resumeStore(state) { listOf(m1, m2, expectedText, startedTool) }
        store.exhaustive = true
        store.send(Action.ContentBatch(sessionID = "ses_live", events = batch)) {
            it.applyLive(baseline = "msg_live-tool-t1").copy(
                liveText = mapOf("msg_live-text-0" to "Hello world"),
                items = listOf(m1, m2, expectedText, expectedTool),
                newMessagesWatched = setOf("msg_live-text-0", "msg_live-tool-t1"),
                refresh = it.refresh?.copy(
                    racedEvents = listOf(
                        OpenCodeLiveContentEvent.TextStarted(messageID = "msg_live", ordinal = 0),
                        OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_live", toolID = "t1", name = "bash"),
                        OpenCodeLiveContentEvent.ToolInputDelta(messageID = "msg_live", toolID = "t1", delta = "ls"),
                    ),
                ),
            )
        }
        store.send(pageRead(listOf(m1, m2, expectedText, startedTool))) {
            it.copy(refresh = null, initialTranscriptIsFresh = true, newMessagesBaseline = null, newMessagesWatched = emptySet())
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_live", items = listOf(m1, m2, expectedText, expectedTool)))
        h.skipInFlightEffects()
    }

    // endregion
}
