package ai.opencode.app

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One in-memory [SharedStore] per test, installed both where a test builds `AppFeature.State()` and
 * in the store, so their `Shared` references agree.
 */
class AppTestShared {
    val store: SharedStore = SharedStore.inMemory()

    val values: DependencyValues get() = DependencyValues.testing().update { this[SharedStore] = store }

    fun <R> run(block: () -> R): R = DependencyValues.withValues(values, block)

    /** The saved server and its cached Home restored. */
    fun appState(): AppFeature.State = run { AppFeature.State.initial() }
}

fun AppTestShared.appStore(
    state: AppFeature.State,
    scope: CoroutineScope,
    dependencies: DependencyValues.Builder.() -> Unit = {},
): TestStore<AppFeature.State, AppFeature.Action> =
    TestStore(state, AppFeature(), scope) {
        this[SharedStore] = store
        dependencies()
    }

/**
 * Effects launch here instead of the test's own scope, so work still in flight when a test ends is
 * cancelled (TCA's `skipInFlightEffects`).
 */
fun detachedScope(context: CoroutineContext): CoroutineScope = CoroutineScope(context + Job())

fun CoroutineScope.skipInFlightEffects() = cancel()

class Locked<T>(initial: T) {
    @Volatile private var current: T = initial
    val value: T get() = synchronized(this) { current }
    fun set(newValue: T) = synchronized(this) { current = newValue }
    fun update(block: (T) -> T) = synchronized(this) { current = block(current) }
}

/** Polls until [condition] holds. */
suspend fun eventually(timeout: Duration = 5.seconds, condition: () -> Boolean) {
    withTimeout(timeout) { while (!condition()) delay(10) }
}

/** The next action of type [T]. */
suspend inline fun <reified T : AppFeature.Action> TestStore<AppFeature.State, AppFeature.Action>.receiveCase(
    noinline update: ((AppFeature.State) -> AppFeature.State)? = null,
) = receive({ it is T }, T::class.simpleName ?: "action", update = update)

/**
 * Health passes, and Home's snapshot waits for [GatedHome.snapshots]. Closing the channel without a
 * value fails the read as cancelled.
 */
class GatedHome {
    val snapshots = Channel<OpenCodeHomeSnapshot>(Channel.UNLIMITED)

    fun yield(snapshot: OpenCodeHomeSnapshot) {
        snapshots.trySend(snapshot)
    }

    fun finish() {
        snapshots.close()
    }

    fun client(base: OpenCodeClient = OpenCodeClient.testValue): OpenCodeClient = base.copy(
        health = {},
        homeSnapshot = {
            snapshots.receiveCatching().getOrNull() ?: throw OpenCodeClientError("Cancelled")
        },
    )
}

val emptySnapshot: OpenCodeHomeSnapshot get() = OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet())
