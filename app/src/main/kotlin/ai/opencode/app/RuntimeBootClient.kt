package ai.opencode.app

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.CompletableDeferred

/**
 * The chat side's handle on the local daemon.
 *
 * The runtime remains the only owner of install/start/pair commands; this client only asks the
 * visible window to run that handoff and waits for the server it produces. The pending request lives
 * here rather than in an activity, so a configuration change during pairing can reattach the new
 * window without losing the chat's wait. No Compose feature starts `opencode` itself.
 */
class RuntimeBootClient internal constructor(
    private val canStart: Boolean,
    initialRequester: (() -> Unit)? = null,
) {
    private val lock = Any()
    private var requester: (() -> Unit)? = initialRequester
    private var pending: CompletableDeferred<Result<OpenCodeServer>>? = null
    private var started = false

    /**
     * The visible window registers how to show the runtime handoff while it is alive. Store effects
     * run on the main dispatcher in the app, so invoking it directly stays on the UI thread; a test
     * can register a plain lambda without a main dispatcher.
     */
    fun register(requester: () -> Unit): () -> Unit {
        synchronized(lock) { this.requester = requester }
        return { synchronized(lock) { if (this.requester === requester) this.requester = null } }
    }

    /** True in the unified app: a chat with no server can bring its local runtime up. */
    val canStartRuntime: Boolean get() = canStart

    /** Starts (or restarts) the local daemon and returns the paired server. */
    suspend fun ensure(): Result<OpenCodeServer> {
        val requester = synchronized(lock) { requester }
            ?: return Result.failure(IllegalStateException("No window can start the local runtime."))
        val deferred = synchronized(lock) {
            pending ?: CompletableDeferred<Result<OpenCodeServer>>().also { pending = it }
        }
        val shouldStart = synchronized(lock) {
            if (started) false else {
                started = true
                true
            }
        }
        if (shouldStart) {
            try {
                requester()
            } catch (error: Throwable) {
                complete(Result.failure(error))
            }
        }
        return deferred.await()
    }

    /** Delivers the handoff result to the waiting chat request. False if nothing is waiting. */
    fun complete(result: Result<OpenCodeServer>): Boolean = synchronized(lock) {
        val deferred = pending ?: return false
        pending = null
        started = false
        deferred.complete(result)
        true
    }

    companion object Key : DependencyKey<RuntimeBootClient> {
        override val liveValue: RuntimeBootClient by lazy { RuntimeBootClient(canStart = true) }
        override val testValue: RuntimeBootClient get() = RuntimeBootClient(canStart = false)
    }
}
