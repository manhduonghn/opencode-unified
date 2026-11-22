package ai.opencode.feature.system.share

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.intents.SystemQuickAction
import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * [SystemQuickAction] publishes each recent session once, with a share category for the chooser and
 * a session-opening intent for the launcher. An open/send reports usage without inserting,
 * reranking or changing the launcher's items.
 */
object ShareSuggestion {
    const val CATEGORY = "ai.opencode.mobilecode.category.SHARE_SESSION"
    private const val SESSION_PREFIX = SystemQuickAction.SHORTCUT_PREFIX + "session:"
    private const val MATCH_SESSIONS = ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED

    fun shortcutID(serverKey: String, sessionID: String): String =
        SESSION_PREFIX + ShareConversation.identifier(serverKey, sessionID)

    fun conversation(shortcutID: String?): String? =
        shortcutID?.takeIf { it.startsWith(SESSION_PREFIX) }?.removePrefix(SESSION_PREFIX)

    /** Blocking: call off the main thread. A session outside the published list doesn't create a launcher item. */
    fun donate(context: Context, serverKey: String, sessionID: String) {
        val id = shortcutID(serverKey, sessionID)
        runCatching {
            if (ShortcutManagerCompat.getShortcuts(context, MATCH_SESSIONS).any { it.id == id }) {
                ShortcutManagerCompat.reportShortcutUsed(context, id)
            }
        }.onFailure { AppLog.app.error { "Couldn’t donate share suggestion: ${it::class.java.simpleName}" } }
    }

    /** Also forget cached conversations that have fallen out of the launcher's list. */
    fun remove(context: Context, serverKey: String, sessionIDs: List<String>) {
        if (sessionIDs.isEmpty()) return
        runCatching {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, sessionIDs.map { shortcutID(serverKey, it) })
        }
    }

    fun removeAll(context: Context, serverKey: String) {
        runCatching {
            val ids = ShortcutManagerCompat.getShortcuts(context, MATCH_SESSIONS).map { it.id }.filter { it.startsWith("$SESSION_PREFIX$serverKey:") }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }
    }
}
