package ai.opencode.core.store

import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes [Effect]s for [Store] and [TestStore] alike. Cancels and sends run before the call
 * returns; coroutines launch in [scope].
 */
internal class EffectRunner<A>(
    private val scope: CoroutineScope,
    /** Delivers an action synchronously (queued after the current reduce). */
    private val sendNow: (A) -> Unit,
    /** Delivers an action from a running effect. */
    private val sendFromEffect: suspend (A) -> Unit,
    /** An effect threw (not cancellation): `Store` logs it, `TestStore` fails the test. */
    private val onUnhandledError: (Throwable) -> Unit = { error ->
        AppLog.app.error { "Unhandled effect error: ${error.logSummary}" }
    },
) {
    private val lock = Any()
    private data class CancelKey(val path: List<Any>, val id: Any)

    private val inFlight = HashMap<CancelKey, MutableSet<Job>>()
    private val running = HashSet<Job>()

    /** Whether any effect coroutine is still running (`TestStore.finish` asserts none are). */
    val hasRunningEffects: Boolean get() = synchronized(lock) { running.any { it.isActive } }

    val runningCount: Int get() = synchronized(lock) { running.count { it.isActive } }

    fun execute(effect: Effect<A>) = execute(effect, sendNow, sendFromEffect)

    fun cancelAll() {
        val jobs = synchronized(lock) { running.toList().also { running.clear(); inFlight.clear() } }
        jobs.forEach { it.cancel() }
    }

    /**
     * Coroutines register with every enclosing key at once, so a cancel sent before one first runs
     * still reaches it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <B> execute(
        effect: Effect<B>,
        now: (B) -> Unit,
        later: suspend (B) -> Unit,
        path: List<Any> = emptyList(),
        keys: List<CancelKey> = emptyList(),
    ) {
        when (effect) {
            is Effect.None -> Unit
            is Effect.SendAction -> now(effect.action)
            is Effect.Cancel -> cancel(effect.ids.map { CancelKey(path, it) })
            is Effect.Merge -> effect.effects.forEach { execute(it, now, later, path, keys) }
            is Effect.NavigationScoped -> execute(effect.effect, now, later, path + effect.id, keys)
            is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                execute(mapped.effect, { now(mapped.transform(it)) }, { later(mapped.transform(it)) }, path, keys)
            }
            is Effect.Cancellable -> {
                val key = CancelKey(path, effect.id)
                if (effect.cancelInFlight) cancel(listOf(key))
                // Sends and cancels inside stay synchronous, so a child's `.send` or `.cancel`
                // under `ifLet`/`forEach` keeps TCA's ordering.
                execute(effect.effect, now, later, path, keys + key)
            }
            is Effect.Run, is Effect.Concatenate -> launchTracked(keys) { runToCompletion(effect, later, path) }
        }
    }

    private fun launchTracked(ids: List<CancelKey>, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(lock) {
            running += job
            for (id in ids) inFlight.getOrPut(id) { HashSet() } += job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                running -= job
                for (id in ids) inFlight[id]?.let { set -> set -= job; if (set.isEmpty()) inFlight.remove(id) }
            }
        }
        job.start()
    }

    private fun cancel(ids: List<CancelKey>) {
        val jobs = synchronized(lock) { ids.flatMap { inFlight.remove(it).orEmpty() } }
        jobs.forEach { it.cancel() }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <B> runToCompletion(effect: Effect<B>, send: suspend (B) -> Unit, path: List<Any>) {
        when (effect) {
            is Effect.None -> Unit
            is Effect.SendAction -> send(effect.action)
            is Effect.Cancel -> cancel(effect.ids.map { CancelKey(path, it) })
            is Effect.NavigationScoped -> runToCompletion(effect.effect, send, path + effect.id)
            is Effect.Run -> withContext(effect.dependencies.asContextElement()) {
                try {
                    effect.operation(Send { send(it) })
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // Effects never crash the app; reducers model failures as actions.
                    onUnhandledError(error)
                }
            }
            is Effect.Merge -> coroutineScope { effect.effects.forEach { launch { runToCompletion(it, send, path) } } }
            is Effect.Concatenate -> effect.effects.forEach { runToCompletion(it, send, path) }
            is Effect.Mapped<*, *> -> {
                val mapped = effect as Effect.Mapped<Any?, B>
                runToCompletion(mapped.effect, { send(mapped.transform(it)) }, path)
            }
            is Effect.Cancellable -> {
                val key = CancelKey(path, effect.id)
                if (effect.cancelInFlight) cancel(listOf(key))
                coroutineScope {
                    val child = launch(start = CoroutineStart.LAZY) { runToCompletion(effect.effect, send, path) }
                    synchronized(lock) { inFlight.getOrPut(key) { HashSet() } += child }
                    child.start()
                    child.invokeOnCompletion {
                        synchronized(lock) { inFlight[key]?.let { set -> set -= child; if (set.isEmpty()) inFlight.remove(key) } }
                    }
                }
            }
        }
    }
}
