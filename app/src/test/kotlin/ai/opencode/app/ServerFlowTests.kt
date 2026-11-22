package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.ServerMenuStatus
import ai.opencode.feature.drawer.ServerReachability
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The `AppFeature` part. A `Shared` here is a reference, so shared values are checked after the
 * step.
 */
class ServerFlowTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope
    private val snapshot = emptySnapshot
    private val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a", name = "Laptop")
    private val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b", name = "Studio")

    private val savedServer get() = shared.run { shared(ServerSharedKeys.savedServer).value }
    private val savedServers get() = shared.run { shared(ServerSharedKeys.savedServers).value }
    private val cachedHomeSnapshots get() = shared.run { shared(ServerSharedKeys.cachedHomeSnapshots).value }

    private fun home(server: OpenCodeServer): HomeFeature.State = shared.run { HomeFeature.State(server, snapshot) }

    private fun connectedState(): AppFeature.State {
        shared.run {
            shared(ServerSharedKeys.savedServer).set(laptop)
            shared(ServerSharedKeys.savedServers).set(listOf(laptop, studio))
        }
        return shared.appState().copy(connection = AppFeature.State.Connection.Connected, home = home(laptop)).load(laptop)
    }

    private fun TestScope.store(
        state: AppFeature.State,
        clock: TestClock = TestClock(),
        client: (OpenCodeClient) -> OpenCodeClient,
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects) {
            this[Clock] = clock
            this[OpenCodeClient] = client(OpenCodeClient.testValue.copy(archivedSessions = { _, _, _ -> emptyList() }))
        }
    }

    private fun delegate(action: HomeFeature.DelegateAction) = AppFeature.Action.Home(HomeFeature.Action.Delegate(action))

    /** The switch to Studio, which the drawer shows from the start. */
    private fun switching(state: AppFeature.State, attempt: Int = 1): AppFeature.State = state.load(studio).copy(
        returnServer = laptop,
        home = state.home?.copy(switchingTo = studio),
        connection = AppFeature.State.Connection.Connecting,
        connectionGeneration = attempt,
        connectionAttempt = AppFeature.ConnectionAttempt(id = attempt, server = studio, switchesBack = true),
    )

    /** Sends the switch to Studio, which the drawer shows from the start. */
    private suspend fun switchToStudio(store: TestStore<AppFeature.State, AppFeature.Action>, attempt: Int = 1) {
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio))) { switching(it, attempt) }
    }

    /** Back on the laptop's Home, as it was, with a note when it went back on its own. */
    private fun switchedBack(state: AppFeature.State, reason: ServerSwitchNote.Reason?): AppFeature.State {
        var home = checkNotNull(state.home).copy(switchingTo = null)
        if (reason != null) {
            val note = ServerSwitchNote(server = studio, returnedTo = "Laptop", reason = reason)
            home = home.copy(serverSwitchNote = note, serverReachability = home.serverReachability + (studio.persistenceID to note.reachability))
        }
        return state.copy(
            connectionAttempt = null,
            connection = AppFeature.State.Connection.Connected,
            returnServer = null,
            home = home,
        ).load(laptop)
    }

    // MARK: Switching

    @Test
    fun aSwitchThatAnswersSwapsHomeAndStopsTheTimeout() = runTest {
        val gate = GatedHome()
        val state = connectedState().let { it.copy(home = it.home?.copy(serverReachability = mapOf(studio.persistenceID to ServerReachability.unreachable))) }
        val store = store(state) { gate.client(it) }
        switchToStudio(store)
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(studio)) }
        assertEquals(studio, savedServer)
        assertEquals(listOf(studio, laptop), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                returnServer = null,
                // It answered: the menu no longer calls it unreachable.
                home = home(studio),
            )
        }
        assertEquals(
            CachedHomeSnapshot(serverID = studio.persistenceID, snapshot = snapshot, capturedAt = Instant.EPOCH),
            cachedHomeSnapshots[studio.persistenceID],
        )
        store.finish()
    }

    @Test
    fun aSwitchThatDoesNotAnswerGoesBackOnItsOwn() = runTest {
        val clock = TestClock()
        // Never answers until the switch gives up on it.
        val store = store(connectedState(), clock = clock) { it.copy(health = { awaitCancellation() }) }
        switchToStudio(store)
        clock.advance(ServerFlowTunables.timeout.seconds)
        store.receiveCase<AppFeature.Action.ServerSwitchTimedOut> {
            switchedBack(it, ServerSwitchNote.Reason.TimedOut(ServerFlowTunables.timeout))
        }
        assertEquals("Switched back to Laptop", store.state.home?.serverSwitchNote?.title)
        assertEquals("Studio didn’t answer in 8 s", store.state.home?.serverSwitchNote?.detail)
        assertEquals("Try Again", store.state.home?.serverSwitchNote?.action)
        store.finish()
    }

    @Test
    fun anUnreachableServerGoesBackAndIsMarkedInTheMenu() = runTest {
        val store = store(connectedState()) { it.copy(health = { throw OpenCodeClientError("Offline") }) }
        switchToStudio(store)
        store.receiveCase<AppFeature.Action.HealthResponse> { switchedBack(it, ServerSwitchNote.Reason.Unreachable) }
        val home = checkNotNull(store.state.home)
        assertEquals("the Home it left, sessions and all", laptop, home.server)
        assertEquals(
            ServerMenuStatus.unreachable,
            ServerMenuStatus.of(
                studio, current = laptop, status = home.connectionStatus, credentialsRejected = false,
                switchingTo = null, reachability = home.serverReachability,
            ),
        )
        store.finish()
    }

    @Test
    fun aRejectedSignInGoesBackAndOffersPairAgain() = runTest {
        val store = store(connectedState()) { it.copy(health = { throw OpenCodeClientError("Unauthorized", statusCode = 401) }) }
        switchToStudio(store)
        store.receiveCase<AppFeature.Action.HealthResponse> { switchedBack(it, ServerSwitchNote.Reason.SignInFailed) }
        assertEquals("Pair Again", store.state.home?.serverSwitchNote?.action)
        assertEquals("Couldn’t sign in to Studio", store.state.home?.serverSwitchNote?.detail)

        store.send(AppFeature.Action.Home(HomeFeature.Action.ServerSwitchNoteActionTapped)) {
            it.copy(home = it.home?.copy(serverSwitchNote = null))
        }
        store.receive(delegate(HomeFeature.DelegateAction.PairServerRequested), update = { it.copy(isScanningPairingCode = true) })
        store.finish()
    }

    @Test
    fun aFailedSessionsReadGoesBackAndKeepsTheLaptopSaved() = runTest {
        val gate = GatedHome()
        val store = store(connectedState()) { gate.client(it) }
        switchToStudio(store)
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(studio)) }
        assertEquals(studio, savedServer)
        assertEquals(listOf(studio, laptop), savedServers)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> { switchedBack(it, ServerSwitchNote.Reason.Unreachable) }
        // The laptop is the server to reopen again.
        assertEquals(laptop, savedServer)
        store.finish()
    }

    @Test
    fun pickingTheServerInUseMidSwitchTurnsBackWithoutANote() = runTest {
        val store = store(connectedState()) { it.copy(health = { awaitCancellation() }) }
        switchToStudio(store)
        // Picking Studio again changes nothing.
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio)))
        store.send(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(laptop))) { switchedBack(it, reason = null) }
        store.finish()
    }

    @Test
    fun tryAgainSwitchesToTheServerAgain() = runTest {
        val store = store(connectedState()) { it.copy(health = { throw OpenCodeClientError("Offline") }) }
        switchToStudio(store)
        store.receiveCase<AppFeature.Action.HealthResponse> { switchedBack(it, ServerSwitchNote.Reason.Unreachable) }
        store.send(AppFeature.Action.Home(HomeFeature.Action.ServerSwitchNoteActionTapped)) {
            it.copy(home = it.home?.copy(serverSwitchNote = null))
        }
        store.receive(delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio)), update = { switching(it, attempt = 2) })
        store.receiveCase<AppFeature.Action.HealthResponse> { switchedBack(it, ServerSwitchNote.Reason.Unreachable) }
        store.finish()
    }
}
