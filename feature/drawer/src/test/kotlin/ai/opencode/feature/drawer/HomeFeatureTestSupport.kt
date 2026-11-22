package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.TranscriptCachePersistence
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/** Everything lives in this object so it never clashes with other test files' helpers. */
internal object HomeFeatureTestSupport {
    val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
    val distantFuture: Instant = Instant.parse("4001-01-01T00:00:00Z")

    /** `Date(timeIntervalSince1970:)`. */
    fun date(seconds: Number): Instant = Instant.ofEpochMilli(Math.round(seconds.toDouble() * 1_000))

    fun session(
        id: String,
        title: String = id,
        projectID: String = "p",
        directory: String = "/p",
        updatedAt: Instant = distantPast,
        idle: Long? = null,
        viewed: Long? = null,
        parentID: String? = null,
        model: OpenCodeModelRef? = null,
    ) = OpenCodeSession(
        id = id,
        serverTitle = title,
        projectID = projectID,
        directory = directory,
        updatedAt = updatedAt,
        idleMilliseconds = idle,
        viewedMilliseconds = viewed,
        model = model,
        parentID = parentID,
    )

    fun snapshot(
        sessions: List<OpenCodeSession>,
        active: Set<String> = emptySet(),
        projects: List<OpenCodeProject> = emptyList(),
        parents: Map<String, String>? = null,
    ) = OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = active, activeSessionParents = parents)

    class LockIsolated<T>(initial: T) {
        @Volatile private var current: T = initial
        val value: T get() = synchronized(this) { current }
        fun setValue(value: T) = synchronized(this) { current = value }
        fun update(block: (T) -> T) = synchronized(this) { current = block(current) }
    }

    fun <T> LockIsolated<List<T>>.append(value: T) = update { it + value }
    fun LockIsolated<Int>.increment() = update { it + 1 }

    /** `for await _ in stream { break }` returns at the first yield or the finish. */
    class Gate {
        private val channel = Channel<Unit>(Channel.UNLIMITED)
        suspend fun await() {
            channel.receiveCatching()
        }
        fun yield() {
            channel.trySend(Unit)
        }
        fun finish() {
            channel.close()
        }
    }

    /** Lets launched effects run. */
    suspend fun settle(times: Int = 50) = repeat(times) { yield() }

    /** A copy of the inert test client. */
    fun client(block: OpenCodeClient.() -> OpenCodeClient = { this }): OpenCodeClient = OpenCodeClient.testValue.block()

    /**
     * One test's dependency world: the shared store, composer persistence, outbox and transcript
     * persistence that states built before the store and the store share, and the scope effects run
     * in. The store's scope is not a child of the test, so effects a test leaves running are
     * cancelled by [skipInFlightEffects].
     */
    class Harness(scope: CoroutineScope) {
        val sharedStore: SharedStore = SharedStore.inMemory()
        val composerPersistence: ComposerPersistence = ComposerPersistence.inMemory()
        val promptOutbox: PromptOutbox = PromptOutbox()
        val transcriptPersistence: TranscriptCachePersistence = TranscriptCachePersistence.testValue
        private val job = Job()
        val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)

        private fun DependencyValues.Builder.world() {
            this[SharedStore] = sharedStore
            this[ComposerPersistence] = composerPersistence
            this[PromptOutbox] = promptOutbox
            this[TranscriptCachePersistence] = transcriptPersistence
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }

        private val values: DependencyValues by lazy { DependencyValues.testing().update { world() } }

        /** Builds or reads a value that touches shared keys (`HomeFeature.State`) in this world. */
        fun <R> state(block: () -> R): R = DependencyValues.withValues(values, block)

        /** A shared key's current value in this world. */
        fun <T> shared(key: SharedKey<T>): T = state { ai.opencode.core.sharing.shared(key).value }

        fun <T> setShared(key: SharedKey<T>, value: T) = state { ai.opencode.core.sharing.shared(key).set(value) }

        private val stores = ArrayList<TestStore<*, *>>()

        fun store(
            state: HomeFeature.State,
            dependencies: DependencyValues.Builder.() -> Unit = {},
        ): TestStore<HomeFeature.State, HomeFeature.Action> = TestStore(state, HomeFeature(), scope) {
            world()
            dependencies()
        }.also(stores::add)

        fun sessionStore(
            state: SessionFeature.State,
            dependencies: DependencyValues.Builder.() -> Unit = {},
        ): TestStore<SessionFeature.State, SessionFeature.Action> {
            val deps: DependencyValues.Builder.() -> Unit = {
                world()
                dependencies()
            }
            // Built under the test's dependencies: the reducer resolves its watchdog clock
            // (`SessionTimers`) when it's made.
            val reducer = DependencyValues.withValues(DependencyValues.testing().update(deps)) { SessionFeature() }
            return TestStore(state, reducer, scope, deps).also(stores::add)
        }

        fun skipInFlightEffects() = job.cancel()

        /**
         * Surfaces any such failure, and for an exhaustive store any action left unreceived.
         * Effects still running are let go, as `skipInFlightEffects()` does.
         */
        suspend fun verifyStores() {
            for (store in stores) {
                try {
                    store.finish()
                } catch (error: AssertionError) {
                    if (error.message?.contains("still running at finish") != true) throw error
                }
            }
        }
    }

    fun homeTest(block: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness(this)
        try {
            block(harness)
            harness.verifyStores()
        } finally {
            harness.skipInFlightEffects()
        }
    }

    /**
     * Matches [matches], skipping at most [limit] other actions (non-exhaustive stores), so a
     * polling effect that keeps sending can't keep a missing action waiting forever.
     */
    suspend fun <S, A : Any> TestStore<S, A>.receiveBounded(
        matches: (A) -> Boolean,
        description: String,
        timeout: Duration = 5.seconds,
        limit: Int = 200,
        update: ((S) -> S)? = null,
    ) {
        val skipped = ArrayList<String>()
        receive(
            {
                if (matches(it)) {
                    true
                } else {
                    skipped += it.toString().take(120)
                    if (skipped.size > limit) throw AssertionError("Expected $description; skipped ${skipped.size}: ${skipped.distinct().take(8)}")
                    false
                }
            },
            description,
            timeout,
            update,
        )
    }

    suspend fun <S, A : Any, T : A> TestStore<S, A>.receive(
        case: KClass<T>,
        timeout: Duration = 5.seconds,
        update: ((S) -> S)? = null,
    ) = receiveBounded({ case.isInstance(it) }, case.simpleName ?: "action", timeout, update = update)

    suspend fun <T : SessionFeature.Action> TestStore<HomeFeature.State, HomeFeature.Action>.receiveDetail(
        case: KClass<T>,
        timeout: Duration = 5.seconds,
        update: ((HomeFeature.State) -> HomeFeature.State)? = null,
    ) = receiveBounded(
        { action ->
            val presented = (action as? HomeFeature.Action.SessionDetail)?.action as? ai.opencode.core.store.PresentationAction.Presented
            presented != null && case.isInstance(presented.action)
        },
        "sessionDetail.${case.simpleName}",
        timeout,
        update = update,
    )

    /** `.sessionDetail(.presented(action))`. */
    fun detail(action: SessionFeature.Action): HomeFeature.Action = HomeFeature.Action.detail(action)
}
