package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withRequestFailureAwaitingReconnect
import ai.opencode.feature.session.withRequestsConnectionIsAvailable
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.ServerLinkStore
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.retry.NetworkPathClient
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How Home's connection rides out a lost server, the address race, the quiet grace before
 * Reconnecting, Offline, and what becoming active or leaving the app does to the event stream.
 */
class ServerLinkConnectionTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    private fun HomeFeature.State.key(id: String) = "${server.persistenceID}|$id"

    private companion object {
        const val lan = "http://192.168.1.155:49374"
        const val tailnet = "http://100.114.13.77:49374"
        val saved = OpenCodeServer(
            url = lan, username = "opencode", password = "1790000000.token", alternateURLs = listOf(tailnet), name = "Adams Mini",
        )

        /** Away from home: the LAN address never answers, the tailnet one does (`ServerLinkTests.awayFromHome`). */
        fun awayFromHome(lanCancelled: AtomicBoolean): suspend (OpenCodeServer) -> Unit = { candidate ->
            if (candidate.url == lan) {
                try {
                    delay(30.seconds)
                } catch (cancelled: CancellationException) {
                    lanCancelled.set(true)
                    throw cancelled
                }
                throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
            }
            delay(20.milliseconds)
        }

        fun connectedThenOpen(): kotlinx.coroutines.flow.Flow<OpenCodeServerEvent> {
            val channel = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
            channel.trySend(OpenCodeServerEvent.Connected)
            return channel.consumeAsFlow()
        }
    }

    /**
     * The saved server lists its LAN address first, which drops every packet off the home Wi-Fi.
     */
    @Test fun aColdLaunchAwayFromHomeConnectsThroughTheTailnetWithoutShowingAnything() = runTest {
        val clock = TestClock()
        val lanCancelled = AtomicBoolean(false)
        val streamed = Collections.synchronizedList(mutableListOf<String>())
        val tailnetServer = saved.at(tailnet)
        val state = HomeConnectionFeature.State(server = saved, connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[ServerLinkClient] = ServerLinkClient.live(ServerLinkStore(health = awayFromHome(lanCancelled)))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = { target ->
                    streamed += target.url
                    connectedThenOpen()
                },
            )
        }
        store.withDependencies { shared(ServerSharedKeys.savedServer).set(saved) }

        store.send(HomeConnectionFeature.Action.Task) {
            it.copy(isStreaming = true, isRecovering = true)
        }
        assertEquals("Quiet while it looks", HomeConnectionFeature.State.ConnectionStatus.connected, store.state.displayedStatus)
        store.receive(HomeConnectionFeature.Action.ServerConnected(tailnetServer), timeout = 2.seconds) {
            it.copy(server = tailnetServer, hasConnected = true, isEventStreamLive = true, isRecovering = false)
        }
        assertEquals(listOf(tailnet), streamed.toList())
        assertTrue(lanCancelled.get())
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.connected, store.state.displayedStatus)
        // Well past both graces: nothing was left running to flip it.
        clock.advance(60.seconds)
        assertFalse(store.state.showsRecovery)
        assertEquals("The saved address order is untouched", saved, store.withDependencies { shared(ServerSharedKeys.savedServer).value })
        store.skipInFlightEffects()
    }

    /** Each way the route can have changed forgets the address in use before the stream races again, so requests meanwhile race too. */
    @Test fun aChangedRouteForgetsTheAddressInUse() = runTest {
        for (
            action in listOf(
                HomeConnectionFeature.Action.NetworkRerouted,
                HomeConnectionFeature.Action.RetryConnectionTapped,
                HomeConnectionFeature.Action.SceneBecameActive,
            )
        ) {
            val order = Collections.synchronizedList(mutableListOf<String>())
            val state = HomeConnectionFeature.State(
                server = server,
                isStreaming = true,
                isInBackground = action == HomeConnectionFeature.Action.SceneBecameActive,
            )
            val store = TestStore(state, HomeConnectionFeature(), this) {
                this[Clock] = TestClock()
                this[ServerLinkClient] = this[ServerLinkClient].copy(invalidate = { _, failing -> order += "forget ${failing ?: "any"}" })
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    events = {
                        order += "stream"
                        connectedThenOpen()
                    },
                )
            }
            store.exhaustive = false
            store.send(action)
            store.receive({ it is HomeConnectionFeature.Action.ServerConnected }, "serverConnected")
            assertEquals("$action", listOf("forget any", "stream"), order.toList())
            store.skipInFlightEffects()
        }
    }

    @Test fun aDroppedStreamForgetsTheAddressItWasOn() = runTest {
        val forgotten = Collections.synchronizedList(mutableListOf<String?>())
        val live = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
        val store = TestStore(HomeConnectionFeature.State(server = server, isStreaming = true), HomeConnectionFeature(), this) {
            this[Clock] = TestClock()
            this[ServerLinkClient] = this[ServerLinkClient].copy(invalidate = { _, failing -> forgotten += failing })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    live.trySend(OpenCodeServerEvent.Connected)
                    live.consumeAsFlow()
                },
            )
        }
        store.exhaustive = false
        store.send(HomeConnectionFeature.Action.Task)
        store.receive({ it is HomeConnectionFeature.Action.ServerConnected }, "serverConnected")
        live.close(IOException("The network connection was lost."))
        store.receive(HomeConnectionFeature.Action.ConnectionLost)
        assertEquals(listOf<String?>(server.url), forgotten.toList())
        store.skipInFlightEffects()
    }

    /** A reroute while the app holds its stream in the background doesn't reconnect there: becoming active does. */
    @Test fun aRerouteInTheBackgroundStopsTheStream() = runTest {
        val state = HomeConnectionFeature.State(server = server, isStreaming = true, isEventStreamLive = true, isInBackground = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = { throw AssertionError("No stream in the background") },
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted) { it.copy(isEventStreamLive = false) }
        store.finish()
    }

    /** Back to a server shown Offline: a live stream ends Offline at once, so one failed snapshot can't leave it stuck there. */
    @Test fun aLiveStreamEndsOffline() = runTest {
        val state = HomeConnectionFeature.State(server = server, connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected)
        val store = TestStore(state, HomeConnectionFeature(), this)
        store.send(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                hasConnected = true,
                isEventStreamLive = true,
            )
        }
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.connected, store.state.displayedStatus)
        store.finish()
    }

    @Test fun withNoNetworkPathOfflineComesSooner() = runTest {
        val clock = TestClock()
        val now = Instant.ofEpochSecond(100)
        val store = TestStore(HomeConnectionFeature.State(server = server, isEventStreamLive = true), HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[NetworkPathClient] = NetworkPathClient.testValue.copy(isOffline = { true })
        }
        store.send(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
                isRecovering = true,
                recoveryFailures = 1,
            )
        }
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.connected, store.state.displayedStatus)
        clock.advance(2.5.seconds)
        store.receive(HomeConnectionFeature.Action.ReconnectingGraceElapsed) { it.copy(showsRecovery = true) }
        assertEquals(HomeConnectionFeature.State.ConnectionStatus.reconnecting, store.state.displayedStatus)
        clock.advance(1.5.seconds)
        store.receive(HomeConnectionFeature.Action.OfflineGraceElapsed(pathDown = true)) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                isRecovering = false,
                showsRecovery = false,
                recoveryFailures = 0,
            )
        }
        clock.advance(HomeFeature.lastKnownActivityLifetime)
        store.receive(HomeConnectionFeature.Action.LastKnownActivityExpired) { it.copy(lastKnownActivityDeadline = null) }
        store.finish()
    }

    @Test fun aRejectedSignInIsShownAtOnce() = runTest {
        val state = HomeConnectionFeature.State(
            server = server,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
            isRecovering = true,
        )
        val store = TestStore(state, HomeConnectionFeature(), this)
        val rejected = OpenCodeClientError("The server rejected these credentials.", statusCode = 401)
        store.send(HomeConnectionFeature.Action.EventStreamFailed(rejected)) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
                connectionErrorMessage = rejected.message,
                credentialsRejected = true,
                isRecovering = false,
            )
        }
        store.finish()
    }

    /** Pulling down the notification shade or a system dialog briefly pauses the activity. Coming back leaves a healthy stream alone. */
    @Test fun becomingActiveAfterABriefInterruptionLeavesALiveStreamAlone() = runTest {
        val state = HomeConnectionFeature.State(server = server, isStreaming = true, isEventStreamLive = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[ServerLinkClient] = this[ServerLinkClient].copy(streamSilence = { 5.seconds })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = { throw AssertionError("A healthy stream must not be restarted") },
            )
        }
        store.send(HomeConnectionFeature.Action.SceneBecameActive)
        store.finish()
    }

    @Test fun becomingActiveAfterTheBackgroundOrALongSilenceResyncs() = runTest {
        for (silent in listOf(false, true)) {
            val subscriptions = AtomicInteger(0)
            val state = HomeConnectionFeature.State(
                server = server,
                isStreaming = true,
                isEventStreamLive = true,
                hasConnected = true,
                isInBackground = !silent,
            )
            val store = TestStore(state, HomeConnectionFeature(), this) {
                this[Clock] = TestClock()
                this[ServerLinkClient] = this[ServerLinkClient].copy(
                    streamSilence = { if (silent) HomeConnectionFeature.foregroundSilence else 1.seconds },
                )
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    events = {
                        subscriptions.incrementAndGet()
                        connectedThenOpen()
                    },
                )
            }
            store.send(HomeConnectionFeature.Action.SceneBecameActive) {
                it.copy(isInBackground = false, isEventStreamLive = false)
            }
            store.receive(HomeConnectionFeature.Action.ServerConnected(server)) { it.copy(isEventStreamLive = true) }
            assertEquals("silent: $silent", 1, subscriptions.get())
            store.skipInFlightEffects()
        }
    }

    @Test fun becomingActiveWhileOfflineStartsAQuietReconnect() = runTest {
        val clock = TestClock()
        val state = HomeConnectionFeature.State(
            server = server,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
            isInBackground = true,
        )
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
        }
        store.send(HomeConnectionFeature.Action.SceneBecameActive) {
            it.copy(
                isInBackground = false,
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isRecovering = true,
            )
        }
        assertEquals("The old failure doesn't greet the return", HomeConnectionFeature.State.ConnectionStatus.connected, store.state.displayedStatus)
        clock.advance(2.5.seconds)
        store.receive(HomeConnectionFeature.Action.ReconnectingGraceElapsed) { it.copy(showsRecovery = true) }
        clock.advance(9.5.seconds)
        store.receive(HomeConnectionFeature.Action.OfflineGraceElapsed(pathDown = false)) { it.copy(offlineGraceElapsed = true) }
        store.finish()
    }

    /**
     * The stream drops while the app holds it open in the background (the phone was locked leaving
     * Wi-Fi): no reconnect loop runs until the app is active again.
     */
    @Test fun aStreamThatDropsInTheBackgroundIsNotRetriedThere() = runTest {
        val clock = TestClock()
        val now = Instant.ofEpochSecond(100)
        val subscriptions = AtomicInteger(0)
        val live = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
        val store = TestStore(HomeConnectionFeature.State(server = server, isStreaming = true), HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[BackgroundTimeClient] = BackgroundTimeClient { _, _ -> clock.sleep(3600.seconds) }
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions.incrementAndGet()
                    live.trySend(OpenCodeServerEvent.Connected)
                    live.consumeAsFlow()
                },
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted)
        store.receive(HomeConnectionFeature.Action.ServerConnected(server)) {
            it.copy(hasConnected = true, isEventStreamLive = true)
        }
        store.send(HomeConnectionFeature.Action.SceneEnteredBackground) { it.copy(isInBackground = true) }
        live.close(IOException("The network connection was lost."))
        store.receive(HomeConnectionFeature.Action.ConnectionLost) {
            it.copy(
                connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                isEventStreamLive = false,
                lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
            )
        }
        clock.advance(60.seconds)
        assertEquals("No background reconnect loop", 1, subscriptions.get())
        assertFalse(store.state.isRecovering)
        clock.advance(HomeFeature.lastKnownActivityLifetime)
        store.receive(HomeConnectionFeature.Action.LastKnownActivityExpired) { it.copy(lastKnownActivityDeadline = null) }
        store.finish()
    }

    /**
     * A server down for minutes is asked less and less often (five minutes at most), but a changed
     * route wakes the wait and starts the backoff over.
     */
    @Test fun aLongOutageBacksOffAndARerouteStartsItOver() = runTest {
        val clock = TestClock()
        val subscriptions = AtomicInteger(0)
        val listeners = Collections.synchronizedList(mutableListOf<Channel<Unit>>())
        val state = HomeConnectionFeature.State(
            server = server,
            isStreaming = true,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected,
        )
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = {
                    subscriptions.incrementAndGet()
                    flow { throw OpenCodeClientError.unreachable(TransportFailure.NotSent) }
                },
            )
            this[NetworkPathClient] = NetworkPathClient.testValue.copy(
                reroutes = {
                    val channel = Channel<Unit>(Channel.UNLIMITED)
                    listeners += channel
                    channel.receiveAsFlow()
                },
            )
        }
        store.exhaustive = false
        store.send(HomeConnectionFeature.Action.RetryConnectionTapped)
        // Ten minutes of failures: the waits ramp past the fast window.
        repeat(600) { clock.advance(1.seconds) }
        val afterTenMinutes = subscriptions.get()
        assertTrue("Not every 1–2 s for ten minutes ($afterTenMinutes)", afterTenMinutes < 30)
        assertTrue(afterTenMinutes > 5)
        // The longest wait is five minutes: well within the next six, one more try.
        repeat(360) { clock.advance(1.seconds) }
        assertTrue(subscriptions.get() > afterTenMinutes)
        val beforeReroute = subscriptions.get()
        for (listener in listeners.toList()) listener.trySend(Unit)
        for (attempt in 0 until 100) {
            if (subscriptions.get() != beforeReroute) break
            delay(5)
        }
        assertEquals("A reroute tries again at once", beforeReroute + 1, subscriptions.get())
        // And the backoff started over: the next try is within two seconds.
        clock.advance(2.seconds)
        assertTrue(subscriptions.get() >= beforeReroute + 2)
        for (listener in listeners.toList()) listener.close()
        store.skipInFlightEffects()
    }

    // Home recovery

    @Test
    fun retryReplacesCachedHomeWithAuthoritativeSnapshotAndReconnects() = homeTest { h ->
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "secret")
        val oldSession = OpenCodeSession(id = "ses_old", serverTitle = "Old work", projectID = "proj_old", directory = "/old", updatedAt = date(1_000))
        val latest = OpenCodeSession(id = "ses_latest", serverTitle = "Latest work", projectID = "proj_latest", directory = "/latest", updatedAt = date(2_000))
        val refreshed = snapshot(listOf(latest), active = setOf(latest.id), projects = listOf(OpenCodeProject(id = "proj_latest", name = "Latest", directory = "/latest")))
        val state = h.state {
            val base = HomeFeature.State(server, snapshot(listOf(oldSession), projects = listOf(OpenCodeProject(id = "proj_old", name = "Old", directory = "/old"))))
            base.copy(connection = base.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected))
                .persistingSnapshot(date(0))
                .let { it.copy(transcriptCache = it.transcriptCache + (latest.id to listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "cached")))) }
        }
        val clock = TestClock()
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { requested ->
                    assertEquals(server, requested)
                    refreshed
                },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }

        store.send(HomeFeature.Action.RetryConnectionTapped) {
            it.copy(
                connection = it.connection.copy(
                    connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                    showsRecovery = true,
                    isRecovering = true,
                ),
            )
        }
        assertEquals(listOf(oldSession), store.state.sessions)
        clock.advance(250.milliseconds)
        store.receive(HomeFeature.Action.SnapshotRefreshed(refreshed)) {
            it.copy(
                noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                projects = refreshed.projects,
                sessions = listOf(latest),
                activeSessionIDs = setOf(latest.id),
                sessionDetail = it.sessionDetail?.copy(projects = refreshed.projects),
                connection = it.connection.copy(
                    connectionStatus = HomeConnectionFeature.State.ConnectionStatus.connected,
                    showsRecovery = false,
                    isRecovering = false,
                ),
                sessionReadDates = it.sessionReadDates + (it.key(latest.id) to latest.updatedAt),
                initializedInboxServerIDs = setOf(server.persistenceID),
            )
        }
        store.receive(detail(SessionFeature.Action.RequestsConnectionChanged(true))) // the draft hears it's back
        clock.advance(150.milliseconds)
        store.receive(HomeFeature.Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }
        assertTrue(store.state.isConnected)
        assertTrue(store.state.sessions.none { store.state.isUnread(it) })
        assertEquals(CachedHomeSnapshot(server.persistenceID, refreshed, date(0)), h.state { store.state.cachedHomeSnapshot })
        store.finish()
    }

    @Test
    fun lostConnectionAndFailedRetryRetainCachedSessions() = homeTest { h ->
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "secret")
        val session = OpenCodeSession(id = "ses_cached", serverTitle = "Cached work", projectID = "proj_cached", directory = "/work", updatedAt = date(1_000))
        val snapshot = snapshot(listOf(session), active = setOf(session.id), projects = listOf(OpenCodeProject(id = "proj_cached", name = "Work", directory = "/work")))
        val lostAt = date(5_000)
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m_cached", text = "Cached message"))
        val state = h.state {
            val base = HomeFeature.State(server, snapshot).persistingSnapshot(lostAt)
            base.copy(
                sessionDetail = SessionFeature.State.session(server, session, cachedItems = items).withDraft("Unsent draft"),
                connection = base.connection.copy(hasConnected = true),
            )
        }
        val cache = h.state { state.cachedHomeSnapshot }
        var detail = state.sessionDetail
        val clock = TestClock()
        val store = h.store(state) {
            this[Clock] = clock
            this[HomeSnapshotDate] = DateClient.constant(lostAt)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(homeSnapshot = { _ -> throw OpenCodeClientError("Offline") })
        }

        store.send(HomeFeature.Action.ConnectionLost) {
            it.copy(
                connection = it.connection.copy(
                    connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                    lastKnownActivityDeadline = lostAt.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
                    isRecovering = true,
                    recoveryFailures = 1,
                ),
            )
        }
        store.receive(detail(SessionFeature.Action.RequestsConnectionChanged(false))) {
            it.copy(sessionDetail = it.sessionDetail?.withRequestsConnectionIsAvailable(false)?.withRequestFailureAwaitingReconnect(true))
        }
        detail = detail?.withRequestsConnectionIsAvailable(false)?.withRequestFailureAwaitingReconnect(true)
        assertEquals(listOf(session), store.state.sessions)
        assertEquals("Reconnecting keeps the last-known state on screen", HomeFeature.State.Indicator.running, store.state.indicator(session))
        clock.advance(2.5.seconds)
        store.receive(HomeFeature.Action.ReconnectingGraceElapsed) { it.copy(connection = it.connection.copy(showsRecovery = true)) }
        clock.advance(9.5.seconds)
        // One failure in all: past the grace, but not yet an outage.
        store.receive(HomeFeature.Action.OfflineGraceElapsed(pathDown = false)) { it.copy(connection = it.connection.copy(offlineGraceElapsed = true)) }
        // Not forever: two minutes after the stream was live, it's no longer trusted.
        clock.advance(107.seconds)
        assertEquals(HomeFeature.State.Indicator.running, store.state.indicator(session))
        clock.advance(1.seconds)
        store.receive(HomeFeature.Action.LastKnownActivityExpired) {
            it.copy(connection = it.connection.copy(lastKnownActivityDeadline = null))
        }
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(session))
        store.send(HomeFeature.Action.RetryConnectionTapped) {
            it.copy(connection = it.connection.copy(offlineGraceElapsed = false, recoveryFailures = 0))
        }
        clock.advance(250.milliseconds)
        store.receive(HomeFeature.Action.SnapshotFailed) { it.copy(connection = it.connection.copy(recoveryFailures = 1)) }
        store.receive(detail(SessionFeature.Action.RequestsConnectionChanged(false)))
        clock.advance(12.seconds)
        store.receive(HomeFeature.Action.OfflineGraceElapsed(pathDown = false)) { it.copy(connection = it.connection.copy(offlineGraceElapsed = true)) }
        assertFalse(store.state.isConnected)
        assertEquals(snapshot.projects, store.state.projects)
        assertEquals(snapshot.sessions, store.state.sessions)
        assertEquals(cache, h.state { store.state.cachedHomeSnapshot })
        assertEquals(detail, store.state.sessionDetail)
        assertTrue(store.state.initializedInboxServerIDs.isEmpty())
        store.finish()
    }
}
