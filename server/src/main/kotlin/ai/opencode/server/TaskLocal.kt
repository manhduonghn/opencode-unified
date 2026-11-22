package ai.opencode.server

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A value bound for the duration of a block and inherited by everything (child coroutines included)
 * that block starts.
 *
 * ```kotlin
 * val current = TaskLocal<String?> { null }
 * current.withValue("x") { current.current() } // "x"
 * ```
 */
class TaskLocal<T>(private val defaultValue: () -> T) {
    private class Key<T> : CoroutineContext.Key<Element<T>>

    /** Also mirrors the value into [local] while its coroutine runs. */
    private class Element<T>(
        val value: T,
        override val key: CoroutineContext.Key<*>,
        private val local: ThreadLocal<Element<T>?>,
    ) : ThreadContextElement<Element<T>?> {
        override fun updateThreadContext(context: CoroutineContext): Element<T>? = local.get().also { local.set(this) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Element<T>?) = local.set(oldState)
    }

    private val key = Key<T>()
    private val local = ThreadLocal<Element<T>?>()

    /** The bound value in this coroutine, else the default. */
    suspend fun current(): T = get(currentCoroutineContext())

    /**
     * The bound value from a non-suspending function running inside a coroutine that bound it, else
     * the default.
     */
    fun currentBlocking(): T = local.get()?.value ?: defaultValue()

    fun get(context: CoroutineContext): T {
        val element = context[key] ?: return defaultValue()
        return element.value
    }

    /** The context element binding [value], for `withContext` / `launch`. */
    fun asContextElement(value: T): CoroutineContext = Element(value, key, local)

    suspend fun <R> withValue(value: T, block: suspend () -> R): R = withContext(Element(value, key, local)) { block() }
}
