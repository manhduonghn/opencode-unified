package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The cover stays until content is ready, at most [TIMEOUT_MILLIS]. Main thread only. */
object LaunchBloomController {
    const val TIMEOUT_MILLIS = 1_500L

    /** Once true the cover never shows again in this process. */
    internal var hasCompletedColdLaunch = false

    /** When the cover went up; null until [start]. */
    internal var startedAt: Duration? = null

    /** Called once the activity installs its splash screen. */
    fun start() {
        if (startedAt == null) startedAt = dependency(Clock).now()
    }

    /** The cover is up. */
    val isRunning: Boolean get() = startedAt != null && !hasCompletedColdLaunch

    /** Ends the cover now: something the person asked for is about to show. */
    fun completeColdLaunch() {
        hasCompletedColdLaunch = true
    }

    /** The splash screen's keep-on-screen condition. */
    fun keepsCover(isContentReady: Boolean): Boolean {
        if (hasCompletedColdLaunch) return false
        val now = dependency(Clock).now()
        if (isContentReady || now - (startedAt ?: now) >= TIMEOUT_MILLIS.milliseconds) {
            hasCompletedColdLaunch = true
            return false
        }
        return true
    }
}
