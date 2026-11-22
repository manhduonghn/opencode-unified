package ai.opencode.core.log

/**
 * What asked for a call, logged once per start. In `:core` because [SystemActionOrigin] is shared
 * with `:feature:system`.
 */
sealed interface CallTrigger {
    /** The composer's call button. */
    data object CallButton : CallTrigger
    /** Stop held mid-run (`StopLongPressed`). */
    data object StopHeld : CallTrigger
    /** Raise to Talk's proximity sensor. */
    data object RaiseToTalk : CallTrigger
    /** The drawer's call button. */
    data object Drawer : CallTrigger
    /** What’s New's Try It on the call. */
    data object WhatsNew : CallTrigger
    /** A dropped call's Rejoin. */
    data object Rejoin : CallTrigger
    /** A failed call's Try Again. */
    data object TryAgain : CallTrigger
    /** App shortcuts, Assistant, Quick Settings tiles, widgets or launcher quick actions. */
    data class System(val origin: SystemActionOrigin) : CallTrigger

    val logName: String
        get() = when (this) {
            CallButton -> "call button"
            StopHeld -> "Stop held"
            RaiseToTalk -> "Raise to Talk"
            Drawer -> "drawer call button"
            WhatsNew -> "What’s New Try It"
            Rejoin -> "rejoin"
            TryAgain -> "try again"
            is System -> origin.logName
        }

    val logLine: String get() = "Call trigger: $logName"

    fun log() {
        observer?.invoke(this)
        AppLog.voice.notice(logLine)
    }

    companion object {
        /** Sees each trigger as it logs, for tests to check which calls logged ([observing]). */
        @Volatile
        private var observer: ((CallTrigger) -> Unit)? = null

        /** Runs [body] with [seen] watching every log. */
        suspend fun <T> observing(seen: (CallTrigger) -> Unit, body: suspend () -> T): T {
            val previous = observer
            observer = seen
            try {
                return body()
            } finally {
                observer = previous
            }
        }
    }
}

/** Which surface made a `SystemAction`. */
enum class SystemActionOrigin {
    /** Start Voice Call: the app shortcut, Assistant, or a home-screen shortcut pinned from it. */
    shortcut,
    /** Start Voice Call in Background. */
    backgroundShortcut,
    /** A Quick Settings tile or a widget's control. */
    control,
    /** A launcher quick action (long-press on the app icon). */
    quickAction,
    /** Links, search, entities. */
    other,
    ;

    val logName: String
        get() = when (this) {
            shortcut -> "Start Voice Call shortcut (app shortcut, Assistant or pinned shortcut)"
            backgroundShortcut -> "Start Voice Call in Background shortcut"
            control -> "Quick Settings tile or widget control"
            quickAction -> "launcher quick action"
            other -> "system action"
        }
}
