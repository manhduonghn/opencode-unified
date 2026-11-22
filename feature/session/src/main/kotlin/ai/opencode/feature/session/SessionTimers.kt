package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyKey
import kotlinx.serialization.Serializable

/**
 * Background timers `SessionFeature` starts on its own. Live sessions run them all; reducer
 * tests opt into the ones they exercise so unrelated tests do not inherit background loops.
 */
@Serializable
data class SessionTimers(
    val watchdog: Boolean = true,
    val runEndCheck: Boolean = true,
    val requestRetry: Boolean = true,
    /** A reader back at the end lets go of the history they paged through. */
    val windowReset: Boolean = true,
) {
    companion object Key : DependencyKey<SessionTimers> {
        override val liveValue = SessionTimers()
        override val testValue = SessionTimers(watchdog = false, runEndCheck = false, requestRetry = false, windowReset = false)
    }
}
