package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.SelfViewReceipts
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The `HomeFeature` half: this phone's own read receipts (`POST /view`) and the `session.viewed`
 * echo the event stream drops. A receipt that can't echo any more never swallows a desktop read at
 * the same idle.
 */
class NewMessagesReceiptTests {
    @get:Rule val isolation = TestIsolationRule()

    private val items: List<OpenCodeTranscriptItem> = listOf(
        OpenCodeTranscriptItem.UserText(id = "u1", text = "u1"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1", text = "Reply a1"),
        OpenCodeTranscriptItem.UserText(id = "u2", text = "u2"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a2", text = "Reply a2"),
    )

    private fun session(id: String, updated: Long) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(updated),
        idleMilliseconds = 2_000, viewedMilliseconds = 1_000,
    )

    /** A server whose `/view` answers [view] and whose event stream sends a `session.viewed` for `ses_target` at idle 2000, then ends. */
    private fun fixture(view: Reply): WireFixture = WireFixture(scheme = "http") { request ->
        if (request.path.endsWith("/view")) view
        else Reply.Json(200, "data: {\"type\":\"session.viewed\",\"data\":{\"sessionID\":\"ses_target\",\"idle\":2000}}\n\n")
    }

    /** One connection's events, as the client delivers them. */
    private suspend fun streamedEvents(server: OpenCodeServer, receipts: SelfViewReceipts): List<OpenCodeServerEvent> =
        withDependenciesSuspending({ this[SelfViewReceipts] = receipts }) {
            val events = mutableListOf<OpenCodeServerEvent>()
            try {
                OpenCodeClient.liveValue.events(server).collect { events += it }
            } catch (_: OpenCodeClientError) {
                // The stream ending is its expected terminal error.
            }
            events
        }

    /** [body] in the test's dependencies. */
    private fun <R> on(body: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), body)

    private fun store(server: OpenCodeServer, receipts: SelfViewReceipts, scope: CoroutineScope): TestStore<HomeFeature.State, HomeFeature.Action> {
        val current = session("ses_current", updated = 30)
        val target = session("ses_target", updated = 20)
        val home = on {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(current, target), activeSessionIDs = emptySet()))
            home = home.copy(transcriptCache = mapOf(current.id to emptyList(), target.id to items), unopenedSeen = mapOf(target.id to setOf("u1", "a1")))
            val (next, detail) = home.makeDetail(current, cachedItems = emptyList())
            next.copy(sessionDetail = detail)
        }
        val cached = items
        val live = OpenCodeClient.liveValue
        return TestStore(home, HomeFeature(), scope) {
            this[SelfViewReceipts] = receipts
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient { Instant.ofEpochSecond(100) }
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                transcript = { _, _, _ -> cached },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                getSession = { _, id -> OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10)) },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
                pendingInputs = { _, _ -> emptyList() },
                contextUsage = { _, _ -> OpenCodeContextReading.Inconclusive },
                viewSession = live.viewSession,
            )
        }
    }

    /** Run with the store's dependencies, so the detail's shared values are the store's own. */
    private fun TestStore<HomeFeature.State, HomeFeature.Action>.select(id: String, state: HomeFeature.State): HomeFeature.State = withDependencies {
        val session = state.sessions.first { it.id == id }
        val s = state.updatingLastViewedSessionIDs { it + (state.server.persistenceID to session.id) }.markingReadLocally(session)
        val (next, detail) = s.makeDetail(session, cachedItems = items)
        next.copy(sessionDetail = detail)
    }

    /** Real time, as the fixture answers on OkHttp's threads (virtual time would time the receive out). */
    private fun wireTest(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    /** Opening sends a receipt that fails: nothing will echo, so the desktop's read at the same idle still clears New. */
    @Test
    fun aFailedReceiptLeavesADesktopReadAtTheSameIdleToClearNew() = wireTest { scope ->
        val receipts = SelfViewReceipts()
        val wire = fixture(Reply.status(500))
        try {
            val store = store(wire.server, receipts, scope)
            store.send(HomeFeature.Action.SessionTapped("ses_target")) { store.select("ses_target", it) }
            store.finish(timeout = 5.seconds)
            assertEquals(NewMessagesMark(itemID = "a2", lastItemID = "a2", generation = 1), store.state.sessionDetail?.newMessages)
            assertEquals(0, receipts.pendingCount)
            val events = streamedEvents(wire.server, receipts)
            assertEquals(listOf(OpenCodeServerEvent.SessionViewChanged(sessionID = "ses_target", idle = 2_000)), events)
            store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(sessionID = "ses_target", idle = 2_000))) {
                store.withDependencies { it.clearingUnseenBaseline("ses_target") }
            }
            assertNull(store.state.sessionDetail?.newMessages)
        } finally {
            wire.close()
        }
    }

    /** The receipt landed, then the stream reconnected: its echo went out on the stream that dropped, so a read on the new one is the desktop's. */
    @Test
    fun aReconnectDropsAnAnsweredReceiptSoTheNextReadClearsNew() = wireTest { scope ->
        val receipts = SelfViewReceipts()
        val wire = fixture(Reply.status(204))
        try {
            val store = store(wire.server, receipts, scope)
            store.send(HomeFeature.Action.SessionTapped("ses_target")) { store.select("ses_target", it) }
            store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = "ses_target", idle = 2_000), timeout = 5.seconds)
            assertEquals(NewMessagesMark(itemID = "a2", lastItemID = "a2", generation = 1), store.state.sessionDetail?.newMessages)
            assertEquals(1, receipts.pendingCount)
            val events = streamedEvents(wire.server, receipts)
            assertEquals(0, receipts.pendingCount)
            assertEquals(listOf(OpenCodeServerEvent.SessionViewChanged(sessionID = "ses_target", idle = 2_000)), events)
            store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionViewChanged(sessionID = "ses_target", idle = 2_000))) {
                store.withDependencies { it.clearingUnseenBaseline("ses_target") }
            }
            assertNull(store.state.sessionDetail?.newMessages)
        } finally {
            wire.close()
        }
    }

}
