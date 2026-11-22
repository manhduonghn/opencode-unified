package ai.opencode.feature.system.liveactivity

import java.net.URI
import java.time.Instant

/**
 * A run started by a send from this phone reads in an ongoing notification while the app is away
 * (Session Live Activity, Experimental). Tapping it opens the session, where a pending request
 * docks. No message text while it works: the lock screen is for anyone to see.
 */
data class SessionActivityAttributes(
    /** `SessionDirectory.serverKey(for:)`, never an address. */
    val serverKey: String,
    val sessionID: String,
) {
    data class ContentState(
        val title: String,
        val phase: Phase,
        /** While working: the tool the agent is on, or a short status. */
        val step: String? = null,
        /** When done: the reply's opening line. */
        val preview: String? = null,
        /** When the send started the run; the system counts the timer from here. */
        val startedAt: Instant,
    ) {
        enum class Phase(val rawValue: String) {
            working("working"),
            needsInput("needsInput"),
            done("done"),
            failed("failed"),
            ;

            /** Done or Failed: the run is over and the activity soon goes. */
            val isSettled: Boolean get() = this == done || this == failed
        }
    }

    /**
     * `opencode://session/<id>?server=<key>`, which the app routes like a tapped notification
     * (`SystemAction.from(url)`).
     */
    val url: String?
        get() = runCatching { URI("opencode", "session", "/$sessionID", "server=$serverKey", null).toASCIIString() }.getOrNull()
}
