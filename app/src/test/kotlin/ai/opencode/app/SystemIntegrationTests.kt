package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.openingDraft
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.intents.controlTarget
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

private val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
private val otherServer = OpenCodeServer(url = "https://other.example", username = "kit", password = "test")
private val serverKey = SessionDirectory.serverKey(server.persistenceID)
private val project = OpenCodeProject(id = "prj_app", name = "app", directory = "/work/app")
private val docs = OpenCodeProject(id = "prj_docs", name = "docs", directory = "/work/docs")
private val older = OpenCodeSession(id = "ses_older", serverTitle = "Older work", projectID = "prj_docs", directory = "/work/docs", updatedAt = Instant.ofEpochSecond(100))
private val newer = OpenCodeSession(id = "ses_newer", serverTitle = "Fix login", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(300))

private val hangingTranscript: suspend (OpenCodeServer, String, Int) -> List<OpenCodeTranscriptItem> = { _, _, _ -> awaitCancellation() }

private fun opened(destination: SystemAction.Destination, startsCall: Boolean = false) =
    AppFeature.Action.Home(HomeFeature.Action.SystemActionOpened(destination, startsCall = startsCall, inBackground = false))

private fun tapped(id: String) = AppFeature.Action.Home(HomeFeature.Action.SessionTapped(id))

class SystemRoutingTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope

    private fun TestScope.store(
        state: AppFeature.State,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects, dependencies)
    }

    /** The list, no remembered session, and a draft open. */
    private fun home(): HomeFeature.State = shared.run {
        HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project, docs), sessions = listOf(older, newer), activeSessionIDs = emptySet()))
            .openingDraft()
    }

    private fun saveAll(servers: List<OpenCodeServer>) = shared.run { shared(ServerSharedKeys.savedServers).set(servers) }

    @Test
    fun theOpenServersHomeHandlesIt() = runTest {
        saveAll(listOf(server))
        val state = shared.appState().copy(home = home())
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> older }, transcript = hangingTranscript)
        }
        store.exhaustive = false
        val action = SystemAction(serverKey = serverKey, destination = SystemAction.Destination.Session("ses_older"))
        store.send(AppFeature.Action.SystemActionReceived(action))
        store.receive(opened(SystemAction.Destination.Session("ses_older")))
        store.receive(tapped("ses_older"))
        assertNull(store.state.pendingSystemAction)
        effects.skipInFlightEffects()
    }

    @Test
    fun actionsWithoutAServerMeanTheOpenOne() = runTest {
        val state = shared.appState().copy(home = home())
        val store = store(state)
        store.exhaustive = false
        store.send(AppFeature.Action.SystemActionReceived(SystemAction(destination = SystemAction.Destination.Draft(projectID = null))))
        store.receive(opened(SystemAction.Destination.Draft(projectID = null)))
        effects.skipInFlightEffects()
    }

    @Test
    fun anotherSavedServerConnectsFirst() = runTest {
        saveAll(listOf(server, otherServer))
        val state = shared.appState().copy(home = home())
        val otherKey = SessionDirectory.serverKey(otherServer.persistenceID)
        val remote = OpenCodeSession(id = "ses_remote", serverTitle = "Remote", projectID = "p", directory = "/r", updatedAt = Instant.now())
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                health = {},
                homeSnapshot = { requested ->
                    assertEquals(otherServer.persistenceID, requested.persistenceID)
                    OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(remote), activeSessionIDs = emptySet())
                },
                homeAttention = { _, _, _ -> emptySet() },
                events = { emptyFlow() },
                getSession = { _, _ -> remote },
                transcript = hangingTranscript,
            )
        }
        store.exhaustive = false
        val action = SystemAction(serverKey = otherKey, destination = SystemAction.Destination.Session("ses_remote"), startsCall = false)
        store.send(AppFeature.Action.SystemActionReceived(action))
        assertEquals(action, store.state.pendingSystemAction)
        assertNull(store.state.home)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        store.receive(opened(SystemAction.Destination.Session("ses_remote")))
        store.receive(tapped("ses_remote"))
        assertEquals(otherServer.persistenceID, store.state.home?.server?.persistenceID)
        assertNull(store.state.pendingSystemAction)
        effects.skipInFlightEffects()
    }

    @Test
    fun aForgottenServerExplainsItself() = runTest {
        val store = store(shared.appState())
        store.send(AppFeature.Action.SystemActionReceived(SystemAction(serverKey = "0123456789ab", destination = SystemAction.Destination.LastViewed))) {
            it.copy(systemActionError = "This server is no longer saved on this phone. Connect to it again to open the session.")
        }
        store.finish()
    }

    @Test
    fun anAddressNamesASavedServerBehindItsExperiment() = runTest {
        saveAll(listOf(server, otherServer))
        val action = SystemAction(serverKey = "https://other.example:443/", destination = SystemAction.Destination.Session("ses_remote"))
        val off = store(shared.appState())
        off.send(AppFeature.Action.SystemActionReceived(action)) {
            it.copy(systemActionError = "This server is no longer saved on this phone. Connect to it again to open the session.")
        }
        off.finish()

        val on = store(shared.appState().copy(home = home())) {
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.serverAddressLinks))
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(health = {}, events = { emptyFlow() }, transcript = hangingTranscript)
        }
        on.exhaustive = false
        on.send(AppFeature.Action.SystemActionReceived(action))
        assertEquals(action.copy(serverKey = SessionDirectory.serverKey(otherServer.persistenceID)), on.state.pendingSystemAction)
        effects.skipInFlightEffects()
    }

    @Test
    fun aSessionOutsideTheListIsFetchedFirst() = runTest {
        saveAll(listOf(server))
        val state = shared.appState().copy(home = home())
        val archived = OpenCodeSession(id = "ses_archived", serverTitle = "Archived", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(10))
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, sessionID ->
                    assertEquals(archived.id, sessionID)
                    archived
                },
                transcript = hangingTranscript,
            )
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        val action = SystemAction(serverKey = serverKey, destination = SystemAction.Destination.Session(archived.id), startsCall = false)
        store.send(AppFeature.Action.SystemActionReceived(action))
        store.receiveCase<AppFeature.Action.SystemSessionLoaded>()
        store.receive(opened(SystemAction.Destination.Session(archived.id)))
        store.receive(tapped(archived.id))
        assertTrue(store.state.home?.sessions?.contains(archived) == true)
        effects.skipInFlightEffects()
    }

    @Test
    fun aControlsDeletedSessionSaysSo() = runTest {
        saveAll(listOf(server))
        val state = shared.appState().copy(home = home())
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> throw OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE) })
        }
        store.exhaustive = false
        store.send(AppFeature.Action.SystemActionReceived(SystemAction.controlTarget("$serverKey:ses_gone", startsCall = true)))
        store.receiveCase<AppFeature.Action.SystemSessionLoaded>()
        assertEquals("It may have been deleted, or the server may be unavailable.", store.state.systemActionError)
        assertNull(store.state.home?.sessionDetail?.voice)
        effects.skipInFlightEffects()
    }

    @Test
    fun listensForTheWholeRun() = runTest {
        val actions = Channel<SystemAction>(Channel.UNLIMITED)
        val state = shared.appState().copy(home = home())
        val store = store(state) {
            this[SystemIntegrationClient] = SystemIntegrationClient.testValue.let { base ->
                SystemIntegrationClient(actions = { actions.receiveAsFlow() }, publish = base.publish, removeServer = base.removeServer)
            }
        }
        store.exhaustive = false
        store.send(AppFeature.Action.SystemActionsTask)
        actions.trySend(SystemAction(destination = SystemAction.Destination.Draft(projectID = null)))
        store.receive(AppFeature.Action.SystemActionReceived(SystemAction(destination = SystemAction.Destination.Draft(projectID = null))))
        store.send(AppFeature.Action.SystemActionsTaskEnded)
        effects.skipInFlightEffects()
    }
}

class SystemColdLaunchTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope
    private val snapshot = OpenCodeHomeSnapshot(projects = listOf(project, docs), sessions = listOf(older, newer), activeSessionIDs = emptySet())

    private fun TestScope.store(
        state: AppFeature.State,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects, dependencies)
    }

    private fun unloadedHome(): HomeFeature.State = shared.run {
        HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()), loaded = false)
    }

    private fun saveAll(servers: List<OpenCodeServer>) = shared.run { shared(ServerSharedKeys.savedServers).set(servers) }
    private fun remember(sessionID: String) = shared.run { shared(SharedKeys.lastViewedSessionIDs).set(mapOf(server.persistenceID to sessionID)) }

    @Test
    fun actionsWaitForTheFirstSnapshotThenOpenTheRememberedSession() = runTest {
        saveAll(listOf(server))
        remember("ses_older")
        val state = shared.appState().copy(home = unloadedHome())
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                getSession = { _, _ -> older },
                transcript = hangingTranscript,
            )
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(AppFeature.Action.SystemActionReceived(SystemAction(destination = SystemAction.Destination.LastViewed)))
        assertEquals(SystemAction(serverKey = serverKey, destination = SystemAction.Destination.LastViewed), store.state.pendingSystemAction)
        assertEquals(true, store.state.home?.sessionDetail?.isDraft)

        store.send(AppFeature.Action.Home(HomeFeature.Action.SnapshotRefreshed(snapshot)))
        store.receive(opened(SystemAction.Destination.LastViewed))
        store.receive(tapped("ses_older"))
        assertNull(store.state.pendingSystemAction)
        effects.skipInFlightEffects()
    }

    @Test
    fun aRememberedSessionOutsideTheListIsFetched() = runTest {
        val remembered = OpenCodeSession(id = "ses_remembered", serverTitle = "Remembered", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(5))
        saveAll(listOf(server))
        val state = shared.appState().copy(home = shared.run { HomeFeature.State(server, snapshot) })
        // Remembered after Home opened, so its launch doesn't forget it.
        remember(remembered.id)
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> remembered }, transcript = hangingTranscript)
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(AppFeature.Action.SystemActionReceived(SystemAction(destination = SystemAction.Destination.LastViewed)))
        store.receiveCase<AppFeature.Action.SystemSessionLoaded>()
        store.receive(opened(SystemAction.Destination.Session(remembered.id)))
        store.receive(tapped(remembered.id))
        effects.skipInFlightEffects()
    }

    @Test
    fun aRememberedSessionThatsGoneFallsBackToTheNewest() = runTest {
        saveAll(listOf(server))
        val state = shared.appState().copy(home = shared.run { HomeFeature.State(server, snapshot) })
        remember("ses_deleted")
        val store = store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, id ->
                    if (id == "ses_deleted") throw OpenCodeClientError(message = "Not found", statusCode = 404)
                    newer
                },
                transcript = hangingTranscript,
            )
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(AppFeature.Action.SystemActionReceived(SystemAction(destination = SystemAction.Destination.LastViewed)))
        store.receiveCase<AppFeature.Action.SystemSessionLoaded>()
        store.receive(opened(SystemAction.Destination.LastViewed))
        store.receive(tapped("ses_newer"))
        assertNull(store.state.systemActionError)
        effects.skipInFlightEffects()
    }
}
