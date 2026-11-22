package ai.opencode.server.model

/**
 * The page the voice agent hears about. Lives in `:server` because the in-app browser
 * (`:feature:media`) produces it and the call (`:feature:voice`) consumes it, and neither module
 * may depend on the other.
 */
data class VoiceViewingPage(
    /** The page's URL as a string. */
    val url: String,
    val title: String,
    val isOpen: Boolean,
)
