package ai.opencode.feature.system.intents

import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.share.ShareSuggestion
import ai.opencode.core.ui.SFSymbol
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * One launcher shortcut (Home Screen quick action). Shown at most four: the static New Session
 * (`res/xml/shortcuts.xml`), then Start Call while Voice is set up, then the most recent sessions
 * to fill the rest.
 */
data class SystemQuickAction(
    val kind: Kind,
    val title: String,
    val subtitle: String? = null,
    val symbol: SFSymbol,
    val serverKey: String? = null,
    val sessionID: String? = null,
) {
    enum class Kind(val rawValue: String) {
        NewSession("ai.opencode.mobilecode.new-session"),
        StartCall("ai.opencode.mobilecode.start-call"),
        OpenSession("ai.opencode.mobilecode.open-session"),
        ;

        companion object {
            fun of(rawValue: String) = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    val action: SystemAction?
        get() = when (kind) {
            Kind.NewSession -> SystemAction(serverKey, SystemAction.Destination.Draft(null))
            Kind.StartCall -> SystemAction(serverKey, SystemAction.Destination.LastViewed, startsCall = true, origin = SystemActionOrigin.quickAction)
            Kind.OpenSession -> sessionID?.let { SystemAction(serverKey, SystemAction.Destination.Session(it)) }
        }

    val userInfo: Map<String, String?> get() = mapOf("server" to serverKey, "session" to sessionID)

    /** A stable ID per slot, so updating the set replaces rather than piles up. */
    val shortcutID: String
        get() = when (kind) {
            Kind.OpenSession -> ShareSuggestion.shortcutID(serverKey.orEmpty(), sessionID.orEmpty())
            else -> SHORTCUT_PREFIX + kind.rawValue
        }

    fun shortcut(context: Context, rank: Int): ShortcutInfoCompat {
        val intent = Intent(context, SystemActionActivity::class.java)
            .setAction(SystemActionActivity.ACTION_SYSTEM)
            .putExtra(SystemActionActivity.EXTRA_QUICK_ACTION, kind.rawValue)
        serverKey?.let { intent.putExtra("server", it) }
        sessionID?.let { intent.putExtra("session", it) }
        val label = title.ifEmpty { context.getString(R.string.system_quick_action_session_fallback) }
        return ShortcutInfoCompat.Builder(context, shortcutID)
            .setShortLabel(label)
            .setLongLabel(subtitle?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.system_quick_action_long_label, label, it) } ?: label)
            .setIcon(IconCompat.createWithResource(context, symbol.drawable))
            .setIntent(intent)
            .setRank(rank)
            .apply {
                if (kind == Kind.OpenSession) {
                    setCategories(setOf(ShareSuggestion.CATEGORY))
                    setLongLived(true)
                }
            }
            .build()
    }

    companion object {
        const val LIMIT = 4
        /** New Session is declared in `shortcuts.xml`, so it shows before first launch. */
        const val STATIC_COUNT = 1
        /** Quick actions' dynamic shortcut IDs. The session entries also serve Direct Share. */
        const val SHORTCUT_PREFIX = "qa:"

        /** The dynamic items for the server on screen. */
        fun items(server: SessionDirectory.Server?, voiceAvailable: Boolean): List<SystemQuickAction> {
            server ?: return emptyList()
            val items = mutableListOf<SystemQuickAction>()
            if (voiceAvailable) {
                items += SystemQuickAction(Kind.StartCall, localized(R.string.system_start_call), server.lastViewed?.title, SFSymbol.Waveform, server.key)
            }
            for (session in server.sessions.take(LIMIT - STATIC_COUNT - items.size)) {
                items += SystemQuickAction(
                    Kind.OpenSession, session.title, session.projectName, SFSymbol.BubbleLeft, server.key, session.id,
                )
            }
            return items
        }

        /** Reads a tapped item back, static or dynamic. */
        fun action(type: String, userInfo: Map<String, String?>?): SystemAction? {
            val kind = Kind.of(type) ?: return null
            return SystemQuickAction(kind, "", null, SFSymbol.BubbleLeft, userInfo?.get("server"), userInfo?.get("session")).action
        }

        /** Publishes the launcher list once for both surfaces. Skips unchanged items. */
        fun apply(context: Context, items: List<SystemQuickAction>) {
            val shortcuts = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)
            // Older builds published a second, launcher-visible session shortcut for each donation.
            val obsolete = shortcuts.map { it.id }.filter { it.startsWith("share:") }
            if (obsolete.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, obsolete)
            val existing = shortcuts.filter { it.isDynamic && it.id.startsWith(SHORTCUT_PREFIX) }
            // The platform numbers dynamic ranks from zero, separately from manifest shortcuts.
            val wanted = items.mapIndexed { index, item -> item.shortcut(context, index) }
            val same = existing.size == wanted.size && existing.sortedBy { it.rank }.zip(wanted).all { (a, b) ->
                a.id == b.id && a.shortLabel == b.shortLabel && a.longLabel == b.longLabel && a.rank == b.rank && a.categories == b.categories
            }
            if (same) return
            val stale = existing.map { it.id }.filter { id -> wanted.none { it.id == id } }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            if (wanted.isNotEmpty()) ShortcutManagerCompat.addDynamicShortcuts(context, wanted)
        }
    }
}
