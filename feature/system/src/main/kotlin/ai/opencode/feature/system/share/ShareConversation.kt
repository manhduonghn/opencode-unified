package ai.opencode.feature.system.share

import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SessionDirectory
import java.security.MessageDigest

/**
 * How the system names a session, shared by the app and the share target: the share sheet's
 * direct-share suggestions (sharing shortcuts' IDs), the share sheet's local notifications, and the
 * links that open the app. A session is `<serverKey>:<sessionID>`, where the key is a short hash of
 * the server's persistence ID, so no address ever reaches the system.
 */
object ShareConversation {
    /** `SessionDirectory.serverKey(for:)`: the first 6 bytes of SHA-256, as 12 lowercase hex digits. */
    fun serverKey(persistenceID: String): String =
        MessageDigest.getInstance("SHA-256").digest(persistenceID.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun identifier(serverKey: String, sessionID: String): String = "$serverKey:$sessionID"

    data class Named(val serverKey: String?, val sessionID: String)

    /** The server key (null for a bare session ID) and session in a conversation identifier. */
    fun parse(identifier: String): Named? {
        val parts = identifier.split(":", limit = 2)
        return when {
            parts.size == 2 && parts[0].isNotEmpty() && parts[1].startsWith("ses") -> Named(parts[0], parts[1])
            parts.size == 1 && parts[0].startsWith("ses") -> Named(null, parts[0])
            else -> null
        }
    }

    /**
     * Opens the app without asking it to do anything: the share waiting in `Shares/` says
     * where to go. `SystemAction.fromUrl` doesn't know the host, so it routes nowhere and only
     * the share is delivered.
     */
    const val CONTINUE_URL = "opencode://share"

    fun isContinueURL(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "opencode" && uri.host?.lowercase() == "share"
    }

    /** The extras key of a share sheet's notification: tapping it opens the session it sent to. */
    const val NOTIFICATION_KEY = "opencode-share"

    fun notificationPayload(serverKey: String, sessionID: String): Map<String, String> =
        mapOf("server" to serverKey, "session" to sessionID)

    data class Destination(val serverKey: String, val sessionID: String)

    /** The session a share notification's payload names. */
    fun destination(payload: Map<String, String>?): Destination? {
        payload ?: return null
        val server = payload["server"] ?: return null
        val session = payload["session"] ?: return null
        if (server.isEmpty() || server.toByteArray().size > 64) return null
        if (!session.startsWith("ses") || session.toByteArray().size > 200) return null
        return Destination(server, session)
    }
}
