package ai.opencode.core.dependencies

import ai.opencode.core.log.AppLog
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Android keeps a backgrounded process running until the system freezes it, so the live value is a
 * scoped marker that logs.
 */
fun interface BackgroundTaskClient {
    /** Begins an assertion named [name]; the returned function ends it. */
    suspend fun begin(name: String): () -> Unit

    companion object Key : DependencyKey<BackgroundTaskClient> {
        override val liveValue = BackgroundTaskClient { name ->
            AppLog.app.debug { "Background task began: $name" }
            val end: () -> Unit = { AppLog.app.debug { "Background task ended: $name" } }
            end
        }
        override val testValue = BackgroundTaskClient { { } }
    }
}

/**
 * Keeps the app's event stream alive for a short while after it leaves the screen
 * (`HomeFeature.liveUpdatesAfterLeaving`). Holds until [seconds] pass or the effect is cancelled.
 */
fun interface BackgroundTimeClient {
    suspend fun hold(name: String, seconds: Duration)

    companion object Key : DependencyKey<BackgroundTimeClient> {
        override val liveValue = BackgroundTimeClient { name, seconds ->
            val end = dependency(BackgroundTaskClient).begin(name)
            try {
                delay(seconds)
            } finally {
                end()
            }
        }
        override val testValue = BackgroundTimeClient { _, _ -> }
    }
}
