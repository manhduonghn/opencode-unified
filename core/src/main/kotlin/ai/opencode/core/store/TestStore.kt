package ai.opencode.core.store

import ai.opencode.core.dependencies.DependencyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Port of TCA's `TestStore`. Exhaustive by default: assert every state change, [receive] every
 * effect action, and [finish] fails while effects run. [exhaustive] = false is TCA's `exhaustivity
 * = .off`.
 */
class TestStore<S, A>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    scope: CoroutineScope,
    dependencies: DependencyValues.Builder.() -> Unit = {},
) {
    /** The container reducers and effects resolve; read `Shared` values the reducer wrote through it. */
    var dependencies: DependencyValues = DependencyValues.testing().update(dependencies)
        private set

    fun updateDependencies(block: DependencyValues.Builder.() -> Unit) {
        dependencies = dependencies.update(block)
    }

    /** Reads a value with this store's dependencies installed (e.g. `withDependencies { shared(key).value }`). */
    fun <R> withDependencies(block: () -> R): R = DependencyValues.withValues(dependencies, block)

    /** Every action effects send, in the order production would reduce them. */
    private val received = Channel<A>(Channel.UNLIMITED)

    /**
     * Actions sent synchronously (`Effect.send`): `Store` (and TCA) reduces them right after the
     * action that sent them, ahead of anything a running effect sent meanwhile, so they come first.
     */
    private val immediate = ArrayDeque<A>()

    private fun nextReceived(): A? = synchronized(immediate) { immediate.removeFirstOrNull() } ?: received.tryReceive().getOrNull()
    private var unhandledError: Throwable? = null

    var exhaustive: Boolean = true

    var state: S = initialState
        private set

    /**
     * The sending coroutine's context, less its job and dispatcher, so effects see the locals it
     * bound.
     */
    private var callerContext: CoroutineContext = EmptyCoroutineContext
    private val effectScope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext get() = scope.coroutineContext + callerContext
    }

    private suspend fun captureCaller() {
        callerContext = currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor)
    }

    private val runner = EffectRunner<A>(
        scope = effectScope,
        sendNow = { synchronized(immediate) { immediate.addLast(it) } },
        // As `Store`'s hop to the main thread (and TCA's `Send`): a cancelled effect's sends are dropped.
        sendFromEffect = { currentCoroutineContext().ensureActive(); received.send(it) },
        onUnhandledError = { if (unhandledError == null) unhandledError = it },
    )

    /** Sends [action] and asserts the resulting state equals `update(previous)`. */
    suspend fun send(action: A, update: ((S) -> S)? = null) {
        captureCaller()
        throwUnhandled()
        if (exhaustive) assertNoUnreceived("before sending $action") else drain()
        apply(action, update, "send($action)")
        yield()
    }

    /** Waits for an effect to send an action equal to [expected], then asserts the state change. */
    suspend fun receive(expected: A, timeout: Duration = 1.seconds, update: ((S) -> S)? = null) {
        receive({ it == expected }, "$expected", timeout, update)
    }

    /** Waits for an action matching [matches] (for actions carrying non-comparable payloads). */
    suspend fun receive(
        matches: (A) -> Boolean,
        description: String = "matching action",
        timeout: Duration = 1.seconds,
        update: ((S) -> S)? = null,
    ) {
        captureCaller()
        throwUnhandled()
        while (true) {
            val action = nextReceived() ?: withTimeout(timeout) { received.receive() }
            if (matches(action)) {
                apply(action, update, "receive($action)")
                break
            }
            if (exhaustive) throw AssertionError("Expected to receive $description but received $action")
            apply(action, null, "skipped receive($action)", assert = false)
        }
        yield()
        throwUnhandled()
    }

    /** Cancels every effect still running (TCA's `skipInFlightEffects`), for long-living ones a test leaves. */
    suspend fun skipInFlightEffects() {
        yield()
        throwUnhandled()
        runner.cancelAll()
        yield()
    }

    /** Reduces actions effects sent without asserting them (TCA's `skipReceivedActions`). */
    suspend fun skipReceivedActions() {
        captureCaller()
        yield()
        drain()
    }

    /**
     * Asserts every action was received and no effect still runs. Non-exhaustive, effects still
     * running after [timeout] are cancelled without failing.
     */
    suspend fun finish(timeout: Duration = 1.seconds) {
        captureCaller()
        yield()
        throwUnhandled()
        if (exhaustive) assertNoUnreceived("at finish") else drain()
        try {
            withTimeout(timeout) {
                while (true) {
                    if (!exhaustive) drain()
                    if (!runner.hasRunningEffects) break
                    delay(5)
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val count = runner.runningCount
            runner.cancelAll()
            if (!exhaustive) return
            throw AssertionError("$count effect(s) still running at finish. Cancel them or await their actions.")
        }
        throwUnhandled()
        if (exhaustive) assertNoUnreceived("at finish") else drain()
    }

    private fun drain() {
        while (true) {
            val action = nextReceived() ?: return
            apply(action, null, "skipped($action)", assert = false)
        }
    }

    private fun apply(action: A, update: ((S) -> S)?, label: String, assert: Boolean = true) {
        val before = state
        val reduced = DependencyValues.withValues(dependencies) { reducer.reduce(before, action) }
        state = reduced.state
        DependencyValues.withValues(dependencies) { runner.execute(reduced.effect) }
        if (!assert) return
        val base = if (exhaustive) before else reduced.state
        val expected = update?.invoke(base) ?: base
        if (expected != reduced.state) {
            throw AssertionError("State mismatch after $label\nExpected: $expected\n  Actual: ${reduced.state}")
        }
    }

    private fun throwUnhandled() {
        val error = unhandledError ?: return
        unhandledError = null
        throw AssertionError("An effect threw: $error", error)
    }

    private fun assertNoUnreceived(where: String) {
        val next = nextReceived() ?: return
        throw AssertionError("Unreceived action $where: $next")
    }
}
