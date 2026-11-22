package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.persistingSnapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Pairing a new computer opens the drawer on its sessions once its list loads. */
class PairingOpensDrawerAppTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope

    private val link = "http://10.0.10.26:4196/auth/connect/abc"
    private val paired = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = "1790000000.sig")

    /** A computer saved before, sharing no address: the paired one is new, but not the install's first. */
    private val elsewhere = OpenCodeServer(url = "http://10.0.0.9:4096", username = "opencode", password = "c")

    private val withAnotherComputer: AppFeature.State
        get() = shared.run {
            shared(ServerSharedKeys.savedServers).set(listOf(elsewhere))
            AppFeature.State.initial()
        }

    /** What the link names before it's redeemed. */
    private val unredeemed: OpenCodeServer get() = OpenCodeServer(url = paired.url, username = "opencode", password = "")

    /** Each read waits for the test, so shared writes land on the step that makes them. */
    private class Gates {
        val health = Channel<Unit>(Channel.UNLIMITED)
        val transcript = Channel<Unit>(Channel.UNLIMITED)
    }

    private fun TestScope.pairingStore(
        state: AppFeature.State? = null,
        paired: OpenCodeServer,
        gates: Gates = Gates(),
        homeSnapshot: suspend () -> OpenCodeHomeSnapshot,
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state ?: withAnotherComputer, effects) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                redeemPairing = { paired },
                health = { gates.health.receiveCatching() },
                homeSnapshot = { homeSnapshot() },
                // Only the session the person picks reads; a prefetch of another fails quietly, so it sends nothing.
                transcript = { _, sessionID, _ ->
                    if (sessionID != "older") throw OpenCodeClientError("Not prefetched")
                    gates.transcript.receiveCatching()
                    listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Hello"))
                },
                viewSession = { _, _, _ -> },
            )
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[UuidClient] = UuidClient.incrementing()
        }
    }

    /** A list read that answers when the test releases it. */
    private class GatedSnapshot(private val snapshot: OpenCodeHomeSnapshot) {
        val release = CompletableDeferred<Unit>()
        val read: suspend () -> OpenCodeHomeSnapshot = {
            release.await()
            snapshot
        }
    }

    private fun session(id: String, project: String, updated: Long) = OpenCodeSession(
        id = id, serverTitle = id, projectID = project, directory = "/$project",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = 1_000, viewedMilliseconds = 1_000,
    )

    private val recentSnapshot: OpenCodeHomeSnapshot
        get() = OpenCodeHomeSnapshot(
            projects = listOf(
                OpenCodeProject(id = "api", name = "api", directory = "/api"),
                OpenCodeProject(id = "web", name = "web", directory = "/web"),
            ),
            sessions = listOf(session("older", project = "api", updated = 10), session("recent", project = "web", updated = 20)),
            activeSessionIDs = emptySet(),
        )

    private val savedServer get() = shared.run { shared(ServerSharedKeys.savedServer).value }
    private val savedServers get() = shared.run { shared(ServerSharedKeys.savedServers).value }

    /** Scan, redeem, probe: the steps every pairing takes before its list read. */
    private suspend fun pairUntilListLoads(
        store: TestStore<AppFeature.State, AppFeature.Action>,
        gates: Gates,
        arrival: PairingArrival = PairingArrival.newComputer,
        saved: List<OpenCodeServer> = listOf(elsewhere),
    ) {
        store.send(AppFeature.Action.PairingCodeScanned(link)) {
            it.copy(
                connection = AppFeature.State.Connection.Pairing,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = unredeemed),
            )
        }
        store.receiveCase<AppFeature.Action.PairingRedeemed> {
            it.load(paired).copy(
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 2,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 2, server = paired, pairing = arrival),
            )
        }
        gates.health.trySend(Unit)
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(paired)) }
        assertEquals(paired, savedServer)
        assertEquals(listOf(paired) + saved.filter { !paired.supersedes(it) }, savedServers)
    }

    /** Home lands on today's draft. */
    private fun landHome(state: AppFeature.State, snapshot: OpenCodeHomeSnapshot): AppFeature.State = state.copy(
        home = shared.run { HomeFeature.State(paired, snapshot).persistingSnapshot(Instant.EPOCH) },
        connection = AppFeature.State.Connection.Connected,
        connectionAttempt = null,
    )

    private suspend fun receivePairedComputerOpened(store: TestStore<AppFeature.State, AppFeature.Action>) {
        store.receive(AppFeature.Action.Home(HomeFeature.Action.PairedComputerOpened)) {
            it.copy(home = it.home?.copy(drawerOpenRequest = it.home?.detailGeneration))
        }
    }

    @Test
    fun pairingANewComputerOpensTheDrawerOnItsSessions() = runTest {
        val snapshot = recentSnapshot
        val gates = Gates()
        val list = GatedSnapshot(snapshot)
        val store = pairingStore(paired = paired, gates = gates, homeSnapshot = list.read)
        pairUntilListLoads(store, gates)
        list.release.complete(Unit)
        store.receiveCase<AppFeature.Action.HomeResponse> { landHome(it, snapshot) }
        receivePairedComputerOpened(store)
        assertEquals(true, store.state.home?.opensDrawerForPairing)
        // Nothing is picked: the person chooses from the drawer.
        assertEquals(true, store.state.home?.sessionDetail?.isDraft)
        effects.skipInFlightEffects()
    }

    @Test
    fun rePairingAKnownComputerDoesNotMoveThePerson() = runTest {
        val saved = OpenCodeServer(url = paired.url, username = "opencode", password = "1780000000.old")
        shared.run { shared(ServerSharedKeys.savedServers).set(listOf(saved)) }
        val snapshot = recentSnapshot
        val gates = Gates()
        val list = GatedSnapshot(snapshot)
        val store = pairingStore(shared.appState(), paired = paired, gates = gates, homeSnapshot = list.read)
        pairUntilListLoads(store, gates, arrival = PairingArrival.knownComputer, saved = listOf(saved))
        list.release.complete(Unit)
        store.receiveCase<AppFeature.Action.HomeResponse> { landHome(it, snapshot) }
        assertEquals(true, store.state.home?.sessionDetail?.isDraft)
        assertNull(store.state.home?.drawerOpenRequest)
        effects.skipInFlightEffects()
    }

    @Test
    fun aFailedListReadAfterPairingKeepsTodaysFailure() = runTest {
        val gates = Gates()
        val store = pairingStore(paired = paired, gates = gates) { throw OpenCodeClientError("Couldn’t load projects and sessions") }
        pairUntilListLoads(store, gates)
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                connectionAttempt = null,
                connection = AppFeature.State.Connection.Failed(
                    message = "Couldn’t load projects and sessions",
                    context = AppFeature.FailureContext.of(paired, AppFeature.FailureContext.Phase.LoadSessions, 2),
                ),
            )
        }
        effects.skipInFlightEffects()
    }

    /** Cancelling ends the list read, and an answer that still arrives for that attempt can't open Home. */
    @Test
    fun cancellingWhileTheListLoadsOpensNothing() = runTest {
        val cancelled = Locked(false)
        val snapshot = recentSnapshot
        val gates = Gates()
        val store = pairingStore(paired = paired, gates = gates) {
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        pairUntilListLoads(store, gates)
        store.send(AppFeature.Action.CancelEditingTapped) {
            it.copy(connection = AppFeature.State.Connection.Picking, connectionAttempt = null)
        }
        eventually { cancelled.value }
        store.send(AppFeature.Action.HomeResponse(2, Result.success(snapshot)))
        store.finish()
    }

    /**
     * A session picked after the pairing asked for the drawer, but before it opened, drops the
     * request; late answers then move nothing.
     */
    @Test
    fun pickingASessionBeforeTheDrawerOpensDropsIt() = runTest {
        val snapshot = recentSnapshot
        val older = snapshot.sessions[0]
        val gates = Gates()
        val list = GatedSnapshot(snapshot)
        val store = pairingStore(paired = paired, gates = gates, homeSnapshot = list.read)
        pairUntilListLoads(store, gates)
        list.release.complete(Unit)
        store.receiveCase<AppFeature.Action.HomeResponse> { landHome(it, snapshot) }
        receivePairedComputerOpened(store)

        store.exhaustive = false
        store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(older.id))) {
            it.copy(home = it.home?.copy(drawerOpenRequest = null))
        }
        assertEquals(older.id, shared.run { shared(ai.opencode.core.sharing.SharedKeys.lastViewedSessionIDs).value[paired.persistenceID] })
        assertEquals(false, store.state.home?.opensDrawerForPairing)
        store.receive(AppFeature.Action.Home(HomeFeature.Action.SessionNavigationReady(older.id)))
        assertEquals(older.id, store.state.home?.sessionDetail?.session?.id)
        gates.transcript.trySend(Unit)
        store.receive({ (it as? AppFeature.Action.Home)?.action is HomeFeature.Action.SelectedTranscriptResponse }, "selectedTranscriptResponse")
        store.skipReceivedActions()
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Hello")), store.state.home?.sessionDetail?.items)

        store.send(AppFeature.Action.HomeResponse(2, Result.success(snapshot)))
        store.send(AppFeature.Action.Home(HomeFeature.Action.PairedComputerOpened))
        assertEquals(older.id, store.state.home?.sessionDetail?.session?.id)
        assertEquals(false, store.state.home?.opensDrawerForPairing)
        assertNull(store.state.home?.drawerOpenRequest)
        effects.skipInFlightEffects()
    }

    /** Pairs and lands Home with the drawer open asked for, not yet taken. */
    private suspend fun TestScope.landWithDrawerRequested(): TestStore<AppFeature.State, AppFeature.Action> {
        val snapshot = recentSnapshot
        val gates = Gates()
        val list = GatedSnapshot(snapshot)
        val store = pairingStore(paired = paired, gates = gates, homeSnapshot = list.read)
        pairUntilListLoads(store, gates)
        list.release.complete(Unit)
        store.receiveCase<AppFeature.Action.HomeResponse> { landHome(it, snapshot) }
        receivePairedComputerOpened(store)
        return store
    }

    /** Another pairing link arrives before the view opens the drawer: its confirmation shows and the drawer never opens beneath it. */
    @Test
    fun aPairingLinkBeforeTheDrawerOpensDropsIt() = runTest {
        val store = landWithDrawerRequested()
        val raw = "http://10.0.10.27:4196/auth/connect/xyz"
        val link = OpenCodePairingLink.decode(raw)!!.normalizedForRedemption
        val id = UUID(0, 0).toString()
        store.send(AppFeature.Action.PairingURLOpened(raw)) {
            it.copy(pendingPairing = AppFeature.PendingPairing(id = id, link = link), home = it.home?.copy(drawerOpenRequest = null))
        }
        assertTrue(store.state.isPresentingAnything)
        store.send(AppFeature.Action.PairingCancelled(id)) { it.copy(pendingPairing = null) }
        assertEquals(false, store.state.home?.opensDrawerForPairing)
        effects.skipInFlightEffects()
    }

    /** A broken pairing link before the view opens the drawer: its error shows and the drawer never opens beneath it. */
    @Test
    fun aPairingErrorBeforeTheDrawerOpensDropsIt() = runTest {
        val store = landWithDrawerRequested()
        store.send(AppFeature.Action.PairingURLOpened("https://opencode.abc123.opentunnel.xyz/auth/connect/bad!code")) {
            it.copy(pairingError = invalidPairingCodeMessage, home = it.home?.copy(drawerOpenRequest = null))
        }
        assertTrue(store.state.isPresentingAnything)
        store.send(AppFeature.Action.PairingErrorDismissed) { it.copy(pairingError = null) }
        assertEquals(false, store.state.home?.opensDrawerForPairing)
        effects.skipInFlightEffects()
    }

    /** Every presentation the app, Home or the draft owns counts, so the drawer can't open under any of them. */
    @Test
    fun everyPresentationCountsAsPresentingAnything() {
        val base = shared.appState().copy(home = shared.run { HomeFeature.State(paired, recentSnapshot) })
        assertFalse(base.isPresentingAnything)
        val link = OpenCodePairingLink.decode(this.link)!!
        fun AppFeature.State.home(change: (HomeFeature.State) -> HomeFeature.State) = copy(home = home?.let(change))
        fun AppFeature.State.detail(change: (SessionFeature.State) -> SessionFeature.State) =
            home { it.copy(sessionDetail = it.sessionDetail?.let(change)) }
        val changes: List<Pair<String, (AppFeature.State) -> AppFeature.State>> = listOf(
            "server setup" to { it.copy(isEditingServer = true) },
            "scanner" to { it.copy(isScanningPairingCode = true) },
            "pairing confirmation" to { it.copy(pendingPairing = AppFeature.PendingPairing(id = "pending", link = link)) },
            "pairing error" to { it.copy(pairingError = "x") },
            "share error" to { it.copy(shareError = "x") },
            "notification error" to { it.copy(notificationError = "x") },
            "system action error" to { it.copy(systemActionError = "x") },
            "Catch Up" to { state ->
                state.home { it.copy(catchUpState = CatchUpFeature.State(stack = CatchUpFeature.Stack(queue = listOf("recent"), returnSessionID = null))) }
            },
            "Settings" to { state -> state.home { it.copy(settings = SettingsFeature.State(server = paired)) } },
            "What’s New" to { state -> state.home { it.copy(settings = SettingsFeature.State(server = paired, root = SettingsFeature.State.Root.whatsNew)) } },
            "delete confirmation" to { state -> state.home { it.copy(pendingDeletion = it.sessions.first()) } },
            "subagent push" to { state -> state.home { it.copy(pendingSubagentPush = it.sessions.first()) } },
            "model picker" to { state -> state.detail { it.copy(activePicker = SessionFeature.State.ActivePicker.model) } },
            "call" to { state ->
                state.detail { it.copy(voice = shared.run { VoiceFeature.State(paired, draftDirectory = "/web", model = null) }) }
            },
            "browser" to { state -> state.detail { it.copy(browser = it.browser.copy(pageID = UUID.randomUUID())) } },
        )
        for ((name, change) in changes) {
            assertTrue(name, change(base).isPresentingAnything)
        }
    }
}
