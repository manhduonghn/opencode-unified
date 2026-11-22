package ai.opencode.feature.drawer.notices

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.sharing.shared
import ai.opencode.feature.session.InAppNotice
import ai.opencode.feature.system.notifications.SessionPushNotifications
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The rules half: the mode (the notice itself, [InAppNotice], moved down to `:feature:session`,
// whose `SessionListContext` carries it), the signal Home derives from its live state, and the
// clients that clear delivered notifications and speak to TalkBack. The drawer button's label (the
// view) is `DrawerNoticeLabel` in `motion/DrawerButton.kt`, which reads only [InAppNotice] (its
// [InAppNotice.title], [InAppNotice.status], [InAppNotice.needsInput], [InAppNotice.target]) and
// sends Home's `inAppNoticeTapped`; Home's wiring (queueing, the timer, the haptic and the
// announcement) is in `HomeFeatureNotifications.kt`.

/**
 * Whether the mode shows a notice of this kind ([InAppNoticeMode] itself moved down to `:core`,
 * `ai.opencode.core.experiments`, as Settings › Notifications writes it).
 */
fun InAppNoticeMode.shows(kind: InAppNotice.Kind): Boolean = when (this) {
    InAppNoticeMode.off -> false
    InAppNoticeMode.needsInput -> kind == InAppNotice.Kind.needsInput
    InAppNoticeMode.all -> true
}

/**
 * The mode as reducers and the notification handler read it. Android has no launch environment, so
 * the saved choice decides.
 */
class InAppNoticeSettings(val mode: () -> InAppNoticeMode) {
    companion object Key : DependencyKey<InAppNoticeSettings> {
        override val liveValue = InAppNoticeSettings { shared(InAppNoticeMode.key).value }
        override val testValue = InAppNoticeSettings { InAppNoticeMode.off }

        fun constant(mode: InAppNoticeMode) = InAppNoticeSettings { mode }
    }
}

/**
 * What the notice rules read about each listed root session, derived from Home's live state (the
 * event stream, the attention read, read receipts), never from a push.
 */
data class InAppNoticeSignal(
    /** Only changes between two live readings count: a reconnect or a cold launch's first list isn't news. */
    val isLive: Boolean,
    val sessions: Map<String, Session>,
) {
    data class Session(
        val title: String,
        val needsInput: Boolean,
        val unread: Boolean,
        val idle: Long?,
    )

    /** An event and the key that remembers it was noticed. */
    data class KeyedEvent(val event: InAppNotice.Event, val key: String)

    /** Whether a shown event still holds: answered or read anywhere, it goes. */
    fun stillHolds(event: InAppNotice.Event): Boolean {
        val session = sessions[event.sessionID] ?: return false
        return if (event.kind == InAppNotice.Kind.needsInput) session.needsInput else session.unread && !session.needsInput
    }

    companion object {
        val none = InAppNoticeSignal(isLive = false, sessions = emptyMap())

        /**
         * The events between two readings. The caller filters out the session on screen, the
         * mode, and anything already noticed.
         */
        fun events(from: InAppNoticeSignal, to: InAppNoticeSignal): List<KeyedEvent> {
            if (!from.isLive || !to.isLive) return emptyList()
            val events = mutableListOf<KeyedEvent>()
            for ((id, session) in to.sessions.entries.sortedBy { it.key }) {
                val before = from.sessions[id]
                if (before == null) {
                    // A session that appears already asking is still asking.
                    if (session.needsInput) {
                        events += KeyedEvent(InAppNotice.Event(id, session.title, InAppNotice.Kind.needsInput), "input|$id")
                    }
                    continue
                }
                val idle = session.idle
                if (session.needsInput && !before.needsInput) {
                    events += KeyedEvent(InAppNotice.Event(id, session.title, InAppNotice.Kind.needsInput), "input|$id")
                } else if (!session.needsInput && session.unread && idle != null && (before.idle?.let { idle > it } ?: true)) {
                    // A newer idle watermark is a run that finished; unread means no client has
                    // read it since.
                    events += KeyedEvent(InAppNotice.Event(id, session.title, InAppNotice.Kind.done), "done|$id|$idle")
                }
            }
            return events
        }
    }
}

/**
 * Removes delivered notifications from the notification shade: `:feature:system`'s
 * [SessionPushNotifications.remove], which knows how it posts them.
 */
class DeliveredNotificationsClient(
    /** Every notification for these sessions (their thread identifiers). */
    val removeAll: suspend (sessionIDs: Set<String>) -> Unit,
    /** Only their needs-input notifications, once the request is answered. */
    val removeNeedsInput: suspend (sessionIDs: Set<String>) -> Unit,
) {
    companion object Key : DependencyKey<DeliveredNotificationsClient> {
        override val liveValue: DeliveredNotificationsClient by lazy {
            val context = dependency(AppContext)
            DeliveredNotificationsClient(
                removeAll = { ids -> withContext(Dispatchers.Default) { SessionPushNotifications.remove(context, ids, needsInputOnly = false) } },
                removeNeedsInput = { ids -> withContext(Dispatchers.Default) { SessionPushNotifications.remove(context, ids, needsInputOnly = true) } },
            )
        }
        override val testValue = DeliveredNotificationsClient(removeAll = {}, removeNeedsInput = {})
    }
}

/** TalkBack announcements that don't move focus. Main thread. */
class AccessibilityAnnouncer(val announce: (text: String, urgent: Boolean) -> Unit) {
    companion object Key : DependencyKey<AccessibilityAnnouncer> {
        override val liveValue: AccessibilityAnnouncer by lazy {
            val context = dependency(AppContext)
            AccessibilityAnnouncer { text, _ ->
                // Android has no announcement priority; an urgent one reads the same.
                val manager = context.getSystemService(AccessibilityManager::class.java)
                if (manager == null || !manager.isEnabled) return@AccessibilityAnnouncer
                @Suppress("DEPRECATION")
                val event = AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                    packageName = context.packageName
                    this.text.add(text)
                }
                manager.sendAccessibilityEvent(event)
            }
        }
        override val testValue = AccessibilityAnnouncer { _, _ -> }
    }
}
