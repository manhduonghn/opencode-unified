package ai.opencode.feature.system.intents

import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SessionDirectory
/**
 * Control Center and Lock Screen controls → Android Quick Settings tiles and home-screen widgets.
 * Each tap opens the app through [SystemActionActivity], which runs [perform] and posts a
 * [SystemAction]. Start Call and Open Session are set up for a session (the widget's Choose
 * Session; a tile is Last Viewed Session). Their buttons carry only the choice's ID, so a tap never
 * reads the directory: the app's router resolves it.
 */
object OpenCodeControlKind {
    const val START_CALL = "ai.opencode.mobilecode.control.start-call"
    const val OPEN_SESSION = "ai.opencode.mobilecode.control.open-session"
    const val NEW_SESSION = "ai.opencode.mobilecode.control.new-session"
    /** The ones that name a session. */
    val sessionControls = listOf(START_CALL, OPEN_SESSION)
}

object ControlIntents {
    /** Start Call / Open Session / New Session: “Starts a voice call in the session the control is set up for.” etc. */
    fun perform(kind: String, target: String?): SystemAction? = when (kind) {
        OpenCodeControlKind.START_CALL -> SystemAction.controlTarget(target, startsCall = true).copy(origin = SystemActionOrigin.control)
        OpenCodeControlKind.OPEN_SESSION -> SystemAction.controlTarget(target, startsCall = false)
        OpenCodeControlKind.NEW_SESSION -> SystemAction(destination = SystemAction.Destination.Draft(null))
        else -> null
    }
}

/**
 * A control's choice, a [SessionEntity] ID. Nothing chosen, or an ID that isn't one, means
 * Last Viewed Session. A session that has since gone is still asked for: the router says
 * so, as it does for a shortcut's.
 */
fun SystemAction.Companion.controlTarget(id: String?, startsCall: Boolean): SystemAction {
    val chosen = id?.let { SessionEntity(it, "", null).action(startsCall) }
    return chosen ?: SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = startsCall)
}

/** What a session control shows, its choice's name as it reads today. */
data class SessionControlValue(
    /** The choice's [SessionEntity] ID; null is Last Viewed Session. */
    val targetID: String?,
    val title: String,
    /** The session's project, when it has one. */
    val detail: String?,
) {
    val isNewSession: Boolean get() = targetID == SessionEntity.NEW_SESSION_ID

    companion object {
        /**
         * Names the choice from the directory, so a renamed session reads as it does now; a
         * session the directory no longer lists keeps the name it was chosen with.
         */
        fun of(entity: SessionEntity?, directory: SessionDirectory): SessionControlValue {
            val current = entity?.let { directory.sessionEntities(listOf(it.id)).firstOrNull() }
            return when (entity?.id) {
                null, SessionEntity.LAST_VIEWED_ID -> SessionControlValue(entity?.id, localized(R.string.system_last_viewed_session), null)
                SessionEntity.NEW_SESSION_ID -> SessionControlValue(entity.id, localized(R.string.system_new_session), null)
                else -> {
                    val title = current?.title ?: entity.title
                    SessionControlValue(entity.id, title.ifEmpty { localized(R.string.system_quick_action_session_fallback) }, current?.detail ?: entity.detail)
                }
            }
        }
    }
}
