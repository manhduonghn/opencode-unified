package ai.opencode.core.store

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import kotlin.time.Duration

/** Sends actions back into the store from an effect (TCA's `Send`). */
fun interface Send<in A> {
    suspend operator fun invoke(action: A)
}

/**
 * Port of TCA's `Effect<Action>`: work a reducer asks for, run by the store after the transition. A
 * [run] effect captures the current [DependencyValues]; cancellation IDs need stable equality.
 */
sealed interface Effect<out A> {
    data object None : Effect<Nothing>

    class Run<A> internal constructor(
        internal val dependencies: DependencyValues,
        internal val operation: suspend (Send<A>) -> Unit,
    ) : Effect<A>

    data class SendAction<A>(val action: A) : Effect<A>

    data class Merge<A>(val effects: List<Effect<A>>) : Effect<A>

    data class Concatenate<A>(val effects: List<Effect<A>>) : Effect<A>

    data class Cancellable<A>(val id: Any, val cancelInFlight: Boolean, val effect: Effect<A>) : Effect<A>

    data class Cancel(val ids: List<Any>) : Effect<Nothing>

    /** A navigation boundary: all nested cancellation IDs belong to this child path. */
    class NavigationScoped<A> internal constructor(
        internal val id: Any,
        internal val effect: Effect<A>,
    ) : Effect<A>

    /** Transforms an effect's actions (`Scope` uses it to embed child actions). */
    class Mapped<A, B> internal constructor(
        internal val effect: Effect<A>,
        internal val transform: (A) -> B,
    ) : Effect<B>

    companion object {
        fun none(): Effect<Nothing> = None

        /**
         * A coroutine effect, started on the main thread synchronously up to its first suspension.
         * A throw is reported through [onError], else logged; it never crashes the app.
         */
        fun <A> run(
            onError: (suspend (Throwable, Send<A>) -> Unit)? = null,
            operation: suspend (send: Send<A>) -> Unit,
        ): Effect<A> = Run(DependencyValues.current) { send ->
            if (onError == null) {
                operation(send)
            } else {
                try {
                    operation(send)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onError(error, send)
                }
            }
        }

        fun <A> send(action: A): Effect<A> = SendAction(action)

        fun <A> merge(vararg effects: Effect<A>): Effect<A> = merge(effects.toList())

        fun <A> merge(effects: List<Effect<A>>): Effect<A> {
            val real = effects.filter { it !is None }
            return when (real.size) {
                0 -> None
                1 -> real[0]
                else -> Merge(real)
            }
        }

        fun <A> concatenate(vararg effects: Effect<A>): Effect<A> = concatenate(effects.toList())

        fun <A> concatenate(effects: List<Effect<A>>): Effect<A> {
            val real = effects.filter { it !is None }
            return when (real.size) {
                0 -> None
                1 -> real[0]
                else -> Concatenate(real)
            }
        }

        fun cancel(id: Any): Effect<Nothing> = Cancel(listOf(id))

        fun cancel(ids: List<Any>): Effect<Nothing> = Cancel(ids)
    }
}

/** Tags this effect with [id] (TCA's `.cancellable(id:cancelInFlight:)`). */
fun <A> Effect<A>.cancellable(id: Any, cancelInFlight: Boolean = false): Effect<A> =
    if (this is Effect.None) this else Effect.Cancellable(id, cancelInFlight, this)

internal fun <A> Effect<A>.navigationScoped(id: Any): Effect<A> =
    if (this is Effect.None) this else Effect.NavigationScoped(id, this)

/** Transforms this effect's actions. */
fun <A, B> Effect<A>.map(transform: (A) -> B): Effect<B> = when (this) {
    is Effect.None -> Effect.None
    is Effect.Cancel -> this
    else -> Effect.Mapped(this, transform)
}

/** Runs [other] alongside this one. */
fun <A> Effect<A>.merge(other: Effect<A>): Effect<A> = Effect.merge(this, other)

/** Runs [other] after this one finishes. */
fun <A> Effect<A>.concatenate(other: Effect<A>): Effect<A> = Effect.concatenate(this, other)

/**
 * Delivers this effect's actions only after [duration] of quiet under [id], cancelling
 * the previous one (TCA's `.debounce(id:for:scheduler:)`). Sleeps on the [Clock] dependency.
 */
fun <A> Effect<A>.debounce(id: Any, duration: Duration): Effect<A> {
    if (this is Effect.None) return this
    val inner = this
    return Effect.concatenate(
        Effect.run<A> { dependency(Clock).sleep(duration) },
        inner,
    ).cancellable(id, cancelInFlight = true)
}
