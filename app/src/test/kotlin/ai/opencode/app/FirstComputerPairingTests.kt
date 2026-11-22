package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.InstallHistory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.installHistory
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.feature.drawer.persistingSnapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.firstSessionStarters
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.settings.whatsnew.all
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The `AppFeature` halves. `makeDraft` draws a new session ID, so
 * `theFirstComputerOpensAReadySessionInItsLatestProject` receives it non-exhaustively.
 */
class FirstComputerPairingTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope

    private val link = "http://10.0.10.26:4196/auth/connect/abc"
    private val paired = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = "1790000000.sig")
    private val unredeemed: OpenCodeServer get() = OpenCodeServer(url = paired.url, username = "opencode", password = "")

    private fun session(id: String, directory: String, updated: Long) = OpenCodeSession(
        id = id, serverTitle = id, projectID = directory, directory = directory,
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = 1_000, viewedMilliseconds = 1_000,
    )

    private fun project(directory: String, name: String? = null) = OpenCodeProject(id = directory, name = name ?: directory, directory = directory)

    /** The home folder holds the newest session. */
    private val snapshot: OpenCodeHomeSnapshot
        get() = OpenCodeHomeSnapshot(
            projects = listOf(project("/Users/kit"), project("/Users/kit/code/api", name = "api"), project("/Users/kit/code/web", name = "web")),
            sessions = listOf(
                session("home", directory = "/Users/kit", updated = 30),
                session("web", directory = "/Users/kit/code/web", updated = 20),
                session("api", directory = "/Users/kit/code/api", updated = 10),
            ),
            activeSessionIDs = emptySet(),
        )

    /** Each read waits for the test, so shared writes (saved servers, the cached list) land on the step that makes them. */
    private class Gates {
        val health = Channel<Unit>(Channel.UNLIMITED)
        val list = Channel<Unit>(Channel.UNLIMITED)
    }

    private fun TestScope.pairingStore(
        state: AppFeature.State = shared.appState(),
        snapshot: OpenCodeHomeSnapshot,
        gates: Gates,
        haptics: Locked<List<Haptic>> = Locked(emptyList()),
        announcements: Locked<List<String>> = Locked(emptyList()),
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                redeemPairing = { paired },
                health = { gates.health.receiveCatching() },
                homeSnapshot = {
                    gates.list.receiveCatching()
                    snapshot
                },
                // A prefetch fails quietly, so it sends nothing.
                transcript = { _, _, _ -> throw OpenCodeClientError("Not prefetched") },
                viewSession = { _, _, _ -> },
            )
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[UuidClient] = UuidClient.incrementing()
            this[HapticsClient] = HapticsClient(play = { haptic -> haptics.update { it + haptic } })
            this[AccessibilityAnnouncer] = AccessibilityAnnouncer { text, _ -> announcements.update { it + text } }
        }
    }

    private val savedServers get() = shared.run { shared(ServerSharedKeys.savedServers).value }

    /** Scan, redeem, probe and the list read, landing on today's Home. */
    private suspend fun pairAndLand(
        store: TestStore<AppFeature.State, AppFeature.Action>,
        gates: Gates,
        snapshot: OpenCodeHomeSnapshot,
        arrival: PairingArrival,
        saved: List<OpenCodeServer> = emptyList(),
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
        assertEquals(listOf(paired) + saved, savedServers)
        gates.list.trySend(Unit)
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                home = shared.run { HomeFeature.State(paired, snapshot).persistingSnapshot(Instant.EPOCH) },
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
            )
        }
    }

    private suspend fun receivePairedComputerOpened(store: TestStore<AppFeature.State, AppFeature.Action>) {
        store.receive(AppFeature.Action.Home(HomeFeature.Action.PairedComputerOpened)) {
            it.copy(home = it.home?.copy(drawerOpenRequest = it.home?.detailGeneration))
        }
    }

    private var history: InstallHistory
        get() = shared.run { shared(SharedKeys.installHistory).value }
        set(value) = shared.run { shared(SharedKeys.installHistory).set(value) }

    private var seenIDs: Set<String>
        get() = shared.run { shared(WhatsNewFeature.seenIDsKey).value }
        set(value) = shared.run { shared(WhatsNewFeature.seenIDsKey).set(value) }

    @Test
    fun theFirstComputerOpensAReadySessionInItsLatestProject() = runTest {
        val haptics = Locked<List<Haptic>>(emptyList())
        val announcements = Locked<List<String>>(emptyList())
        val snapshot = snapshot
        val gates = Gates()
        val store = pairingStore(snapshot = snapshot, gates = gates, haptics = haptics, announcements = announcements)
        pairAndLand(store, gates, snapshot, arrival = PairingArrival.firstComputer)
        store.exhaustive = false
        store.receive(AppFeature.Action.Home(HomeFeature.Action.FirstComputerPaired))
        val detail = store.state.home?.sessionDetail
        assertEquals("The home folder is never the project", "web", detail?.draftProject?.name)
        assertEquals(true, detail?.isDraft)
        assertEquals(SessionFeature.State.firstSessionStarters, detail?.starterPrompts)
        assertNull(store.state.home?.drawerOpenRequest)
        // The scan answers the finger; the pairing landing is the handshake.
        assertEquals(listOf(Haptic.tap, Haptic.success), haptics.value)
        assertEquals(listOf("Connected to ${paired.friendlyName}"), announcements.value)
        assertEquals("Saving the computer records that this install has paired", InstallHistory.paired, history)
        effects.skipInFlightEffects()
    }

    @Test
    fun pairingAgainAfterRemovingEveryComputerOpensTheDrawer() = runTest {
        history = InstallHistory.paired
        val snapshot = snapshot
        val gates = Gates()
        val store = pairingStore(snapshot = snapshot, gates = gates)
        pairAndLand(store, gates, snapshot, arrival = PairingArrival.newComputer)
        receivePairedComputerOpened(store)
        assertEquals(emptyList<String>(), store.state.home?.sessionDetail?.starterPrompts)
        effects.skipInFlightEffects()
    }

    @Test
    fun aFirstComputerWithOnlyItsHomeFolderOpensTheDrawer() = runTest {
        val snapshot = OpenCodeHomeSnapshot(
            projects = listOf(project("/Users/kit")),
            sessions = listOf(session("home", directory = "/Users/kit", updated = 30)),
            activeSessionIDs = emptySet(),
        )
        val gates = Gates()
        val store = pairingStore(snapshot = snapshot, gates = gates)
        pairAndLand(store, gates, snapshot, arrival = PairingArrival.firstComputer)
        store.receive(AppFeature.Action.Home(HomeFeature.Action.FirstComputerPaired)) {
            it.copy(home = it.home?.copy(drawerOpenRequest = it.home?.detailGeneration))
        }
        assertEquals(emptyList<String>(), store.state.home?.sessionDetail?.starterPrompts)
        effects.skipInFlightEffects()
    }

    @Test
    fun aSecondComputerOpensTheDrawerAndStillShakesHands() = runTest {
        val haptics = Locked<List<Haptic>>(emptyList())
        val elsewhere = OpenCodeServer(url = "http://10.0.0.9:4096", username = "opencode", password = "c")
        shared.run { shared(ServerSharedKeys.savedServers).set(listOf(elsewhere)) }
        val snapshot = snapshot
        val gates = Gates()
        val store = pairingStore(shared.appState(), snapshot, gates, haptics)
        pairAndLand(store, gates, snapshot, arrival = PairingArrival.newComputer, saved = listOf(elsewhere))
        receivePairedComputerOpened(store)
        assertEquals(emptyList<String>(), store.state.home?.sessionDetail?.starterPrompts)
        assertEquals(listOf(Haptic.tap, Haptic.success), haptics.value)
        effects.skipInFlightEffects()
    }

    // MARK: What’s New

    private suspend fun TestScope.launch(state: AppFeature.State = shared.appState()) {
        val store = shared.appStore(state, detachedScope(coroutineContext))
        store.send(AppFeature.Action.Task)
        store.finish()
    }

    @Test
    fun aFreshInstallStartsWithWhatsNewRead() = runTest {
        seenIDs = emptySet()
        launch()
        assertEquals(WhatsNewEntry.all.map { it.id }.toSet(), seenIDs)
        assertEquals(InstallHistory.fresh, history)
    }

    @Test
    fun anUpgradeKeepsWhatItHasntRead() = runTest {
        seenIDs = setOf("voice-gpt-live")
        launch()
        assertEquals("Something seen: not a fresh install", setOf("voice-gpt-live"), seenIDs)
        assertEquals(InstallHistory.paired, history)

        seenIDs = emptySet()
        history = InstallHistory.unrecorded
        shared.run { shared(ServerSharedKeys.savedServers).set(listOf(paired)) }
        launch()
        assertTrue("A saved server: not a fresh install", seenIDs.isEmpty())
        assertEquals(InstallHistory.paired, history)
    }

    @Test
    fun anUpgradeWithEveryComputerRemovedKeepsWhatItHasntRead() = runTest {
        seenIDs = emptySet()
        // An earlier build's one-time migration ran; no server or seen entry is left.
        shared.run { shared(SharedKey.appStorage("console-sign-in-removed", false)).set(true) }
        launch()
        assertTrue(seenIDs.isEmpty())
        assertEquals(InstallHistory.paired, history)
    }

    @Test
    fun removingEveryComputerLaterKeepsWhatsNewUnread() = runTest {
        seenIDs = emptySet()
        history = InstallHistory.paired
        launch()
        assertTrue("Recorded once: an empty server list isn't a fresh install", seenIDs.isEmpty())
    }
}
