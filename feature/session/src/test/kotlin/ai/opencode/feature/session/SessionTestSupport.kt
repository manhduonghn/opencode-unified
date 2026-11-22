package ai.opencode.feature.session

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * They live in one object so test files never clash. File-specific helpers stay private to their
 * file.
 */
internal object SessionTestSupport {
    val testServer = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")

    fun date(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    /** `updatedAt` is a fixed instant; tests compare ids, not dates. */
    fun testSession(
        id: String = "ses_1",
        title: String = "Chat",
        directory: String = "/project",
        updatedAt: Instant = Instant.EPOCH,
        model: OpenCodeModelRef? = null,
        parentID: String? = null,
        agent: String? = null,
        idleMilliseconds: Long? = null,
        viewedMilliseconds: Long? = null,
    ) = OpenCodeSession(
        id = id,
        serverTitle = title,
        projectID = "p",
        directory = directory,
        updatedAt = updatedAt,
        idleMilliseconds = idleMilliseconds,
        viewedMilliseconds = viewedMilliseconds,
        model = model,
        parentID = parentID,
        agent = agent,
    )

    class LockIsolated<T>(initial: T) {
        @Volatile private var current: T = initial
        val value: T get() = synchronized(this) { current }
        fun setValue(value: T) = synchronized(this) { current = value }
        fun <R> withValue(block: (T) -> Pair<T, R>): R = synchronized(this) {
            val (next, result) = block(current)
            current = next
            result
        }
        fun update(block: (T) -> T) = synchronized(this) { current = block(current) }
    }

    /** `Shared` compares by reference, so the port checks the write after it. */
    fun SessionFeature.State.assertRemembersModelNames(models: List<OpenCodeModel>) =
        org.junit.Assert.assertEquals(ModelDisplayNames(models), modelDisplayNamesByServer.value[server.persistenceID])

    fun <T> LockIsolated<List<T>>.append(value: T) = update { it + value }
    fun LockIsolated<Int>.increment(): Int = withValue { (it + 1) to (it + 1) }

    /** Lets launched effects run. */
    suspend fun settle(times: Int = 50) = repeat(times) { yield() }

    /** Records played haptics. */
    class HapticsRecorder {
        private val played = mutableListOf<Haptic>()
        val log: List<Haptic> get() = synchronized(played) { played.toList() }
        val client = HapticsClient(play = { synchronized(played) { played += it } })
    }

    /**
     * One test's dependency world: an in-memory shared store that states and stores share, and the
     * scope effects run in. The store's scope is not a child of the test, so effects a test leaves
     * running are cancelled by [skipInFlightEffects], and at the end of [sessionTest].
     */
    class Harness(scope: CoroutineScope) {
        val sharedStore: SharedStore = SharedStore.inMemory()
        private val job = Job()
        val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)

        val values: DependencyValues
            get() = DependencyValues.testing().update {
                this[SharedStore] = sharedStore
                this[DateClient] = DateClient.constant(Instant.EPOCH)
            }

        /** Builds a value that reads shared keys (a `SessionFeature.State`) in this world. */
        fun <R> state(block: () -> R): R = DependencyValues.withValues(values, block)

        fun makeState(
            session: OpenCodeSession? = null,
            cachedItems: List<OpenCodeTranscriptItem> = emptyList(),
            initialTranscriptRequestIsExternal: Boolean = false,
            isRunning: Boolean = false,
            draft: String = "",
            hasRenderedFirstContentFrame: Boolean? = null,
        ): SessionFeature.State = state {
            SessionFeature.State.session(
                server = testServer,
                session = session ?: testSession(),
                cachedItems = cachedItems,
                initialTranscriptRequestIsExternal = initialTranscriptRequestIsExternal,
                isRunning = isRunning,
            ).copy(draft = draft, hasRenderedFirstContentFrame = hasRenderedFirstContentFrame ?: cachedItems.isEmpty())
        }

        /**
         * The reducer is built inside the store's dependencies, since `SessionFeature` reads some
         * at construction.
         */
        fun store(
            state: SessionFeature.State,
            dependencies: DependencyValues.Builder.() -> Unit = {},
        ): TestStore<SessionFeature.State, SessionFeature.Action> =
            store(state, { SessionFeature() }, dependencies)

        fun <S, A> store(
            state: S,
            reducer: () -> Reducer<S, A>,
            dependencies: DependencyValues.Builder.() -> Unit = {},
        ): TestStore<S, A> {
            val deps: DependencyValues.Builder.() -> Unit = {
                this[SharedStore] = sharedStore
                this[DateClient] = DateClient.constant(Instant.EPOCH)
                dependencies()
            }
            val built = DependencyValues.withValues(DependencyValues.testing().update(deps), reducer)
            return TestStore(state, built, scope, deps)
        }

        fun skipInFlightEffects() = job.cancel()
    }

    /**
     * Runs one test with a [Harness]. `runBlocking`, not `runTest`: some effects hop to real
     * dispatchers, so receive timeouts must be real time. Time inside reducers is a dependency
     * (`TestClock`/`ImmediateClock`), never a real sleep.
     */
    fun sessionTest(block: suspend CoroutineScope.(Harness) -> Unit) = runBlocking {
        val harness = Harness(this)
        try {
            block(harness)
        } finally {
            harness.skipInFlightEffects()
        }
    }

    /** A test container with these overrides. */
    suspend fun <R> withTestDependencies(update: DependencyValues.Builder.() -> Unit, block: suspend () -> R): R =
        withContext(DependencyValues.testing().update(update).asContextElement()) { block() }

    suspend fun <S, A : Any, T : A> TestStore<S, A>.receive(
        case: KClass<T>,
        timeout: Duration = 5.seconds,
        update: ((S) -> S)? = null,
    ) = receive({ case.isInstance(it) }, case.simpleName ?: "action", timeout, update)
}
