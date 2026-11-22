package ai.opencode.feature.system

import ai.opencode.core.dependencies.DependencyKey

/**
 * What a further window shows, a saved server and one of its sessions. Multiple Windows map to
 * Android's multi-instance windows on a tablet: each window is a task of its own. The first
 * window's target stays null; it restores as the app always has (`lastViewedSessionIDs`).
 */
data class SessionWindowTarget(
    /** The saved server's persistence ID. */
    val serverID: String,
    val sessionID: String,
) {
    companion object {
        /** The action of the intent that opens a session window. */
        const val ACTION = "ai.opencode.mobilecode.session-window"
        /** The intent's (and the window's saved state's) extras. */
        const val EXTRA_SERVER_ID = "serverID"
        const val EXTRA_SESSION_ID = "sessionID"
    }
}

/**
 * Opens session windows; inert where the device has one window (a phone, as iPhone). The app
 * installs the live value; the drawer's hold menu offers Open in New Window only when
 * [isAvailable].
 */
class WindowsClient(
    val isAvailable: () -> Boolean,
    val open: (SessionWindowTarget) -> Unit,
) {
    companion object Key : DependencyKey<WindowsClient> {
        /** Replaced by `:app` at launch (`SceneWindows`); before that, no windows. */
        override val liveValue: WindowsClient get() = testValue
        override val testValue: WindowsClient = WindowsClient(isAvailable = { false }, open = {})
    }
}
