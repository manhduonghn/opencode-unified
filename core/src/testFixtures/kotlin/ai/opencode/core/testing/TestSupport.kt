package ai.opencode.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polls something an effect changes outside a store's own actions, every [step] for up to
 * [timeout]. Returns whether it came true; wrap in `assertTrue` to require it. Under `runTest` the
 * delays are virtual; [wallClock] waits real time, for work on other threads (OkHttp calls).
 */
suspend fun eventually(
    timeout: Duration = 3.seconds,
    step: Duration = 10.milliseconds,
    yield: Boolean = false,
    wallClock: Boolean = false,
    condition: () -> Boolean,
): Boolean {
    val attempts = maxOf(1, (timeout / step).toInt())
    repeat(attempts) {
        if (condition()) return true
        if (yield) yield()
        if (wallClock) withContext(Dispatchers.IO) { Thread.sleep(step.inWholeMilliseconds) } else delay(step)
    }
    return condition()
}
