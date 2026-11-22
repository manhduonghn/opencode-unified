package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.session.RefreshReason
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.from
import ai.opencode.feature.session.updateNewMessagesMark
import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.feature.transcript.SessionSeenWatermarks
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.feature.transcript.sessionSeenWatermarks
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.SelfViewReceipts
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeSessionFamily
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NewMessagesTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")

    /** [body] in the test's dependencies. */
    private fun <R> on(body: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), body)

    private fun session(id: String) = OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10))
    private fun user(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.UserText(id = id, text = id)
    private fun reply(id: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = "Reply $id")

    @Test
    fun switchingBackMarksWhatArrivedSince() {
        val first = session("first")
        val second = session("second")
        val back = on {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(first, second), activeSessionIDs = emptySet()))
            home = home.openingDetail(first, cachedItems = listOf(user("m1"), reply("m2")))
            home = home.openingDetail(second, cachedItems = listOf(user("s1")))
            home.makeDetail(first, cachedItems = listOf(user("m1"), reply("m2"), reply("m3"))).second
        }
        assertEquals("m3", back.newMessages?.itemID)
    }

    @Test
    fun theDrawerReadsLikelySessionsBeforeATap() = runTest {
        val open = session("open")
        val other = session("other")
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, other), activeSessionIDs = emptySet()))
        state = state.copy(transcriptCache = mapOf(other.id to listOf(user("o1"))))
        state = state.openingDetail(open, cachedItems = listOf(user("m1")))
        val reads = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[DateClient] = DateClient { Instant.ofEpochSecond(100) }
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(transcript = { _, id, _ ->
                reads += id
                listOf(OpenCodeTranscriptItem.UserText(id = "o1", text = "o1"), OpenCodeTranscriptItem.UserText(id = "o2", text = "o2"))
            })
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SessionDrawerArrived(isOpen = true))
        store.receive({ it is HomeFeature.Action.CachedTranscriptRefreshed }, "cachedTranscriptRefreshed")
        assertEquals(listOf("other"), reads.toList())
        assertEquals(listOf("o1", "o2"), store.state.transcriptCache[other.id]?.map { it.id })
        // Just read: a press doesn't read it again.
        store.send(HomeFeature.Action.DrawerRowPressed(other.id))
        assertEquals(listOf("other"), reads.toList())
        // The open session is the screen's own to read.
        store.send(HomeFeature.Action.DrawerRowPressed(open.id))
        assertEquals(listOf("other"), reads.toList())
    }

    @Test
    fun leavingTheAppHoldsTheStreamUntilTheReturn() = runTest {
        val held = Collections.synchronizedList(mutableListOf<Duration>())
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
            .let { it.copy(connection = it.connection.copy(isEventStreamLive = true)) }
        val store = TestStore(state, HomeFeature(), this) {
            this[BackgroundTimeClient] = BackgroundTimeClient { _, seconds -> held += seconds }
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SceneEnteredBackground)
        store.finish()
        assertEquals(listOf(HomeFeature.liveUpdatesAfterLeaving), held.toList())
    }

    // region The read watermarks, Catch Up and the covered screen

    /** [body] in this test's world. */
    private fun <R> Harness.on(body: () -> R): R = state(body)

    /** An expected-state update run in this test's world. */
    private fun <S> Harness.expect(update: (S) -> S): (S) -> S = { s -> on { update(s) } }

    private val liveID = "msg_live-text-0"
    private val liveDelta: HomeFeature.Action
        get() = detail(
            SessionFeature.Action.ContentBatch(
                sessionID = "ses_live",
                events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = "Streaming reply")),
            ),
        )

    private fun agent(id: String): OpenCodeTranscriptItem =
        OpenCodeTranscriptItem.UserText(id = id, text = id, source = OpenCodePromptSource.Session(id = "ses_other"))

    private fun child(): OpenCodeSession =
        OpenCodeSession(id = "child", serverTitle = "child", projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10), parentID = "ses_live")

    private fun home(vararg sessions: OpenCodeSession): HomeFeature.State =
        HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions.toList(), activeSessionIDs = emptySet()))

    /** A server that answers every read a screen makes with [transcript]. */
    private fun DependencyValues.Builder.stub(transcript: () -> List<OpenCodeTranscriptItem>) {
        this[Clock] = ImmediateClock()
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
        this[UuidClient] = UuidClient.incrementing()
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            transcript = { _, _, _ -> transcript() },
            message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            getSession = { _, id -> OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10)) },
            sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
            runningShells = { _, _ -> emptyList() },
            modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            pendingInputs = { _, _ -> emptyList() },
            contextUsage = { _, _ -> OpenCodeContextReading.Inconclusive },
            viewSession = { _, _, _ -> },
        )
    }

    @Test fun coveredBySubagentOrBrowserMarksLeavingAndRestoresDividerOnPop() = homeTest { h ->
        val root = session("ses_live")
        val child = child()
        var home = h.on { home(root).openingDetail(root, cachedItems = listOf(user("m1"))) }
        home = home.updatingDetail {
            it.copy(
                familySessions = listOf(root, child),
                familyTranscriptCache = it.familyTranscriptCache + (child.id to listOf(user("c1"))),
                isVisible = true,
                hasRenderedFirstContentFrame = true,
                // Its open read settled: in front of the reader, nothing's pending.
                newMessagesBaseline = null,
            )
        }
        val store = h.store(home) { stub { emptyList() } }
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.SubagentTapped("child")))
        store.receive({ it is HomeFeature.Action.SessionDetail && (it.action as? PresentationAction.Presented)?.action is SessionFeature.Action.Delegate }, "openSubagent")
        assertEquals(1, store.state.subagentStack.size)
        assertEquals("covered is away", setOf("m1"), store.state.sessionDetail?.newMessagesBaseline)
        store.send(liveDelta)
        assertEquals(liveID, store.state.sessionDetail?.newMessages?.itemID)
        store.send(HomeFeature.Action.SubagentsPopped(depth = 0))
        assertTrue(store.state.subagentStack.isEmpty())
        assertEquals("back in front of what arrived", liveID, store.state.sessionDetail?.newMessages?.itemID)
        assertNull(store.state.sessionDetail?.newMessagesBaseline)
        h.skipInFlightEffects()
    }

    /** Switching in place to another session of the family compares with what the reader saw there last. */
    @Test fun pickingAFamilySessionMarksWhatArrivedSinceItWasSeen() = homeTest { h ->
        val root = session("ses_live")
        val child = child()
        var home = home(root)
        val seenChild = h.state {
            SessionFeature.State.session(server, child, cachedItems = listOf(user("c1"), reply("c2"))).copy(familySessions = listOf(root, child))
        }
        home = home.copy(sessionSnapshots = mapOf(child.id to checkNotNull(SessionFeature.SwitchSnapshot.from(seenChild))), sessionSnapshotOrder = listOf(child.id))
        home = h.on { home.openingDetail(root, cachedItems = listOf(user("m1"))) }
        home = home.updatingDetail {
            it.copy(
                familySessions = listOf(root, child),
                familyTranscriptCache = it.familyTranscriptCache + (child.id to listOf(user("c1"), reply("c2"), reply("c3"))),
            )
        }
        val store = h.store(home) { stub { emptyList() } }
        store.exhaustive = false
        store.send(detail(SessionFeature.Action.FamilyPicked("child")))
        assertEquals("child", store.state.sessionDetail?.session?.id)
        assertEquals("c3", store.state.sessionDetail?.newMessages?.itemID)
        h.skipInFlightEffects()
    }

    /** First open this run: the copy it would have opened with is what's compared, even when a background read replaced it before the tap. */
    @Test fun aReadBeforeTheFirstTapStillMarksWhatItBrought() = homeTest { h ->
        val open = session("open")
        val other = session("other")
        var state = home(open, other).copy(transcriptCache = mapOf(other.id to listOf(user("o1"))))
        state = h.on { state.openingDetail(open, cachedItems = listOf(user("m1"))) }
        val store = h.store(state) {
            stub { listOf(OpenCodeTranscriptItem.UserText(id = "o1", text = "o1"), OpenCodeTranscriptItem.AssistantMarkdown(id = "o2", text = "o2")) }
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.DrawerRowPressed(other.id))
        store.receive({ it is HomeFeature.Action.CachedTranscriptRefreshed }, "cachedTranscriptRefreshed")
        assertEquals(listOf("o1", "o2"), store.state.transcriptCache[other.id]?.map { it.id })
        store.send(HomeFeature.Action.SessionTapped(other.id))
        assertEquals(other.id, store.state.sessionDetail?.session?.id)
        assertEquals("o2", store.state.sessionDetail?.newMessages?.itemID)
        h.skipInFlightEffects()
    }

    /** A Catch Up card kept unread leaves what it showed new: opened later, the session marks it again. */
    @Test fun catchUpKeepUnreadPreservesUnseenBaselineForLaterOpen() = homeTest { h ->
        fun unread(id: String, updated: Long, idle: Long = 2_000) = OpenCodeSession(
            id = id, serverTitle = id, projectID = "project", directory = "/project",
            updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = idle, viewedMilliseconds = 1_000,
        )
        val newer = unread("newer", updated = 20)
        val older = unread("older", updated = 10)
        val read = unread("read", updated = 40, idle = 1_000)
        val state = h.on {
            var home = home(older, read, newer)
            val seen = SessionFeature.State.session(server, newer, cachedItems = listOf(user("n1")))
            home = home.copy(
                sessionSnapshots = mapOf(newer.id to checkNotNull(SessionFeature.SwitchSnapshot.from(seen))),
                sessionSnapshotOrder = listOf(newer.id),
                transcriptCache = mapOf(newer.id to listOf(user("n1")), older.id to emptyList()),
            )
            home.openingDetail(read, cachedItems = emptyList())
        }
        val store = h.store(state) {
            stub { listOf(OpenCodeTranscriptItem.UserText(id = "n1", text = "n1"), OpenCodeTranscriptItem.AssistantMarkdown(id = "n2", text = "Reply n2")) }
            this[OpenCodeClient] = this[OpenCodeClient].copy(homeAttention = { _, _, _ -> emptySet() })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.Tapped))
        assertEquals(newer.id, store.state.sessionDetail?.session?.id)
        assertEquals(setOf("n1"), store.state.sessionDetail?.newMessagesBaseline)
        // The card in front of the reader reads what arrived.
        store.send(detail(SessionFeature.Action.VisibilityChanged(true)))
        store.send(detail(SessionFeature.Action.RefreshRequested(RefreshReason.Open)))
        store.receive(
            { (((it as? HomeFeature.Action.SessionDetail)?.action as? PresentationAction.Presented)?.action is SessionFeature.Action.RefreshResponse) },
            "refreshResponse",
        )
        assertEquals("n2", store.state.sessionDetail?.newMessages?.itemID)
        store.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.CardDismissed(newer.id, CatchUpFeature.Disposition.keepUnread)))
        assertEquals(older.id, store.state.sessionDetail?.session?.id)
        assertEquals(setOf("n1"), store.state.sessionSnapshots[newer.id]?.seenItemIDs)
        store.send(HomeFeature.Action.SessionTapped(newer.id))
        assertEquals("n2", store.state.sessionDetail?.newMessages?.itemID)
        h.skipInFlightEffects()
    }

    private fun timedID(milliseconds: Long, counter: Long = 1): String {
        val value = (milliseconds * 0x1000 + counter) and 0xFFFF_FFFF_FFFFL
        return "msg_" + "%012x".format(value) + "NewMessagesFix"
    }

    private fun timedSession(id: String, updated: Long = 20, idle: Long = 2_000, viewed: Long = 1_000) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "project", directory = "/project",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = idle, viewedMilliseconds = viewed,
    )

    private fun HomeFeature.State.selecting(id: String, cached: List<OpenCodeTranscriptItem>): HomeFeature.State {
        val session = sessions.first { it.id == id }
        val marked = updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }.markingReadLocally(session)
        val (home, detail) = marked.makeDetail(session, cachedItems = cached)
        return home.copy(sessionDetail = detail)
    }

    /** Truth table #1 & #3: first open of an unread session seeds the unseen boundary from `viewedMilliseconds`. */
    @Test fun firstOpenOfUnreadSessionSeedsBaselineFromViewedMilliseconds() = homeTest { h ->
        val u1 = user(timedID(900))
        val a1 = reply(timedID(1_000))
        val ag2 = agent(timedID(1_500))
        val a2 = reply(timedID(2_000))
        val items = listOf(u1, a1, ag2, a2)
        val current = timedSession("ses_open", updated = 30, idle = 1_000)
        val unread = timedSession("ses_unread")
        val opened = h.on {
            val home = home(current, unread).markingReadLocally(unread)
            home.makeDetail(unread, externalTranscript = true).second
        }
        val store = h.sessionStore(opened) { stub { items } }
        store.send(SessionFeature.Action.TranscriptResponse(sessionID = unread.id, result = Result.success(items)), h.expect {
            it.copy(
                initialTranscriptRequestIsExternal = false, initialTranscriptIsFresh = true, isLoading = false,
                items = items, newMessagesGeneration = 1,
                newMessages = NewMessagesMark(itemID = ag2.id, lastItemID = a2.id, generation = 1),
            )
        })
        store.receive(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = unread.id, items = items))
        store.send(SessionFeature.Action.VisibilityChanged(true), h.expect {
            it.copy(isVisible = true, newMessagesBaseline = null, newMessagesBaselineTimestamp = null, newMessagesUnread = false)
        })
    }

    /** Truth table #2: cold launch with a session open on screen marks replies that arrived while terminated. */
    @Test fun coldLaunchMarksRepliesThatArrivedWhileTerminated() = homeTest { h ->
        val u1 = user(timedID(900))
        val a1 = reply(timedID(1_000))
        val a2 = reply(timedID(2_000))
        val remembered = timedSession("ses_cold")
        h.setShared(SharedKeys.lastViewedSessionIDs, mapOf(server.persistenceID to remembered.id))
        h.setShared(
            SharedKeys.sessionSeenWatermarks,
            SessionSeenWatermarks().setting(SessionSeenWatermark(itemID = a1.id, viewedMilliseconds = 1_000), "${server.persistenceID}|${remembered.id}"),
        )
        val detail = h.on {
            val cold = home(remembered)
            checkNotNull(cold.sessionDetail).copy(
                isVisible = true, hasRenderedFirstContentFrame = true, nextRefreshID = 1,
                refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open),
            )
        }
        val store = h.sessionStore(detail) { stub { listOf(u1, a1, a2) } }
        val refreshed = SessionFeature.AuthoritativeRefresh(observed = remembered, items = listOf(u1, a1, a2), scope = SessionFeature.AuthoritativeRefresh.Scope.Page)
        store.send(SessionFeature.Action.RefreshResponse(sessionID = remembered.id, requestID = 1, result = Result.success(refreshed)), h.expect {
            it.copy(
                refresh = null, isLoading = false, initialTranscriptIsFresh = true, items = listOf(u1, a1, a2),
                newMessagesBaseline = null, newMessagesBaselineTimestamp = null, newMessagesUnread = false,
                newMessagesGeneration = 1, newMessages = NewMessagesMark(itemID = a2.id, lastItemID = a2.id, generation = 1),
                session = remembered,
            )
        })
        store.receive(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = remembered.id, items = listOf(u1, a1, a2)))
        store.receive(SessionFeature.Action.ViewAcknowledged(sessionID = remembered.id, idle = 2_000), update = h.expect {
            it.copy(viewedIdle = it.viewedIdle + (remembered.id to 2_000L), session = it.session?.copy(viewedMilliseconds = 2_000))
        })
    }

    @Test fun readAndUnreadActionsAlignWithNewMessagesDivider() = homeTest { h ->
        val current = timedSession("ses_current", updated = 30, idle = 1_000)
        val target = timedSession("ses_target")
        val items = listOf(user("u1"), reply("a1"), user("u2"), reply("a2"))
        val home = h.on {
            val home = home(current, target).copy(
                transcriptCache = mapOf(current.id to listOf(user("c1")), target.id to items),
                unopenedSeen = mapOf(target.id to setOf("u1", "a1")),
            )
            val (made, detail) = home.makeDetail(current, cachedItems = listOf(user("c1")))
            made.copy(sessionDetail = detail)
        }
        val store = h.store(home) { stub { items } }
        fun viewed(s: HomeFeature.State) = s.sessions[1].copy(viewedMilliseconds = 2_000)
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(sessionID = target.id, idle = 2_000)), h.expect {
            it.copy(sessions = it.sessions.toMutableList().also { list -> list[1] = viewed(it) }).clearingUnseenBaseline(target.id)
        })
        store.send(HomeFeature.Action.SessionTapped(target.id), h.expect { it.selecting(target.id, items) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = target.id, idle = 2_000))
        assertNull(store.state.sessionDetail?.newMessages)
        store.send(HomeFeature.Action.SessionTapped(current.id), h.expect { it.selecting(current.id, listOf(user("c1"))) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = current.id, idle = 1_000))
        store.send(HomeFeature.Action.MarkUnread(target.id), h.expect { it.markingUnreadLocally(it.sessions[1]) })
        store.send(HomeFeature.Action.SessionTapped(target.id), h.expect { it.selecting(target.id, items) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = target.id, idle = 2_000))
        assertEquals(NewMessagesMark(itemID = "a2", lastItemID = "a2", generation = 1), store.state.sessionDetail?.newMessages)
        store.send(HomeFeature.Action.SessionTapped(current.id), h.expect { it.selecting(current.id, listOf(user("c1"))) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = current.id, idle = 1_000))
        store.send(HomeFeature.Action.MarkUnread(target.id), h.expect { it.markingUnreadLocally(it.sessions[1]) })
        store.send(HomeFeature.Action.MarkRead(target.id), h.expect { it.markingReadLocally(it.sessions[1]).clearingUnseenBaseline(target.id) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = target.id, idle = 2_000))
        store.send(HomeFeature.Action.SessionTapped(target.id), h.expect { it.selecting(target.id, items) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = target.id, idle = 2_000))
        assertNull(store.state.sessionDetail?.newMessages)
    }

    /**
     * Catch Up Keep Unread preserves the divider even when the transcript first loaded inside Catch
     * Up.
     */
    @Test fun catchUpKeepUnreadWithoutPriorCacheStillMarksDividerOnLaterOpen() = homeTest { h ->
        val older = timedSession("ses_older", updated = 10)
        val read = timedSession("ses_read", updated = 30, idle = 1_000)
        val unread = timedSession("ses_catch", updated = 20)
        val items = listOf(user(timedID(900)), reply(timedID(1_000)), user(timedID(1_500)), reply(timedID(2_000)))
        val home = h.on {
            val home = home(older, read, unread).copy(
                transcriptCache = mapOf(read.id to listOf(user(timedID(900))), unread.id to emptyList(), older.id to emptyList()),
            )
            val (made, detail) = home.makeDetail(read, cachedItems = listOf(user(timedID(900))))
            made.copy(sessionDetail = detail)
        }
        val store = h.store(home) { stub { items } }
        store.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.Tapped), h.expect {
            it.copy(isDrawerOpen = false)
                .withCatchUp(CatchUpFeature.Stack(queue = listOf(unread.id, older.id), returnSessionID = read.id, returnComposer = ai.opencode.feature.composer.DraftState()))
                .openingCatchUpCard().first
        })
        val mark = NewMessagesMark(itemID = items[3].id, lastItemID = items[3].id, generation = 1)
        store.send(detail(SessionFeature.Action.TranscriptResponse(sessionID = unread.id, result = Result.success(items))), h.expect {
            it.updatingDetail { d -> d.copy(isLoading = false, items = items, newMessagesGeneration = 1, newMessages = mark) }
                .publishingTranscript(unread.id, items)
                .persistingTranscriptCache(including = unread.id)
                .let { s -> s.copy(transcriptCache = s.transcriptCache + (unread.id to emptyList())) }
        })
        store.receive(detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = unread.id, items = items)), update = h.expect {
            it.publishingTranscript(unread.id, items)
        })
        store.send(HomeFeature.Action.CatchUp(CatchUpFeature.Action.CardDismissed(unread.id, CatchUpFeature.Disposition.keepUnread)), h.expect {
            val dismissed = it.withCatchUp(it.catchUp!!.dismissFront(unread, CatchUpFeature.Disposition.keepUnread, neededAttention = false))
            val opened = dismissed.openingCatchUpCard().first
            opened.copy(sessionSnapshots = opened.sessionSnapshots + (unread.id to opened.sessionSnapshots.getValue(unread.id).copy(seenItemIDs = emptySet())))
        })
        store.send(HomeFeature.Action.SessionTapped(unread.id), h.expect { it.leavingCatchUp().selecting(unread.id, items) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = unread.id, idle = 2_000))
        assertEquals(mark, store.state.sessionDetail?.newMessages)
    }

    /**
     * Fix 2: desktop `session.viewed` clears a stale New divider on the open session even when opening already set
     * the session's viewed idle, while `OpenCodeClient` consumes only this app's own `POST /view` echo.
     */
    @Test fun desktopSessionViewedClearsDividerOnOpenSession() = homeTest { h ->
        val open = timedSession("ses_open", updated = 20, idle = 2_000, viewed = 1_000)
        val items = listOf(user("u1"), reply("a1"), user("u2"), reply("a2"))
        val receipts = SelfViewReceipts()
        receipts.answered(receipts.begin(serverID = server.persistenceID, sessionID = open.id, idle = 2_000))
        assertTrue(receipts.consumeEcho(serverID = server.persistenceID, sessionID = open.id, idle = 2_000))
        assertTrue(!receipts.consumeEcho(serverID = server.persistenceID, sessionID = open.id, idle = 2_000))
        val home = h.on {
            val home = home(open).copy(transcriptCache = mapOf(open.id to items)).markingReadLocally(open)
            val (made, detail) = home.makeDetail(open, cachedItems = items)
            made.copy(
                sessionDetail = detail.copy(
                    newMessagesBaseline = null, newMessagesBaselineTimestamp = null, newMessagesUnread = false,
                    newMessagesGeneration = 1, newMessages = NewMessagesMark(itemID = "a2", lastItemID = "a2", generation = 1),
                ),
            )
        }
        val store = h.store(home) { stub { items } }
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(sessionID = open.id, idle = 2_000)), h.expect {
            it.clearingUnseenBaseline(open.id)
        })
        assertNull(store.state.sessionDetail?.newMessages)
    }

    /** Fix 3: on relaunch, a newer server `viewedMilliseconds` overrides an older persisted local watermark. */
    @Test fun newerServerViewedWatermarkOverridesStalePersistedWatermarkOnRelaunch() = homeTest { h ->
        val u1 = user(timedID(900))
        val a1 = reply(timedID(1_000))
        val a2 = reply(timedID(2_000))
        val a3 = reply(timedID(3_000))
        val fullyReadOnDesktop = timedSession("ses_desktop_read", updated = 20, idle = 2_000, viewed = 2_000)
        val partiallyReadOnDesktop = timedSession("ses_desktop_partial", updated = 30, idle = 3_000, viewed = 2_000)
        h.setShared(SharedKeys.lastViewedSessionIDs, mapOf(server.persistenceID to fullyReadOnDesktop.id))
        h.setShared(
            SharedKeys.sessionSeenWatermarks,
            SessionSeenWatermarks()
                .setting(SessionSeenWatermark(itemID = a1.id, viewedMilliseconds = 1_000), "${server.persistenceID}|${fullyReadOnDesktop.id}")
                .setting(SessionSeenWatermark(itemID = a1.id, viewedMilliseconds = 1_000), "${server.persistenceID}|${partiallyReadOnDesktop.id}"),
        )
        val coldState = h.on { home(fullyReadOnDesktop, partiallyReadOnDesktop) }
        val detail = checkNotNull(coldState.sessionDetail).copy(
            isVisible = true, hasRenderedFirstContentFrame = true, nextRefreshID = 1,
            refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open),
        )
        val store = h.sessionStore(detail) { stub { listOf(u1, a1, a2) } }
        val refreshed = SessionFeature.AuthoritativeRefresh(observed = fullyReadOnDesktop, items = listOf(u1, a1, a2), scope = SessionFeature.AuthoritativeRefresh.Scope.Page)
        store.send(SessionFeature.Action.RefreshResponse(sessionID = fullyReadOnDesktop.id, requestID = 1, result = Result.success(refreshed)), h.expect {
            it.copy(refresh = null, isLoading = false, initialTranscriptIsFresh = true, items = listOf(u1, a1, a2), session = fullyReadOnDesktop)
        })
        store.receive(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = fullyReadOnDesktop.id, items = listOf(u1, a1, a2)))
        assertNull(store.state.newMessages)
        val partialDetail = h.on {
            coldState.markingReadLocally(partiallyReadOnDesktop).makeDetail(partiallyReadOnDesktop, cachedItems = listOf(u1, a1, a2, a3)).second
        }
        assertEquals(NewMessagesMark(itemID = a3.id, lastItemID = a3.id, generation = 1), partialDetail.newMessages)
    }

    /** Fix 4: switching away or leaving while an unread divider is up keeps the unread boundary across switches and cold relaunches. */
    @Test fun leavingOrSwitchingAwayWithUnreadDividerKeepsUnreadBoundary() = homeTest { h ->
        val first = timedSession("ses_first", updated = 20, idle = 2_000, viewed = 1_000)
        val second = timedSession("ses_second", updated = 30, idle = 1_000, viewed = 1_000)
        val items = listOf(user("u1"), reply("a1"), reply("a2"))
        val home = h.on {
            val home = home(first, second).copy(transcriptCache = mapOf(first.id to items, second.id to listOf(user("s1")))).markingReadLocally(first)
            val (made, opened) = home.makeDetail(first, cachedItems = items)
            val detail = opened.copy(isVisible = true, hasRenderedFirstContentFrame = true, newMessagesBaseline = setOf("u1", "a1"))
                .updateNewMessagesMark(settles = true)
            assertNull(detail.newMessagesBaseline)
            assertEquals(NewMessagesMark(itemID = "a2", lastItemID = "a2", generation = 2), detail.newMessages)
            made.copy(sessionDetail = detail)
        }
        val store = h.store(home) { stub { items } }
        store.send(HomeFeature.Action.SessionTapped(second.id), h.expect { it.selecting(second.id, listOf(user("s1"))) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = second.id, idle = 1_000))
        assertEquals(setOf("u1", "a1"), store.state.sessionSnapshots[first.id]?.seenItemIDs)
        assertEquals(
            SessionSeenWatermark(itemID = "a1", viewedMilliseconds = null, acknowledgedViewedMilliseconds = 2_000),
            store.state.sessionSeenWatermarks[store.state.sessionKey(first.id)],
        )
        store.send(HomeFeature.Action.SessionTapped(first.id), h.expect { it.selecting(first.id, items) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = first.id, idle = 2_000))
        assertEquals(NewMessagesMark(itemID = "a2", lastItemID = "a2", generation = 1), store.state.sessionDetail?.newMessages)
    }

    /** Fix 4b: a cold relaunch after backgrounding with an unread divider keeps the pre-divider boundary despite the opening receipt. */
    @Test fun relaunchingWithUnreadDividerPreservesBoundaryDespiteOpenReceipt() = homeTest { h ->
        val u1 = user(timedID(900))
        val a1 = reply(timedID(1_000))
        val a2 = reply(timedID(2_000))
        val items = listOf(u1, a1, a2)
        val opened = timedSession("ses_relaunch_unread", updated = 20, idle = 2_000, viewed = 1_000)
        val afterReceipt = timedSession("ses_relaunch_unread", updated = 20, idle = 2_000, viewed = 2_000)
        var home = h.on { home(opened).copy(transcriptCache = mapOf(opened.id to items)).markingReadLocally(opened) }
        val (made, made2) = h.on { home.makeDetail(opened, cachedItems = items) }
        home = made
        val detail = made2.copy(
            isVisible = true, hasRenderedFirstContentFrame = true, newMessagesBaseline = null,
            newMessagesBaselineTimestamp = 1_000, newMessagesUnread = false, newMessagesGeneration = 1,
            newMessages = NewMessagesMark(itemID = a2.id, lastItemID = a2.id, generation = 1),
        )
        val leavingStore = h.sessionStore(detail) { stub { items } }
        leavingStore.send(SessionFeature.Action.SceneActiveChanged(false), h.expect { it.copy(isSceneActive = false) })
        leavingStore.send(SessionFeature.Action.SceneEnteredBackground, h.expect {
            it.copy(isBackgrounded = true, newMessages = null, newMessagesBaseline = setOf(u1.id, a1.id), newMessagesBaselineTimestamp = null)
        })
        home = h.on { home.copy(sessionDetail = leavingStore.state).syncingSeenItemsFromOpenDetail(opened.id) }
        assertEquals(
            SessionSeenWatermark(itemID = a1.id, viewedMilliseconds = null, acknowledgedViewedMilliseconds = 2_000),
            home.sessionSeenWatermarks[home.sessionKey(opened.id)],
        )
        h.setShared(SharedKeys.lastViewedSessionIDs, mapOf(server.persistenceID to afterReceipt.id))
        val coldDetail = checkNotNull(h.on { home(afterReceipt) }.sessionDetail).copy(
            isVisible = true, hasRenderedFirstContentFrame = true, nextRefreshID = 1,
            refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open),
        )
        val store = h.sessionStore(coldDetail) { stub { items } }
        val refreshed = SessionFeature.AuthoritativeRefresh(observed = afterReceipt, items = items, scope = SessionFeature.AuthoritativeRefresh.Scope.Page)
        store.send(SessionFeature.Action.RefreshResponse(sessionID = afterReceipt.id, requestID = 1, result = Result.success(refreshed)), h.expect {
            it.copy(
                refresh = null, isLoading = false, initialTranscriptIsFresh = true, items = items, session = afterReceipt,
                newMessagesBaseline = null, newMessagesBaselineTimestamp = null, newMessagesUnread = false,
                newMessagesGeneration = 1, newMessages = NewMessagesMark(itemID = a2.id, lastItemID = a2.id, generation = 1),
            )
        })
        store.receive(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = afterReceipt.id, items = items))
    }

    /** Fix 5: persisted watermarks store one compact entry per session, evict the least recent past the cap, and purge on delete. */
    @Test fun persistedSeenWatermarksStoreCompactEntryAndEvictLRUAndDeletedSessions() = homeTest { h ->
        val s1 = timedSession("ses_1", updated = 10, idle = 1_000, viewed = 1_000)
        val s2 = timedSession("ses_2", updated = 20, idle = 2_000, viewed = 2_000)
        val home = h.on {
            val home = home(s1, s2).copy(transcriptCache = mapOf(s1.id to listOf(user("u1"), reply("a1"), reply("a2")), s2.id to listOf(user("u2"))))
            var (made, detail) = home.makeDetail(s2, cachedItems = listOf(user("u2")))
            made = made.copy(sessionDetail = detail)
            for (index in 0 until SessionSeenWatermarks.maximumEntries) {
                made = made.settingSeenWatermark(SessionSeenWatermark(itemID = "item_$index", viewedMilliseconds = (index + 1).toLong()), "evict_$index")
            }
            made
        }
        assertEquals(SessionSeenWatermarks.maximumEntries, home.sessionSeenWatermarks.order.size)
        val store = h.store(home) { stub { emptyList() } }
        store.send(HomeFeature.Action.MarkRead(s1.id), h.expect { it.markingReadLocally(s1).clearingUnseenBaseline(s1.id) })
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = s1.id, idle = 1_000))
        assertEquals(SessionSeenWatermarks.maximumEntries, store.state.sessionSeenWatermarks.order.size)
        assertNull(store.state.sessionSeenWatermarks[store.state.sessionKey("evict_0")])
        assertEquals(SessionSeenWatermark(itemID = "a2", viewedMilliseconds = 1_000), store.state.sessionSeenWatermarks[store.state.sessionKey(s1.id)])
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = s1.id)), h.expect {
            it.purgingSession(s1.id).persistingSnapshot(Instant.EPOCH)
        })
        assertNull(store.state.sessionSeenWatermarks[store.state.sessionKey(s1.id)])
    }

    // endregion
}
