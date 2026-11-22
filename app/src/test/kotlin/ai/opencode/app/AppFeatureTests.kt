package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.cachedHomeSnapshot
import ai.opencode.feature.drawer.indicator
import ai.opencode.feature.drawer.persistingSnapshot
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.BrowserLinkPolicy
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.media.LinkPreviewPolicy
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.liveactivity.DictationActivityAttributes
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.voice.ScreenAwakeClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.core.sharing.shared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AppFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private lateinit var effects: CoroutineScope

    private val Instant0: Instant = Instant.EPOCH
    private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")

    private fun TestScope.store(
        state: AppFeature.State,
        client: OpenCodeClient = OpenCodeClient.testValue,
        dependencies: ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {},
    ): TestStore<AppFeature.State, AppFeature.Action> {
        effects = detachedScope(coroutineContext)
        return shared.appStore(state, effects) {
            this[OpenCodeClient] = client
            dependencies()
        }
    }

    private fun home(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot): HomeFeature.State =
        shared.run { HomeFeature.State(server, snapshot) }

    private fun cache(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot, capturedAt: Instant? = Instant0) =
        CachedHomeSnapshot(serverID = server.persistenceID, snapshot = snapshot, capturedAt = capturedAt)

    private val savedServer get() = shared.run { shared(ServerSharedKeys.savedServer).value }
    private val savedServers get() = shared.run { shared(ServerSharedKeys.savedServers).value }
    private val cachedHomeSnapshots get() = shared.run { shared(ServerSharedKeys.cachedHomeSnapshots).value }

    private fun save(server: OpenCodeServer?) = shared.run { shared(ServerSharedKeys.savedServer).set(server) }
    private fun saveAll(servers: List<OpenCodeServer>) = shared.run { shared(ServerSharedKeys.savedServers).set(servers) }

    private fun gated(gate: GatedHome) = gate.client()

    /** Probes and reads hold open until cancelled. */
    private fun TestScope.suspendedConnectionStore(): TestStore<AppFeature.State, AppFeature.Action> {
        val clock = TestClock()
        val state = shared.appState()
        save(null)
        saveAll(emptyList())
        val store = store(
            state,
            OpenCodeClient.testValue.copy(
                health = { clock.sleep(3_600.seconds) },
                homeSnapshot = {
                    clock.sleep(3_600.seconds)
                    emptySnapshot
                },
            ),
        )
        store.exhaustive = false
        return store
    }

    @Test
    fun notificationForRemovedServerDoesNotConnect() = runTest {
        val store = store(shared.appState())
        store.send(AppFeature.Action.NotificationOpened(NotificationDestination(serverKey = "removed", sessionID = "ses_missing"))) {
            it.copy(notificationError = "This server is no longer saved on this phone. Connect to it again to open the session.")
        }
        assertNull(store.state.home)
        store.finish()
    }

    @Test
    fun notificationOpensCachedSessionWithoutRebuildingAReconnectingHome() = runTest {
        val server = OpenCodeServer(url = "https://saved.example", username = "opencode", password = "test")
        val target = OpenCodeSession(id = "ses_cached", serverTitle = "Cached", projectID = "project", directory = "/work", updatedAt = Instant.now())
        saveAll(listOf(server))
        val base = home(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(target), activeSessionIDs = emptySet()))
        val state = shared.appState().copy(
            home = base.copy(
                connection = base.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting),
                transcriptCache = base.transcriptCache + (target.id to emptyList()),
            ),
        )
        val store = store(
            state,
            OpenCodeClient.testValue.copy(getSession = { _, _ -> throw AssertionError("A listed session must open without a server round trip") }),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.NotificationOpened(NotificationDestination(serverKey = server.persistenceID, sessionID = target.id)))
        store.receive(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(target.id)))
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.reconnecting, store.state.home?.connectionStatus)
        assertEquals(target, store.state.home?.sessionDetail?.session)
        assertNull(store.state.pendingNotification)
        effects.skipInFlightEffects()
    }

    @Test
    fun notificationLoadsSessionOutsideTheRecentList() = runTest {
        val server = OpenCodeServer(url = "https://saved.example", username = "opencode", password = "test")
        val target = OpenCodeSession(id = "ses_old", serverTitle = "Older session", projectID = "project", directory = "/work", updatedAt = Instant.ofEpochSecond(100))
        saveAll(listOf(server))
        val state = shared.appState().copy(home = home(server, emptySnapshot))
        val store = store(
            state,
            OpenCodeClient.testValue.copy(
                getSession = { requestedServer, sessionID ->
                    assertEquals(server, requestedServer)
                    assertEquals(target.id, sessionID)
                    target
                },
                transcript = { _, _, _ -> awaitCancellation() },
            ),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.NotificationOpened(NotificationDestination(serverKey = server.persistenceID, sessionID = target.id)))
        store.receiveCase<AppFeature.Action.NotificationSessionLoaded>()
        store.receive(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(target.id)))
        store.receive({ it == AppFeature.Action.Home(HomeFeature.Action.SessionNavigationReady(target.id)) }, "sessionNavigationReady")
        assertEquals(target, store.state.home?.sessionDetail?.session)
        assertNull(store.state.pendingNotification)
        assertNull(store.state.notificationError)
        effects.skipInFlightEffects()
    }

    @Test
    fun abandonedOrMalformedNotificationsOpenNothing() = runTest {
        val target = OpenCodeSession(id = "ses_old", serverTitle = "Old", projectID = "project", directory = "/work", updatedAt = distantPast)
        val store = store(shared.appState())
        store.send(AppFeature.Action.NotificationSessionLoaded(NotificationDestination(serverKey = "old-server", sessionID = target.id), Result.success(target)))
        store.send(AppFeature.Action.NotificationOpened(NotificationDestination(serverKey = "old-server", sessionID = "ses_/../../config")))
        assertNull(store.state.home)
        store.finish()
    }

    @Test
    fun connectsToServer() = runTest {
        val snapshot = emptySnapshot
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
        val gate = GatedHome()
        val store = store(shared.appState().copy(serverURL = server.url), gated(gate))

        store.send(AppFeature.Action.ConnectButtonTapped) {
            it.copy(
                manualEntryDraft = server,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = server),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(server)) }
        assertEquals(server, savedServer)
        assertEquals(listOf(server), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                manualEntryDraft = null,
                home = home(server, snapshot),
            )
        }
        assertEquals(mapOf(server.persistenceID to cache(server, snapshot)), cachedHomeSnapshots)
        store.finish()
    }

    @Test
    fun connectionProgressRemainsVisibleUntilHomeArrives() = runTest {
        val server = OpenCodeServer(url = "https://slow-home.example", username = "alice", password = "a")
        val gate = GatedHome()
        val store = store(shared.appState(), gated(gate))
        store.exhaustive = false

        store.send(AppFeature.Action.ServerTapped(server))
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertNull(store.state.home)
        // A healthy server is not a loaded home. The picker must still show work.
        assertEquals(server.id, store.state.connectingServerID)

        gate.yield(emptySnapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        assertEquals(home(server, emptySnapshot), store.state.home)
        assertNull(store.state.connectingServerID)
        store.finish()
    }

    @Test
    fun autoConnectsToSavedServerOnLaunch() = runTest {
        val snapshot = emptySnapshot
        val saved = OpenCodeServer(url = "http://saved.example:4096", username = "kit", password = "secret")
        val initial = shared.appState()
        save(saved)
        val gate = GatedHome()
        val store = store(initial, gated(gate))

        store.send(AppFeature.Action.Task) {
            it.copy(
                serverURL = saved.url,
                username = saved.username,
                password = saved.password,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = saved),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(saved)) }
        assertEquals(listOf(saved), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(connection = AppFeature.State.Connection.Connected, connectionAttempt = null, home = home(saved, snapshot))
        }
        assertEquals(mapOf(saved.persistenceID to cache(saved, snapshot)), cachedHomeSnapshots)
        store.finish()
    }

    @Test
    fun restoresCachedHomeBeforeLaunchTaskWithoutProbing() = runTest {
        val saved = OpenCodeServer(
            url = "https://saved.example", username = "kit", password = "secret",
            alternateURLs = listOf("http://192.168.1.20:4096"),
        )
        val session = OpenCodeSession(id = "ses_cached", serverTitle = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = Instant.ofEpochSecond(1_000))
        val snapshot = OpenCodeHomeSnapshot(
            projects = listOf(OpenCodeProject(id = "proj_cached", name = "Work", directory = "/work")),
            sessions = listOf(session), activeSessionIDs = setOf(session.id),
        )
        val cache = CachedHomeSnapshot(serverID = saved.persistenceID, snapshot = snapshot)
        save(saved)
        shared.run { shared(ServerSharedKeys.cachedHomeSnapshots).set(mapOf(saved.persistenceID to cache)) }

        val store = store(shared.appState())
        assertEquals(saved, store.state.server)
        assertEquals(snapshot.projects, store.state.home?.projects)
        assertEquals(listOf(session), store.state.home?.sessions)
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.reconnecting, store.state.home?.connectionStatus)
        assertEquals(false, store.state.home?.isConnected)
        assertEquals(HomeFeature.State.Indicator.none, store.state.home?.indicator(session))
        assertEquals(true, store.state.home?.initializedInboxServerIDs?.isEmpty())
        assertEquals(mapOf(saved.persistenceID to cache), cachedHomeSnapshots)

        // The Home task owns reconnection; App must not replace the restored list with the picker
        // or call unimplemented networking dependencies.
        store.send(AppFeature.Action.Task)
        assertEquals(listOf(session), store.state.home?.sessions)
        store.finish()
    }

    @Test
    fun launchWithoutMatchingCacheShowsEmptyReconnectingHome() = runTest {
        for (hasOtherServerCache in listOf(false, true)) {
            val shared = AppTestShared()
            val saved = OpenCodeServer(url = "https://saved.example", username = "kit", password = "saved-secret")
            // Same address, different account: identities must not share cached work.
            val other = OpenCodeServer(url = saved.url, username = "another-user", password = "other-secret")
            val otherCache = CachedHomeSnapshot(
                serverID = other.persistenceID,
                snapshot = OpenCodeHomeSnapshot(
                    projects = listOf(OpenCodeProject(id = "other-project", name = "Private work", directory = "/other")),
                    sessions = listOf(
                        OpenCodeSession(id = "other-session", serverTitle = "Other account", projectID = "other-project", directory = "/other", updatedAt = Instant.ofEpochSecond(1_000)),
                    ),
                    activeSessionIDs = setOf("other-session"),
                ),
            )
            val expectedCaches = if (hasOtherServerCache) mapOf(other.persistenceID to otherCache) else emptyMap()
            shared.run {
                shared(ServerSharedKeys.savedServer).set(saved)
                shared(ServerSharedKeys.cachedHomeSnapshots).set(expectedCaches)
            }
            effects = detachedScope(coroutineContext)
            val store = shared.appStore(shared.appState(), effects)
            assertEquals(saved, store.state.home?.server)
            assertEquals(true, store.state.home?.projects?.isEmpty())
            assertEquals(true, store.state.home?.sessions?.isEmpty())
            assertEquals(true, store.state.home?.activeSessionIDs?.isEmpty())
            assertEquals(HomeConnectionFeature.State.ConnectionStatus.reconnecting, store.state.home?.connectionStatus)
            assertEquals(expectedCaches, shared.run { shared(ServerSharedKeys.cachedHomeSnapshots).value })
            store.send(AppFeature.Action.Task)
            store.finish()
        }
    }

    @Test
    fun encodedHomeCacheDoesNotPersistServerPassword() {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "unique-keychain-secret")
        val session = OpenCodeSession(id = "ses_cached", serverTitle = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = Instant.ofEpochSecond(1_000))
        val cache = shared.run {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
            home.persistingSnapshot(Instant0).cachedHomeSnapshot
        }
        assertNotNull(cache)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val encoded = json.encodeToString(CachedHomeSnapshot.serializer(), cache!!)
        assertFalse(encoded.contains(server.password))
        assertFalse(encoded.contains("\"password\""))
        assertEquals(cache, json.decodeFromString(CachedHomeSnapshot.serializer(), encoded))
        assertEquals(listOf(session), cache.snapshot.sessions)
    }

    @Test
    fun waitsForPairingWithoutSavedServer() = runTest {
        val store = store(shared.appState())

        // Unimplemented network dependencies fail this test if launch probes a server.
        store.send(AppFeature.Action.Task)
        assertFalse(store.state.isEditingServer)
        assertNull(store.state.errorMessage)
        store.finish()

        store.send(AppFeature.Action.ScanPairingCodeTapped) {
            it.copy(isScanningPairingCode = true, connection = AppFeature.State.Connection.Picking)
        }
        store.send(AppFeature.Action.ScannerDismissed) { it.copy(isScanningPairingCode = false) }
        store.send(AppFeature.Action.AddServerTapped) { it.copy(isEditingServer = true, password = "") }
        store.send(AppFeature.Action.CancelEditingTapped) { it.copy(isEditingServer = false) }
        store.finish()
    }

    @Test
    fun reportsConnectionFailure() = runTest {
        val store = store(shared.appState(), OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError("Offline") }))

        store.send(AppFeature.Action.ConnectButtonTapped) {
            it.copy(
                manualEntryDraft = it.server,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = it.server),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> {
            it.copy(
                connectionAttempt = null,
                connection = AppFeature.State.Connection.Failed(
                    message = "Offline",
                    context = AppFeature.FailureContext.of(it.server, AppFeature.FailureContext.Phase.Connect, 1),
                ),
            )
        }
        store.finish()
    }

    @Test
    fun thePickerConnectsAnotherServer() = runTest {
        val snapshot = emptySnapshot
        val laptop = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "a")
        val tailnet = OpenCodeServer(url = "https://box.ts.net:4096", username = "opencode", password = "b")
        saveAll(listOf(laptop, tailnet))
        val initial = shared.appState().copy(connection = AppFeature.State.Connection.Picking, returnServer = laptop)
        val gate = GatedHome()
        val store = store(initial, gated(gate))

        // `.picking` must not auto-reconnect when the view's task re-runs.
        store.send(AppFeature.Action.Task)

        store.send(AppFeature.Action.ServerTapped(tailnet)) {
            it.copy(
                serverURL = tailnet.url,
                username = tailnet.username,
                password = tailnet.password,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = tailnet),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(tailnet)) }
        assertEquals(tailnet, savedServer)
        assertEquals(listOf(tailnet, laptop), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                returnServer = null,
                home = home(tailnet, snapshot),
            )
        }
        assertEquals(cache(tailnet, snapshot), cachedHomeSnapshots[tailnet.persistenceID])
        store.finish()
    }

    @Test
    fun pairingPayloadRedeemsAcrossEveryAddress() = runTest {
        val link = OpenCodePairingLink(origin = "http://100.64.0.3:4096", code = "abc", alternateOrigins = listOf("http://192.168.1.10:4096"))
        // The LAN address redeemed the code even though the tailnet one is listed first.
        val paired = OpenCodeServer(
            url = "http://192.168.1.10:4096", username = "opencode", password = "1790000000.sig",
            alternateURLs = listOf("http://100.64.0.3:4096"),
        )
        val store = store(
            shared.appState(),
            OpenCodeClient.testValue.copy(
                redeemPairing = { redeemed ->
                    assertEquals(link, redeemed)
                    paired
                },
                health = { server -> if (server.url != paired.url) throw OpenCodeClientError("Unreachable") },
                homeSnapshot = { emptySnapshot },
            ),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("""{"code":"abc","urls":["http://100.64.0.3:4096","http://192.168.1.10:4096"]}""")) {
            it.copy(
                connection = AppFeature.State.Connection.Pairing,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(
                    id = 1,
                    server = OpenCodeServer(
                        url = "http://100.64.0.3:4096", username = "opencode", password = "",
                        alternateURLs = listOf("http://192.168.1.10:4096"),
                    ),
                ),
            )
        }
        store.receiveCase<AppFeature.Action.PairingRedeemed> {
            it.copy(
                serverURL = paired.url,
                password = paired.password,
                alternateURLs = paired.alternateURLs,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 2,
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse> { it.copy(connection = AppFeature.State.Connection.Connected) }
        assertEquals(paired, savedServer)
        effects.skipInFlightEffects()
    }

    @Test
    fun pairingLinkRedeemsATokenThenConnects() = runTest {
        val link = OpenCodePairingLink(origin = "http://10.0.10.26:4196", code = "abc")
        val paired = OpenCodeServer(
            url = link.origin, username = "opencode", password = "1790000000.sig",
            alternateURLs = listOf("http://100.84.220.87:4196"),
        )
        val store = store(
            shared.appState(),
            OpenCodeClient.testValue.copy(
                redeemPairing = { redeemed ->
                    assertEquals(link, redeemed)
                    paired
                },
                health = { server -> if (server.url != paired.url) throw OpenCodeClientError("Unreachable") },
                homeSnapshot = { emptySnapshot },
            ),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("http://10.0.10.26:4196/auth/connect/abc")) {
            it.copy(connection = AppFeature.State.Connection.Pairing, connectionGeneration = 1)
        }
        store.receiveCase<AppFeature.Action.PairingRedeemed> {
            it.copy(
                serverURL = paired.url,
                password = paired.password,
                alternateURLs = paired.alternateURLs,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 2,
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse> { it.copy(connection = AppFeature.State.Connection.Connected) }
        assertEquals(paired, savedServer)
        effects.skipInFlightEffects()
    }

    @Test
    fun aPairingLinkEnteredAsANewServerAddressPairs() = runTest {
        val link = OpenCodePairingLink(origin = "https://opencode.abc123.opentunnel.xyz", code = "abc")
        val store = store(
            shared.appState(),
            OpenCodeClient.testValue.copy(
                redeemPairing = { redeemed ->
                    assertEquals(link, redeemed)
                    throw OpenCodeClientError("stop here")
                },
            ),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.AddServerTapped)
        store.send(AppFeature.Action.ServerURLChanged(" https://opencode.abc123.opentunnel.xyz/auth/connect/abc\n"))
        store.send(AppFeature.Action.ConnectButtonTapped) { it.copy(serverURL = "") }
        store.receiveCase<AppFeature.Action.PairingCodeScanned> {
            it.copy(isEditingServer = false, connection = AppFeature.State.Connection.Pairing)
        }
        effects.skipInFlightEffects()
    }

    @Test
    fun anAddressThatIsNotAPairingLinkStillConnectsAsAnAddress() = runTest {
        val store = store(shared.appState(), OpenCodeClient.testValue.copy(health = { awaitCancellation() }))
        store.exhaustive = false
        store.send(AppFeature.Action.AddServerTapped)
        store.send(AppFeature.Action.ServerURLChanged("192.168.1.20:4096"))
        store.send(AppFeature.Action.ConnectButtonTapped) {
            it.copy(isEditingServer = true, serverURL = "http://192.168.1.20:4096", connection = AppFeature.State.Connection.Connecting)
        }
        effects.skipInFlightEffects()
    }

    @Test
    fun expiredPairingLinkExplainsHowToRetry() = runTest {
        val store = store(
            shared.appState(),
            OpenCodeClient.testValue.copy(
                redeemPairing = { throw OpenCodeClientError("This pairing code has expired or was already used.", statusCode = 401) },
            ),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("http://10.0.10.26:4196/auth/connect/used"))
        store.receiveCase<AppFeature.Action.PairingRedeemed> {
            it.copy(
                connectionAttempt = null,
                connection = AppFeature.State.Connection.Failed(
                    message = "This pairing code has expired or was already used.",
                    context = AppFeature.FailureContext.of(
                        OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = ""),
                        AppFeature.FailureContext.Phase.Pair, 1,
                    ),
                ),
            )
        }
        effects.skipInFlightEffects()
    }

    @Test
    fun loopbackPairingLinkFailsWithoutSpendingTheCode() = runTest {
        val store = store(shared.appState())
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("http://127.0.0.1:4096/auth/connect/abc"))
        assertEquals(true, store.state.errorMessage?.contains("only listens on localhost"))
        assertEquals(false, store.state.errorMessage?.contains("`"))
        effects.skipInFlightEffects()
    }

    @Test
    fun credentialFieldFocusOrderAndNamePromptUsesFriendlyName() {
        var state = shared.appState().copy(serverURL = "")
        assertEquals("Optional", shared.run { state.credentialNamePrompt })
        state = state.copy(serverURL = "http://my-mac.local:4096")
        assertEquals("My Mac", shared.run { state.credentialNamePrompt })
        state = state.copy(serverURL = "https://work_box.tail1234.ts.net:4096")
        assertEquals("Work Box", shared.run { state.credentialNamePrompt })

        val paired = OpenCodeServer(
            url = "http://192.168.1.20:4096", username = "opencode", password = "token",
            alternateURLs = listOf("https://old-mac.tail1234.ts.net:4096"),
        )
        state = state.copy(editingSavedServer = paired).load(paired)
        assertEquals("Old Mac", shared.run { state.credentialNamePrompt })
        state = state.copy(serverURL = "http://10.0.0.5:4096")
        assertEquals("Server 5", shared.run { state.credentialNamePrompt })
    }

    @Test
    fun credentialBearingPairingDeepLinkFailsInPairPhaseWithoutProbingOrSaving() = runTest {
        val badLink = "opencode-ios://connect?url=https://mini-trial.taila8e7c7.ts.net:4196&username=opencode&password=synthetic-pw"
        assertTrue(OpenCodePairingLink.shouldHandleOpenURL(badLink))

        val store = store(shared.appState())
        store.exhaustive = false
        store.send(AppFeature.Action.AddServerTapped)
        store.send(AppFeature.Action.ServerURLChanged(badLink))
        store.send(AppFeature.Action.ConnectButtonTapped) { it.copy(serverURL = "") }
        store.receiveCase<AppFeature.Action.PairingCodeScanned> { it.copy(isEditingServer = false) }
        assertEquals(AppFeature.FailureContext.Phase.Pair, store.state.errorPhase)
        assertEquals(true, store.state.errorMessage?.contains("That isn’t an OpenCode pairing code."))
        assertEquals(false, store.state.errorMessage?.contains("synthetic-pw"))
        assertTrue(savedServers.isEmpty())
        effects.skipInFlightEffects()
    }

    @Test
    fun unsolicitedPairingLinksRequireConfirmationFreezeIdentityDiscloseAllOriginsAndCancelDoesNothing() = runTest {
        val httpsPairing = "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q"
        val httpsWithFragment = "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q#frag"
        val httpsWithQuery = "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q?utm_source=x"
        val tailnetPairing = "https://mini-trial.taila8e7c7.ts.net:4196/auth/connect/Ab3_x-9Q"
        val deepLinkPairing = "opencode-ios://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196"
        val competingPairing = "https://intruder.example.com:4096/auth/connect/Other_99"
        val multiOriginPairing = "opencode-ios://pair?code=Multi_12&url=http://127.0.0.1:4096&url=http://100.64.0.3:4096&url=http://192.168.1.10:4096"

        for (url in listOf(httpsPairing, httpsWithFragment, httpsWithQuery, tailnetPairing, deepLinkPairing, multiOriginPairing)) {
            assertFalse(url, LinkPreviewPolicy.isPreviewable(url))
            assertFalse(url, BrowserLinkPolicy.isPublicWebPage(url))
            assertTrue(url, OpenCodePairingLink.shouldHandleOpenURL(url))
        }

        val studio = OpenCodeServer(url = "https://studio.taila8e7c7.ts.net:4096", username = "opencode", password = "pw", name = "Studio")
        var savedMini = OpenCodeServer(url = "https://mini-trial.taila8e7c7.ts.net:4196", username = "opencode", password = "1780000000.old_sig", name = "Mini Trial")
        val savedTailnet = OpenCodeServer(url = "http://100.64.0.3:4096", username = "opencode", password = "pw1", name = "Box Tailnet")
        val savedLAN = OpenCodeServer(url = "http://192.168.1.10:4096", username = "opencode", password = "pw2", name = "Box LAN")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Pair mini-trial", projectID = "p", directory = "/project", updatedAt = Instant.ofEpochSecond(1))
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet())
        save(studio)
        saveAll(listOf(studio, savedMini, savedTailnet, savedLAN))
        val home = home(studio, snapshot).copy(sessionDetail = shared.run { SessionFeature.State.session(studio, session) })
        val initial = shared.appState().copy(connection = AppFeature.State.Connection.Connected, home = home)

        val openedExternally = Locked<List<String>>(emptyList())
        val redeemedLinks = Locked<List<OpenCodePairingLink>>(emptyList())
        val paired = OpenCodeServer(url = "https://mini-trial.taila8e7c7.ts.net:4196", username = "opencode", password = "1790000000.new_sig")

        val store = store(
            initial,
            OpenCodeClient.testValue.copy(
                redeemPairing = { link ->
                    redeemedLinks.update { it + link }
                    paired
                },
                health = {},
                homeSnapshot = { emptySnapshot },
            ),
        ) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenURLClient] = OpenURLClient(open = { url -> openedExternally.update { it + url }; true })
        }
        store.exhaustive = false

        fun id(n: Long) = UUID(0, n).toString()
        val expectedLink = OpenCodePairingLink(origin = "https://mini-trial.taila8e7c7.ts.net:4196", code = "Ab3_x-9Q")
        val firstPending = AppFeature.PendingPairing(id = id(0), link = expectedLink, replacedServerNames = listOf("Mini Trial"))
        assertEquals("Pair with mini-trial.taila8e7c7.ts.net:4196?", firstPending.title)
        assertEquals(
            "OpenCode will pair with mini-trial.taila8e7c7.ts.net:4196 and switch to it. Saved credentials for Mini Trial may be replaced.",
            firstPending.message,
        )

        val freshPending = AppFeature.PendingPairing(
            id = id(99),
            link = OpenCodePairingLink(origin = "https://opencode.abc123.opentunnel.xyz", code = "Ab3_x-9Q"),
        )
        assertEquals("Pair with opencode.abc123.opentunnel.xyz?", freshPending.title)
        assertEquals("OpenCode will pair with opencode.abc123.opentunnel.xyz and switch to it.", freshPending.message)

        val freshMultiPending = AppFeature.PendingPairing(
            id = id(100),
            link = OpenCodePairingLink(
                origin = "http://100.64.0.3:4096", code = "Multi_12",
                alternateOrigins = listOf("http://192.168.1.10:4096", "http://box.local:4096"),
            ),
        )
        assertEquals("Pair with 100.64.0.3:4096?", freshMultiPending.title)
        assertEquals(
            "OpenCode will try 100.64.0.3:4096, 192.168.1.10:4096, and box.local:4096 and switch to the first that answers.",
            freshMultiPending.message,
        )

        // Redemption deduplicates by (scheme, hostname, effective port) so http://box (80) and
        // https://box (443) both stay in redeemOrigins while http://BOX:80 collapses, and display
        // labels dedup separately.
        val dualSchemeLink = OpenCodePairingLink(
            origin = "http://box.example.com", code = "Dual_1",
            alternateOrigins = listOf("http://BOX.EXAMPLE.COM:80", "https://box.example.com", "https://box.example.com:443"),
        )
        assertEquals(listOf("http://box.example.com", "https://box.example.com"), dualSchemeLink.redeemOrigins)
        val dualSchemePending = AppFeature.PendingPairing(id = id(101), link = dualSchemeLink.normalizedForRedemption)
        assertEquals(listOf("box.example.com"), dualSchemePending.hosts)
        assertEquals("Pair with box.example.com?", dualSchemePending.title)
        assertEquals("OpenCode will try box.example.com and switch to the first address that answers.", dualSchemePending.message)

        // 1. Tapping a pairing link in a transcript stages confirmation without redeeming or leaving Home.
        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.LinkTapped(tailnetPairing)))) {
            it.copy(pendingPairing = firstPending)
        }
        assertNotNull(store.state.home)
        assertTrue(redeemedLinks.value.isEmpty())
        assertTrue(openedExternally.value.isEmpty())

        // 2. While a pairing alert is pending, a second unsolicited link is ignored rather than overwriting the alert.
        store.send(AppFeature.Action.PairingURLOpened(competingPairing)) { it.copy(ignoredPendingPairingCount = 1) }
        assertEquals(firstPending, store.state.pendingPairing)

        // Stale confirmation/cancellation IDs are ignored.
        store.send(AppFeature.Action.PairingConfirmed(id(999)))
        store.send(AppFeature.Action.PairingCancelled(id(999)))
        assertEquals(firstPending, store.state.pendingPairing)
        assertTrue(redeemedLinks.value.isEmpty())

        // 3. Cancelling with the matching ID clears the pending pairing and resets the ignored count.
        store.send(AppFeature.Action.PairingCancelled(id(0))) { it.copy(pendingPairing = null, ignoredPendingPairingCount = 0) }
        assertNotNull(store.state.home)
        assertTrue(redeemedLinks.value.isEmpty())

        // 4. A multi-origin link omits loopback, discloses all reachable origins, and names every replaced saved server.
        val expectedMultiLink = OpenCodePairingLink(origin = "http://100.64.0.3:4096", code = "Multi_12", alternateOrigins = listOf("http://192.168.1.10:4096"))
        val expectedMultiPending = AppFeature.PendingPairing(id = id(1), link = expectedMultiLink, replacedServerNames = listOf("Box Tailnet", "Box LAN"))
        assertEquals("Pair with 100.64.0.3:4096?", expectedMultiPending.title)
        assertEquals(
            "OpenCode will try 100.64.0.3:4096 and 192.168.1.10:4096 and switch to the first that answers. Saved credentials for Box Tailnet, Box LAN may be replaced.",
            expectedMultiPending.message,
        )
        store.send(AppFeature.Action.PairingURLOpened(multiOriginPairing)) { it.copy(pendingPairing = expectedMultiPending) }
        store.send(AppFeature.Action.PairingCancelled(id(1))) { it.copy(pendingPairing = null) }

        // 5. In-app browser taps also stage confirmation and confirming with the frozen ID redeems only the shown origins.
        val thirdPending = AppFeature.PendingPairing(id = id(2), link = expectedLink, replacedServerNames = listOf("Mini Trial"))
        store.send(
            AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Browser(BrowserFeature.Action.PairingLinkTapped(tailnetPairing)))),
        ) { it.copy(pendingPairing = thirdPending) }
        assertTrue(redeemedLinks.value.isEmpty())

        savedMini = savedMini.copy(password = paired.password)
        store.send(AppFeature.Action.PairingConfirmed(id(2))) {
            it.copy(pendingPairing = null, returnServer = studio, home = null, connection = AppFeature.State.Connection.Pairing)
        }
        store.receiveCase<AppFeature.Action.PairingRedeemed>()
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse> { it.copy(connection = AppFeature.State.Connection.Connected) }
        assertTrue(openedExternally.value.isEmpty())
        assertEquals(listOf(expectedLink), redeemedLinks.value)
        assertEquals(savedMini, savedServer)
        effects.skipInFlightEffects()
    }

    @Test
    fun invalidOrLoopbackPairingLinksPreserveHomeInFlightSwitchAndEditorState() = runTest {
        val studio = OpenCodeServer(url = "https://studio.taila8e7c7.ts.net:4096", username = "opencode", password = "pw", name = "Studio")
        val targetServer = OpenCodeServer(url = "https://mini-trial.taila8e7c7.ts.net:4196", username = "opencode", password = "pw2", name = "Mini Trial")
        val draftServer = OpenCodeServer(url = "https://draft.example.com:4096", username = "opencode", password = "draft-pw", name = "Draft")
        val session = OpenCodeSession(id = "ses_1", serverTitle = "Live call", projectID = "p", directory = "/project", updatedAt = Instant.ofEpochSecond(1))
        val snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet())
        save(studio)
        saveAll(listOf(studio, targetServer))
        val detail = shared.run { SessionFeature.State.session(studio, session).copy(voice = VoiceFeature.State(studio, session)) }
        val home = home(studio, snapshot).copy(
            sessionDetail = detail,
            voiceCallActivity = VoiceCallActivityAttributes.ContentState(
                phase = VoiceCallActivityAttributes.ContentState.Phase.Live, isMuted = false, startedAt = Instant.ofEpochSecond(100),
            ),
            dictationActivity = DictationActivityAttributes.ContentState(title = "Live call", startedAt = Instant.ofEpochSecond(100)),
        )
        val initial = shared.appState().copy(
            connection = AppFeature.State.Connection.Connected,
            isInForeground = true,
            manualEntryDraft = draftServer,
            home = home,
        )

        val voiceEnded = Locked(false)
        val dictationEnded = Locked(false)
        val awakeStates = Locked<List<Boolean>>(emptyList())
        val switchGate = Channel<Unit>(Channel.UNLIMITED)
        val gatedClient = OpenCodeClient.testValue.copy(
            health = { switchGate.receiveCatching() },
            homeSnapshot = { emptySnapshot },
            archivedSessions = { _, _, _ -> emptyList() },
        )

        val store = store(initial, gatedClient) {
            this[VoiceLiveActivityClient] = VoiceLiveActivityClient(
                update = { state, _ -> if (state == null) voiceEnded.set(true) }, endAll = {}, commands = { emptyFlow() },
            )
            this[DictationLiveActivityClient] = DictationLiveActivityClient(
                update = { state, _ -> if (state == null) dictationEnded.set(true) }, endAll = {}, commands = { emptyFlow() },
            )
            this[ScreenAwakeClient] = ScreenAwakeClient { awake -> awakeStates.update { it + awake } }
        }
        store.exhaustive = false

        // 1. Active voice call and dictation on Home are untouched by invalid or loopback unsolicited pairing links.
        val malformedLinks = listOf(
            "opencode-ios://connect?url=https://mini-trial.taila8e7c7.ts.net:4196&username=opencode&password=synthetic-pw",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q?extra=1#frag",
            "http://127.0.0.1:4096/auth/connect/Ab3_x-9Q",
        )
        for (raw in malformedLinks) {
            store.send(AppFeature.Action.PairingURLOpened(raw))
            assertNotNull(store.state.pairingError)
            assertNull(store.state.pendingPairing)
            assertEquals("Invalid link $raw must not alter connection state", AppFeature.State.Connection.Connected, store.state.connection)
            assertNotNull("Invalid link $raw must not clear Home", store.state.home)
            assertEquals("Invalid link $raw must not drop manual draft", draftServer, store.state.manualEntryDraft)
            assertNotNull("Invalid link $raw must not end an active call", store.state.home?.voiceCallActivity)
            assertNotNull("Invalid link $raw must not end active dictation", store.state.home?.dictationActivity)
            assertTrue(store.state.keepsScreenAwake)
            store.send(AppFeature.Action.PairingErrorDismissed) { it.copy(pairingError = null) }
        }

        // Transcript and in-app browser taps on malformed pairing links also preserve Home and active call/dictation.
        val brokenTap = "https://opencode.abc123.opentunnel.xyz/auth/connect/bad!code"
        val expectedInvalidMessage = "That isn’t an OpenCode pairing code. Run opencode pair --remote on your computer, then scan its QR code or paste the link it prints."
        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.LinkTapped(brokenTap)))) {
            it.copy(pairingError = expectedInvalidMessage)
        }
        store.send(AppFeature.Action.PairingErrorDismissed) { it.copy(pairingError = null) }
        store.send(
            AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Browser(BrowserFeature.Action.PairingLinkTapped(brokenTap)))),
        ) { it.copy(pairingError = expectedInvalidMessage) }
        store.send(AppFeature.Action.PairingErrorDismissed) { it.copy(pairingError = null) }
        assertFalse(voiceEnded.value)
        assertFalse(dictationEnded.value)
        assertFalse(awakeStates.value.contains(false))
        effects.skipInFlightEffects()

        // 2. During an in-flight server switch over Home, invalid unsolicited pairing links do not cancel
        // the switch, clear switchingTo, or overwrite .connecting when dismissed.
        val switchingInitial = shared.appState().copy(
            connection = AppFeature.State.Connection.Connected,
            manualEntryDraft = draftServer,
            home = home(studio, snapshot),
        )
        val switchingStore = store(switchingInitial, gatedClient) { this[Clock] = TestClock() }
        switchingStore.exhaustive = false

        switchingStore.send(AppFeature.Action.Home(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerSwitchTapped(targetServer)))) {
            it.copy(
                returnServer = studio,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = targetServer, switchesBack = true),
                home = it.home?.copy(switchingTo = targetServer),
            )
        }
        switchingStore.send(AppFeature.Action.PairingURLOpened(brokenTap)) { it.copy(pairingError = expectedInvalidMessage) }
        assertEquals(AppFeature.State.Connection.Connecting, switchingStore.state.connection)
        assertEquals(AppFeature.ConnectionAttempt(id = 1, server = targetServer, switchesBack = true), switchingStore.state.connectionAttempt)
        assertEquals(targetServer, switchingStore.state.home?.switchingTo)
        assertEquals(draftServer, switchingStore.state.manualEntryDraft)
        switchingStore.send(AppFeature.Action.PairingErrorDismissed) { it.copy(pairingError = null) }
        assertEquals(
            "Dismissing pairingError must not overwrite .connecting with .connected",
            AppFeature.State.Connection.Connecting, switchingStore.state.connection,
        )

        switchGate.trySend(Unit)
        switchGate.close()
        switchingStore.receiveCase<AppFeature.Action.HealthResponse>()
        switchingStore.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(connection = AppFeature.State.Connection.Connected, connectionAttempt = null)
        }
        assertEquals(targetServer, switchingStore.state.home?.server)
        effects.skipInFlightEffects()

        // 3. Scanner and server-editor sheets stay open when an unsolicited link fails validation.
        val editingState = shared.appState().copy(
            isScanningPairingCode = true,
            isEditingServer = true,
            editingSavedServer = studio,
            manualEntryDraft = draftServer,
        )
        val editingStore = store(editingState)
        editingStore.exhaustive = false
        editingStore.send(AppFeature.Action.PairingURLOpened(brokenTap)) { it.copy(pairingError = expectedInvalidMessage) }
        assertTrue(editingStore.state.isScanningPairingCode)
        assertTrue(editingStore.state.isEditingServer)
        assertEquals(studio, editingStore.state.editingSavedServer)
        assertEquals(draftServer, editingStore.state.manualEntryDraft)
        effects.skipInFlightEffects()
    }

    @Test
    fun lateFieldFlushAfterConnectRetriesWithTheCompletedEntry() = runTest {
        val store = store(shared.appState(), OpenCodeClient.testValue.copy(health = { awaitCancellation() }))
        store.exhaustive = false
        store.send(AppFeature.Action.AddServerTapped) { it.copy(isEditingServer = true) }
        store.send(AppFeature.Action.ServerURLChanged("http://10.0.10.2"))
        store.send(AppFeature.Action.ConnectButtonTapped) {
            it.copy(connection = AppFeature.State.Connection.Connecting, connectionGeneration = 1)
        }
        // The keyboard was still up: the field delivers its final text after Connect.
        store.send(AppFeature.Action.ServerURLChanged("http://10.0.10.26:4196")) {
            it.copy(
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 2,
                connectionAttempt = AppFeature.ConnectionAttempt(
                    id = 2,
                    server = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = ""),
                ),
            )
        }
        effects.skipInFlightEffects()
    }

    @Test
    fun serverListOffersAWayBackToTheServerThatWasOpen() = runTest {
        val server = OpenCodeServer(url = "http://10.0.10.26:4196", username = "opencode", password = "pw")
        save(server)
        saveAll(listOf(server))
        val state = shared.appState().copy(connection = AppFeature.State.Connection.Picking, returnServer = server)
        val store = store(state, OpenCodeClient.testValue.copy(health = {}, homeSnapshot = { emptySnapshot }))
        store.exhaustive = false

        store.send(AppFeature.Action.ReturnToServerTapped) { it.copy(connection = AppFeature.State.Connection.Connecting) }
        store.receiveCase<AppFeature.Action.HealthResponse>()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(connection = AppFeature.State.Connection.Connected, returnServer = null)
        }
        assertEquals(server, store.state.home?.server)
        effects.skipInFlightEffects()
    }

    @Test
    fun failedManualEntryKeepsFormOpenWithValuesAndClosesOnSuccess() = runTest {
        val snapshot = emptySnapshot
        val gate = GatedHome()
        val shouldFail = Locked(true)
        save(null)
        saveAll(emptyList())
        val initialState = shared.appState().copy(home = null)
        val store = store(
            initialState,
            gated(gate).copy(
                health = { if (shouldFail.value) throw OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE) },
            ),
        )

        store.send(AppFeature.Action.AddServerTapped) {
            it.copy(isEditingServer = true, password = "", connection = AppFeature.State.Connection.Picking)
        }
        store.send(AppFeature.Action.ServerNameChanged("Work Mac")) { it.copy(serverName = "Work Mac") }
        store.send(AppFeature.Action.ServerURLChanged("http://my-mac.local:4096")) { it.copy(serverURL = "http://my-mac.local:4096") }
        store.send(AppFeature.Action.UsernameChanged("kit")) { it.copy(username = "kit") }
        store.send(AppFeature.Action.PasswordChanged("secret")) { it.copy(password = "secret") }
        store.send(AppFeature.Action.HeadersChanged(listOf(OpenCodeServer.Header("X-Custom", "1")))) {
            it.copy(headers = listOf(OpenCodeServer.Header("X-Custom", "1")))
        }

        val expectedDraft = OpenCodeServer(
            url = "http://my-mac.local:4096", username = "kit", password = "secret", name = "Work Mac",
            headers = listOf(OpenCodeServer.Header("X-Custom", "1")),
        )

        store.send(AppFeature.Action.ConnectButtonTapped) {
            it.copy(
                manualEntryDraft = expectedDraft,
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = expectedDraft),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> {
            it.copy(
                connectionAttempt = null,
                connection = AppFeature.State.Connection.Failed(
                    message = OpenCodeClientError.UNREACHABLE_MESSAGE,
                    context = AppFeature.FailureContext.of(expectedDraft, AppFeature.FailureContext.Phase.Connect, 1),
                ),
            )
        }
        assertTrue(store.state.isEditingServer)
        assertEquals("Work Mac", store.state.serverName)
        assertEquals("http://my-mac.local:4096", store.state.serverURL)
        assertEquals("kit", store.state.username)
        assertEquals("secret", store.state.password)
        assertEquals(listOf(OpenCodeServer.Header("X-Custom", "1")), store.state.headers)
        assertEquals("Work Mac", store.state.errorContext?.friendlyName)

        shouldFail.set(false)
        store.send(AppFeature.Action.ConnectButtonTapped) {
            it.copy(
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 2,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 2, server = expectedDraft),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(expectedDraft)) }
        assertEquals(expectedDraft, savedServer)
        assertEquals(listOf(expectedDraft), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                isEditingServer = false,
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                manualEntryDraft = null,
                home = home(expectedDraft, snapshot),
            )
        }
        assertEquals(mapOf(expectedDraft.persistenceID to cache(expectedDraft, snapshot)), cachedHomeSnapshots)
        store.finish()
    }

    @Test
    fun reopeningManualEntryRestoresTheFailedAttempt() = runTest {
        val store = store(shared.appState(), OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError("Could not reach the OpenCode server.") }))
        store.exhaustive = false
        store.send(AppFeature.Action.AddServerTapped) { it.copy(serverURL = "") }
        store.send(AppFeature.Action.ServerURLChanged("http://my-mac.local:4096"))
        store.send(AppFeature.Action.PasswordChanged("secret"))
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertTrue(store.state.isEditingServer)
        store.send(AppFeature.Action.CancelEditingTapped) { it.copy(isEditingServer = false) }
        store.send(AppFeature.Action.AddServerTapped) {
            it.copy(isEditingServer = true, serverURL = "http://my-mac.local:4096", password = "secret")
        }
        effects.skipInFlightEffects()
    }

    @Test
    fun manualEntryWithoutASchemeConnectsOverHTTP() = runTest {
        val attempted = Locked<List<String>>(emptyList())
        val store = store(
            shared.appState(),
            OpenCodeClient.testValue.copy(
                health = { server ->
                    attempted.update { it + server.url }
                    throw OpenCodeClientError("Could not reach the OpenCode server.")
                },
            ),
        )
        store.exhaustive = false

        // A bare Tailscale host:port, as `opencode serve` prints it, means plain HTTP.
        store.send(AppFeature.Action.AddServerTapped)
        store.send(AppFeature.Action.ServerURLChanged(" 100.84.220.87:4102 "))
        store.send(AppFeature.Action.ConnectButtonTapped) { it.copy(serverURL = "http://100.84.220.87:4102") }
        store.receiveCase<AppFeature.Action.HealthResponse>()

        // An explicit scheme is kept as typed.
        store.send(AppFeature.Action.AddServerTapped)
        store.send(AppFeature.Action.ServerURLChanged("https://my-mac.example:4096"))
        store.send(AppFeature.Action.ConnectButtonTapped) { it.copy(serverURL = "https://my-mac.example:4096") }
        store.receiveCase<AppFeature.Action.HealthResponse>()

        assertEquals(listOf("http://100.84.220.87:4102", "https://my-mac.example:4096"), attempted.value)
        effects.skipInFlightEffects()
    }

    @Test
    fun blankUsernameConnectsAsTheDefaultUser() = runTest {
        val attempted = Locked<List<OpenCodeServer>>(emptyList())
        val store = store(
            shared.appState(),
            OpenCodeClient.testValue.copy(
                health = { server ->
                    attempted.update { it + server }
                    throw OpenCodeClientError("The server rejected these credentials.", statusCode = 401)
                },
            ),
        )
        store.exhaustive = false
        store.send(AppFeature.Action.AddServerTapped)
        store.send(AppFeature.Action.ServerURLChanged("http://my-box:4096"))
        store.send(AppFeature.Action.UsernameChanged(" "))
        store.send(AppFeature.Action.PasswordChanged("secret"))
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()

        assertEquals(listOf("opencode"), attempted.value.map { it.username })
        assertEquals("Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray()), attempted.value.first().basicAuthorization)
        effects.skipInFlightEffects()
    }

    @Test
    fun aPairingCodeWithAnUnusableAddressSaysWhichAndWhy() = runTest {
        val store = store(shared.appState())
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("""{"code":"Ab3_x-9QAb3_x-9QAb3_x-","urls":["https://box.example/opencode"]}"""))
        assertEquals(
            "Couldn’t use “https://box.example/opencode” from this pairing code: it has a path, and the server must be at the root of its address.",
            store.state.errorMessage,
        )
        assertTrue(savedServers.isEmpty())
        assertNull(store.state.connectionAttempt)
        store.finish()
    }

    @Test
    fun malformedLinkShowsAnErrorWithoutSavingCredentials() = runTest {
        val store = store(shared.appState())
        store.exhaustive = false
        store.send(AppFeature.Action.PairingCodeScanned("file:///auth/connect/do-not-echo"))
        assertNotNull(store.state.errorMessage)
        assertEquals(false, store.state.errorMessage?.contains("do-not-echo"))
        assertTrue(savedServers.isEmpty())
        assertNull(store.state.connectionAttempt)
        store.finish()
    }

    @Test
    fun reconnectingTheSameAddressWithNewCredentialsReplacesTheSavedEntry() = runTest {
        val old = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "old")
        val renamed = OpenCodeServer(url = old.url, username = "kit", password = "new")
        val other = OpenCodeServer(url = "http://10.0.0.5:4096", username = "opencode", password = "pw")
        val initialState = shared.appState().load(renamed)
        saveAll(listOf(old, other))
        val store = store(initialState, OpenCodeClient.testValue.copy(health = {}, homeSnapshot = { emptySnapshot }))
        store.exhaustive = false

        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertEquals(listOf(renamed, other), savedServers)
        store.receiveCase<AppFeature.Action.HomeResponse>()
        store.finish()
    }

    @Test
    fun editingSavedLANAddressReplacesItOnlyAfterSessionsLoad() = runTest {
        val lan = OpenCodeServer(
            url = "http://192.168.1.20:4096", username = "opencode", password = "secret",
            alternateURLs = listOf("http://192.168.1.21:4096"),
        )
        val tailnet = OpenCodeServer(url = "https://box.tailnet.ts.net:4096", username = "opencode", password = "secret")
        val other = OpenCodeServer(url = "https://other.example", username = "other", password = "other")
        val snapshot = emptySnapshot
        val gate = GatedHome()
        save(lan)
        saveAll(listOf(lan, other))
        val state = shared.appState().load(lan).copy(connection = AppFeature.State.Connection.Picking, returnServer = lan)
        val store = store(
            state,
            gated(gate).let { client ->
                client.copy(
                    health = { server -> assertEquals(tailnet, server) },
                    homeSnapshot = { server ->
                        assertEquals(tailnet, server)
                        client.homeSnapshot(server)
                    },
                )
            },
        )
        store.exhaustive = false

        store.send(AppFeature.Action.EditServerTapped(lan))
        assertTrue(store.state.isEditingServer)
        assertEquals(lan, store.state.server)
        store.send(AppFeature.Action.ServerURLChanged(tailnet.url))
        store.send(AppFeature.Action.ConnectButtonTapped)
        assertEquals(tailnet, store.state.connectionAttempt?.server)
        assertEquals(listOf(lan, other), savedServers)

        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertEquals(lan, savedServer)
        assertEquals(listOf(lan, other), savedServers)
        gate.yield(snapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse>()
        assertEquals(tailnet, savedServer)
        assertEquals(listOf(tailnet, other), savedServers)
        assertNull(store.state.editingSavedServer)
        assertEquals(tailnet, store.state.home?.server)
        store.finish()
    }

    @Test
    fun failedEditKeepsTheSavedServerAndCancelDoesNotChangeIt() = runTest {
        val original = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "secret")
        val updatedURL = "https://box.tailnet.ts.net:4096"
        val state = shared.appState()
        save(original)
        saveAll(listOf(original))
        val store = store(state, OpenCodeClient.testValue.copy(health = { throw OpenCodeClientError("Offline") }))
        store.exhaustive = false

        store.send(AppFeature.Action.EditServerTapped(original))
        store.send(AppFeature.Action.ServerURLChanged(updatedURL))
        store.send(AppFeature.Action.CancelEditingTapped)
        assertEquals(listOf(original), savedServers)
        assertEquals(original, savedServer)
        store.send(AppFeature.Action.EditServerTapped(original))
        store.send(AppFeature.Action.ServerURLChanged(updatedURL))
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertEquals(listOf(original), savedServers)
        assertEquals(original, savedServer)
        store.finish()
    }

    @Test
    fun editedAddressWithUnloadedSessionsKeepsTheOriginalEntry() = runTest {
        val original = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "secret")
        val state = shared.appState()
        save(original)
        saveAll(listOf(original))
        val store = store(state, OpenCodeClient.testValue.copy(health = {}, homeSnapshot = { throw OpenCodeClientError("Sessions unavailable") }))
        store.exhaustive = false

        store.send(AppFeature.Action.EditServerTapped(original))
        store.send(AppFeature.Action.ServerURLChanged("https://box.tailnet.ts.net:4096"))
        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertEquals(original, savedServer)
        store.receiveCase<AppFeature.Action.HomeResponse>()
        assertEquals(original, savedServer)
        assertEquals(listOf(original), savedServers)
        assertNull(store.state.editingSavedServer)
        store.finish()
    }

    // Connecting picks the address, keeps the saved order.

    @Test
    fun connectFallsBackToAnAlternateAddressAndAdoptsIt() = runTest {
        val initialState = shared.appState().copy(
            serverURL = "https://box.ts.net:4096",
            alternateURLs = listOf("http://192.168.1.20:4096"),
            password = "pw",
        )
        val store = store(
            initialState,
            OpenCodeClient.testValue.copy(
                // Away from the tailnet, only the LAN address answers.
                health = { server -> if (server.url != "http://192.168.1.20:4096") throw OpenCodeClientError("Offline") },
                homeSnapshot = { emptySnapshot },
            ),
        )
        store.exhaustive = false

        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()

        // The LAN address is used now; the server stays saved as entered.
        assertEquals(AppFeature.State.Connection.LoadingHome(store.state.server.at("http://192.168.1.20:4096")), store.state.connection)
        assertEquals("https://box.ts.net:4096", store.state.serverURL)
        assertEquals(listOf("http://192.168.1.20:4096"), store.state.alternateURLs)
        assertEquals("https://box.ts.net:4096", savedServer?.url)
        effects.skipInFlightEffects()
    }

    @Test
    fun savedServerKeepsOneEntryWhicheverAddressWins() = runTest {
        val tailnet = OpenCodeServer(
            url = "https://box.ts.net:4096", username = "opencode", password = "pw",
            alternateURLs = listOf("http://192.168.1.20:4096"),
        )
        val lan = tailnet.at("http://192.168.1.20:4096")
        val initialState = shared.appState().load(tailnet)
        saveAll(listOf(tailnet))
        val store = store(
            initialState,
            OpenCodeClient.testValue.copy(
                health = { server -> if (server.url != lan.url) throw OpenCodeClientError("Offline") },
                homeSnapshot = { emptySnapshot },
            ),
        )
        store.exhaustive = false

        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()
        assertEquals(AppFeature.State.Connection.LoadingHome(lan), store.state.connection)
        assertEquals(listOf(tailnet), savedServers)
        store.receiveCase<AppFeature.Action.HomeResponse>()

        // Removing the entry by any of its addresses forgets the server.
        store.send(AppFeature.Action.ServerDeleted(tailnet))
        assertTrue(savedServers.isEmpty())
        assertNull(savedServer)
        store.finish()
    }

    @Test
    fun connectUsesTheFasterLANWithoutReorderingTheSavedAddresses() = runTest {
        val initialState = shared.appState().copy(
            serverURL = "http://192.168.1.20:4102",
            alternateURLs = listOf("http://100.84.220.87:4102"),
            password = "pw",
        )
        val store = store(
            initialState,
            OpenCodeClient.testValue.copy(
                health = { server -> delay(if (server.url.contains("100.84")) 100.milliseconds else 10.milliseconds) },
                homeSnapshot = { emptySnapshot },
            ),
        )
        store.exhaustive = false

        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()

        // Fastest verified answer wins; no route is preferred over another.
        assertEquals(AppFeature.State.Connection.LoadingHome(store.state.server), store.state.connection)
        assertEquals("http://192.168.1.20:4102", store.state.serverURL)
        assertEquals("http://192.168.1.20:4102", savedServer?.url)
        assertEquals(listOf("http://100.84.220.87:4102"), store.state.alternateURLs)
        effects.skipInFlightEffects()
    }

    @Test
    fun connectUsesTheFastestHealthyAddress() = runTest {
        val initialState = shared.appState().copy(
            serverURL = "https://slow.example:4096",
            alternateURLs = listOf("https://fast.example:4096"),
            password = "pw",
        )
        val store = store(
            initialState,
            OpenCodeClient.testValue.copy(
                health = { server -> delay(if (server.url.contains("slow")) 250.milliseconds else 10.milliseconds) },
                homeSnapshot = { emptySnapshot },
            ),
        )
        store.exhaustive = false

        store.send(AppFeature.Action.ConnectButtonTapped)
        store.receiveCase<AppFeature.Action.HealthResponse>()

        assertEquals(AppFeature.State.Connection.LoadingHome(store.state.server.at("https://fast.example:4096")), store.state.connection)
        assertEquals("https://slow.example:4096", store.state.serverURL)
        assertEquals(listOf("https://fast.example:4096"), store.state.alternateURLs)
        assertEquals("https://slow.example:4096", savedServer?.url)
        effects.skipInFlightEffects()
    }

    @Test
    fun lateHealthCannotReplaceTheSelectedServerOrItsCredentials() = runTest {
        val first = OpenCodeServer(url = "https://a.example", username = "alice", password = "a")
        val second = OpenCodeServer(url = "https://b.example", username = "bob", password = "b", alternateURLs = listOf("https://b-alternate.example"))
        val winner = second.at("https://b-alternate.example")
        val store = suspendedConnectionStore()

        store.send(AppFeature.Action.ServerTapped(first))
        val firstID = store.state.connectionAttempt!!.id
        store.send(AppFeature.Action.ServerTapped(second))
        val secondID = store.state.connectionAttempt!!.id
        assertNotEquals(firstID, secondID)

        store.send(AppFeature.Action.HealthResponse(secondID, Result.success(winner)))
        store.send(AppFeature.Action.HealthResponse(firstID, Result.success(first)))
        store.send(AppFeature.Action.HealthResponse(firstID, Result.failure(OpenCodeClientError("A went offline"))))

        assertEquals(second, store.state.server)
        assertEquals(second, savedServer)
        assertEquals(listOf(second), savedServers)
        assertEquals(AppFeature.State.Connection.LoadingHome(winner), store.state.connection)
        assertEquals(winner.id, store.state.connectingServerID)
        assertNull(store.state.home)
        store.send(AppFeature.Action.CancelEditingTapped)
        store.finish()
    }

    @Test
    fun lateHomeCannotMixServersOrEndTheCurrentLoadingPhase() = runTest {
        val first = OpenCodeServer(url = "https://a.example", username = "alice", password = "a")
        val second = OpenCodeServer(url = "https://b.example", username = "bob", password = "b")
        val firstHome = OpenCodeHomeSnapshot(
            projects = listOf(OpenCodeProject(id = "a-project", name = "A only", directory = "/a")), sessions = emptyList(), activeSessionIDs = emptySet(),
        )
        val secondHome = OpenCodeHomeSnapshot(
            projects = listOf(OpenCodeProject(id = "b-project", name = "B only", directory = "/b")), sessions = emptyList(), activeSessionIDs = emptySet(),
        )
        val store = suspendedConnectionStore()

        store.send(AppFeature.Action.ServerTapped(first))
        val firstID = store.state.connectionAttempt!!.id
        store.send(AppFeature.Action.HealthResponse(firstID, Result.success(first)))
        store.send(AppFeature.Action.ServerTapped(second))
        val secondID = store.state.connectionAttempt!!.id
        store.send(AppFeature.Action.HealthResponse(secondID, Result.success(second)))

        // Replay already-delivered responses at the reducer boundary: correctness must not rely on
        // the networking dependency cooperating with cancellation.
        store.send(AppFeature.Action.HomeResponse(firstID, Result.success(firstHome)))
        store.send(AppFeature.Action.HomeResponse(firstID, Result.failure(OpenCodeClientError("A snapshot failed"))))
        assertNull(store.state.home)
        assertEquals(AppFeature.State.Connection.LoadingHome(second), store.state.connection)
        assertEquals("Loading sessions", store.state.connectionProgressLabel)
        assertEquals(second.id, store.state.connectingServerID)

        store.send(AppFeature.Action.HomeResponse(secondID, Result.success(secondHome)))
        store.send(AppFeature.Action.HomeResponse(firstID, Result.success(firstHome)))
        store.send(AppFeature.Action.HomeResponse(firstID, Result.failure(OpenCodeClientError("A snapshot failed"))))
        assertEquals(home(second, secondHome), store.state.home)
        assertEquals(second, store.state.server)
        assertEquals(second, savedServer)
        assertEquals(AppFeature.State.Connection.Connected, store.state.connection)
        assertNull(store.state.connectionProgressLabel)
        store.send(AppFeature.Action.PairingCodeScanned("not a pairing code"))
        store.finish()
    }

    @Test
    fun cancellingEditingOrScanningInvalidatesBothPhases() = runTest {
        for (afterHealth in listOf(false, true)) {
            val server = OpenCodeServer(url = "https://a.example", username = "alice", password = "a")
            val interruptions: List<AppFeature.Action> = listOf(
                AppFeature.Action.CancelEditingTapped,
                AppFeature.Action.AddServerTapped,
                AppFeature.Action.EditServerTapped(server),
                AppFeature.Action.ScanPairingCodeTapped,
                AppFeature.Action.ScannerDismissed,
                AppFeature.Action.PairingCodeScanned("not a pairing code"),
                AppFeature.Action.ServerURLChanged("https://edited.example"),
                AppFeature.Action.UsernameChanged("edited-user"),
                AppFeature.Action.PasswordChanged("edited-password"),
            )
            for (interruption in interruptions) {
                val store = suspendedConnectionStore()
                store.send(AppFeature.Action.ServerTapped(server))
                val id = store.state.connectionAttempt!!.id
                if (afterHealth) store.send(AppFeature.Action.HealthResponse(id, Result.success(server)))
                store.send(interruption)
                val connection = store.state.connection
                val editedServer = store.state.server
                val saved = savedServers
                assertNull("$interruption", store.state.connectionAttempt)

                store.send(AppFeature.Action.HealthResponse(id, Result.success(server)))
                store.send(AppFeature.Action.HealthResponse(id, Result.failure(OpenCodeClientError("Late health failure"))))
                store.send(AppFeature.Action.HomeResponse(id, Result.success(emptySnapshot)))
                store.send(AppFeature.Action.HomeResponse(id, Result.failure(OpenCodeClientError("Late home failure"))))
                assertEquals("$interruption", connection, store.state.connection)
                assertEquals("$interruption", editedServer, store.state.server)
                assertEquals("$interruption", saved, savedServers)
                assertNull("$interruption", store.state.home)
                assertNull("$interruption", store.state.connectionProgressLabel)
                store.finish()
            }
        }
    }

    @Test
    fun retryingTheSameAddressUsesANewAttempt() = runTest {
        val original = OpenCodeServer(url = "https://a.example", username = "alice", password = "old")
        val updated = OpenCodeServer(url = original.url, username = "bob", password = "new")
        val store = suspendedConnectionStore()
        store.send(AppFeature.Action.ServerTapped(original))
        val oldID = store.state.connectionAttempt!!.id
        store.send(AppFeature.Action.ServerTapped(updated))
        val newID = store.state.connectionAttempt!!.id
        assertNotEquals(oldID, newID)
        store.send(AppFeature.Action.HealthResponse(oldID, Result.success(original)))
        assertEquals(AppFeature.State.Connection.Connecting, store.state.connection)
        assertEquals(updated, store.state.server)
        assertNull(savedServer)
        store.send(AppFeature.Action.CancelEditingTapped)
        store.finish()
    }

    @Test
    fun unchangedFormBindingsDoNotCancelConnection() = runTest {
        for (afterHealth in listOf(false, true)) {
            val server = OpenCodeServer(url = "https://fixture.example", username = "fixture", password = "")
            val store = suspendedConnectionStore()
            store.send(AppFeature.Action.ServerTapped(server))
            val attempt = store.state.connectionAttempt
            if (afterHealth) store.send(AppFeature.Action.HealthResponse(attempt!!.id, Result.success(server)))
            val connection = store.state.connection
            // These unchanged values arrive as the credential sheet resigns focus.
            store.send(AppFeature.Action.ServerURLChanged(server.url))
            store.send(AppFeature.Action.UsernameChanged(server.username))
            store.send(AppFeature.Action.PasswordChanged(server.password))
            assertEquals(attempt, store.state.connectionAttempt)
            assertEquals(connection, store.state.connection)
            store.send(AppFeature.Action.CancelEditingTapped)
            store.finish()
        }
    }
}
