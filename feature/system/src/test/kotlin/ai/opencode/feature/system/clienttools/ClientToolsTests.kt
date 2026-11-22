package ai.opencode.feature.system.clienttools

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.system.clienttools.ClientToolsFeature.Action
import ai.opencode.feature.system.clienttools.ClientToolsFeature.DelegateAction
import ai.opencode.feature.system.clienttools.ClientToolsFeature.State
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientMcpProtocol
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.ClientToolsEvent
import ai.opencode.server.clienttools.ClientToolsTarget
import ai.opencode.server.retry.NetworkPathClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Its reducer tests (MARK: Reducer). */
class ClientToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://studio.example.ts.net:4096", username = "opencode", password = "pw")
    private val screen = setOf(ClientTool.voiceRequestScreen)
    private val target get() = ClientToolsTarget(server = server, directory = "/work/app", tools = screen)
    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val third = UUID.fromString("00000000-0000-0000-0000-000000000021")

    @Test fun connectsReconnectsWithBackoffAndDisconnects() = runTest {
        val clock = TestClock()
        val sockets = mutableListOf<Channel<ClientToolsEvent>>()
        val store = TestStore(State(tools = screen), ClientToolsFeature(), this) {
            this[Clock] = clock
            this[ClientToolsClient] = ClientToolsClient(
                connect = {
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    synchronized(sockets) { sockets.add(channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, _ -> true },
            )
        }

        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Connecting) }
        waitForSockets(sockets, count = 1)
        sockets[0].send(ClientToolsEvent.Attached(connection))
        store.receive(Action.Socket(ClientToolsEvent.Attached(connection))) {
            it.copy(status = State.Status.Attached, connection = connection)
        }

        sockets[0].send(ClientToolsEvent.Closed(connection, "close code 1006"))
        sockets[0].close()
        store.receive(Action.Socket(ClientToolsEvent.Closed(connection, "close code 1006"))) {
            it.copy(status = State.Status.Waiting, failures = 1, connection = null)
        }
        // What its calls showed goes with it.
        store.receive(Action.Delegate(DelegateAction.Disconnected(connection)))
        clock.advance(499.milliseconds)
        clock.advance(1.milliseconds)
        store.receive(Action.RetryElapsed) { it.copy(status = State.Status.Connecting) }
        waitForSockets(sockets, count = 2)

        // A second failure before any handshake backs off longer.
        sockets[1].send(ClientToolsEvent.Closed(second, "HTTP 502"))
        sockets[1].close()
        store.receive(Action.Socket(ClientToolsEvent.Closed(second, "HTTP 502"))) {
            it.copy(status = State.Status.Waiting, failures = 2)
        }
        store.receive(Action.Delegate(DelegateAction.Disconnected(second)))
        clock.advance(999.milliseconds)
        clock.advance(1.milliseconds)
        store.receive(Action.RetryElapsed) { it.copy(status = State.Status.Connecting) }
        waitForSockets(sockets, count = 3)
        sockets[2].send(ClientToolsEvent.Attached(third))
        store.receive(Action.Socket(ClientToolsEvent.Attached(third))) {
            it.copy(status = State.Status.Attached, failures = 0, connection = third)
        }

        // The same target again keeps the socket, renamed or not; null closes it.
        val renamed = ClientToolsTarget(server = server.copy(name = "Studio"), directory = "/work/app", tools = screen)
        store.send(Action.TargetChanged(renamed))
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle, connection = null) }
        store.receive(Action.Delegate(DelegateAction.Disconnected(third)))
        store.finish()
    }

    @Test fun aServerWithoutTheRouteIsNotRetriedUntilTheServerChanges() = runTest {
        val clock = TestClock()
        val sockets = mutableListOf<Channel<ClientToolsEvent>>()
        val store = TestStore(State(tools = screen), ClientToolsFeature(), this) {
            this[Clock] = clock
            this[ClientToolsClient] = ClientToolsClient(
                connect = {
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    synchronized(sockets) { sockets.add(channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, _ -> true },
            )
        }
        assertEquals(setOf(404, 405, 501), ClientMcpProtocol.PERMANENT_STATUSES)

        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Connecting) }
        waitForSockets(sockets, count = 1)
        sockets[0].send(ClientToolsEvent.Unavailable(connection, 404))
        sockets[0].close()
        store.receive(Action.Socket(ClientToolsEvent.Unavailable(connection, 404))) {
            it.copy(status = State.Status.Unavailable, unavailableServer = server)
        }
        store.receive(Action.Delegate(DelegateAction.Disconnected(connection)))
        // No backoff: nothing wakes to try again.
        clock.advance(60.seconds)
        assertEquals(1, synchronized(sockets) { sockets.size })

        // Backgrounding and coming back (the target goes and returns), or a different tool list,
        // still finds the same server without the route.
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle) }
        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Unavailable) }
        val more = ClientToolsTarget(server = server, directory = "/work/app", tools = setOf(ClientTool.voiceRequestScreen, ClientTool.voiceOpenURL))
        store.send(Action.TargetChanged(more)) { it.copy(target = more) }
        clock.advance(60.seconds)
        assertEquals(1, synchronized(sockets) { sockets.size })

        // A changed server (here its password) tries again.
        val changed = ClientToolsTarget(server = server.copy(password = "new"), directory = "/work/app", tools = screen)
        store.send(Action.TargetChanged(changed)) {
            it.copy(target = changed, status = State.Status.Connecting, unavailableServer = null)
        }
        waitForSockets(sockets, count = 2)
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle) }
        store.finish()
    }

    @Test fun backoffDoublesToAMinute() {
        val delays = mutableListOf<kotlin.time.Duration>()
        for (failures in 0..9) delays.add(ClientToolsFeature.backoff(failures))
        assertEquals(
            listOf(
                500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 32.seconds,
                60.seconds, 60.seconds, 60.seconds,
            ),
            delays,
        )
    }

    /**
     * A long backoff mid-call doesn't hold the tools back once the network is back: a reroute
     * redials at once and starts the backoff over.
     */
    @Test fun aRerouteWakesTheBackoffAndStartsItOver() = runTest {
        val clock = TestClock()
        val sockets = mutableListOf<Channel<ClientToolsEvent>>()
        val reroutes = Channel<Unit>(Channel.UNLIMITED)
        val store = TestStore(State(tools = screen), ClientToolsFeature(), this) {
            this[Clock] = clock
            this[NetworkPathClient] = NetworkPathClient.testValue.copy(reroutes = { reroutes.receiveAsFlow() })
            this[ClientToolsClient] = ClientToolsClient(
                connect = {
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    synchronized(sockets) { sockets.add(channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, _ -> true },
            )
        }
        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Connecting) }
        waitForSockets(sockets, count = 1)
        // Six failures in: the next wait is a minute.
        for (index in 0 until 7) {
            sockets[index].send(ClientToolsEvent.Closed(connection, "close code 1006"))
            store.receive(Action.Socket(ClientToolsEvent.Closed(connection, "close code 1006"))) {
                it.copy(status = State.Status.Waiting, failures = index + 1)
            }
            store.receive(Action.Delegate(DelegateAction.Disconnected(connection)))
            if (index >= 6) break
            clock.advance(ClientToolsFeature.backoff(index))
            store.receive(Action.RetryElapsed) { it.copy(status = State.Status.Connecting) }
            waitForSockets(sockets, count = index + 2)
        }
        reroutes.send(Unit)
        store.receive(Action.Rerouted) { it.copy(status = State.Status.Connecting, failures = 0) }
        waitForSockets(sockets, count = 8)

        // A dial still in progress when the route changes starts over too.
        reroutes.send(Unit)
        store.receive(Action.Rerouted)
        waitForSockets(sockets, count = 9)
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle) }
        reroutes.close()
        store.finish()
    }

    /**
     * The server answering REST says nothing about its client MCP route: it wakes one wait, then
     * the backoff runs its course.
     */
    @Test fun aServerAnswerWakesOneWaitAndKeepsTheBackoff() = runTest {
        val clock = TestClock()
        val sockets = mutableListOf<Channel<ClientToolsEvent>>()
        val answers = mutableListOf<CompletableDeferred<Unit>>()
        val store = TestStore(State(tools = screen), ClientToolsFeature(), this) {
            this[Clock] = clock
            this[NetworkPathClient] = NetworkPathClient.testValue.copy(
                serverAnswered = {
                    val answer = CompletableDeferred<Unit>()
                    synchronized(answers) { answers.add(answer) }
                    answer.await()
                },
            )
            this[ClientToolsClient] = ClientToolsClient(
                connect = {
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    synchronized(sockets) { sockets.add(channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, _ -> true },
            )
        }
        store.send(Action.TargetChanged(target)) { it.copy(target = target, status = State.Status.Connecting) }
        waitForSockets(sockets, count = 1)
        sockets[0].send(ClientToolsEvent.Closed(connection, "HTTP 502"))
        store.receive(Action.Socket(ClientToolsEvent.Closed(connection, "HTTP 502"))) {
            it.copy(status = State.Status.Waiting, failures = 1)
        }
        store.receive(Action.Delegate(DelegateAction.Disconnected(connection)))
        waitForSockets(answers, count = 1)
        synchronized(answers) { answers.removeAt(0) }.complete(Unit)
        store.receive(Action.ServerAnswered) { it.copy(status = State.Status.Connecting, answerWakeUsed = true) }
        waitForSockets(sockets, count = 2)
        sockets[1].send(ClientToolsEvent.Closed(second, "HTTP 502"))
        store.receive(Action.Socket(ClientToolsEvent.Closed(second, "HTTP 502"))) {
            it.copy(status = State.Status.Waiting, failures = 2)
        }
        store.receive(Action.Delegate(DelegateAction.Disconnected(second)))
        // No second wake: it waits out its backoff (1 s).
        clock.advance(999.milliseconds)
        assertEquals(2, synchronized(sockets) { sockets.size })
        clock.advance(1.milliseconds)
        store.receive(Action.RetryElapsed) { it.copy(status = State.Status.Connecting) }
        waitForSockets(sockets, count = 3)
        assertTrue("Not listening for answers again until attached", synchronized(answers) { answers.isEmpty() })
        sockets[2].send(ClientToolsEvent.Attached(third))
        store.receive(Action.Socket(ClientToolsEvent.Attached(third))) {
            it.copy(status = State.Status.Attached, failures = 0, answerWakeUsed = false, connection = third)
        }
        store.send(Action.TargetChanged(null)) { it.copy(target = null, status = State.Status.Idle, connection = null) }
        store.receive(Action.Delegate(DelegateAction.Disconnected(third)))
        store.finish()
    }

    private suspend fun waitForSockets(sockets: List<Any>, count: Int) {
        for (attempt in 0 until 1_000) {
            if (synchronized(sockets) { sockets.size } >= count) break
            yield()
        }
        assertEquals(count, synchronized(sockets) { sockets.size })
    }
}
