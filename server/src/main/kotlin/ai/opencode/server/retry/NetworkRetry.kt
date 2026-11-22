package ai.opencode.server.retry

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.TaskLocal
import ai.opencode.server.UrlErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import java.net.SocketException
import java.net.UnknownServiceException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The app's one retry policy for talking to the OpenCode server. Rationale, parameters, and the
// audit of every call site.

/** Whether a request can be sent twice without a second effect. */
enum class RequestIdempotency {
    /** Reads, PUT, and POSTs the server dedupes by a client-made ID (a prompt's message ID, a session's ID). */
    Idempotent,

    /** A second copy could act twice (interrupt, fork, a permission reply). Retried only when provably unsent. */
    Unsafe;

    companion object {
        /**
         * GET, HEAD, PUT, and OPTIONS are idempotent by HTTP's definition. PATCH, POST, and
         * DELETE are not assumed to be; call sites opt in after checking the V2 handler.
         */
        fun forMethod(method: String): RequestIdempotency = when (method.uppercase()) {
            "GET", "HEAD", "PUT", "OPTIONS" -> Idempotent
            else -> Unsafe
        }
    }
}

/** How a request failed before any HTTP status arrived. */
enum class TransportFailure(val rawValue: String) {
    /** No connection was ever made (offline, no route, DNS, refused): the server can't have seen it. */
    NotSent("notSent"),

    /** The connection broke after the request may have reached the server. */
    Interrupted("interrupted"),

    /** No answer in time; the server may still be working on it. */
    TimedOut("timedOut");

    companion object {
        private val CLEARTEXT_REFUSAL = Regex("CLEARTEXT communication to (\\S+) not permitted")

        /** Nil for cancellation and failures sending again won't change. */
        fun ofUrlErrorCode(code: Int): TransportFailure? = when (code) {
            UrlErrorCode.NOT_CONNECTED_TO_INTERNET, UrlErrorCode.CANNOT_FIND_HOST, UrlErrorCode.CANNOT_CONNECT_TO_HOST,
            UrlErrorCode.DNS_LOOKUP_FAILED, UrlErrorCode.INTERNATIONAL_ROAMING_OFF, UrlErrorCode.DATA_NOT_ALLOWED,
            UrlErrorCode.CALL_IS_ACTIVE, UrlErrorCode.SECURE_CONNECTION_FAILED -> NotSent
            UrlErrorCode.NETWORK_CONNECTION_LOST, UrlErrorCode.BAD_SERVER_RESPONSE, UrlErrorCode.CANNOT_PARSE_RESPONSE,
            UrlErrorCode.RESOURCE_UNAVAILABLE, -1014 /* zeroByteResource */, -1021 /* requestBodyStreamExhausted */,
            -997 /* backgroundSessionWasDisconnected */ -> Interrupted
            UrlErrorCode.TIMED_OUT -> TimedOut
            else -> null
        }

        /**
         * The JVM/OkHttp exception, classified through its `URLError` equivalent: refused,
         * unresolvable and unroutable connections were never sent; resets, aborts, broken pipes and
         * truncated responses were interrupted.
         */
        fun of(error: Throwable): TransportFailure? {
            if (error is CancellationException) return null
            // EACCES (no permission to open a socket) is none of them.
            if (error is SocketException && error.message?.contains("EACCES") == true) return null
            return UrlErrorCode.fromThrowable(error)?.let(::ofUrlErrorCode)
        }

        /**
         * A transport-security refusal (cleartext policy) is permanent, unless this exact HTTP
         * origin already answered in this process, when it's `interrupted`.
         */
        fun of(error: Throwable, requestUrl: HttpUrl, contact: ServerContact): TransportFailure? {
            of(error)?.let { return it }
            if (error !is UnknownServiceException || !contact.hasAnsweredHTTPOrigin(requestUrl)) return null
            // A rejected redirect to another host isn't evidence of this failure (OkHttp names the refused host).
            val refusedHost = CLEARTEXT_REFUSAL.find(error.message.orEmpty())?.groupValues?.get(1)
            if (refusedHost != null && !refusedHost.equals(requestUrl.host, ignoreCase = true)) return null
            return Interrupted
        }
    }
}

/**
 * Bounded exponential backoff with jitter, a retry window, and a wait for the network
 * instead of spending attempts while the phone is offline.
 */
data class RetryPolicy(
    /** Attempts in all, the first included; null retries until cancelled. */
    val maxAttempts: Int?,
    /** The ceiling before the first retry; it doubles for each one after. */
    val baseDelay: Duration,
    /** No wait between attempts is longer than this. */
    val maxDelay: Duration,
    /** How long to keep trying once the first attempt failed. Null: bounded by [maxAttempts] alone. */
    val retryWindow: Duration?,
    /** The longest a server's `Retry-After` is honoured. */
    val longestRetryAfter: Duration = 30.seconds,
    /** While the phone has no network path, wait for one (within the window). */
    val waitsForNetwork: Boolean = true,
    /** Keep retries responsive briefly, then resume exponential backoff. */
    val fastWindow: Duration? = null,
    val fastMaxDelay: Duration = 2.seconds,
) {
    /** The longest wait before retry [n] (1 for the first retry). */
    fun ceiling(n: Int): Duration {
        if (n <= 0 || baseDelay <= Duration.ZERO) return Duration.ZERO
        val doublings = minOf(n - 1, 30)
        val uncapped = baseDelay * (1L shl doublings).toDouble()
        return minOf(maxDelay, uncapped)
    }

    fun delay(n: Int, unit: Double): Duration = jitter(ceiling(n), unit)

    companion object {
        /**
         * "Equal jitter": uniformly between half [ceiling] and [ceiling]. [unit] is in 0..<1.
         * Unlike full jitter, every wait is at least half its ceiling, so the retries are spread
         * over a few seconds of dropout instead of possibly bunching at its start.
         */
        fun jitter(ceiling: Duration, unit: Double): Duration {
            val clamped = unit.coerceIn(0.0, 1.0)
            return ceiling / 2 + (ceiling / 2) * clamped
        }

        /** Every request: five attempts over 7.5–15 s of waits, within 30 s of the first failure. */
        val request = RetryPolicy(maxAttempts = 5, baseDelay = 1.seconds, maxDelay = 8.seconds, retryWindow = 30.seconds)

        /** What the person sent: kept trying, at most 15 s apart, for five minutes of failures. */
        val send = RetryPolicy(maxAttempts = null, baseDelay = 1.seconds, maxDelay = 15.seconds, retryWindow = 300.seconds)

        /** One attempt: the caller owns retrying, or a fast answer matters more. */
        val once = RetryPolicy(maxAttempts = 1, baseDelay = Duration.ZERO, maxDelay = Duration.ZERO, retryWindow = null, waitsForNetwork = false)

        /**
         * The event stream doubles as the recovery probe. Waits stay within 2 s for the first 15 s
         * (a tunnel or Wi-Fi handoff), then double to five minutes: some VPN clients stall the
         * whole tunnel while packets to a dead peer queue.
         */
        val stream = RetryPolicy(maxAttempts = null, baseDelay = 1.seconds, maxDelay = 300.seconds, retryWindow = null, fastWindow = 15.seconds)

        /** Local consumers falling behind need time, not another address. */
        val streamOverflow = RetryPolicy(maxAttempts = null, baseDelay = 1.seconds, maxDelay = 15.seconds, retryWindow = null, waitsForNetwork = false)

        /** Waiting for a session's run to settle: about seven minutes. */
        val sessionWait = RetryPolicy(maxAttempts = 30, baseDelay = 1.seconds, maxDelay = 16.seconds, retryWindow = null)
    }
}

/** Whether a failed attempt should be sent again, and how soon at the earliest. */
sealed interface RetryVerdict {
    data class Transient(val retryAfter: Duration?) : RetryVerdict
    data object Permanent : RetryVerdict

    val logSummary: String
        get() = when (this) {
            is Transient -> "transient"
            Permanent -> "permanent"
        }
}

/**
 * The one classification of failures for every retry loop. Retried: connection failures
 * and timeouts, and 408, 429, 502, 503, 504 (+ Cloudflare 520–527/530). A request that
 * isn't idempotent is retried only when the server provably didn't act: no connection,
 * or 408, 429, 503.
 */
fun OpenCodeClientError.retryVerdict(idempotency: RequestIdempotency): RetryVerdict {
    if (isUnsupported) return RetryVerdict.Permanent
    val after = retryAfter?.let { Math.round(it * 1000).milliseconds }
    transport?.let {
        return if (it == TransportFailure.NotSent || idempotency == RequestIdempotency.Idempotent) {
            RetryVerdict.Transient(after)
        } else RetryVerdict.Permanent
    }
    val status = statusCode ?: return RetryVerdict.Permanent
    return when {
        status == 408 || status == 429 || status == 503 -> RetryVerdict.Transient(after)
        OpenCodeClientError.isGatewayFailure(status) ->
            if (idempotency == RequestIdempotency.Idempotent) RetryVerdict.Transient(after) else RetryVerdict.Permanent
        else -> RetryVerdict.Permanent
    }
}

/** The pure decision of one retry loop. Holds no clock and no randomness. */
class RetryBackoff(val policy: RetryPolicy) {
    sealed interface Decision {
        data class Retry(val after: Duration) : Decision
        /** Offline: wait at most this long for a network path, then retry at once. */
        data class WaitForNetwork(val upTo: Duration?) : Decision
        data object GiveUp : Decision
    }

    /** Failed attempts counted against `maxAttempts`. Failures while offline don't count. */
    var failures = 0
        private set
    private var slowFailures = 0

    /** [elapsed] is the time since the first failure; [unit] a random value in 0..<1. */
    fun decide(verdict: RetryVerdict, elapsed: Duration, offline: Boolean, unit: Double): Decision {
        if (verdict !is RetryVerdict.Transient) return Decision.GiveUp
        val remaining = policy.retryWindow?.let { it - elapsed }
        if (remaining != null && remaining <= Duration.ZERO) return Decision.GiveUp
        if (offline && policy.waitsForNetwork) return Decision.WaitForNetwork(remaining)
        failures += 1
        policy.maxAttempts?.let { if (failures >= it) return Decision.GiveUp }
        var delay: Duration
        val window = policy.fastWindow
        if (window != null) {
            val ceiling = if (elapsed <= window) {
                minOf(policy.fastMaxDelay, policy.ceiling(failures))
            } else {
                slowFailures += 1
                minOf(policy.maxDelay, policy.fastMaxDelay * (1L shl minOf(slowFailures, 30)).toDouble())
            }
            delay = RetryPolicy.jitter(ceiling, unit)
        } else {
            delay = policy.delay(failures, unit)
        }
        verdict.retryAfter?.let { retryAfter ->
            if (retryAfter > policy.longestRetryAfter) return Decision.GiveUp
            delay = maxOf(delay, retryAfter)
        }
        if (remaining != null && delay > remaining) return Decision.GiveUp
        return Decision.Retry(delay)
    }

    /** Whether to try again with no wait at all: a transient failure, within the window and attempts. */
    fun decideNow(verdict: RetryVerdict, elapsed: Duration): Boolean {
        if (verdict !is RetryVerdict.Transient) return false
        policy.retryWindow?.let { if (elapsed >= it) return false }
        failures += 1
        policy.maxAttempts?.let { if (failures >= it) return false }
        return true
    }

    /** A healthy connection: the next failure starts over. */
    fun reset() {
        failures = 0
        slowFailures = 0
    }
}

/** What a retry loop needs from the world, so tests can script it. */
class RetryEnvironment(
    /** A random value in 0..<1. */
    val random: () -> Double,
    /** True only when the phone is known to have no network path. */
    val isOffline: () -> Boolean,
    /** Returns once a network path is available (or at once if one is). */
    val networkAvailable: suspend () -> Unit,
) {
    companion object {
        val live: RetryEnvironment by lazy { NetworkPathClient.liveValue.retryEnvironment }

        /** Always online, with jitter fixed at [unit]. */
        fun online(unit: Double = 0.5) = RetryEnvironment(random = { unit }, isOffline = { false }, networkAvailable = {})
    }
}

/** One retry loop's schedule: a [RetryBackoff] with a clock and the world. */
class RetrySchedule(
    policy: RetryPolicy,
    private val clock: Clock,
    private val environment: RetryEnvironment,
    private val budget: RetryBudget? = null,
) {
    private var backoff = RetryBackoff(policy)
    private var firstFailureAt: Duration? = null

    val failures: Int get() = backoff.failures

    private fun elapsedSinceFirstFailure(): Duration {
        if (firstFailureAt == null) {
            // With a budget, the window is what's left of it when the first attempt fails.
            budget?.let { backoff = RetryBackoff(it.capping(backoff.policy)) }
            firstFailureAt = clock.now()
        }
        return clock.now() - firstFailureAt!!
    }

    /** The pause before the next attempt, or null to stop trying. */
    fun next(verdict: RetryVerdict): RetryPause? {
        val elapsed = elapsedSinceFirstFailure()
        val decision = backoff.decide(
            verdict,
            elapsed = elapsed,
            offline = backoff.policy.waitsForNetwork && environment.isOffline(),
            unit = environment.random(),
        )
        return when (decision) {
            RetryBackoff.Decision.GiveUp -> null
            is RetryBackoff.Decision.Retry -> RetryPause(RetryPause.Kind.Sleep(decision.after), clock, environment)
            is RetryBackoff.Decision.WaitForNetwork -> RetryPause(RetryPause.Kind.Network(decision.upTo), clock, environment)
        }
    }

    /** True to try again at once, with no pause, while the window lasts. */
    fun retryNow(verdict: RetryVerdict): Boolean = backoff.decideNow(verdict, elapsedSinceFirstFailure())

    /** A connection that held: the next failure starts the schedule over. */
    fun reset() {
        backoff.reset()
        firstFailureAt = null
    }
}

/** The wait between two attempts. */
class RetryPause internal constructor(
    val kind: Kind,
    private val clock: Clock,
    private val environment: RetryEnvironment,
) {
    sealed interface Kind {
        data class Sleep(val delay: Duration) : Kind
        data class Network(val limit: Duration?) : Kind

        val waitsForNetwork: Boolean get() = this is Network

        val logSummary: String
            get() = when (this) {
                is Sleep -> "retry in ${delay.logSummary}"
                is Network -> "wait for the network" + (limit?.let { " (up to ${it.logSummary})" } ?: "")
            }
    }

    /**
     * Waits it out. False if the network didn't come back in time. [wake] ends a sleep early
     * (the server answering again). Cancellation propagates as [CancellationException].
     */
    suspend fun run(wake: (suspend () -> Unit)? = null): Boolean {
        when (kind) {
            is Kind.Sleep -> {
                if (wake == null) {
                    clock.sleep(kind.delay)
                    return currentCoroutineContext().isActive
                }
                return first({ clock.sleep(kind.delay); true }, { wake(); true })
            }
            is Kind.Network -> {
                val available = environment.networkAvailable
                val limit = kind.limit
                if (limit == null) {
                    available()
                    return currentCoroutineContext().isActive
                }
                return first({ available(); true }, { clock.sleep(limit); false })
            }
        }
    }

    internal companion object {
        /** The first of two waits to finish; the other is cancelled. */
        suspend fun first(a: suspend () -> Boolean, b: suspend () -> Boolean): Boolean = coroutineScope {
            val first = async { a() }
            val second = async { b() }
            val result = select {
                first.onAwait { it }
                second.onAwait { it }
            }
            coroutineContext.cancelChildren()
            result && isActive
        }
    }
}

object NetworkRetry {
    /** Identity of the retry-owning operation, not a user/session identifier. */
    data class Correlation(
        val operationID: String = UUID.randomUUID().toString().uppercase(),
        val attempt: Int = 1,
    ) {
        fun identify(request: Request.Builder, requestID: String): Request.Builder = request
            .header("X-OpenCode-Op", operationID)
            .header("X-OpenCode-Req", requestID)
            .header("X-OpenCode-Attempt", attempt.toString())
    }

    /** Observation owned by an existing manual retry loop. Never decides whether/when to retry. */
    class ManualOperation {
        private val lock = Any()
        private var correlation = Correlation()
        private var hasStarted = false
        val current: Correlation get() = synchronized(lock) { correlation }
        fun started() = synchronized(lock) { hasStarted = true }
        fun retried() = synchronized(lock) {
            if (hasStarted) correlation = correlation.copy(attempt = correlation.attempt + 1)
            hasStarted = false
        }
    }

    val correlation = TaskLocal<Correlation?> { null }
    val manualOperation = TaskLocal<ManualOperation?> { null }
    val streamOperationID = TaskLocal<String?> { null }

    /**
     * Overrides the policy for `OpenCodeClient` requests made in this coroutine. A loop with
     * its own retries sets [RetryPolicy.once] so retries never nest. Unset in tests,
     * requests aren't retried unless a test opts in.
     */
    val policy = TaskLocal<RetryPolicy?> { null }
    val environment = TaskLocal { RetryEnvironment.live }
    val clock = TaskLocal<Clock?> { null }

    /** The latency budget of the operation this coroutine belongs to (a voice tool call). */
    val budget = TaskLocal<RetryBudget?> { null }

    /** Runs [block] outside any retry scope: it gets the default policy, not a loop's `.once` or a budget. */
    suspend fun <R> outsideScope(block: suspend () -> R): R = withContext(
        manualOperation.asContextElement(null) + streamOperationID.asContextElement(null) +
            correlation.asContextElement(null) + policy.asContextElement(null) + budget.asContextElement(null),
    ) { block() }

    suspend fun currentPolicy(): RetryPolicy = currentPolicy(RetryPolicy.request)

    /** The policy a coroutine set, else [fallback] (requests are sent once under tests). */
    suspend fun currentPolicy(fallback: RetryPolicy): RetryPolicy =
        policy.current() ?: if (DependencyValues.current.isTesting) RetryPolicy.once else fallback

    internal suspend fun resolvedClock(override: Clock? = null): Clock = override ?: clock.current() ?: dependency(Clock)

    /**
     * Runs [operation] under [policy], trying again after transient failures. The error it
     * throws is the last attempt's, with `attempts` counting every attempt. [reachable]
     * returns once the server answers again: after a connection failure it ends the wait
     * early. A wait after an HTTP status always runs its course. [label] names the request
     * in the log ("POST /api/session/…/prompt").
     */
    suspend fun <T> run(
        policy: RetryPolicy? = null,
        idempotency: RequestIdempotency,
        label: String? = null,
        clock: Clock? = null,
        environment: RetryEnvironment? = null,
        reachable: (suspend () -> Unit)? = null,
        operation: suspend (attempt: Int) -> T,
    ): T {
        val resolvedPolicy = policy ?: currentPolicy()
        val resolvedClock = resolvedClock(clock)
        val resolvedEnvironment = environment ?: this.environment.current()
        val schedule = RetrySchedule(resolvedPolicy, resolvedClock, resolvedEnvironment, budget.current())
        var attempt = 0
        var mayHaveArrived = false
        // A caller that owns retries runs its individual calls under .once.
        // Preserve that owner's identity; independent polls/reconnects start fresh.
        val inherited = if (resolvedPolicy.maxAttempts == 1) correlation.current() ?: manualOperation.current()?.current else null
        val operationID = inherited?.operationID ?: UUID.randomUUID().toString().uppercase()
        while (true) {
            attempt += 1
            try {
                val identity = Correlation(operationID, inherited?.attempt ?: attempt)
                val value = correlation.withValue(identity) { operation(attempt) }
                if (attempt > 1 && label != null) AppLog.network.notice { "$label succeeded on attempt $attempt" }
                return value
            } catch (thrown: OpenCodeClientError) {
                val error = thrown.copy(attempts = attempt, earlierAttemptMayHaveArrived = mayHaveArrived)
                mayHaveArrived = error.mayHaveReachedServer
                val verdict = error.retryVerdict(idempotency)
                currentCoroutineContext().ensureActive()
                // A loop that owns its retries logs its own decisions.
                val logs = label != null && resolvedPolicy.maxAttempts != 1
                val pause = schedule.next(verdict)
                if (pause == null) {
                    if (logs) AppLog.network.error { "$label gave up: ${error.logSummary} (${verdict.logSummary})" }
                    throw error
                }
                if (logs) AppLog.network.notice { "$label attempt $attempt failed: ${error.logSummary}; ${pause.kind.logSummary}" }
                if (!pause.run(wake = if (error.transport == null) null else reachable)) {
                    currentCoroutineContext().ensureActive()
                    if (logs) AppLog.network.error { "$label gave up waiting: ${error.logSummary}" }
                    throw error
                }
            }
        }
    }
}

/** Runs [block] with every `OpenCodeClient` request in it on [policy]. */
suspend fun <R> withRetryPolicy(policy: RetryPolicy?, block: suspend () -> R): R = NetworkRetry.policy.withValue(policy, block)

/**
 * One latency budget across the steps of an operation (a voice tool that creates a
 * session, sets its model, then sends to it). Required steps retry within what's left;
 * optional ones are sent once, given a short deadline, and skipped when it passes.
 */
class RetryBudget(val total: Duration, private val clock: Clock = dependency(Clock)) {
    private val origin = clock.now()

    val remaining: Duration get() = maxOf(Duration.ZERO, total - (clock.now() - origin))

    /** The policy for a required step: the caller's, with its retry window cut to what's left. */
    suspend fun policy(): RetryPolicy = capping(NetworkRetry.currentPolicy())

    /** [policy] with its retry window cut to what's left of the budget. `.once` is left alone. */
    fun capping(policy: RetryPolicy): RetryPolicy {
        if (policy.maxAttempts == 1) return policy
        val remaining = remaining
        return policy.copy(retryWindow = minOf(policy.retryWindow ?: remaining, remaining))
    }

    /** Runs a whole operation on this budget: every request inside retries within what's left. */
    suspend fun <T> scope(operation: suspend () -> T): T = NetworkRetry.budget.withValue(this, operation)

    /** A step the operation can't do without, retried within what's left. */
    suspend fun <T> required(operation: suspend () -> T): T {
        // Bound as the coroutine's budget too (unless an enclosing one is tighter).
        val enclosing = NetworkRetry.budget.current()
        val budget = enclosing?.takeIf { it.remaining <= remaining } ?: this
        return NetworkRetry.policy.withValue(policy()) { NetworkRetry.budget.withValue(budget, operation) }
    }

    /** A step the operation can do without: sent once, null if it fails or takes longer than [limit]. */
    suspend fun <T> optional(limit: Duration, operation: suspend () -> T): T? {
        val bounded = minOf(limit, remaining)
        if (bounded <= Duration.ZERO) return null
        return bounded(bounded) {
            withRetryPolicy(RetryPolicy.once) {
                try { operation() } catch (_: OpenCodeClientError) { null }
            }
        }
    }

    companion object {
        /** A budget on `NetworkRetry.clock` when one is set, so the budget and its retries share a clock. */
        suspend fun start(total: Duration): RetryBudget = RetryBudget(total, NetworkRetry.resolvedClock())

        /**
         * The work's result, or null if it takes longer than [limit]; the work is cancelled then.
         */
        suspend fun <T> bounded(limit: Duration, clock: Clock? = null, work: suspend () -> T?): T? {
            val resolved = clock ?: Clock.liveValue
            return coroutineScope {
                val result = async { work() }
                val timer = async { resolved.sleep(limit); null }
                val first = select {
                    result.onAwait { it }
                    timer.onAwait { it }
                }
                coroutineContext.cancelChildren()
                first
            }
        }
    }
}

internal fun liveRandom(): Double = Random.nextDouble()
