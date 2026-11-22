package ai.opencode.core.store

import ai.opencode.core.dependencies.DependencyValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What views hold: read state, send actions. Both [Store] and its [scope]s implement it,
 * so a composable takes `StoreOf<Feature.State, Feature.Action>` regardless of depth.
 */
interface StoreOf<S, A> {
    /** The state right now. */
    val state: S
    /** Every distinct state, starting with the current one. */
    val states: Flow<S>
    fun send(action: A)
}

/**
 * Port of TCA's `Store`: runs [reducer] on the main thread. Call `send` on the main thread;
 * synchronous [Effect.send] actions are processed before it returns.
 */
class Store<S, A>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    /** Overrides applied over the process root (previews pass `DependencyValues.testing()`-based values). */
    dependencies: DependencyValues = DependencyValues.root,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : StoreOf<S, A> {
    private val dependencies = dependencies
    private val stateFlow = MutableStateFlow(initialState)
    private val queue = ArrayDeque<A>()
    private var isSending = false

    private val runner = EffectRunner<A>(
        scope = scope,
        sendNow = { queue.addLast(it) },
        sendFromEffect = { action -> withContext(Dispatchers.Main.immediate) { send(action) } },
    )

    override val state: S get() = stateFlow.value
    override val states: StateFlow<S> get() = stateFlow

    override fun send(action: A) {
        check(android.os.Looper.getMainLooper().isCurrentThread) { "Store.send must be called on the main thread" }
        queue.addLast(action)
        if (isSending) return
        isSending = true
        try {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                val reduced = DependencyValues.withValues(dependencies) { reducer.reduce(stateFlow.value, next) }
                stateFlow.value = reduced.state
                DependencyValues.withValues(dependencies) { runner.execute(reduced.effect) }
            }
        } catch (error: Throwable) {
            queue.clear()
            throw error
        } finally {
            isSending = false
        }
    }

    /** Cancels every running effect (the root store's owner calls this when it is destroyed). */
    fun close() {
        runner.cancelAll()
        scope.cancel()
    }
}

/**
 * Port of `store.scope(state:action:)`: a child view of a store. In composables use
 * [rememberScope], so recomposition doesn't create a new scope (and re-subscribe) each time.
 */
fun <S, A, CS, CA> StoreOf<S, A>.scope(state: (S) -> CS, action: (CA) -> A): StoreOf<CS, CA> =
    ScopedStore(this, state, action)

private class ScopedStore<S, A, CS, CA>(
    private val parent: StoreOf<S, A>,
    private val toState: (S) -> CS,
    private val fromAction: (CA) -> A,
) : StoreOf<CS, CA> {
    override val state: CS get() = toState(parent.state)
    override val states: Flow<CS> = parent.states.map(toState).distinctUntilChanged()
    override fun send(action: CA) = parent.send(fromAction(action))
}

/** Observes the store's state in Compose. Prefer scoping to the smallest slice a view needs. */
@Composable
fun <S, A> StoreOf<S, A>.collectState(): State<S> = states.collectAsState(initial = state)

/** Observes one derived value; recomposes only when it changes. */
@Composable
fun <S, A, V> StoreOf<S, A>.observe(select: (S) -> V): State<V> {
    val flow = remember(this) { states.map(select).distinctUntilChanged() }
    return flow.collectAsState(initial = select(state))
}

/** [scope], remembered for this store: the form composables should use. */
@Composable
fun <S, A, CS, CA> StoreOf<S, A>.rememberScope(state: (S) -> CS, action: (CA) -> A): StoreOf<CS, CA> =
    remember(this) { scope(state, action) }
