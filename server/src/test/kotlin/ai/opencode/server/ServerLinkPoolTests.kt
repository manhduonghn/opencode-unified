package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.DataPayload
import ai.opencode.server.live.perform
import ai.opencode.server.live.request
import ai.opencode.server.live.untilLinkChanges
import ai.opencode.server.retry.AppPhase
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryEnvironment
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.TransportFailure
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Starting connections over: fresh OkHttp pools, reads hung on a dead socket going again, and what
 * triggers it.
 */
class ServerLinkPoolTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A store whose clock the test moves. */
    private fun store(
        resets: AtomicInteger = AtomicInteger(0),
        offset: AtomicReference<Duration> = AtomicReference(Duration.ZERO),
    ): ServerLinkStore = ServerLinkStore(
        health = {},
        resetPools = { resets.incrementAndGet() },
        now = { offset.get() },
    )

    @Test fun freshPoolsReplaceTheOldOnes() {
        val pools = RotatingPools { OpenCodeTransport.pools(TestNetworkBlock.interceptor) }
        val before = pools.current
        pools.reset()
        val after = pools.current
        assertNotSame(before.request, after.request)
        assertNotSame(before.measuredRequest, after.measuredRequest)
        assertNotSame(before.eventStream, after.eventStream)
        assertNotSame(before.request.connectionPool, after.request.connectionPool)
        assertEquals(after.request.dispatcher.maxRequests, after.request.dispatcher.maxRequestsPerHost)
    }

    @Test fun aResetForgetsAddressesBumpsTheEpochAndCoalescesABurst() {
        val resets = AtomicInteger(0)
        val offset = AtomicReference(Duration.ZERO)
        val store = store(resets, offset)
        store.adopt(ServerLinkTests.saved.at(ServerLinkTests.tailnet))
        store.reset(ServerLinkReset.Reroute)
        assertEquals(1, resets.get())
        assertEquals(ServerLinkEpoch(pools = 1, changes = 1), store.epoch)
        assertEquals("Raced again, not assumed", ServerLinkTests.lan, store.resolve(ServerLinkTests.saved).url)
        // Every window reroutes at once: one reset.
        store.reset(ServerLinkReset.Reroute)
        assertEquals(1, resets.get())
        offset.set(2.seconds)
        store.reset(ServerLinkReset.Retry)
        assertEquals(2, resets.get())
    }

    @Test fun onlyALongTripToTheBackgroundResets() {
        val resets = AtomicInteger(0)
        val offset = AtomicReference(Duration.ZERO)
        val store = store(resets, offset)
        store.note(AppPhase.Background)
        offset.set(5.seconds)
        store.note(AppPhase.Active)
        store.reset(ServerLinkReset.Foreground)
        assertEquals("A quick look at another app keeps the pools", 0, resets.get())
        store.note(AppPhase.Background)
        offset.set(5.seconds + ServerLinkStore.longBackground)
        store.note(AppPhase.Active)
        store.reset(ServerLinkReset.Foreground)
        assertEquals(1, resets.get())
        offset.set(60.seconds)
        store.reset(ServerLinkReset.Foreground)
        assertEquals("Each return counts once", 1, resets.get())
    }

    @Test fun twoTimeoutsInARowResetPoolsThatHadAnswered() {
        val resets = AtomicInteger(0)
        val offset = AtomicReference(Duration.ZERO)
        val store = store(resets, offset)
        store.noteTimeout()
        store.noteTimeout()
        assertEquals("Nothing has answered on these pools: the host is away, not the pools wedged", 0, resets.get())
        store.noteAnswer()
        store.noteTimeout()
        assertEquals(0, resets.get())
        store.noteTimeout()
        assertEquals(1, resets.get())
        // Fresh pools that haven't answered yet don't reset again and again.
        offset.set(5.seconds)
        store.noteTimeout()
        store.noteTimeout()
        store.noteTimeout()
        assertEquals(1, resets.get())
    }

    @Test fun aTimeoutInBetweenAnswersStartsTheCountOver() {
        val resets = AtomicInteger(0)
        val store = store(resets)
        store.noteAnswer()
        store.noteTimeout()
        store.noteAnswer()
        store.noteTimeout()
        assertEquals(0, resets.get())
    }

    /**
     * Home's resume can arrive before the app's own activation is observed: the trip away still
     * counts. On Android [ServerLinkStore.followAppPhases] relays `AppPhases`, so the phases are
     * noted directly.
     */
    @Test fun aReturnCountsEvenBeforeTheAppsActivationArrives() {
        val resets = AtomicInteger(0)
        val offset = AtomicReference(Duration.ZERO)
        val store = store(resets, offset)
        store.note(AppPhase.Background)
        offset.set(ServerLinkStore.longBackground + 1.seconds)
        store.reset(ServerLinkReset.Foreground)
        assertEquals("Measured from leaving", 1, resets.get())
        store.note(AppPhase.Active)
        offset.set(ServerLinkStore.longBackground * 3)
        store.reset(ServerLinkReset.Foreground)
        assertEquals("That return was already counted", 1, resets.get())
    }

    @Test fun aReturnAfterTheAppsActivationCountsToo() {
        val resets = AtomicInteger(0)
        val offset = AtomicReference(Duration.ZERO)
        val store = store(resets, offset)
        store.note(AppPhase.Background)
        offset.set(ServerLinkStore.longBackground)
        store.note(AppPhase.Active)
        store.reset(ServerLinkReset.Foreground)
        assertEquals(1, resets.get())
    }

    @Test fun aMoveToAnotherAddressWakesWaitersButTheSameAddressDoesNot() = runBlocking {
        val store = store()
        val saved = ServerLinkTests.saved
        store.adopt(saved)
        assertEquals(0, store.epoch.changes)
        store.adopt(saved)
        assertEquals(0, store.epoch.changes)
        val woke = AtomicBoolean(false)
        val waiter = launch(Dispatchers.Default) {
            store.changed(0)
            woke.set(true)
        }
        delay(50)
        assertFalse(woke.get())
        store.adopt(saved.at(ServerLinkTests.tailnet))
        withTimeout(5_000) { waiter.join() }
        assertTrue(woke.get())
        assertEquals(ServerLinkEpoch(pools = 0, changes = 1), store.epoch)
    }

    // Interrupting reads

    /** A read stuck on a socket the phone moved away from goes again on the fresh pools at once instead of waiting out its timeout. */
    @Test fun aHungReadGoesAgainWhenTheConnectionsReset() = runBlocking {
        val attempts = AtomicInteger(0)
        val fixture = WireFixture { if (attempts.incrementAndGet() == 1) Reply.Silent else Reply.Json(200, """{"data":[]}""") }
        try {
            val store = store()
            val started = TimeSource.Monotonic.markNow()
            val read = async(Dispatchers.Default) {
                withDependenciesSuspending({
                    this[ServerLinkClient] = ServerLinkClient.live(store)
                    this[Clock] = ImmediateClock()
                }) {
                    NetworkRetry.policy.withValue(RetryPolicy.request) {
                        NetworkRetry.environment.withValue(RetryEnvironment.online(unit = 0.0)) {
                            request<DataPayload<List<String>>>("/api/session", server = fixture.server)
                        }
                    }
                }
            }
            for (attempt in 0 until 100) {
                if (attempts.get() != 0) break
                delay(10)
            }
            delay(50)
            store.reset(ServerLinkReset.Reroute)
            withTimeout(9_000) { read.await() }
            assertEquals(2, attempts.get())
            assertTrue("Not the 10 s request timeout", started.elapsedNow() < 5.seconds)
        } finally {
            fixture.close()
        }
    }

    /** A GET that spends something (a pairing code) or a probe of one address isn't cut off either. */
    @Test fun anUnsafeGetOrAProbeIsNotInterrupted() {
        for (probe in listOf(false, true)) {
            anUnsafeGetOrAProbeIsNotInterrupted(probe)
        }
    }

    private fun anUnsafeGetOrAProbeIsNotInterrupted(probe: Boolean) = runBlocking {
        val fixture = WireFixture { Reply.Silent }
        try {
            val store = store()
            val finished = AtomicBoolean(false)
            val read = launch(Dispatchers.Default) {
                withDependenciesSuspending({ this[ServerLinkClient] = ServerLinkClient.live(store) }) {
                    NetworkRetry.policy.withValue(RetryPolicy.once) {
                        runCatching {
                            perform(
                                "/auth/connect/code",
                                idempotency = if (probe) RequestIdempotency.Idempotent else RequestIdempotency.Unsafe,
                                isConnectionProbe = probe,
                                server = fixture.server,
                            )
                        }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    }
                }
                finished.set(true)
            }
            for (attempt in 0 until 100) {
                if (fixture.requests.isNotEmpty()) break
                delay(10)
            }
            store.reset(ServerLinkReset.Retry)
            delay(300)
            assertFalse("Still waiting for its answer (probe: $probe)", finished.get())
            read.cancel()
            read.join()
            assertEquals(1, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    /** A change that lands before the read is sent (while its address was being found) still sends it again. */
    @Test fun aChangeBeforeTheReadStartsCountsToo() = runBlocking {
        val store = store()
        val link = ServerLinkClient.live(store)
        val before = store.epoch
        store.reset(ServerLinkReset.Reroute)
        val started = TimeSource.Monotonic.markNow()
        val error = runCatching {
            untilLinkChanges(before, ServerLinkTests.saved, link) { delay(30.seconds) }
        }.exceptionOrNull()
        assertTrue(error != null && error !is CancellationException)
        assertTrue(started.elapsedNow() < 2.seconds)
    }

    /** Timeouts from probes don't count toward a reset; a read's do. */
    @Test fun probeTimeoutsDontResetThePools() = runBlocking {
        val fixture = WireFixture { Reply.Failure(UrlErrorCode.TIMED_OUT) }
        try {
            val resets = AtomicInteger(0)
            val store = store(resets)
            store.noteAnswer()
            withDependenciesSuspending({ this[ServerLinkClient] = ServerLinkClient.live(store) }) {
                NetworkRetry.policy.withValue(RetryPolicy.once) {
                    NetworkRetry.environment.withValue(RetryEnvironment.online()) {
                        for (probe in listOf(true, true, false, false)) {
                            try {
                                perform("/api/info", isConnectionProbe = probe, server = fixture.server)
                            } catch (error: OpenCodeClientError) {
                                // Timed out, as expected.
                            }
                            if (probe) assertEquals(0, resets.get())
                        }
                    }
                }
            }
            assertEquals(1, resets.get())
        } finally {
            fixture.close()
        }
    }

    /** A reset ends the event stream on the retired pools, as a retryable interruption: the socket under it may be the wedged one. */
    @Test fun aResetEndsTheEventStream() = runBlocking {
        val fixture = WireFixture { Reply.Open(200, "data: {\"type\":\"server.connected\"}\n\n") }
        try {
            val store = store()
            val outcome = AtomicReference<OpenCodeClientError?>(null)
            val connected = AtomicBoolean(false)
            val reader = launch(Dispatchers.Default) {
                withDependenciesSuspending({ this[ServerLinkClient] = ServerLinkClient.live(store) }) {
                    try {
                        OpenCodeClient.liveValue.events(fixture.server).collect { event ->
                            if (event == OpenCodeServerEvent.Connected) connected.set(true)
                        }
                    } catch (error: OpenCodeClientError) {
                        outcome.set(error)
                    }
                }
            }
            for (attempt in 0 until 200) {
                if (connected.get()) break
                delay(10)
            }
            assertTrue(connected.get())
            store.reset(ServerLinkReset.Timeouts)
            for (attempt in 0 until 200) {
                if (outcome.get() != null) break
                delay(10)
            }
            reader.cancel()
            assertEquals(TransportFailure.Interrupted, outcome.get()?.transport)
        } finally {
            fixture.close()
        }
    }

    /** A send isn't cut off: whether it acted would be unknown. */
    @Test fun aPostInFlightIsNotInterrupted() = runBlocking {
        val fixture = WireFixture { Reply.Silent }
        try {
            val store = store()
            val finished = AtomicBoolean(false)
            val send = launch(Dispatchers.Default) {
                withDependenciesSuspending({ this[ServerLinkClient] = ServerLinkClient.live(store) }) {
                    NetworkRetry.policy.withValue(RetryPolicy.once) {
                        runCatching {
                            perform("/api/session", method = "POST", body = "{}".encodeToByteArray(), server = fixture.server)
                        }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    }
                }
                finished.set(true)
            }
            for (attempt in 0 until 100) {
                if (fixture.requests.isNotEmpty()) break
                delay(10)
            }
            store.reset(ServerLinkReset.Retry)
            delay(300)
            assertFalse("Still waiting for its answer", finished.get())
            send.cancel()
            send.join()
            assertEquals(1, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }
}
