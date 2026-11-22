package ai.opencode.feature.system

/** Where a tapped notification leads, a saved server and one of its sessions. */
data class NotificationDestination(
    /** The saved server's persistence ID (the key its `ref` is saved under). */
    val serverKey: String,
    val sessionID: String,
) {
    val isValid: Boolean
        get() = serverKey.isNotEmpty() && serverKey.toByteArray().size <= 512 &&
            sessionID.startsWith("ses") && sessionID.toByteArray().size <= 200 &&
            sessionID.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' }

    companion object {
        /** The action of a session notification's explicit intent to the app's launcher activity. */
        const val ACTION_OPEN = "ai.opencode.action.OPEN_SESSION_NOTIFICATION"
        /** Extra keys, matching the notification payload. */
        const val EXTRA_SERVER = "server"
        const val EXTRA_SESSION = "session"
    }
}
