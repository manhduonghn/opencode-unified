package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.retry.NetworkPathClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.ServerContact
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The event stream reconnects on the shared backoff, at once on a network change, and resyncs from
 * REST each time it connects.
 */
class EventStreamReconnectTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")
    private val empty = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())

    private fun HomeFeature.State.streaming(status: HomeConnectionFeature.State.ConnectionStatus? = null) = copy(
        connection = connection.copy(isStreaming = true, connectionStatus = status ?: connection.connectionStatus),
        sessionDetail = null,
    )

    /** A cancellable scope on the test's scheduler. */
    private fun TestScope.effectScope() = CoroutineScope(coroutineContext + Job())

    private fun matching(name: String, test: (HomeFeature.Action) -> Boolean) = test to name

    private suspend fun TestStore<HomeFeature.State, HomeFeature.Action>.receive(m: Pair<(HomeFeature.Action) -> Boolean, String>) =
        receive(m.first, m.second, timeout = 5.seconds)

    private val serverConnected = matching("serverConnected") { it is HomeFeature.Action.ServerConnected }
    private val connectionLost = matching("connectionLost") { it == HomeFeature.Action.ConnectionLost }
    private val snapshotRefreshed = matching("snapshotRefreshed") { it is HomeFeature.Action.SnapshotRefreshed }
    private val attentionRefreshed = matching("attentionRefreshed") { it is HomeFeature.Action.AttentionRefreshed }

    private fun repeatedOverflows(useAlternate: Boolean) = runTest {
        val clock = TestClock()
        val server = OpenCodeServer(url = "https://primary.example", username = "", password = "", alternateURLs = listOf("https://alternate.example"))
        val expectedAddress = server.candidateURLs[if (useAlternate) 1 else 0]
        val addresses = Collections.synchronizedList(mutableListOf<String>())
        val channels = Collections.synchronizedList(mutableListOf<Channel<OpenCodeServerEvent>>())
        val snapshots = AtomicInteger(0)
        val wakeListeners = AtomicInteger(0)
        val state = HomeFeature.State(server, empty).streaming()
        val effects = effectScope()
        val store = TestStore(state, HomeFeature(), effects) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                // Only the expected address answers the race.
                health = { candidate ->
                    if (candidate.url != expectedAddress) throw OpenCodeClientError.unreachable(TransportFailure.NotSent)
                },
                events = { candidate ->
                    addresses += candidate.url
                    if (candidate.url != expectedAddress) {
                        flow { throw IOException("Cannot connect to host") }
                    } else {
                        val channel = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
                        channels += channel
                        channel.trySend(OpenCodeServerEvent.Connected)
                        channel.receiveAsFlow()
                    }
                },
                homeSnapshot = {
                    snapshots.incrementAndGet()
                    empty
                },
                homeAttention = { _, _, _ -> emptySet() },
            )
            // Even a stale offline path with an immediately available network, or a REST answer
            // ready to wake a retry, cannot bypass local backpressure.
            this[NetworkPathClient] = NetworkPathClient.testValue.copy(
                isOffline = { true },
                available = {},
                serverAnswered = { wakeListeners.incrementAndGet() },
            )
        }
        // Exercise real Home REST effects; assert their counts and the retry timing rather than
        // unrelated persistence and notice state transitions.
        store.exhaustive = false
        store.send(HomeFeature.Action.NetworkRerouted)
        for ((index, ceiling) in listOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000).withIndex()) {
            val attempt = index + 1
            store.receive(serverConnected)
            store.receive(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.Connected))
            clock.advance(250.milliseconds)
            store.receive(snapshotRefreshed)
            clock.advance(150.milliseconds)
            store.receive(attentionRefreshed)
            assertEquals("Exactly one authoritative Home reload per connection", attempt, snapshots.get())

            channels[index].close(EventStreamOverflow())
            store.receive(connectionLost)
            clock.advance((ceiling / 2 - 1).milliseconds)
            assertEquals("No reconnect before the growing equal-jitter minimum", attempt, channels.size)
            assertEquals("No REST reloads during overload backoff", attempt, snapshots.get())
            assertEquals("REST responses must not wake an overflow retry", 0, wakeListeners.get())
            if (index < 5) clock.advance((ceiling / 2 + 1).milliseconds)
        }
        // The race found the answering address, so no stream tried the other; overflows reconnect
        // to the same one.
        assertEquals(List(6) { expectedAddress }, addresses.toList())
        effects.cancel()
        clock.advance(HomeFeature.lastKnownActivityLifetime)
        assertEquals("Cancellation during overflow backoff prevents further subscriptions", 6, channels.size)
        assertEquals(6, snapshots.get())
        store.finish()
    }

    @Test
    fun repeatedOverflowsBackOffOnTheSameAddressAndBoundRESTReloads() {
        repeatedOverflows(useAlternate = false)
        repeatedOverflows(useAlternate = true)
    }

    @Test
    fun aLongOutageKeepsRetriesBoundedAndAServerAnswerWakesOneAttempt() = runTest {
        val clock = TestClock()
        val subscriptions = AtomicInteger(0)
        val contact = ServerContact()
        val operations = Collections.synchronizedList(mutableListOf<String?>())
        val listening = Channel<Unit>(Channel.UNLIMITED)
        val state = HomeFeature.State(server, empty, loaded = false).streaming(HomeConnectionFeature.State.ConnectionStatus.disconnected)
        val effects = effectScope()
        val store = TestStore(state, HomeFeature(), effects) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                events = { _ ->
                    flow {
                        subscriptions.incrementAndGet()
                        operations += NetworkRetry.streamOperationID.current()
                        throw OpenCodeClientError(message = "Outage", statusCode = 503)
                    }
                },
            )
            this[NetworkPathClient] = NetworkPathClient.testValue.copy(
                serverAnswered = { server ->
                    val answers = contact.answers(server)
                    listening.trySend(Unit)
                    answers.first()
                },
            )
        }

        store.send(HomeFeature.Action.NetworkRerouted)
        store.receive(connectionLost)
        // The first failure cancels the scheduled snapshot. No failed read may replace durable
        // state, and no health probe is needed to keep retrying.
        for (attempt in 1..6) {
            clock.advance((if (attempt == 1) 400 else 800).milliseconds)
            assertEquals(attempt, subscriptions.get())
            repeat(if (attempt == 1) 6 else 12) {
                if (subscriptions.get() > attempt) return@repeat
                clock.advance(100.milliseconds)
            }
            store.receive(connectionLost)
            assertEquals(attempt + 1, subscriptions.get())
        }
        repeat(7) { listening.receive() }
        contact.heard(server.persistenceID)
        store.receive(connectionLost)
        assertEquals("An answer wakes the waiting retry without advancing the clock", 8, subscriptions.get())
        assertEquals(8, operations.size)
        assertEquals(1, operations.filterNotNull().toSet().size)
        assertTrue(operations.all { it != null })
        // The answer restarted the backoff: the next wait is its first (0.5–1 s).
        clock.advance(450.milliseconds)
        assertEquals("A single recovery cue cannot cause a retry storm", 8, subscriptions.get())
        effects.cancel()
        store.finish()
    }

    @Test
    fun backgroundCancelsAnOutageWithoutHoldingBackgroundTime() = runTest {
        val clock = TestClock()
        val subscriptions = AtomicInteger(0)
        val state = HomeFeature.State(server, empty).streaming(HomeConnectionFeature.State.ConnectionStatus.disconnected)
        val store = TestStore(state, HomeFeature(), this) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                events = { _ ->
                    flow {
                        subscriptions.incrementAndGet()
                        throw IOException("Cannot connect to host")
                    }
                },
            )
            this[BackgroundTimeClient] = BackgroundTimeClient { _, _ -> throw AssertionError("An outage must not hold background time") }
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.NetworkRerouted)
        store.receive(connectionLost)
        store.send(HomeFeature.Action.SceneEnteredBackground) {
            it.copy(connection = it.connection.copy(isInBackground = true))
        }
        clock.advance(120.seconds)
        assertEquals(1, subscriptions.get())
        store.finish()
    }

    /** Each subscription says `.connected`; the first then drops, later ones stay open. */
    private fun TestScope.store(
        clock: TestClock,
        subscriptions: AtomicInteger,
        snapshots: AtomicInteger,
        reroutes: Flow<Unit>? = null,
        answers: Flow<Unit>? = null,
    ): TestStore<HomeFeature.State, HomeFeature.Action> {
        val state = HomeFeature.State(server, empty).let { it.copy(connection = it.connection.copy(isStreaming = true)) }
        val store = TestStore(state, HomeFeature(), backgroundScope) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                events = { _ ->
                    val number = subscriptions.incrementAndGet()
                    flow {
                        emit(OpenCodeServerEvent.Connected)
                        if (number == 1) throw IOException("The network connection was lost")
                        kotlinx.coroutines.awaitCancellation()
                    }
                },
                homeSnapshot = {
                    snapshots.incrementAndGet()
                    empty
                },
                homeAttention = { _, _, _ -> emptySet() },
            )
            var path = NetworkPathClient.testValue
            if (reroutes != null) path = path.copy(reroutes = { reroutes })
            if (answers != null) path = path.copy(serverAnswered = { answers.first() })
            this[NetworkPathClient] = path
        }
        store.exhaustive = false
        return store
    }

    @Test
    fun aDroppedStreamReconnectsOnTheBackoffAndResyncs() = runTest {
        val clock = TestClock()
        val subscriptions = AtomicInteger(0)
        val snapshots = AtomicInteger(0)
        val store = store(clock, subscriptions, snapshots)

        store.send(HomeFeature.Action.NetworkRerouted)
        store.receive(serverConnected)
        store.receive(connectionLost.first, connectionLost.second) {
            it.copy(connection = it.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting))
        }
        assertEquals(1, subscriptions.get())

        // The first reconnect waits 0.5–1 s, then the new stream rehydrates from REST.
        clock.advance(400.milliseconds)
        assertEquals("Not before half the first ceiling", 1, subscriptions.get())
        clock.advance(600.milliseconds)
        store.receive(serverConnected)
        assertEquals(2, subscriptions.get())
        // TCA reduces the reconnect's own `.serverEvent(.connected)` as it arrives, before the clock
        // moves; this TestStore reduces received actions when asked, so take it (and its refresh
        // timer, which starts sleeping before the clock moves) first.
        store.skipReceivedActions()
        runCurrent()
        clock.advance(1.seconds)
        store.receive(snapshotRefreshed)
        assertTrue(snapshots.get() >= 1)
    }

    @Test
    fun aRequestReachingTheServerCutsTheBackoffShort() = runTest {
        val clock = TestClock()
        val subscriptions = AtomicInteger(0)
        val snapshots = AtomicInteger(0)
        val answers = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val store = store(clock, subscriptions, snapshots, answers = answers)

        store.send(HomeFeature.Action.NetworkRerouted)
        store.receive(serverConnected)
        store.receive(connectionLost)
        // A read went through while the stream waited: the route is back.
        answers.emit(Unit)
        store.receive(serverConnected)
        assertEquals(2, subscriptions.get())
    }

    @Test
    fun aNetworkChangeCutsTheBackoffShort() = runTest {
        val clock = TestClock()
        val subscriptions = AtomicInteger(0)
        val snapshots = AtomicInteger(0)
        val reroutes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val store = store(clock, subscriptions, snapshots, reroutes = reroutes)

        store.send(HomeFeature.Action.NetworkRerouted)
        store.receive(serverConnected)
        store.receive(connectionLost)
        // Cellular came back (or moved to Wi-Fi): reconnect without waiting out the backoff.
        reroutes.emit(Unit)
        store.receive(serverConnected)
        assertEquals(2, subscriptions.get())
    }
}
