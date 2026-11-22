package ai.opencode.feature.drawer

import ai.opencode.core.diagnostics.ProblemReportBuilder
import ai.opencode.core.diagnostics.ProblemReportContext
import ai.opencode.devtools.traces.ProblemReportDestination
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.voice.VoiceFeature

/* Where the app is, for a Report a Problem report, and which session the report also goes into. */

/** Where the app is, for a report. Tagged with the session ID and server persistence ID: never server addresses or message text. */
internal val State.problemReportContext: ProblemReportContext
    get() {
        val detail = topDetail
        val session = detail?.session
        val screen = when {
            catchUp != null -> "Catch Up"
            detail != null -> if (detail.isDraft) "New session" else if (subagentStack.isNotEmpty() || session?.parentID != null) "Subagent session" else "Session"
            else -> "Home"
        }
        val connection = when (connectionStatus) {
            HomeConnectionFeature.State.ConnectionStatus.connected -> "Connected"
            HomeConnectionFeature.State.ConnectionStatus.reconnecting -> "Reconnecting"
            HomeConnectionFeature.State.ConnectionStatus.disconnected -> connectionErrorMessage?.let { "Offline: $it" } ?: "Offline"
        }
        val call = sessionDetail?.voice?.let { voice ->
            val parts = mutableListOf(voice.statusText)
            if (voice.isReconnecting && voice.phase != VoiceFeature.State.Phase.Live) parts += "reconnecting"
            if (voice.pendingWork.isNotEmpty()) parts += if (voice.pendingWork.size == 1) "1 request working" else "${voice.pendingWork.size} requests working"
            if (voice.isMuted) parts += "muted"
            parts.joinToString(" · ")
        }
        return ProblemReportContext(
            sessionID = session?.id,
            serverID = ProblemReportBuilder.serverKey(server.persistenceID),
            screen = screen,
            project = session?.let { s -> projects.firstOrNull { it.id == s.projectID }?.name },
            connection = connection,
            call = call,
        )
    }

/** The report's session family root, else the most recently updated top-level session, on this Home's server. Never a subagent. */
internal fun State.problemReportDestination(context: ProblemReportContext): ProblemReportDestination? {
    val candidate = topDetail?.familyRootID ?: context.sessionID
    val isRoot = candidate?.let { id ->
        activeSessionParents[id] == null &&
            sessions.firstOrNull { it.id == id }?.parentID == null &&
            (topDetail?.session?.id != id || topDetail?.session?.parentID == null)
    } ?: false
    val sessionID = if (isRoot) candidate else sessions.filter { it.parentID == null }.maxByOrNull { it.updatedAt }?.id
    return sessionID?.let { id ->
        val directory = sessions.firstOrNull { it.id == id }?.directory
            ?: if (topDetail?.session?.id == id) topDetail?.session?.directory else null
        ProblemReportDestination(server = server, sessionID = id, directory = directory)
    }
}
