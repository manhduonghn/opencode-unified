package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Shared activation, not exclusive ownership. Only the last holder releases the
 * Android audio focus, communication device and mode. Admission/recovery/release
 * serialize the OS call too. A failed activation retains teardown responsibility. */
class AudioSessionOwner(
    private val schedule: (Duration, () -> Unit) -> Unit = { delay, work ->
        retryScope.launch { dependency(Clock).sleep(delay); work() }
    },
) {
    private val lock = Any()
    private var nextID = 0
    private var epoch = 0
    private val live = mutableSetOf<Int>()
    private val active = mutableSetOf<Int>()
    private val ready = mutableSetOf<Int>()
    val activeCount: Int get() = synchronized(lock) { active.size }

    /**
     * Runs [body] under the lock with whether no holder has the session (no call, dictation or
     * media), so none activates midway. For sounds that borrow the idle session (Sound Effects).
     */
    fun <T> withIdleState(body: (isIdle: Boolean) -> T): T = synchronized(lock) { body(active.isEmpty()) }

    /** Stops a borrowed sound as a holder activates, under the lock and before the holder touches the session. */
    private var borrowerStop: (() -> Unit)? = null

    fun onHolderActivating(stop: () -> Unit) = synchronized(lock) { borrowerStop = stop }

    fun holder(deactivate: () -> Unit): Holder = synchronized(lock) {
        Holder(++nextID, deactivate).also { live.add(nextID) }
    }

    private fun deactivate(body: () -> Unit, attempt: Int = 0): Boolean = try {
        body()
        if (attempt > 0) AppLog.voice.notice("Released audio session on retry $attempt")
        true
    } catch (error: Exception) {
        val code = error.javaClass.simpleName
        if (attempt >= retryDelays.size) {
            AppLog.voice.error("Couldn’t release audio session after $attempt retries ($code)")
        } else {
            AppLog.voice.error("Couldn’t release audio session ($code), retry ${attempt + 1}")
            val activation = epoch
            schedule(retryDelays[attempt]) {
                synchronized(lock) {
                    if (epoch == activation && active.isEmpty()) deactivate(body, attempt + 1)
                }
            }
        }
        false
    }

    inner class Holder internal constructor(private val id: Int, private val deactivate: () -> Unit) : AutoCloseable {
        fun activate(body: () -> Unit) = synchronized(lock) {
            if (id !in live || Thread.currentThread().isInterrupted) throw CancellationException()
            borrowerStop?.invoke()
            active.add(id)
            ready.remove(id)
            epoch++
            body()
            if (id !in live || Thread.currentThread().isInterrupted) throw CancellationException()
            ready.add(id)
            Unit
        }
        fun invalidateActivation() = synchronized(lock) { ready.remove(id); Unit }
        fun withActive(body: () -> Unit) = synchronized(lock) {
            if (id !in live || id !in ready || Thread.currentThread().isInterrupted) throw CancellationException()
            body()
        }
        /**
         * Ends admission without touching the OS session: later activations and engine starts
         * throw, and [release] still deactivates. For a hang-up that lands while another thread
         * configures the engine.
         */
        fun withdraw() = synchronized(lock) { live.remove(id); ready.remove(id); Unit }
        /** Whether this was the last holder and the session deactivated. A refused deactivation returns false and retries in the background. */
        fun release(): Boolean = synchronized(lock) {
            live.remove(id)
            ready.remove(id)
            if (!active.remove(id) || active.isNotEmpty()) return@synchronized false
            deactivate(deactivate)
        }
        override fun close() { release() }
    }
    companion object Key : DependencyKey<AudioSessionOwner> {
        val retryDelays = listOf(500.milliseconds, 1500.milliseconds, 4.seconds)
        private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        override val liveValue by lazy { AudioSessionOwner() }
        override val testValue get() = AudioSessionOwner(schedule = { _, _ -> })
    }
}

/** Press/cue identity cancels a hang-up tail or dictation hold, never a call holder. */
object AudioSessionGeneration {
    private val lock = Any()
    private var generation = 0
    private var onNext: (() -> Unit)? = null
    val value: Int get() = synchronized(lock) { generation }
    fun bump(): Int {
        val (value, cancel) = synchronized(lock) {
            generation++
            (generation to onNext).also { onNext = null }
        }
        cancel?.invoke()
        return value
    }
    fun onNext(after: Int, cancel: () -> Unit) {
        val now = synchronized(lock) {
            if (generation != after) true else { onNext = cancel; false }
        }
        if (now) cancel()
    }
}
