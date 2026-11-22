package ai.opencode.feature.system.intents

import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.ScopedID
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.shared
import ai.opencode.server.ServerID
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.sendPromptConfirming
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.retry.RetryBudget
import ai.opencode.feature.system.share.ShareSendRequest
import kotlin.time.Duration.Companion.seconds

/**
 * Ask OpenCode's work, done in the background without the app's store. It resolves the target from
 * the published [SessionDirectory] and the saved server's credentials, then sends like a voice
 * delegation does.
 */
object SystemPromptSender {
    data class Sent(
        val sessionTitle: String,
        val delivery: OpenCodeDelivery,
        /** Created for this message, in the project named by [sessionTitle]. */
        val isNewSession: Boolean = false,
    ) {
        /** What the confirmation says once it's sent. */
        val confirmation: String
            get() = when {
                isNewSession -> localized(R.string.system_ask_started_new_session, sessionTitle)
                delivery == OpenCodeDelivery.Queue -> localized(R.string.system_ask_queued_for, sessionTitle)
                else -> localized(R.string.system_ask_sent_to, sessionTitle)
            }
    }

    class Failure(override val message: String) : Exception(message) {
        override fun equals(other: Any?) = other is Failure && other.message == message
        override fun hashCode() = message.hashCode()

        companion object {
            val noServer: Failure get() = Failure(localized(R.string.system_ask_no_server))
            val noProject: Failure get() = Failure(localized(R.string.system_ask_no_project))
            val emptyMessage: Failure get() = Failure(localized(R.string.system_ask_empty_message))
        }
    }

    /** Every required step of one send, together. */
    val budget = 30.seconds

    /** [target] is a [SessionEntity] ID; null means Last Viewed Session. */
    suspend fun send(
        text: String,
        target: String?,
        delivery: OpenCodeDelivery,
        directory: SessionDirectory?,
        servers: List<OpenCodeServer>,
        client: OpenCodeClient,
    ): Sent {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw Failure.emptyMessage
        val scoped = target?.let(ScopedID::parse)
        val key = scoped?.serverKey ?: directory?.currentServerKey ?: throw Failure.noServer
        val server = servers.firstOrNull { SessionDirectory.serverKey(it.persistenceID) == key } ?: throw Failure.noServer
        val entry = directory?.servers?.get(key)

        var session: Pair<String, String>? = null
        if (scoped != null) {
            session = scoped.id to (entry?.session(scoped.id)?.title ?: localized(R.string.system_ask_your_session))
        } else if (target != SessionEntity.NEW_SESSION_ID) {
            entry?.lastViewed?.let { session = it.id to it.title }
        }
        // New Session, or Last Viewed with nothing viewed yet.
        val project = if (session == null) entry?.projects?.firstOrNull() else null
        if (session == null && project == null) throw Failure.noProject
        val title = project?.name ?: session?.second ?: ""
        try {
            // Creating and sending share one budget, like a voice tool's steps.
            RetryBudget(budget).scope {
                var sessionID = session?.first ?: ""
                if (project != null) {
                    sessionID = client.createSession(server, project.directory, ServerID.newSessionID()).id
                }
                client.sendPromptConfirming(server, sessionID, trimmed, delivery)
            }
        } catch (error: OpenCodeClientError) {
            throw Failure(localized(R.string.system_ask_send_failed, error.message.toString()))
        }
        return Sent(title, delivery, isNewSession = project != null)
    }

    /** The live directory, saved servers and client. */
    suspend fun send(text: String, target: String?, delivery: OpenCodeDelivery): Sent {
        val context = dependency(AppContext)
        val savedServers = shared(ServerSharedKeys.savedServers).value
        val savedServer = shared(ServerSharedKeys.savedServer).value
        return send(
            text, target, delivery,
            directory = SessionDirectory.load(SessionDirectory.file(context.filesDir)),
            servers = savedServers + listOfNotNull(savedServer),
            client = dependency(OpenCodeClient),
        )
    }
}
