package ai.opencode.feature.system.intents

import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.ScopedID
import ai.opencode.core.ui.SFSymbol
import java.text.Normalizer

/**
 * A session, or one of two choices that aren't one yet: **Last Viewed Session** and **New
 * Session**. Real sessions are server-scoped (`<serverKey>:<sessionID>`). Widgets' Choose Session
 * and Ask OpenCode's session picker list these from the [SessionDirectory], never the network.
 */
data class SessionEntity(
    val id: String,
    val title: String,
    /** The project, or for a choice, what it currently means. */
    val detail: String?,
) {
    /** The icon a picker shows beside it. */
    val symbol: SFSymbol get() = if (id == NEW_SESSION_ID) SFSymbol.SquareAndPencil else SFSymbol.BubbleLeft

    /** Where choosing this takes you. */
    fun action(startsCall: Boolean = false, origin: SystemActionOrigin = SystemActionOrigin.other): SystemAction? = when (id) {
        LAST_VIEWED_ID -> SystemAction(destination = SystemAction.Destination.LastViewed, startsCall = startsCall, origin = origin)
        NEW_SESSION_ID -> SystemAction(destination = SystemAction.Destination.Draft(null), startsCall = startsCall, origin = origin)
        else -> ScopedID.parse(id)?.let {
            SystemAction(it.serverKey, SystemAction.Destination.Session(it.id), startsCall, origin = origin)
        }
    }

    companion object {
        val TYPE_DISPLAY_NAME: String get() = localized(R.string.system_session_type_name)
        const val LAST_VIEWED_ID = "last-viewed"
        const val NEW_SESSION_ID = "new-session"

        fun of(session: SessionDirectory.Session, serverKey: String) =
            SessionEntity(ScopedID(serverKey, session.id).rawValue, session.title, session.projectName)

        /** Last Viewed Session, naming the session it means today. */
        fun lastViewed(server: SessionDirectory.Server?) =
            SessionEntity(LAST_VIEWED_ID, localized(R.string.system_last_viewed_session), server?.lastViewed?.title)

        /** New Session, naming the project it starts in. */
        fun newSession(server: SessionDirectory.Server?) =
            SessionEntity(NEW_SESSION_ID, localized(R.string.system_new_session), server?.projects?.firstOrNull()?.name)
    }
}

/** `<serverKey>:<projectID>`. */
data class ProjectEntity(val id: String, val name: String) {
    val symbol: SFSymbol get() = SFSymbol.Folder

    companion object {
        val TYPE_DISPLAY_NAME: String get() = localized(R.string.system_project_type_name)

        fun of(project: SessionDirectory.Project, serverKey: String) =
            ProjectEntity(ScopedID(serverKey, project.id).rawValue, project.name)
    }
}

/** Reads the published directory. */
object SessionQuery {
    fun entities(directory: SessionDirectory, identifiers: List<String>) = directory.sessionEntities(identifiers)
    fun entities(directory: SessionDirectory, matching: String) = directory.sessionEntities(matching)
    fun suggestedEntities(directory: SessionDirectory) = directory.suggestedSessionEntities()
    fun defaultResult(directory: SessionDirectory) = SessionEntity.lastViewed(directory.current)
}

object ProjectQuery {
    fun entities(directory: SessionDirectory, identifiers: List<String>) = directory.projectEntities(identifiers)
    fun entities(directory: SessionDirectory, matching: String) = directory.projectEntities(matching)
    fun suggestedEntities(directory: SessionDirectory) = directory.projectEntities("")
}

// MARK: Lookups

/**
 * Resolves stored IDs from any saved server, so a shortcut or widget set up on one server
 * keeps working after switching to another.
 */
fun SessionDirectory.sessionEntities(identifiers: List<String>): List<SessionEntity> = identifiers.mapNotNull { id ->
    when (id) {
        SessionEntity.LAST_VIEWED_ID -> SessionEntity.lastViewed(current)
        SessionEntity.NEW_SESSION_ID -> SessionEntity.newSession(current)
        else -> {
            val scoped = ScopedID.parse(id) ?: return@mapNotNull null
            val server = servers[scoped.serverKey] ?: return@mapNotNull null
            val session = server.session(scoped.id) ?: return@mapNotNull null
            SessionEntity.of(session, server.key)
        }
    }
}

/** Last Viewed Session and New Session, then the current server's pinned and recent sessions in drawer order. */
fun SessionDirectory.suggestedSessionEntities(): List<SessionEntity> {
    val choices = listOf(SessionEntity.lastViewed(current), SessionEntity.newSession(current))
    val current = current ?: return choices
    return choices + (current.pinned + current.recents).map { SessionEntity.of(it, current.key) }
}

/** The current server's sessions whose title or project contains `string`. */
fun SessionDirectory.sessionEntities(matching: String): List<SessionEntity> {
    val query = matching.trim()
    val current = current ?: return emptyList()
    if (query.isEmpty()) return suggestedSessionEntities()
    return current.sessions
        .filter { it.title.localizedStandardContains(query) || it.projectName?.localizedStandardContains(query) == true }
        .map { SessionEntity.of(it, current.key) }
}

fun SessionDirectory.projectEntities(identifiers: List<String>): List<ProjectEntity> = identifiers.mapNotNull { id ->
    val scoped = ScopedID.parse(id) ?: return@mapNotNull null
    val server = servers[scoped.serverKey] ?: return@mapNotNull null
    val project = server.project(scoped.id) ?: return@mapNotNull null
    ProjectEntity.of(project, server.key)
}

fun SessionDirectory.projectEntities(matching: String): List<ProjectEntity> {
    val query = matching.trim()
    val current = current ?: return emptyList()
    return current.projects
        .filter { query.isEmpty() || it.name.localizedStandardContains(query) }
        .map { ProjectEntity.of(it, current.key) }
}

/** Foundation's `localizedStandardContains`: case- and diacritic-insensitive. */
internal fun String.localizedStandardContains(other: String): Boolean = folded().contains(other.folded())

private fun String.folded(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
