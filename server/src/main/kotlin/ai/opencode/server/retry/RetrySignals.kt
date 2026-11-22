package ai.opencode.server.retry

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * What a long-lived retry loop watches, counted so a signal during an attempt isn't missed: the
 * server answering, the route changing, and app phase changes. [context] carries the caller's
 * dependencies into the watchers.
 */
class RetrySignals(networkPath: NetworkPathClient, server: String, context: CoroutineContext = EmptyCoroutineContext) {
    data class Counts(
        /** The server answered, or the network path came back or moved. */
        val reconnects: Int = 0,
        val activations: Int = 0,
        val backgrounds: Int = 0,
    )

    private class Waiter(val onReconnect: Boolean, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var state = Counts()
    private val waiters = HashMap<UUID, Waiter>()
    private var isCancelled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + context.minusKey(Job))

    init {
        // Subscribed now (undispatched), so nothing between here and the first wait is missed.
        val reroutes = networkPath.reroutes()
        val phases = networkPath.appPhases()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                networkPath.serverAnswered(server)
                if (!isActive) return@launch
                signal { it.copy(reconnects = it.reconnects + 1) }
                // A burst of answers is one reconnect, and a source that returns without waiting can't spin.
                delay(50)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            reroutes.collect { signal { it.copy(reconnects = it.reconnects + 1) } }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            phases.collect { phase ->
                when (phase) {
                    AppPhase.Active -> signal { it.copy(activations = it.activations + 1) }
                    AppPhase.Background -> signal(wakes = false) { it.copy(backgrounds = it.backgrounds + 1) }
                }
            }
        }
    }

    val counts: Counts get() = synchronized(lock) { state }

    fun cancel() {
        val ready = synchronized(lock) {
            isCancelled = true
            val all = waiters.values.toList()
            waiters.clear()
            all
        }
        scope.cancel()
        ready.forEach { it.continuation.resume(Unit) }
    }

    /**
     * Returns at the next activation, or (with [onReconnect]) reconnect, counting from
     * [since], so one that came on the way here isn't missed; or when cancelled.
     */
    suspend fun next(onReconnect: Boolean, since: Counts) {
        val id = UUID.randomUUID()
        suspendCancellableCoroutine { continuation ->
            val now = synchronized(lock) {
                if (isCancelled || state.activations != since.activations ||
                    (onReconnect && state.reconnects != since.reconnects)
                ) {
                    true
                } else {
                    waiters[id] = Waiter(onReconnect, continuation)
                    false
                }
            }
            if (now) continuation.resume(Unit)
            continuation.invokeOnCancellation { synchronized(lock) { waiters.remove(id) } }
        }
    }

    private fun signal(wakes: Boolean = true, change: (Counts) -> Counts) {
        val ready = synchronized(lock) {
            val before = state
            state = change(state)
            if (!wakes) return
            val reconnect = state.reconnects != before.reconnects
            val woken = waiters.filter { !reconnect || it.value.onReconnect }
            woken.keys.forEach(waiters::remove)
            woken.values.toList()
        }
        ready.forEach { if (it.continuation.isActive) it.continuation.resume(Unit) }
    }
}
