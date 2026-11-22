package ai.opencode.core.dependencies

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.properties.ReadOnlyProperty

/**
 * Port of swift-dependencies' `DependencyKey`: a client's live and test implementations.
 * `liveValue` is read once per process and cached; `testValue` must be inert.
 */
interface DependencyKey<T> {
    val liveValue: T
    /** Consulted only in test containers. */
    val testValue: T
        get() = throw AssertionError(
            "${javaClass.enclosingClass?.simpleName ?: javaClass.simpleName} has no testValue; override it in the test",
        )
}

/**
 * Port of `DependencyValues`. Resolution order: the innermost override (coroutine-local), then the
 * key's `testValue` when [isTesting], else its cached `liveValue`.
 */
class DependencyValues private constructor(
    private val overrides: Map<DependencyKey<*>, Any?>,
    val isTesting: Boolean,
    /** `testValue`s resolved by this test container and its copies (one instance per test, as in TCA). */
    private val testCache: HashMap<DependencyKey<*>, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: DependencyKey<T>): T {
        if (overrides.containsKey(key)) return overrides[key] as T
        if (!isTesting) return live(key)
        return synchronized(testCache) {
            if (testCache.containsKey(key)) testCache[key] as T else key.testValue.also { testCache[key] = it }
        }
    }

    /** A copy with [key] resolved to [value]. */
    fun <T> with(key: DependencyKey<T>, value: T): DependencyValues =
        DependencyValues(overrides + (key to value), isTesting, testCache)

    /** A copy with every override in [other] applied over this one. */
    fun merging(other: DependencyValues): DependencyValues =
        DependencyValues(overrides + other.overrides, isTesting || other.isTesting, testCache)

    /** Builder used by [withDependencies] and `TestStore`. */
    class Builder internal constructor(base: DependencyValues) {
        internal var values = base
        operator fun <T> set(key: DependencyKey<T>, value: T) {
            values = values.with(key, value)
        }
        operator fun <T> get(key: DependencyKey<T>): T = values[key]
    }

    fun update(block: Builder.() -> Unit): DependencyValues = Builder(this).apply(block).values

    companion object {
        private val liveCache = HashMap<DependencyKey<*>, Any?>()

        @Suppress("UNCHECKED_CAST")
        private fun <T> live(key: DependencyKey<T>): T = synchronized(liveCache) {
            if (liveCache.containsKey(key)) liveCache[key] as T else key.liveValue.also { liveCache[key] = it }
        }

        /**
         * swift-dependencies' `isTesting`: true in JVM unit tests (JUnit on the classpath and not
         * on ART).
         */
        val isProcessTesting: Boolean =
            System.getProperty("java.vm.name") != "Dalvik" &&
                runCatching { Class.forName("org.junit.runner.Runner") }.isSuccess

        /** The process root: live values (test values under unit tests) plus what the Application installed. */
        @Volatile
        var root: DependencyValues = DependencyValues(emptyMap(), isTesting = isProcessTesting, HashMap())
            private set

        /**
         * Forgets the root's resolved `testValue`s and installed overrides, so no test sees
         * another's clients. Only under unit tests.
         */
        fun resetTestCache() {
            check(isProcessTesting) { "resetTestCache() is for unit tests" }
            root = DependencyValues(emptyMap(), isTesting = true, HashMap())
        }

        /** Installs process-wide overrides (only `Application.onCreate`, e.g. the app [android.content.Context]). */
        fun installRoot(block: Builder.() -> Unit) {
            root = root.update(block)
        }

        /** A test container: unresolved keys fall back to `testValue`. */
        fun testing(): DependencyValues = DependencyValues(emptyMap(), isTesting = true, HashMap())

        private val local = ThreadLocal<DependencyValues?>()

        /** The values in effect on this thread/coroutine. */
        val current: DependencyValues get() = local.get() ?: root

        /** Runs [block] synchronously with [values] installed (used by the store around `reduce`). */
        fun <R> withValues(values: DependencyValues, block: () -> R): R {
            val previous = local.get()
            local.set(values)
            try {
                return block()
            } finally {
                local.set(previous)
            }
        }

        internal fun element(values: DependencyValues): CoroutineContext = DependencyContext(values)
    }

    /** Propagates [DependencyValues.current] across coroutine suspension and dispatch. */
    private class DependencyContext(private val values: DependencyValues) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<DependencyValues?> {
        companion object Key : CoroutineContext.Key<DependencyContext>

        override fun updateThreadContext(context: CoroutineContext): DependencyValues? {
            val previous = local.get()
            local.set(values)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: DependencyValues?) {
            local.set(oldState)
        }
    }
}

/** Reads a client from the current [DependencyValues] (TCA's `@Dependency(\.x) var x`). */
fun <T> dependency(key: DependencyKey<T>): T = DependencyValues.current[key]

/**
 * Property-delegate form of `@Dependency`, resolved on every read. Hot loops resolve once into a
 * local `val` instead.
 */
@Suppress("FunctionName")
fun <T> Dependency(key: DependencyKey<T>): ReadOnlyProperty<Any?, T> =
    ReadOnlyProperty { _, _ -> DependencyValues.current[key] }

/** Runs [block] with overridden dependencies (TCA's `withDependencies`). */
fun <R> withDependencies(update: DependencyValues.Builder.() -> Unit, block: () -> R): R =
    DependencyValues.withValues(DependencyValues.current.update(update), block)

/** Suspending variant: the overrides follow the coroutine across threads. */
suspend fun <R> withDependenciesSuspending(
    update: DependencyValues.Builder.() -> Unit,
    block: suspend () -> R,
): R = withContext(DependencyValues.element(DependencyValues.current.update(update))) { block() }

/** The coroutine context element carrying [values]; effects launch with it. */
fun DependencyValues.asContextElement(): CoroutineContext = DependencyValues.element(this)
