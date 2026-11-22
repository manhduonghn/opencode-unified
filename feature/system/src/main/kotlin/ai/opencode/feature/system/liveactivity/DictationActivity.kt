package ai.opencode.feature.system.liveactivity

import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import java.time.Instant

/**
 * A hands-free recording reads on the lock screen and in the status bar while the app is away, so
 * it isn't forgotten. Tapping it opens the app, where the recording's bar is on screen.
 */
data class DictationActivityAttributes(val serverID: String) {
    data class ContentState(
        /** The session the words will be sent in; “New session” on a draft. */
        val title: String,
        /** When the recording went hands-free; the system counts the timer from here. */
        val startedAt: Instant,
    )
}

enum class DictationCommand { Stop }

object DictationIntents {
    val STOP_TITLE: String get() = localized(R.string.system_recording_stop_title)
}
