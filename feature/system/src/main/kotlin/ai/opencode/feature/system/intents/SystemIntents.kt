package ai.opencode.feature.system.intents

import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemActionInbox
import ai.opencode.feature.system.ScopedID
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Each opens the app (through [SystemActionActivity]), where `perform` runs and hands its action to
 * [SystemActionInbox]. Android's equivalents of App Intents are app shortcuts, App Actions
 * capabilities (`res/xml/shortcuts.xml`), Quick Settings tiles and widgets.
 */
object OpenSessionIntent {
    val TITLE: String get() = localized(R.string.system_open_session)
    val DESCRIPTION: String get() = localized(R.string.system_intent_open_session_description)

    fun perform(target: SessionEntity) {
        target.action()?.let(SystemActionInbox.shared::post)
    }
}

object NewSessionIntent {
    val TITLE: String get() = localized(R.string.system_new_session)
    val DESCRIPTION: String get() = localized(R.string.system_intent_new_session_description)

    fun perform(project: ProjectEntity?) {
        val scoped = project?.let { ScopedID.parse(it.id) }
        SystemActionInbox.shared.post(SystemAction(scoped?.serverKey, SystemAction.Destination.Draft(scoped?.id)))
    }
}

/** The default: a call in the session you were last looking at. Its Session can instead be New Session or any session. */
object StartVoiceCallIntent {
    val TITLE: String get() = localized(R.string.system_intent_start_voice_call_title)
    val DESCRIPTION: String get() = localized(R.string.system_intent_start_voice_call_description)

    fun perform(session: SessionEntity?) {
        (session ?: SessionEntity.lastViewed(null)).action(startsCall = true, origin = SystemActionOrigin.shortcut)?.let(SystemActionInbox.shared::post)
    }
}

/**
 * Start Voice Call without opening the app (experimental, `backgroundCallIntent`). It shows
 * the call's Live Update first, then routes the call like Start Voice Call while the app
 * stays in the background. Whatever can't run there (the experiment off, a cold launch, the
 * Live Update refused, a call that doesn't start in time) opens the app as Start Voice Call
 * does.
 */
object StartBackgroundVoiceCallIntent {
    val TITLE: String get() = localized(R.string.system_intent_background_call_title)
    val DESCRIPTION: String get() = localized(R.string.system_intent_background_call_description)

    /** How long the call has to reach Connecting before the app opens instead. */
    val callTimeout: Duration = 8.seconds

    /** Settings › Experimental's switch (`ExperimentalFeature.backgroundCallIntent`); the type lives in `:feature:settings`. */
    val experimentKey = SharedKey.appStorage("experimental-background-call-intent", false)

    /**
     * Runs the intent. [continueInForeground] opens the app; returns whether the call is
     * running in the background.
     */
    suspend fun perform(session: SessionEntity?, continueInForeground: () -> Unit): Boolean {
        val action = (session ?: SessionEntity.lastViewed(null)).action(startsCall = true, origin = SystemActionOrigin.backgroundShortcut) ?: return false
        val activity = dependency(VoiceLiveActivityClient)
        if (!shared(experimentKey).value || !SystemActionInbox.shared.isListening ||
            !activity.prestart(VoiceCallActivityAttributes(serverID = action.serverKey ?: "", sessionID = ""))
        ) {
            AppLog.voice.notice("Background call intent opening the app")
            continueInForeground()
            SystemActionInbox.shared.post(action)
            return false
        }
        SystemActionInbox.shared.post(action.copy(startsInBackground = true))
        if (!activity.awaitCall(callTimeout)) {
            // Still routed when the app is ready; it starts the call at most once.
            AppLog.voice.notice("Background call didn't start; opening the app")
            continueInForeground()
            return false
        }
        AppLog.voice.notice("Background call started")
        return true
    }
}
