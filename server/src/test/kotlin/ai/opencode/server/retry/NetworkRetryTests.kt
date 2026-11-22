package ai.opencode.server.retry

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.UrlErrorCode
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A clock whose sleeps return at once, advancing its time and recording each wait, so a test
 * can read the schedule a retry loop chose.
 */
class ScriptedClock(
    /** Sleeps longer than this never end on their own (only by cancellation), so a race against them goes to the other side. */
    private val holdsSleepsLongerThan: Duration? = null,
) : Clock {
    private val lock = Any()
    private var time = Duration.ZERO
    private val recorded = mutableListOf<Duration>()

    override fun now(): Duration = synchronized(lock) { time }
    val sleeps: List<Duration> get() = synchronized(lock) { recorded.toList() }

    /** Time passing inside an attempt (a request that takes a while to fail). */
    fun advance(by: Duration) = synchronized(lock) { time += by }

    override suspend fun sleep(duration: Duration) {
        currentCoroutineContext().ensureActive()
        val limit = holdsSleepsLongerThan
        if (limit != null && duration > limit) awaitCancellation()
        synchronized(lock) {
            val wait = maxOf(Duration.ZERO, duration)
            recorded += wait
            time += wait
        }
    }
}

/** The error [block] throws, failing the test when it returns. */
private suspend inline fun <reified T : Throwable> expectThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw error
    }
    fail("Expected ${T::class.simpleName}")
    throw IllegalStateException()
}

class NetworkCorrelationTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun retriesKeepOperationButNotTaskIdentity() = runTest {
        val identities = mutableListOf<NetworkRetry.Correlation>()
        val requests = mutableListOf<String>()
        NetworkRetry.run(
            RetryPolicy.request, idempotency = RequestIdempotency.Idempotent, clock = ScriptedClock(),
            environment = RetryEnvironment.online(unit = 0.0),
        ) { attempt ->
            val identity = NetworkRetry.correlation.current()!!
            identities += identity
            val request = identity.identify(Request.Builder().url("https://example.invalid/api/info"), UUID.randomUUID().toString()).build()
            requests += request.header("X-OpenCode-Req")!!
            assertEquals(attempt.toString(), request.header("X-OpenCode-Attempt"))
            if (attempt < 3) throw OpenCodeClientError.unreachable(TransportFailure.NotSent)
            attempt
        }
        assertEquals(1, identities.map { it.operationID }.toSet().size)
        UUID.fromString(identities[0].operationID)
        assertEquals(listOf(1, 2, 3), identities.map { it.attempt })
        assertEquals(3, requests.toSet().size)
        assertNull(NetworkRetry.correlation.current())
    }

    @Test
    fun onceInheritsOwnerButIndependentCallsAndEscapedWorkDoNot() = runTest {
        val parent = NetworkRetry.Correlation(attempt = 4)
        NetworkRetry.correlation.withValue(parent) {
            NetworkRetry.run(RetryPolicy.once, idempotency = RequestIdempotency.Idempotent) {
                assertEquals(parent, NetworkRetry.correlation.current())
                NetworkRetry.outsideScope { assertNull(NetworkRetry.correlation.current()) }
                1
            }
        }
        val first = NetworkRetry.run(RetryPolicy.once, idempotency = RequestIdempotency.Idempotent) { NetworkRetry.correlation.current()!! }
        val second = NetworkRetry.run(RetryPolicy.once, idempotency = RequestIdempotency.Idempotent) { NetworkRetry.correlation.current()!! }
        assertNotEquals(first.operationID, second.operationID)
        assertTrue(first.attempt == 1 && second.attempt == 1)
    }
}

class RetryPolicyTests {
    @get:Rule val isolation = TestIsolationRule()

    private val notSent = OpenCodeClientError.unreachable(TransportFailure.NotSent)
    private val lost = OpenCodeClientError.unreachable(TransportFailure.Interrupted)
    private val transient = RetryVerdict.Transient(null)

    private fun status(code: Int, retryAfter: Double? = null) = OpenCodeClientError("", statusCode = code, retryAfter = retryAfter)

    @Test
    fun ceilingsDoubleFromTheBaseUpToTheCap() {
        val policy = RetryPolicy.request
        assertEquals(listOf(1, 2, 4, 8, 8, 8).map { it.seconds }, (1..6).map { policy.ceiling(it) })
        assertEquals(16.seconds, RetryPolicy.sessionWait.ceiling(30))
        assertEquals(Duration.ZERO, RetryPolicy.once.ceiling(1))
    }

    @Test
    fun jitterStaysBetweenHalfTheCeilingAndTheCeiling() {
        for (retry in 1..8) {
            val policy = RetryPolicy.request
            val ceiling = policy.ceiling(retry)
            assertEquals(ceiling / 2, policy.delay(retry, 0.0))
            assertTrue(policy.delay(retry, 0.999_999) <= ceiling)
            repeat(200) {
                val delay = policy.delay(retry, Random.nextDouble())
                assertTrue(delay >= ceiling / 2 && delay <= ceiling)
            }
            // Out-of-range randomness is clamped, never a longer wait.
            assertEquals(ceiling, policy.delay(retry, 7.0))
        }
    }

    @Test
    fun classifiesFailuresOnce() {
        // Connection failures and the retryable statuses pass for idempotent requests.
        for (error in listOf(notSent, lost, OpenCodeClientError.unreachable(TransportFailure.TimedOut)) + listOf(408, 429, 502, 503, 504).map { status(it) }) {
            assertEquals("$error", transient, error.retryVerdict(RequestIdempotency.Idempotent))
        }
        // Validation, auth, missing routes, server bugs, and unreadable replies never do.
        for (error in listOf(400, 401, 403, 404, 409, 422, 500, 501).map { status(it) } +
            listOf(OpenCodeClientError("The transcript does not match the current OpenCode V2 contract."), OpenCodeClientError.uploadsUnsupported)
        ) {
            assertEquals("$error", RetryVerdict.Permanent, error.retryVerdict(RequestIdempotency.Idempotent))
            assertEquals("$error", RetryVerdict.Permanent, error.retryVerdict(RequestIdempotency.Unsafe))
        }
        // A request that isn't idempotent goes again only if the server can't have acted on it.
        assertEquals(transient, notSent.retryVerdict(RequestIdempotency.Unsafe))
        assertEquals(RetryVerdict.Permanent, lost.retryVerdict(RequestIdempotency.Unsafe))
        assertEquals(RetryVerdict.Permanent, OpenCodeClientError.unreachable(TransportFailure.TimedOut).retryVerdict(RequestIdempotency.Unsafe))
        assertEquals(transient, status(503).retryVerdict(RequestIdempotency.Unsafe))
        assertEquals(RetryVerdict.Permanent, status(502).retryVerdict(RequestIdempotency.Unsafe))
        assertEquals(RetryVerdict.Transient(2500.milliseconds), status(429, retryAfter = 2.5).retryVerdict(RequestIdempotency.Unsafe))
    }

    @Test
    fun mapsURLErrorsByWhetherTheRequestCouldHaveArrived() {
        assertEquals(TransportFailure.NotSent, TransportFailure.ofUrlErrorCode(UrlErrorCode.NOT_CONNECTED_TO_INTERNET))
        assertEquals(TransportFailure.NotSent, TransportFailure.ofUrlErrorCode(UrlErrorCode.CANNOT_CONNECT_TO_HOST))
        assertEquals(TransportFailure.NotSent, TransportFailure.ofUrlErrorCode(UrlErrorCode.CANNOT_FIND_HOST))
        assertEquals(TransportFailure.NotSent, TransportFailure.ofUrlErrorCode(UrlErrorCode.DNS_LOOKUP_FAILED))
        assertEquals(TransportFailure.Interrupted, TransportFailure.ofUrlErrorCode(UrlErrorCode.NETWORK_CONNECTION_LOST))
        assertEquals(TransportFailure.TimedOut, TransportFailure.ofUrlErrorCode(UrlErrorCode.TIMED_OUT))
        assertNull(TransportFailure.ofUrlErrorCode(UrlErrorCode.CANCELLED))
        assertNull(TransportFailure.ofUrlErrorCode(UrlErrorCode.SERVER_CERTIFICATE_UNTRUSTED))
        assertNull(TransportFailure.ofUrlErrorCode(UrlErrorCode.BAD_URL))
        assertNull(TransportFailure.of(CancellationException()))
    }

    @Test
    fun anATSRejectionOnlyBecomesTransientForAnAlreadyWorkingHTTPOrigin() {
        val contact = ServerContact()
        val url = "http://100.64.0.1:4096/api/session/ses_1/prompt".toHttpUrl()
        val ats = UnknownServiceException("CLEARTEXT communication not permitted by network security policy")
        assertNull(TransportFailure.of(ats))
        assertNull(TransportFailure.of(ats, url, contact))
        // Hearing from the saved server alone (its stream or another address)
        // doesn't prove this exact origin was accepted by ATS.
        contact.heard("server")
        contact.heard("server", "http://peer.ts.net:4096/api/info".toHttpUrl())
        contact.heard("server", "https://100.64.0.1:4096/api/info".toHttpUrl())
        contact.heard("server", "http://100.64.0.1:4097/api/info".toHttpUrl())
        assertNull(TransportFailure.of(ats, url, contact))

        contact.heard("server", "http://100.64.0.1:4096/api/info".toHttpUrl())
        val failure = TransportFailure.of(ats, url, contact)
        assertEquals(TransportFailure.Interrupted, failure)
        assertEquals(transient, OpenCodeClientError.unreachable(failure!!).retryVerdict(RequestIdempotency.Idempotent))
        assertEquals(
            "A redirect might have followed a mutation: don't assume it never left",
            RetryVerdict.Permanent, OpenCodeClientError.unreachable(failure).retryVerdict(RequestIdempotency.Unsafe),
        )
        // cancelled, serverCertificateUntrusted (userAuthenticationRequired and badURL have no JVM exception).
        for (error in listOf(IOException("Canceled"), SSLPeerUnverifiedException("fixture"))) {
            assertNull("$error", TransportFailure.of(error, url, contact))
        }
        val https = "https://100.64.0.1:4096/api/info".toHttpUrl()
        assertNull(TransportFailure.of(ats, https, contact))

        // OkHttp names the refused host: a redirect elsewhere isn't evidence of this failure.
        val redirect = UnknownServiceException("CLEARTEXT communication to public.example not permitted by network security policy")
        assertNull(TransportFailure.of(redirect, url, contact))
        val sameOrigin = UnknownServiceException("CLEARTEXT communication to 100.64.0.1 not permitted by network security policy")
        assertEquals(TransportFailure.Interrupted, TransportFailure.of(sameOrigin, url, contact))
    }

    /**
     * A request the system tore down while the app was suspended can come back as a bare socket
     * error. On the JVM the POSIX errors arrive as `java.net` exceptions.
     */
    @Test
    fun mapsSocketErrorsLikeTheConnectionFailuresTheyAre() {
        assertEquals("Software caused connection abort", TransportFailure.Interrupted, TransportFailure.of(SocketException("Software caused connection abort")))
        assertEquals(TransportFailure.Interrupted, TransportFailure.of(SocketException("Connection reset")))
        assertEquals(TransportFailure.Interrupted, TransportFailure.of(SocketException("Socket is not connected")))
        assertEquals(TransportFailure.Interrupted, TransportFailure.of(SocketException("Broken pipe")))
        assertEquals(TransportFailure.NotSent, TransportFailure.of(ConnectException("Connection refused")))
        assertEquals(TransportFailure.NotSent, TransportFailure.of(ConnectException("Network is unreachable")))
        assertEquals(TransportFailure.NotSent, TransportFailure.of(NoRouteToHostException("No route to host")))
        assertEquals(TransportFailure.TimedOut, TransportFailure.of(SocketTimeoutException("timeout")))
        assertNull(TransportFailure.of(SocketException("socket failed: EACCES (Permission denied)")))
        assertEquals(TransportFailure.Interrupted, TransportFailure.ofUrlErrorCode(-1021 /* requestBodyStreamExhausted */))
        // Cancellation never is a connection failure, whatever it wraps.
        assertNull(TransportFailure.of(IOException("Canceled", SocketException("Software caused connection abort"))))
    }

    /** Cloudflare's origin errors mean what 502 and 504 mean: the proxy couldn't get an answer from the server behind it. */
    @Test
    fun treatsCloudflaresOriginErrorsAsGatewayFailures() {
        for (code in listOf(520, 521, 522, 523, 524, 525, 526, 527, 530)) {
            val error = status(code)
            assertEquals("$code", transient, error.retryVerdict(RequestIdempotency.Idempotent))
            assertEquals("$code", RetryVerdict.Permanent, error.retryVerdict(RequestIdempotency.Unsafe))
            assertTrue("$code", error.mayHaveReachedServer)
        }
        for (code in listOf(500, 528, 529, 531)) {
            assertEquals("$code", RetryVerdict.Permanent, status(code).retryVerdict(RequestIdempotency.Idempotent))
        }
    }

    /** A send keeps trying for five minutes of failures, at most 15 s apart, where a request stops after five attempts. */
    @Test
    fun aSendKeepsTryingForItsWindow() {
        val backoff = RetryBackoff(RetryPolicy.send)
        var elapsed = Duration.ZERO
        val waits = mutableListOf<Duration>()
        while (true) {
            val decision = backoff.decide(transient, elapsed = elapsed, offline = false, unit = 1.0)
            if (decision !is RetryBackoff.Decision.Retry) break
            waits += decision.after
            elapsed += decision.after
        }
        assertEquals(listOf(1, 2, 4, 8, 15).map { it.seconds }, waits.take(5))
        assertTrue(waits.all { it <= 15.seconds })
        assertTrue("Far more than a request's five attempts", waits.size > 20)
        assertTrue(elapsed > 285.seconds && elapsed <= 300.seconds)
        // A failure that sending again can't fix still ends it at once.
        val fresh = RetryBackoff(RetryPolicy.send)
        assertEquals(RetryBackoff.Decision.GiveUp, fresh.decide(RetryVerdict.Permanent, elapsed = Duration.ZERO, offline = false, unit = 0.0))
        // Offline, it waits for the network with what's left of the window.
        assertEquals(RetryBackoff.Decision.WaitForNetwork(200.seconds), fresh.decide(transient, elapsed = 100.seconds, offline = true, unit = 0.0))
    }

    @Test
    fun succeedsAfterTransientFailures() = runTest {
        val clock = ScriptedClock()
        val calls = AtomicInteger(0)
        val value = NetworkRetry.run(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(0.0)) { attempt ->
            calls.incrementAndGet()
            if (attempt < 3) throw lost
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, calls.get())
        assertEquals(listOf(500.milliseconds, 1.seconds), clock.sleeps)
    }

    @Test
    fun exhaustsAfterFiveAttemptsAndReportsThem() = runTest {
        val clock = ScriptedClock()
        val calls = AtomicInteger(0)
        val error = expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(1.0)) {
                calls.incrementAndGet()
                throw notSent
            }
        }
        assertEquals(OpenCodeClientError.UNREACHABLE_MESSAGE, error.message)
        assertEquals(5, error.attempts)
        assertEquals(TransportFailure.NotSent, error.transport)
        assertEquals(5, calls.get())
        assertEquals(listOf(1, 2, 4, 8).map { it.seconds }, clock.sleeps)
    }

    @Test
    fun permanentFailuresFailAtOnce() = runTest {
        for (code in listOf(400, 401, 404, 409, 500)) {
            val clock = ScriptedClock()
            val calls = AtomicInteger(0)
            expectThrows<OpenCodeClientError> {
                NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online()) {
                    calls.incrementAndGet()
                    throw OpenCodeClientError("HTTP $code", statusCode = code)
                }
            }
            assertEquals("$code", 1, calls.get())
            assertTrue("$code", clock.sleeps.isEmpty())
        }
    }

    @Test
    fun anUnsafeRequestIsSentAgainOnlyWhenItNeverLeft() = runTest {
        val clock = ScriptedClock()
        val calls = AtomicInteger(0)
        expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Unsafe, clock = clock, environment = RetryEnvironment.online()) {
                calls.incrementAndGet()
                throw lost
            }
        }
        assertEquals("A dropped connection may have delivered it", 1, calls.get())

        val retried = NetworkRetry.run(RetryPolicy.request, RequestIdempotency.Unsafe, clock = clock, environment = RetryEnvironment.online()) { attempt ->
            if (attempt == 1) throw notSent
            attempt
        }
        assertEquals(2, retried)
    }

    @Test
    fun honoursRetryAfter() = runTest {
        val clock = ScriptedClock()
        NetworkRetry.run(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(0.0)) { attempt ->
            if (attempt == 1) throw OpenCodeClientError("Slow down", statusCode = 429, retryAfter = 3.0)
            attempt
        }
        assertEquals("Retry-After outranks a shorter backoff", listOf(3.seconds), clock.sleeps)

        // Asked to wait past the policy's patience: say so instead of waiting silently.
        val calls = AtomicInteger(0)
        expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online()) {
                calls.incrementAndGet()
                throw OpenCodeClientError("Down for maintenance", statusCode = 503, retryAfter = 120.0)
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun aConnectionFailureTriesAgainAsSoonAsTheServerAnswers() = runTest {
        // The backoff's sleep never ends on its own: only the server answering
        // (another request, the event stream) lets the second attempt out.
        val clock = ScriptedClock(holdsSleepsLongerThan = Duration.ZERO)
        val value = NetworkRetry.run(
            RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(), reachable = {},
        ) { attempt ->
            if (attempt == 1) throw lost
            attempt
        }
        assertEquals(2, value)
    }

    @Test
    fun aStatusBackoffRunsItsCourseWhateverTheServerSays() = runTest {
        val clock = ScriptedClock()
        var asked = false
        NetworkRetry.run(
            RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(0.0),
            reachable = { asked = true },
        ) { attempt ->
            if (attempt == 1) throw OpenCodeClientError("Busy", statusCode = 503)
            attempt
        }
        assertEquals(listOf(500.milliseconds), clock.sleeps)
        assertFalse("A server that answered 503 is reachable already", asked)
    }

    @Test
    fun serverContactWakesOnlyThatServersWaiters() = runTest {
        val contact = ServerContact()
        val woke = mutableListOf<String>()
        val mine = launch { contact.answers("a").first(); woke += "a" }
        val other = launch { contact.answers("b").first(); woke += "b" }
        testScheduler.runCurrent()
        contact.heard("a")
        mine.join()
        other.cancel()
        assertEquals(listOf("a"), woke)
    }

    @Test
    fun stopsAtTheRetryWindowEvenWithAttemptsLeft() = runTest {
        val clock = ScriptedClock()
        val calls = AtomicInteger(0)
        expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(1.0)) {
                calls.incrementAndGet()
                clock.advance(10.seconds) // Each attempt times out after 10 s.
                throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
            }
        }
        // Failures at 10, 21, 33, and 47 s: the fourth is past 30 s after the first.
        assertEquals(4, calls.get())
        assertEquals(listOf(1, 2, 4).map { it.seconds }, clock.sleeps)
    }

    /** A cancelled coroutine ends with `CancellationException`. */
    @Test
    fun cancellationDuringBackoffEndsAtOnce() = runTest {
        val clock = TestClock()
        val calls = AtomicInteger(0)
        val failed = Channel<Unit>(Channel.UNLIMITED)
        val task = launch {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online()) {
                calls.incrementAndGet()
                failed.trySend(Unit)
                throw lost
            }
        }
        failed.receive()
        task.cancel()
        task.join()
        assertTrue(task.isCancelled)
        assertEquals("No attempt after cancellation", 1, calls.get())
    }

    @Test
    fun offlineFailuresWaitForTheNetworkWithoutSpendingAttempts() = runTest {
        // The network comes back before the window's end.
        val clock = ScriptedClock(holdsSleepsLongerThan = 20.seconds)
        val offline = AtomicInteger(3)
        val waits = AtomicInteger(0)
        val environment = RetryEnvironment(
            random = { 0.0 },
            isOffline = { offline.get() > 0 },
            networkAvailable = {
                waits.incrementAndGet()
                offline.decrementAndGet()
            },
        )
        val calls = AtomicInteger(0)
        expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = environment) {
                calls.incrementAndGet()
                throw notSent
            }
        }
        assertEquals(3, waits.get())
        assertEquals("Offline failures don't count toward the five attempts", 3 + 5, calls.get())
    }

    @Test
    fun givesUpWhenTheNetworkStaysAwayPastTheWindow() = runTest {
        val clock = ScriptedClock()
        val environment = RetryEnvironment(random = { 0.0 }, isOffline = { true }, networkAvailable = { awaitCancellation() })
        val calls = AtomicInteger(0)
        expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = environment) {
                calls.incrementAndGet()
                throw notSent
            }
        }
        assertEquals(1, calls.get())
        assertEquals(listOf(30.seconds), clock.sleeps)
    }

    @Test
    fun theStreamScheduleNeverEndsAndStartsOverAfterAConnection() {
        val backoff = RetryBackoff(RetryPolicy.stream)
        val waits = (0 until 10).map { backoff.decide(transient, elapsed = 15.seconds, offline = false, unit = 1.0) }
        assertEquals(RetryBackoff.Decision.Retry(1.seconds), waits.first())
        assertTrue(waits.drop(1).all { it == RetryBackoff.Decision.Retry(2.seconds) })
        backoff.reset()
        assertEquals(RetryBackoff.Decision.Retry(1.seconds), backoff.decide(transient, elapsed = Duration.ZERO, offline = false, unit = 1.0))
        assertEquals(RetryBackoff.Decision.WaitForNetwork(null), backoff.decide(transient, elapsed = Duration.ZERO, offline = true, unit = 1.0))
        backoff.reset()
        assertEquals(RetryBackoff.Decision.Retry(500.milliseconds), backoff.decide(transient, elapsed = Duration.ZERO, offline = false, unit = 0.0))
        assertEquals(RetryBackoff.Decision.Retry(1.seconds), backoff.decide(transient, elapsed = 15.seconds, offline = false, unit = 0.0))
    }

    /** Fast for a handoff's worth of time, then doubling to five minutes, so a host that stays away isn't sent a packet every couple of seconds. */
    @Test
    fun streamBackoffSlowsAfterFifteenSecondsToFiveMinutesAndAConnectionResetsIt() = runTest {
        val clock = TestClock()
        val schedule = RetrySchedule(RetryPolicy.stream, clock, RetryEnvironment.online(1.0))
        assertEquals(RetryPause.Kind.Sleep(1.seconds), schedule.next(transient)?.kind)
        clock.advance(15.seconds)
        assertEquals(RetryPause.Kind.Sleep(2.seconds), schedule.next(transient)?.kind)
        clock.advance(1.seconds)
        for (seconds in listOf(4, 8, 16, 32, 64, 128, 256, 300, 300)) {
            assertEquals(RetryPause.Kind.Sleep(seconds.seconds), schedule.next(transient)?.kind)
            clock.advance(seconds.seconds)
        }
        schedule.reset()
        assertEquals(RetryPause.Kind.Sleep(1.seconds), schedule.next(transient)?.kind)
        clock.advance(15.seconds)
        assertEquals(RetryPause.Kind.Sleep(2.seconds), schedule.next(transient)?.kind)
    }

    @Test
    fun onceNeverRetries() = runTest {
        val calls = AtomicInteger(0)
        expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.once, RequestIdempotency.Idempotent, clock = ScriptedClock(), environment = RetryEnvironment.online()) {
                calls.incrementAndGet()
                throw notSent
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun aBudgetCutsEveryRequestsWindowToWhatsLeft() = runTest {
        // Inside a budget's scope a request that isn't marked required still
        // stops when the budget does: 28 s are gone, so two tries, not five.
        val clock = ScriptedClock()
        val calls = AtomicInteger(0)
        val budget = RetryBudget(30.seconds, clock)
        clock.advance(28.seconds)
        expectThrows<OpenCodeClientError> {
            budget.scope {
                NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(1.0)) {
                    calls.incrementAndGet()
                    throw notSent
                }
            }
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun aBudgetCountsTheTimeTheFirstAttemptTook() = runTest {
        // The first attempt takes 29.5 s of a 30 s budget to fail: no wait fits
        // in what's left, so it isn't tried again.
        val clock = ScriptedClock()
        val calls = AtomicInteger(0)
        val budget = RetryBudget(30.seconds, clock)
        expectThrows<OpenCodeClientError> {
            budget.scope {
                NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = clock, environment = RetryEnvironment.online(1.0)) {
                    calls.incrementAndGet()
                    clock.advance(29_500.milliseconds)
                    throw OpenCodeClientError.unreachable(TransportFailure.TimedOut)
                }
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun aRequestThatMayHaveLandedOnceSaysSoWhateverTheLastAttemptSays() = runTest {
        // The first attempt broke after it went out; the rest never left.
        val attempts = AtomicInteger(0)
        val failure = expectThrows<OpenCodeClientError> {
            NetworkRetry.run<Int>(RetryPolicy.request, RequestIdempotency.Idempotent, clock = ScriptedClock(), environment = RetryEnvironment.online()) {
                val attempt = attempts.incrementAndGet()
                throw OpenCodeClientError.unreachable(if (attempt == 1) TransportFailure.Interrupted else TransportFailure.NotSent)
            }
        }
        assertEquals(TransportFailure.NotSent, failure.transport)
        assertEquals(5, failure.attempts)
        assertTrue(failure.mayHaveReachedServer)
    }
}

/** The client's requests against a scripted server, through [WireFixture]. */
class NetworkRetryWireTests {
    @get:Rule val isolation = TestIsolationRule()

    private val client get() = OpenCodeClient.liveValue

    /** Requests under the shared policy, with an instant clock and fixed jitter. */
    private suspend fun <T> retrying(clock: ScriptedClock = ScriptedClock(), operation: suspend () -> T): T =
        NetworkRetry.policy.withValue(RetryPolicy.request) {
            NetworkRetry.clock.withValue(clock) {
                NetworkRetry.environment.withValue(RetryEnvironment.online()) { operation() }
            }
        }

    private inline fun <T> WireFixture.use(block: (WireFixture) -> T): T = try { block(this) } finally { close() }

    @Test
    fun aSendWhoseResponseWasLostIsRetriedUnderItsIDAndAdmittedOnce() = runTest {
        // The server admits a message ID once, like V2's inbox (first admission wins).
        val admitted = mutableListOf<String>()
        val posts = AtomicInteger(0)
        WireFixture { request ->
            val id = runCatching { request.json()["id"]?.jsonPrimitive?.content }.getOrNull()
            if (request.route != "POST /api/session/ses_1/prompt" || id == null) return@WireFixture Reply.status(404)
            synchronized(admitted) { if (id !in admitted) admitted += id }
            // The first reply never reaches the phone: the connection drops after the server acted.
            if (posts.incrementAndGet() == 1) Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST) else Reply.Json(200, """{"data":{}}""")
        }.use { fixture ->
            val messageID = retrying { client.sendDeliveredPrompt(fixture.server, "ses_1", "Run the tests", OpenCodeDelivery.Steer) }

            val sentIDs = fixture.requests.map { it.json()["id"]!!.jsonPrimitive.content }
            assertEquals("The retry reuses the message ID", listOf(messageID, messageID), sentIDs)
            assertEquals("The server holds one message, not two", listOf(messageID), admitted)
            val requests = fixture.requests
            assertEquals(listOf("1", "2"), requests.map { it.header("X-OpenCode-Attempt") })
            assertEquals(requests[0].header("X-OpenCode-Op"), requests[1].header("X-OpenCode-Op"))
            assertNotEquals(requests[0].header("X-OpenCode-Req"), requests[1].header("X-OpenCode-Req"))
        }
    }

    @Test
    fun aFirstATSRejectionStaysPermanent() = runTest {
        for (scheme in listOf("http", "https")) {
            WireFixture(scheme = scheme) { Reply.Failure(UrlErrorCode.APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION) }.use { fixture ->
                val error = expectThrows<OpenCodeClientError> {
                    retrying { client.sendPrompt(fixture.server, "ses_1", "Hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer) }
                }
                assertNull(scheme, error.transport)
                assertEquals(scheme, 1, fixture.requests.size)
            }
        }
    }

    @Test
    fun anATSFailureAfterAHealthyHTTPSResponseStaysPermanent() = runTest {
        WireFixture { request ->
            if (request.path == "/api/info") Reply.Json(200, """{"version":"1","pid":1,"urls":[]}""")
            else Reply.Failure(UrlErrorCode.APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION)
        }.use { fixture ->
            client.health(fixture.server)
            expectThrows<OpenCodeClientError> {
                retrying { client.sendPrompt(fixture.server, "ses_1", "Hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer) }
            }
            assertEquals(1, fixture.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun anATSRejectionOfAnUnsafeMutationIsNotRepeatedEvenOnAWorkingOrigin() = runTest {
        WireFixture(scheme = "http") { request ->
            if (request.path == "/api/info") Reply.Json(200, """{"version":"1","pid":1,"urls":[]}""")
            else Reply.Failure(UrlErrorCode.APP_TRANSPORT_SECURITY_REQUIRES_SECURE_CONNECTION)
        }.use { fixture ->
            client.health(fixture.server)
            expectThrows<OpenCodeClientError> { retrying { client.interrupt(fixture.server, "ses_1") } }
            assertEquals(1, fixture.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun anInterruptIsNotSentTwiceAfterAnAmbiguousFailure() = runTest {
        WireFixture { Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST) }.use { fixture ->
            expectThrows<OpenCodeClientError> { retrying { client.interrupt(fixture.server, "ses_1") } }
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun anInterruptThatNeverLeftIsSentAgain() = runTest {
        val attempts = AtomicInteger(0)
        WireFixture {
            if (attempts.incrementAndGet() < 3) Reply.Failure(UrlErrorCode.NOT_CONNECTED_TO_INTERNET) else Reply.Json(200, """{"interrupted":true}""")
        }.use { fixture ->
            retrying { client.interrupt(fixture.server, "ses_1") }
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test
    fun aReadRidesOutADropAndHonoursRetryAfter() = runTest {
        val clock = ScriptedClock()
        val attempts = AtomicInteger(0)
        WireFixture {
            when (attempts.incrementAndGet()) {
                1 -> Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
                2 -> Reply.Json(503, "{}", mapOf("Retry-After" to "4"))
                else -> Reply.Json(200, """{"data":{"id":"ses_1","title":"Ship it","projectID":"p","location":{"directory":"/p"},"time":{"created":1,"updated":2}}}""")
            }
        }.use { fixture ->
            val session = retrying(clock) { client.getSession(fixture.server, "ses_1") }
            assertEquals("Ship it", session.title)
            assertEquals(3, fixture.requests.size)
            assertEquals(listOf(750.milliseconds, 4.seconds), clock.sleeps)
        }
    }

    @Test
    fun aRejectedRequestFailsAtOnce() = runTest {
        WireFixture { Reply.Json(401, "{}") }.use { fixture ->
            val error = expectThrows<OpenCodeClientError> { retrying { client.getSession(fixture.server, "ses_1") } }
            assertEquals(401, error.statusCode)
            assertEquals(1, error.attempts)
            assertEquals(1, fixture.requests.size)
        }
    }

    // Mutations that aren't repeatable

    private fun inbox(delivery: String?): Reply {
        val rows = delivery?.let { """[{"id":"msg_q","type":"user","delivery":"$it","payload":{"text":"Check it"}}]""" } ?: "[]"
        return Reply.Json(200, """{"data":$rows}""")
    }

    @Test
    fun aDeliveryChangeWhoseResponseWasLostIsReadBackNotRepeated() = runTest {
        // V2 changes the row only from the other delivery: a repeat of a change
        // that landed would answer 409. The first PATCH lands; its reply is lost.
        val delivery = AtomicReference("queue")
        WireFixture { request ->
            when (request.route) {
                "PATCH /api/session/ses_1/inbox/msg_q" -> {
                    if (delivery.get() != "queue") return@WireFixture Reply.Json(409, "{}")
                    delivery.set("steer")
                    Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
                }
                "GET /api/session/ses_1/inbox" -> inbox(delivery.get())
                else -> Reply.status(404)
            }
        }.use { fixture ->
            retrying { client.setPendingInputDelivery(fixture.server, "ses_1", "msg_q", OpenCodeDelivery.Steer) }
            assertEquals(listOf("PATCH /api/session/ses_1/inbox/msg_q", "GET /api/session/ses_1/inbox"), fixture.requests.map { it.route })
            assertEquals("steer", delivery.get())
        }
    }

    @Test
    fun aDeliveryChangeThatDidntTakeStaysAFailure() = runTest {
        // Lost, and the inbox shows it unchanged: a failure, never sent again.
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/inbox") inbox("queue") else Reply.Failure(UrlErrorCode.TIMED_OUT)
        }.use { unchanged ->
            expectThrows<OpenCodeClientError> {
                retrying { client.setPendingInputDelivery(unchanged.server, "ses_1", "msg_q", OpenCodeDelivery.Steer) }
            }
            assertEquals(1, unchanged.requests.count { it.method == "PATCH" })
        }

        // A 409 for a row promoted meanwhile isn't success either.
        WireFixture { request ->
            if (request.route == "GET /api/session/ses_1/inbox") inbox(null)
            else Reply.Json(409, """{"message":"Pending input cannot change to queue"}""")
        }.use { promoted ->
            val error = expectThrows<OpenCodeClientError> {
                retrying { client.setPendingInputDelivery(promoted.server, "ses_1", "msg_q", OpenCodeDelivery.Queue) }
            }
            assertEquals("A promoted row's 409 must stay a failure", 409, error.statusCode)
            assertEquals(1, promoted.requests.count { it.method == "PATCH" })
        }

        // A connection that never opened is safe to try again.
        val attempts = AtomicInteger(0)
        WireFixture { if (attempts.incrementAndGet() == 1) Reply.Failure(UrlErrorCode.CANNOT_CONNECT_TO_HOST) else Reply.status(204) }.use { neverSent ->
            runCatching { retrying { client.setPendingInputDelivery(neverSent.server, "ses_1", "msg_q", OpenCodeDelivery.Steer) } }
            assertEquals(List(2) { "PATCH /api/session/ses_1/inbox/msg_q" }, neverSent.requests.map { it.route })
        }
    }

    @Test
    fun autoRenameRunsTheTitleModelOnceButATypedTitleRidesOutADrop() = runTest {
        WireFixture { Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST) }.use { generate ->
            expectThrows<OpenCodeClientError> { retrying { client.renameSession(generate.server, "ses_1", "") } }
            assertEquals("A repeat would run (and bill) the title model again", 1, generate.requests.size)
        }

        val attempts = AtomicInteger(0)
        WireFixture { if (attempts.incrementAndGet() == 1) Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST) else Reply.status(204) }.use { typed ->
            retrying { client.renameSession(typed.server, "ses_1", "Ship it") }
            assertEquals(2, typed.requests.size)
        }
    }

    @Test
    fun archivingASessionPatchesArchivedAndRidesOutADrop() = runTest {
        val attempts = AtomicInteger(0)
        WireFixture { request ->
            when {
                request.route.startsWith("GET") -> Reply.Json(200, sessionReply(archived = true))
                attempts.incrementAndGet() == 1 -> Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
                else -> Reply.status(204)
            }
        }.use { fixture ->
            retrying { client.archiveSession(fixture.server, "ses_1") }
            assertEquals(List(2) { "PATCH /api/session/ses_1" } + "GET /api/session/ses_1", fixture.requests.map { it.route })
            assertEquals("true", fixture.requests[0].json()["archived"]?.jsonPrimitive?.content)
            assertEquals(1, fixture.requests[0].json().size)
        }
    }

    @Test
    fun archivingASessionFailsWhenTheServerLeavesItUnarchived() = runTest {
        WireFixture { request ->
            if (request.route.startsWith("GET")) Reply.Json(200, sessionReply(archived = false)) else Reply.status(204)
        }.use { fixture ->
            val error = runCatching { client.archiveSession(fixture.server, "ses_1") }.exceptionOrNull()
            assertEquals("This server doesn’t support archiving. Update OpenCode on your computer.", error?.message)
        }
    }

    @Test
    fun archivingASessionSucceedsWhenItCannotBeReadBack() = runTest {
        WireFixture { request ->
            if (request.route.startsWith("GET")) Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST) else Reply.status(204)
        }.use { fixture ->
            client.archiveSession(fixture.server, "ses_1")
            assertEquals(listOf("PATCH /api/session/ses_1", "GET /api/session/ses_1"), fixture.requests.map { it.route })
        }
    }

    private fun sessionReply(archived: Boolean): String {
        val time = if (archived) "\"updated\":1000,\"archived\":2000" else "\"updated\":1000"
        return "{\"data\":{\"id\":\"ses_1\",\"projectID\":\"p\",\"location\":{\"directory\":\"/project\"},\"time\":{$time}}}"
    }

    @Test
    fun cancellingAPendingMessageIsRepeatable() = runTest {
        // V2 answers 204 for an item no longer pending.
        val attempts = AtomicInteger(0)
        WireFixture { if (attempts.incrementAndGet() == 1) Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST) else Reply.status(204) }.use { fixture ->
            retrying { client.cancelPendingInput(fixture.server, "ses_1", "msg_q") }
            assertEquals(List(2) { "DELETE /api/session/ses_1/inbox/msg_q" }, fixture.requests.map { it.route })
        }
    }

    @Test
    fun mutationsArentRepeatableUnlessOptedIn() {
        for (method in listOf("PATCH", "POST", "DELETE")) {
            assertEquals(method, RequestIdempotency.Unsafe, RequestIdempotency.forMethod(method))
            assertEquals(RequestIdempotency.Idempotent, RequestIdempotency.forMethod("PUT"))
            assertEquals(RequestIdempotency.Idempotent, RequestIdempotency.forMethod("GET"))
        }
    }

    // Uploads

    @Test
    fun anUploadTriesAgainTheMomentTheServerAnswers() = runTest {
        val writes = AtomicInteger(0)
        WireFixture { request ->
            when (request.route) {
                "GET /api/info" -> Reply.Json(200, """{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/srv/tmp"}}""")
                "POST /api/experimental/fs/write" ->
                    if (writes.incrementAndGet() == 1) Reply.Failure(UrlErrorCode.NETWORK_CONNECTION_LOST)
                    else Reply.Json(200, """{"data":{"path":"/srv/tmp/uploads/u/log.json"}}""")
                else -> Reply.status(404)
            }
        }.use { fixture ->
            val file = File.createTempFile(UUID.randomUUID().toString(), ".json").apply { writeText("{}") }
            try {
                // The backoff never ends on its own: only the server answering lets it go.
                val clock = ScriptedClock(holdsSleepsLongerThan = Duration.ZERO)
                val key = fixture.server.persistenceID
                val answering = CoroutineScope(Dispatchers.Default).launch {
                    while (isActive) {
                        ServerContact.shared.heard(key)
                        delay(20)
                    }
                }
                var path: String? = null
                try {
                    retrying(clock) {
                        client.uploadFile(fixture.server, null, file, "log.json").collect { event ->
                            if (event is OpenCodeUploadEvent.Finished) path = event.path
                        }
                    }
                } finally {
                    answering.cancel()
                }
                assertEquals("/srv/tmp/uploads/u/log.json", path)
                assertEquals(2, writes.get())
                val posts = fixture.requests.filter { it.method == "POST" }
                assertEquals(listOf("1", "2"), posts.map { it.header("X-OpenCode-Attempt") })
                assertEquals(posts[0].header("X-OpenCode-Op"), posts[1].header("X-OpenCode-Op"))
                assertNotEquals(posts[0].header("X-OpenCode-Req"), posts[1].header("X-OpenCode-Req"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun aSuccessfulUploadCountsAsHearingFromTheServer() = runTest {
        // Listening from the moment the write arrives, after the info read's
        // own answer: only the upload's success can wake it.
        val heard = CompletableDeferred<Boolean>()
        val key = AtomicReference("")
        val listening = CoroutineScope(Dispatchers.Unconfined)
        WireFixture { request ->
            if (request.route == "GET /api/info") {
                return@WireFixture Reply.Json(200, """{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/srv/tmp"}}""")
            }
            // Subscribed before the reply returns (undispatched, unconfined).
            listening.launch(start = CoroutineStart.UNDISPATCHED) {
                ServerContact.shared.answers(key.get()).first()
                heard.complete(true)
            }
            Reply.Json(200, """{"data":{"path":"/srv/tmp/uploads/u/a.json"}}""")
        }.use { fixture ->
            key.set(fixture.server.persistenceID)
            val file = File.createTempFile(UUID.randomUUID().toString(), ".json").apply { writeText("{}") }
            try {
                client.uploadFile(fixture.server, null, file, "a.json").collect {}
                val result = withContext(Dispatchers.Default) { withTimeoutOrNull(2.seconds) { heard.await() } }
                assertEquals(true, result)
            } finally {
                file.delete()
                heard.cancel()
                listening.cancel()
            }
        }
    }

    @Test
    fun theOutboxOwnsASendsRetriesSoTheyNeverNest() = runTest {
        WireFixture { Reply.Failure(UrlErrorCode.NOT_CONNECTED_TO_INTERNET) }.use { fixture ->
            expectThrows<OpenCodeClientError> {
                retrying {
                    NetworkRetry.policy.withValue(RetryPolicy.once) {
                        client.sendPrompt(fixture.server, "ses_1", "hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer)
                    }
                }
            }
            assertEquals(1, fixture.requests.size)
        }
    }
}
