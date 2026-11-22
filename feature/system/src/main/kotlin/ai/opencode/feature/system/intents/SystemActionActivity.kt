package ai.opencode.feature.system.intents

import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemActionInbox
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.notifications.SessionNotifications
import ai.opencode.feature.system.share.ShareConversation
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * The one door every system entry point opens: launcher quick actions and app shortcuts, Quick
 * Settings tiles, widgets, App Actions (through [AppActionActivity]), and tapped notifications.
 * Private to the app: other apps can't start calls through it. It reads the [SystemAction] the
 * intent carries, posts it to [SystemActionInbox], then brings the app forward and finishes without
 * drawing.
 */
class SystemActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handle(intent)) openApp(this)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handle(intent)) openApp(this)
        finish()
    }

    /** Routes the intent; false when it runs without opening the app (a background call). */
    private fun handle(intent: Intent): Boolean {
        intent.getStringExtra(EXTRA_APP_FEATURE)?.let { spoken ->
            val directory = SessionDirectory.loadOrEmpty(SessionDirectory.file(filesDir))
            // Calls only for a verified assistant (AppActionActivity); any other app's call opens
            // the session, where the user starts it.
            val fromAssistant = intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false)
            when (val feature = OpenCodeShortcuts.feature(spoken, directory)) {
                OpenCodeShortcuts.Feature.BackgroundCall, OpenCodeShortcuts.Feature.StartVoiceCall -> when {
                    !fromAssistant -> {
                        AppLog.app.notice("App Action call from an unverified caller; opening the session")
                        OpenSessionIntent.perform(SessionEntity.lastViewed(null))
                    }
                    feature == OpenCodeShortcuts.Feature.BackgroundCall -> {
                        startActivity(Intent(this, BackgroundCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return false
                    }
                    else -> StartVoiceCallIntent.perform(null)
                }
                is OpenCodeShortcuts.Feature.NewSession -> NewSessionIntent.perform(feature.project)
                is OpenCodeShortcuts.Feature.OpenSession -> OpenSessionIntent.perform(feature.session)
                null -> Unit
            }
            return true
        }
        // A tapped push: its ref finds the saved server it was minted for.
        val ref = intent.getStringExtra(EXTRA_PUSH_REF)
        val pushSession = intent.getStringExtra(EXTRA_PUSH_SESSION)
        if (ref != null && pushSession != null) {
            // The app's launcher activity routes it (`AppFeature.notificationOpened`).
            SessionNotifications.shared.destination(ref, pushSession)?.let { destination ->
                openApp(this) { launch ->
                    launch.setAction(NotificationDestination.ACTION_OPEN)
                        .putExtra(NotificationDestination.EXTRA_SERVER, destination.serverKey)
                        .putExtra(NotificationDestination.EXTRA_SESSION, destination.sessionID)
                }
            }
            return false
        }
        // The share sheet's "sent" notification opens the session it sent to.
        val share = ShareConversation.destination(
            intent.getStringExtra(EXTRA_SHARE_SERVER)?.let { server ->
                mapOf("server" to server, "session" to (intent.getStringExtra(EXTRA_SHARE_SESSION) ?: ""))
            },
        )
        if (share != null) {
            SystemActionInbox.shared.post(SystemAction(share.serverKey, SystemAction.Destination.Session(share.sessionID)))
            return true
        }
        val action = action(intent) ?: return true
        AppLog.app.info { "System action received destination=${action.destination::class.simpleName} call=${action.startsCall}" }
        SystemActionInbox.shared.post(action)
        return true
    }

    companion object {
        const val ACTION_SYSTEM = "ai.opencode.mobilecode.SYSTEM_ACTION"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_STARTS_CALL = "startsCall"
        private const val EXTRA_ORIGIN = "origin"
        /** A quick action's kind (`SystemQuickAction.Kind`), static or dynamic. */
        const val EXTRA_QUICK_ACTION = "quickAction"
        /** A control's choice (`SessionEntity` ID) and whether it starts a call. */
        const val EXTRA_CONTROL_TARGET = "controlTarget"
        const val EXTRA_CONTROL_KIND = "controlKind"
        /** An App Action's spoken feature (`actions.intent.OPEN_APP_FEATURE`). */
        const val EXTRA_APP_FEATURE = "appFeature"
        /** Set by [AppActionActivity]: the App Action came from a verified assistant, so it may start a call. */
        internal const val EXTRA_FROM_ASSISTANT = "fromAssistant"
        internal const val EXTRA_PUSH_REF = "opencode.ref"
        internal const val EXTRA_PUSH_SESSION = "opencode.sessionID"
        internal const val EXTRA_SHARE_SERVER = "opencode-share.server"
        internal const val EXTRA_SHARE_SESSION = "opencode-share.session"

        /** An explicit intent that routes [action] when started. */
        fun intent(context: Context, action: SystemAction): Intent =
            Intent(context, SystemActionActivity::class.java).setAction(ACTION_SYSTEM)
                .putExtra(EXTRA_SERVER, action.serverKey)
                .putExtra(EXTRA_STARTS_CALL, action.startsCall)
                .putExtra(EXTRA_ORIGIN, action.origin.name)
                .apply {
                    when (val destination = action.destination) {
                        SystemAction.Destination.LastViewed -> putExtra(EXTRA_DESTINATION, "last")
                        is SystemAction.Destination.Session ->
                            putExtra(EXTRA_DESTINATION, "session").putExtra(EXTRA_SESSION, destination.id)
                        is SystemAction.Destination.Draft ->
                            putExtra(EXTRA_DESTINATION, "draft").putExtra(EXTRA_PROJECT, destination.projectID)
                    }
                    // Distinct data keeps PendingIntents for different actions apart.
                    data = Uri.parse("opencode-system://${action.hashCode()}")
                }

        /** A tapped share notification: opens the session it sent to. */
        fun shareIntent(context: Context, serverKey: String, sessionID: String): Intent =
            Intent(context, SystemActionActivity::class.java).setAction(ACTION_SYSTEM)
                .putExtra(EXTRA_SHARE_SERVER, serverKey)
                .putExtra(EXTRA_SHARE_SESSION, sessionID)
                .setData(Uri.parse("opencode-system://share/$serverKey/$sessionID"))

        /** A tapped session push: its ref and session (`opencode.ref`, `opencode.sessionID`). */
        fun pushIntent(context: Context, ref: String, sessionID: String): Intent =
            Intent(context, SystemActionActivity::class.java).setAction(ACTION_SYSTEM)
                .putExtra(EXTRA_PUSH_REF, ref)
                .putExtra(EXTRA_PUSH_SESSION, sessionID)
                .setData(Uri.parse("opencode-system://push/$sessionID"))

        /** The action an intent carries: explicit extras, a quick action, a control, or an `opencode://` link. */
        fun action(intent: Intent): SystemAction? {
            intent.getStringExtra(EXTRA_QUICK_ACTION)?.let { kind ->
                return SystemQuickAction.action(
                    kind,
                    mapOf("server" to intent.getStringExtra(EXTRA_SERVER), "session" to intent.getStringExtra(EXTRA_SESSION)),
                )
            }
            intent.getStringExtra(EXTRA_CONTROL_KIND)?.let { kind ->
                return ControlIntents.perform(kind, intent.getStringExtra(EXTRA_CONTROL_TARGET))
            }
            intent.getStringExtra(EXTRA_DESTINATION)?.let { destination ->
                val server = intent.getStringExtra(EXTRA_SERVER)
                val startsCall = intent.getBooleanExtra(EXTRA_STARTS_CALL, false)
                val resolved = when (destination) {
                    "last" -> SystemAction.Destination.LastViewed
                    "session" -> SystemAction.Destination.Session(intent.getStringExtra(EXTRA_SESSION) ?: return null)
                    "draft" -> SystemAction.Destination.Draft(intent.getStringExtra(EXTRA_PROJECT))
                    else -> return null
                }
                val originName = intent.getStringExtra(EXTRA_ORIGIN)
                val origin = SystemActionOrigin.entries.firstOrNull { it.name == originName } ?: SystemActionOrigin.other
                return SystemAction(server, resolved, startsCall, origin = origin)
            }
            if (intent.action == Intent.ACTION_VIEW) {
                val data = intent.dataString ?: return null
                return SystemAction.fromUrl(data)
            }
            return null
        }

        /** Brings the app forward (its launcher activity is single-task, so a running one resumes). */
        fun openApp(context: Context, configure: (Intent) -> Unit = {}) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            configure(launch)
            context.startActivity(launch)
        }
    }
}
