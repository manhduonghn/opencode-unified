package ai.opencode.feature.voice

import ai.opencode.core.tuning.Tunable
import kotlin.time.Duration

/** The spoken "still working" line, read when a call starts. */
object VoiceProgressTunables {
    val firstAfter = Tunable("call.progress.firstAfter", "Progress Line After (s)", default = 5.0, range = 2.0..20.0, step = 0.5)
    val quiet = Tunable("call.progress.quiet", "Quiet Before Progress (s)", default = 3.0, range = 1.0..10.0, step = 0.5)
    val again = Tunable("call.progress.again", "Second Line After (s)", default = 20.0, range = 5.0..90.0, step = 1.0)
}

/**
 * One short spoken line while a delegation's result is still out, so the
 * call never sits silent. It says what's running, never what's done; the
 * result decides that.
 */
object VoiceProgressLine {
    /** One line, then one more only after a much longer wait. */
    const val maxLines = 2

    data class Timing(
        /** How long a delegation is out before its first line. Zero: off. */
        val firstAfter: Duration = Duration.ZERO,
        /** How long nobody has spoken (the voice agent or the user) before a line. */
        val quiet: Duration = Duration.ZERO,
        /** How long after the first line the second may come. */
        val again: Duration = Duration.ZERO,
    )

    data class Tracker(
        val delegatedAt: Duration,
        val spoken: Int = 0,
        val lastLineAt: Duration? = null,
        /** What the work is doing right now, from its running tools. */
        val activity: String? = null,
    )

    /**
     * When the next line is due, or null when none is: `firstAfter` into the
     * delegation (`again` after the first line), and never before `quiet` has
     * passed since anyone last spoke.
     */
    fun due(tracker: Tracker, lastSpeechAt: Duration?, timing: Timing): Duration? {
        if (timing.firstAfter <= Duration.ZERO || tracker.spoken >= maxLines) return null
        var due = tracker.lastLineAt?.let { it + timing.again } ?: (tracker.delegatedAt + timing.firstAfter)
        if (lastSpeechAt != null) due = maxOf(due, lastSpeechAt + timing.quiet)
        return due
    }

    /**
     * What the voice agent is asked to say, in its own words. A status, not
     * the result: GPT-Live paraphrases commentary aloud.
     */
    fun instruction(activity: String?): String {
        val now = activity?.let { " Right now it's $it." } ?: ""
        return "Status update, not the result: your work on what the user asked for is still running.$now Say that in one short first-person line in your own words, as the one doing it, not as asking, sending, or handing it to anyone, unless the user asked for exactly that. Never say it's done or guess the result."
    }

    /**
     * A running tool's caption (`VoiceSessionDelegator.toolCaption`) as words
     * to say: "Starting subagent · Buy domains" is "working on “Buy domains”".
     * Only captions listed here are spoken; anything else (an MCP or internal
     * tool's "Running …" fallback, "Ending call") is null, so no tool name is
     * ever read aloud.
     */
    fun activity(caption: String): String? {
        val trimmed = caption.trim()
        val subagent = "Starting subagent · "
        if (trimmed.startsWith(subagent)) return "working on “${trimmed.substring(subagent.length)}”"
        spokenCaptions[trimmed]?.let { return it }
        // File tools name the file in the caption; the line doesn't.
        for ((prefix, spoken) in listOf("Reading " to "reading files", "Editing " to "editing files", "Writing " to "writing a file")) {
            if (trimmed.startsWith(prefix)) return spoken
        }
        return null
    }

    private val spokenCaptions = mapOf(
        "Running shell" to "running a command",
        "Searching files" to "searching the code",
        "Finding files" to "searching the code",
        "Searching the web" to "searching the web",
        "Reading web page" to "reading a web page",
        "Opening session" to "opening a session",
        "Watching session" to "setting up a watch on a session",
        "Checking Seer" to "checking Seer",
        "Checking Slack" to "checking Slack",
        "Using browser" to "using the browser",
        "Using Wisp" to "working on your Mac",
    )

    /** The built-in delegator's tool as words to say, or null for one that says nothing useful. */
    fun activityForDelegatorTool(name: String): String? = when (name) {
        "send_message" -> "messaging a session"
        "read_session" -> "reading a session"
        "wait_for_session" -> "waiting on a session"
        "create_session" -> "starting a new session"
        "list_sessions", "search_sessions", "list_projects", "list_servers" -> "looking through your sessions"
        "list_requests" -> "checking pending requests"
        "capture_app_screenshot" -> "taking a screenshot"
        "switch_model", "list_models" -> "checking models"
        else -> null
    }
}
