package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionTimers
import ai.opencode.feature.session.pollsServer
import ai.opencode.feature.session.requestsConnectionIsAvailable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class SessionFeatureTestsQuietPolling {
    @get:Rule val isolation = TestIsolationRule()

    private val testServer = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun testSession(id: String, title: String) =
        OpenCodeSession(id = id, serverTitle = title, projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

    private fun homeState(sessions: List<OpenCodeSession>) =
        HomeFeature.State(testServer, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet()))

    private fun HomeFeature.State.disconnected() =
        copy(connection = connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected))

    /**
     * Every read the session makes is logged and then held open until it's cancelled, so a test
     * sees which reads started.
     */
    private fun DependencyValues.Builder.holdReads(gate: TestClock, log: LockIsolated<List<String>>) {
        suspend fun hold(name: String): Nothing {
            log.append(name)
            try {
                gate.sleep(1.seconds)
            } catch (cancelled: CancellationException) {
                log.append("$name cancelled")
                throw cancelled
            }
            throw OpenCodeClientError(message = "Held")
        }
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            familyPendingRequests = { _, _, _ -> hold("requests") },
            pendingInputs = { _, _ -> hold("inbox") },
            getSession = { _, _ -> hold("session") },
            transcript = { _, _, _ -> hold("transcript") },
        )
    }

    private fun count(name: String, log: LockIsolated<List<String>>): Int {
        var count = 0
        for (entry in log.value) {
            if (entry == name) count += 1
        }
        return count
    }

    /**
     * A screen swapped in while the app is away inherits the background and the inactive scene,
     * so the return restarts its polls and watchdog; so does a backgrounded screen that never saw
     * the scene go inactive.
     */
    @Test
    fun aSessionSwappedInWhileAwayStartsItsTimersOnTheReturn() = homeTest { h ->
        val first = testSession(id = "ses_first", title = "First")
        val second = testSession(id = "ses_second", title = "Second")
        var home = h.state { homeState(listOf(first, second)) }
        val (opened, away) = h.state { home.makeDetail(first, cachedItems = emptyList(), reason = "test") }
        home = opened.copy(sessionDetail = away.copy(isSceneActive = false, isBackgrounded = true))
        var swapped = h.state { home.makeDetail(second, cachedItems = emptyList(), reason = "a notification") }.second
        assertFalse(swapped.isSceneActive)
        assertTrue(swapped.isBackgrounded)
        assertFalse(swapped.pollsServer)

        // The return, and the same for a screen whose scene still reads active.
        for (sceneWasActive in listOf(false, true)) {
            swapped = swapped.copy(isSceneActive = sceneWasActive, hasRenderedFirstContentFrame = true)
            val gate = TestClock()
            val log = LockIsolated<List<String>>(emptyList())
            val store = h.sessionStore(swapped) {
                this[Clock] = TestClock()
                this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
                holdReads(gate, log)
            }
            store.exhaustive = false
            store.send(SessionFeature.Action.SceneActiveChanged(true))
            assertTrue(store.state.pollsServer)
            assertNotNull("sceneWasActive: $sceneWasActive", store.state.pendingWorkPollSeconds)
            assertNotNull("sceneWasActive: $sceneWasActive", store.state.watchdogSchedule)
            assertTrue(eventually { count("requests", log) == 1 })
        }
        h.skipInFlightEffects()
    }

    /**
     * A draft opened while Home can't reach the server hears it's back with Home's first
     * snapshot, so the session it becomes polls from the start.
     */
    @Test
    fun aDraftPromotedAfterAReconnectPolls() = homeTest { h ->
        val session = testSession(id = "ses_new", title = "New")
        var state = h.state { homeState(emptyList()) }.disconnected()
        val (drafted, draft) = h.state { state.makeDraft() }
        state = drafted.copy(sessionDetail = draft)
        assertEquals(false, state.sessionDetail?.requestsConnectionIsAvailable)
        val gate = TestClock()
        val log = LockIsolated<List<String>>(emptyList())
        val store = h.store(state) {
            this[Clock] = TestClock()
            holdReads(gate, log)
            this[OpenCodeClient] = this[OpenCodeClient].copy(homeAttention = { _, _, _ -> emptySet() })
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.SnapshotRefreshed(OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet())))
        store.receiveDetail(SessionFeature.Action.RequestsConnectionChanged::class)
        assertEquals(true, store.state.sessionDetail?.requestsConnectionIsAvailable)

        store.send(detail(SessionFeature.Action.SessionCreated(session)))
        assertEquals(session.id, store.state.sessionDetail?.session?.id)
        assertEquals(2, store.state.sessionDetail?.pendingWorkPollSeconds)
        assertTrue(eventually { count("requests", log) == 1 })
        h.skipInFlightEffects()
    }

    /**
     * A session opened while Home can't reach the server starts quiet, as a cold launch's
     * restored one does; a subagent pushed from it inherits it.
     */
    @Test
    fun aSessionOpenedWhileOfflineStartsWithoutTimedReads() = homeTest { h ->
        val session = testSession(id = "ses_offline", title = "Offline")
        var home = h.state { homeState(listOf(session)) }
        assertTrue(h.state { home.makeDetail(session, cachedItems = emptyList(), reason = "test") }.second.requestsConnectionIsAvailable)
        home = home.disconnected()
        var detail = h.state { home.makeDetail(session, cachedItems = emptyList(), reason = "test") }.second
        assertFalse(detail.requestsConnectionIsAvailable)
        assertFalse(detail.pollsServer)
        detail = detail.copy(isBackgrounded = true)
        home = home.copy(sessionDetail = detail)
        assertTrue(
            "A swap in the background stays in the background",
            h.state { home.makeDetail(session, cachedItems = emptyList(), reason = "test") }.second.isBackgrounded,
        )
    }
}
