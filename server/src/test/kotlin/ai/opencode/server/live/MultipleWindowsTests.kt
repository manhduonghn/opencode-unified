package ai.opencode.server.live

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MultipleWindowsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    /** An upstream the test feeds by hand, counting how often it is opened. */
    private class Upstream {
        private val lock = Any()
        private val streams = mutableListOf<SendChannel<OpenCodeServerEvent>>()
        @Volatile var isTerminated = false
            private set
        val opened: Int get() = synchronized(lock) { streams.size }

        fun make(): Flow<OpenCodeServerEvent> = callbackFlow {
            synchronized(lock) { streams += channel }
            awaitClose { isTerminated = true }
        }

        fun send(event: OpenCodeServerEvent) {
            synchronized(lock) { streams.lastOrNull() }?.trySend(event)
        }

        fun end() {
            synchronized(lock) { streams.lastOrNull() }?.close()
        }
    }

    private suspend fun eventually(timeout: Duration = 1.seconds, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(5) }
    }

    private suspend fun ReceiveChannel<OpenCodeServerEvent>.next(): OpenCodeServerEvent = withTimeout(1.seconds) { receive() }

    @Test
    fun windowsShareOneUpstream() = runBlocking(Dispatchers.Default) {
        val upstream = Upstream()
        val hub = SharedEventStreams { upstream.make() }
        coroutineScope {
            val first = hub.subscribe(server).produceIn(this)
            val second = hub.subscribe(server).produceIn(this)
            eventually { upstream.opened == 1 }
            delay(20)

            upstream.send(OpenCodeServerEvent.ExecutionStarted("a"))
            assertEquals(OpenCodeServerEvent.ExecutionStarted("a"), first.next())
            assertEquals(OpenCodeServerEvent.ExecutionStarted("a"), second.next())
            assertEquals(1, upstream.opened)
            assertEquals(1, hub.upstreamCount)
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun aLateWindowHearsConnectedAtOnce() = runBlocking(Dispatchers.Default) {
        val upstream = Upstream()
        val hub = SharedEventStreams { upstream.make() }
        coroutineScope {
            val first = hub.subscribe(server).produceIn(this)
            eventually { upstream.opened == 1 }
            upstream.send(OpenCodeServerEvent.Connected)
            assertEquals(OpenCodeServerEvent.Connected, first.next())

            val late = hub.subscribe(server).produceIn(this)
            assertEquals(OpenCodeServerEvent.Connected, late.next())
            assertEquals(1, upstream.opened)
            first.cancel()
            late.cancel()
        }
    }

    @Test
    fun aStalledWindowOverflowsWithoutDroppingTheOtherWindow() = runBlocking(Dispatchers.Default) {
        val upstream = Upstream()
        val hub = SharedEventStreams { upstream.make() }
        coroutineScope {
            val stalledSubscribed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Subscribes (its producer is running) but never reads until released.
            val stalled = async {
                val received = Channel<OpenCodeServerEvent>(Channel.RENDEZVOUS)
                try {
                    hub.subscribe(server).collect { event ->
                        if (!stalledSubscribed.isCompleted) stalledSubscribed.complete(Unit)
                        release.await()
                        received.trySend(event)
                    }
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            val healthy = hub.subscribe(server).produceIn(this)
            eventually { upstream.opened == 1 }
            upstream.send(OpenCodeServerEvent.Connected)
            assertEquals(OpenCodeServerEvent.Connected, healthy.next())
            stalledSubscribed.await()
            // Read each event in the healthy window while the other never reads.
            for (index in 0 until 129) {
                val event = OpenCodeServerEvent.ExecutionEnded("s$index")
                upstream.send(event)
                assertEquals(event, healthy.next())
            }
            release.complete(Unit)
            val error = withTimeout(2.seconds) { stalled.await() }
            assertTrue("A stalled subscriber must report the gap, got $error", error is EventStreamOverflow)
            assertEquals(1, hub.upstreamCount)
            val recovered = hub.subscribe(server).produceIn(this)
            assertEquals("Resubscription must trigger REST hydration", OpenCodeServerEvent.Connected, recovered.next())
            assertEquals(1, upstream.opened)
            upstream.end()
            healthy.cancel()
            recovered.cancel()
        }
    }

    @Test
    fun theLastWindowLeavingCancelsTheUpstream() = runTest {
        val upstream = Upstream()
        val hub = SharedEventStreams { upstream.make() }
        val first = launch { hub.subscribe(server).collect {} }
        val second = launch { hub.subscribe(server).collect {} }
        // Register both windows before cancelling either one.
        testScheduler.runCurrent()
        assertEquals(1, upstream.opened)

        first.cancel()
        testScheduler.runCurrent()
        assertEquals(1, hub.upstreamCount)
        assertFalse(upstream.isTerminated)

        second.cancel()
        testScheduler.runCurrent()
        assertEquals(0, hub.upstreamCount)
        assertTrue(upstream.isTerminated)
    }

    @Test
    fun anEndedUpstreamEndsEveryWindowAndReopensOnTheNextSubscribe() = runBlocking(Dispatchers.Default) {
        val upstream = Upstream()
        val hub = SharedEventStreams { upstream.make() }
        coroutineScope {
            val ends = AtomicInteger(0)
            val first = launch { runCatching { hub.subscribe(server).collect { fail("No events were sent") } }.onFailure { ends.incrementAndGet() } }
            val second = launch { runCatching { hub.subscribe(server).collect { fail("No events were sent") } }.onFailure { ends.incrementAndGet() } }
            eventually { upstream.opened == 1 }
            delay(20)

            upstream.end()
            first.join()
            second.join()
            assertEquals(2, ends.get())

            val again = hub.subscribe(server).produceIn(this)
            eventually { upstream.opened == 2 }
            delay(20)
            upstream.send(OpenCodeServerEvent.ExecutionEnded("b"))
            assertEquals(OpenCodeServerEvent.ExecutionEnded("b"), again.next())
            again.cancel()
        }
    }

    @Test
    fun windowsReadingTogetherShareOneRequest() = runBlocking(Dispatchers.Default) {
        val reads = SharedReads<Int>(joinWindow = 5.seconds)
        val gate = CompletableDeferred<Unit>()
        val read: suspend () -> Int = {
            gate.await()
            7
        }
        val first = async { reads.run("server", read) }
        val second = async { reads.run("server", read) }
        delay(50)
        gate.complete(Unit)
        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(1, reads.sent)
    }

    @Test
    fun aLaterReadSendsItsOwnRequest() = runBlocking(Dispatchers.Default) {
        // Joined only within the window: an older request may predate the change.
        val reads = SharedReads<Int>(joinWindow = Duration.ZERO)
        val count = AtomicInteger(0)
        val read: suspend () -> Int = { count.incrementAndGet() }
        reads.run("server", read)
        reads.run("server", read)
        assertEquals(2, reads.sent)
        reads.run("other", read)
        assertEquals(3, reads.sent)
    }
}
