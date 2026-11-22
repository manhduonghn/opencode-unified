package ai.opencode.core.dependencies

import android.content.Context
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The application [Context], installed once by `OpenCodeApplication.onCreate`. Reducers never read
 * it.
 */
object AppContext : DependencyKey<Context> {
    override val liveValue: Context
        get() = error("AppContext is installed by OpenCodeApplication.onCreate before first use")
    override val testValue: Context
        get() = error("AppContext has no test value: override it, or keep Android out of the reducer")
}

/** Port of `@Dependency(\.continuousClock)`: monotonic time and sleeping. */
interface Clock {
    /** Monotonic time since an arbitrary origin. */
    fun now(): Duration
    suspend fun sleep(duration: Duration)

    companion object Key : DependencyKey<Clock> {
        override val liveValue: Clock = object : Clock {
            override fun now(): Duration = System.nanoTime().nanoseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        /** Tests must override with [TestClock] or [ImmediateClock]; the default never fires. */
        override val testValue: Clock get() = UnimplementedClock
    }
}

private object UnimplementedClock : Clock {
    override fun now(): Duration = Duration.ZERO
    override suspend fun sleep(duration: Duration) =
        throw AssertionError("Unimplemented: Clock.sleep. Override Clock in this test (TestClock/ImmediateClock).")
}

/**
 * swift-clocks' `ImmediateClock`: sleeps return at once, but still yield, so a sleeping loop never
 * starves its dispatcher.
 */
class ImmediateClock : Clock {
    @Volatile private var time = Duration.ZERO
    override fun now(): Duration = time
    override suspend fun sleep(duration: Duration) {
        time += duration
        yield()
    }
}

/** A clock that only moves when the test calls [advance] (swift-clocks' `TestClock`). */
class TestClock : Clock {
    private class Sleeper(val deadline: Duration, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var time = Duration.ZERO
    private val sleepers = mutableListOf<Sleeper>()

    override fun now(): Duration = synchronized(lock) { time }

    override suspend fun sleep(duration: Duration) {
        if (duration <= Duration.ZERO) return
        suspendCancellableCoroutine { continuation ->
            val sleeper = synchronized(lock) { Sleeper(time + duration, continuation).also(sleepers::add) }
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(sleeper) } }
        }
    }

    /**
     * Moves time forward by [by], resuming due sleeps one deadline at a time and yielding after
     * each so the woken effect runs before time moves on.
     */
    suspend fun advance(by: Duration = Duration.ZERO) {
        val target = synchronized(lock) { time + by }
        while (true) {
            val next = synchronized(lock) {
                val due = sleepers.filter { it.deadline <= target }.minByOrNull { it.deadline } ?: return@synchronized null
                sleepers.remove(due)
                if (due.deadline > time) time = due.deadline
                due
            } ?: break
            next.continuation.resume(Unit)
            repeat(YIELDS) { yield() }
        }
        synchronized(lock) { time = target }
        repeat(YIELDS) { yield() }
    }

    /** Runs every pending sleep (not ones that keep rescheduling forever). */
    suspend fun run() {
        val last = synchronized(lock) { sleepers.maxOfOrNull { it.deadline } } ?: return
        advance(last - now())
    }

    private companion object {
        const val YIELDS = 8
    }
}

/** Port of `@Dependency(\.date)`: wall-clock now. */
fun interface DateClient {
    fun now(): Instant

    companion object Key : DependencyKey<DateClient> {
        override val liveValue: DateClient = DateClient { Instant.now() }
        override val testValue: DateClient = DateClient {
            throw AssertionError("Unimplemented: DateClient. Override with DateClient { Instant.ofEpochSecond(...) }.")
        }
        fun constant(instant: Instant) = DateClient { instant }
    }
}

/** Port of `@Dependency(\.uuid)`. [incrementing] mirrors `UUIDGenerator.incrementing`. */
fun interface UuidClient {
    fun next(): UUID

    companion object Key : DependencyKey<UuidClient> {
        override val liveValue: UuidClient = UuidClient { UUID.randomUUID() }
        override val testValue: UuidClient = UuidClient {
            throw AssertionError("Unimplemented: UuidClient. Override with UuidClient.incrementing().")
        }

        /** 00000000-0000-0000-0000-000000000000, …-000000000001, … */
        fun incrementing(): UuidClient {
            var next = 0L
            return UuidClient { synchronized(this) { UUID(0, next++) } }
        }
    }
}
