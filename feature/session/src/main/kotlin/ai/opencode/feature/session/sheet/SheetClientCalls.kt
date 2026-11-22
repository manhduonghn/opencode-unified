package ai.opencode.feature.session.sheet

import ai.opencode.feature.session.R
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.CancellationException

/** Runs [block], returning the client error it threw, or null. */
internal suspend fun clientError(block: suspend () -> Unit): OpenCodeClientError? = try {
    block()
    null
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    error.asClientError()
}

/** [clientError] for a call with a value. */
internal suspend fun <T> clientResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error.asClientError())
}

internal fun Throwable.asClientError(): OpenCodeClientError =
    this as? OpenCodeClientError ?: OpenCodeClientError(message ?: localized(R.string.session_something_went_wrong_sentence))
