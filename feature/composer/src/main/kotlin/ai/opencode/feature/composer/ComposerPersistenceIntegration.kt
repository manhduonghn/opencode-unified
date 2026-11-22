package ai.opencode.feature.composer

import java.time.Instant
import ai.opencode.core.strings.localized
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.learningFirstPrompt
import java.util.UUID

// Those reducers live in the session and drawer areas, above this module, so their logic is here as
// plain functions over values; the session/drawer areas call them from their reducers and apply the
// results.

/**
 * What the persisted composer watches for changes: the session's draft and where it belongs. The
 * session reducer compares the old and new watch (`onChange`) and calls
 * [reconcilePersistedComposer].
 */
data class PersistedComposerWatch(
    val serverID: String,
    val sessionID: String?,
    val readsOnView: Boolean,
    val project: OpenCodeProject?,
    val text: String,
    val attachments: List<OpenCodeOutgoingAttachment>,
    val files: List<FileItem>,
    val location: SharedLocation?,
    val chips: List<ComposerAutocompleteChip>,
    val undoSnapshot: UndoSnapshot?,
    val quotes: List<ComposerQuote> = emptyList(),
) {
    sealed interface FilePhase {
        data object Uploading : FilePhase
        data class Uploaded(val path: String) : FilePhase
        data class Failed(val reason: String) : FilePhase
    }

    data class FileItem(val id: UUID, val phase: FilePhase) {
        constructor(file: ComposerFileAttachment) : this(
            file.id,
            when (val status = file.status) {
                is ComposerFileAttachment.Status.Uploading -> FilePhase.Uploading
                is ComposerFileAttachment.Status.Uploaded -> FilePhase.Uploaded(status.path)
                is ComposerFileAttachment.Status.Failed -> FilePhase.Failed(status.message)
            },
        )
    }

    companion object {
        /** [project] is the draft's project, kept only without a session. */
        fun of(
            serverID: String,
            session: OpenCodeSession?,
            readsOnView: Boolean,
            draftProject: OpenCodeProject?,
            composer: DraftState,
        ) = PersistedComposerWatch(
            serverID = serverID,
            sessionID = session?.id,
            readsOnView = readsOnView,
            project = if (session == null) draftProject else null,
            text = composer.text,
            attachments = composer.attachments,
            files = composer.files.map(::FileItem),
            location = composer.location,
            chips = composer.chips,
            undoSnapshot = composer.undoSnapshot,
            quotes = composer.quotes,
        )
    }
}

/**
 * A draft that became a session moves its persisted draft there; otherwise the draft is scheduled
 * (or cleared when empty).
 */
fun ComposerPersistence.reconcilePersistedComposer(
    old: PersistedComposerWatch,
    current: PersistedComposerWatch,
    composer: DraftState,
) {
    if (!current.readsOnView) return
    val createdID = current.sessionID
    if (old.serverID == current.serverID && old.sessionID == null && createdID != null) {
        clearDraft(current.serverID, null, false)
        if (!composer.isEmpty) scheduleDraft(current.serverID, createdID, null, composer)
        return
    }
    if (old.serverID != current.serverID || old.sessionID != current.sessionID) return
    if (composer.isEmpty) {
        clearDraft(current.serverID, current.sessionID, false)
    } else {
        scheduleDraft(current.serverID, current.sessionID, current.project, composer)
    }
}

/** Where a retried draft send creates its session. */
fun persistedDraftDirectory(
    messageID: String,
    isRetry: Boolean,
    serverID: String,
    outbox: PromptOutbox,
    persistence: ComposerPersistence,
): String? {
    if (!isRetry) return null
    outbox.directory(messageID)?.takeIf { it.isNotEmpty() }?.let { return it }
    return persistence.loadOutbox(serverID, null).firstOrNull { it.pending.messageID == messageID }?.directory?.takeIf { it.isNotEmpty() }
}

/**
 * Schedules every on-screen composer that reads on view and isn't empty, then writes at once
 * (backgrounding).
 */
suspend fun ComposerPersistence.syncAndFlush(composers: List<Pair<PersistedComposerWatch, DraftState>>) {
    for ((watch, composer) in composers) {
        if (watch.readsOnView && !composer.isEmpty) scheduleDraft(watch.serverID, watch.sessionID, watch.project, composer)
    }
    flush()
}

/**
 * A new session whose first message hasn't reached the server, so only this phone has it. The
 * session list shows it until the send creates its session or the message is deleted.
 */
data class UnsentDraft(
    /** Its outbox lane (`SessionFeature.State.outboxDraftID`). */
    val draftID: String,
    /** The ID its session gets once the server creates it. */
    val sessionID: String,
    /** The first message's preview; null when it has no text. */
    val title: String?,
    val directory: String,
    val updatedAt: Instant,
    /** The send gave up and waits for Retry, Edit or Delete. */
    val isFailed: Boolean,
    val messageIDs: Set<String>,
)

/** [serverID]'s unsent new sessions, the newest first. */
fun ComposerPersistence.unsentDrafts(serverID: String): List<UnsentDraft> {
    val drafts = LinkedHashMap<String, UnsentDraft>()
    for (entry in loadOutbox(serverID, null)) {
        if (entry.createdSession != null || entry.sessionID != null) continue
        val draftID = entry.draftID ?: entry.pending.messageID
        val existing = drafts[draftID]
        if (existing == null) {
            drafts[draftID] = UnsentDraft(
                draftID = draftID,
                sessionID = entry.newSessionID,
                title = SessionTitle.preview(entry.pending.text),
                directory = entry.directory ?: "",
                updatedAt = entry.updatedAt,
                isFailed = entry.isHeld,
                messageIDs = setOf(entry.pending.messageID),
            )
        } else {
            drafts[draftID] = existing.copy(
                updatedAt = maxOf(existing.updatedAt, entry.updatedAt),
                isFailed = existing.isFailed || entry.isHeld,
                messageIDs = existing.messageIDs + entry.pending.messageID,
            )
        }
    }
    return drafts.values.sortedByDescending { it.updatedAt }
}

/** What [restorePersistedComposer] changed; the session reducer applies it to its state. */
data class PersistedComposerRestore(
    /** The draft to put in the composer, if one was restored. */
    val restoredDraft: RestoredComposerDraft?,
    val session: OpenCodeSession?,
    val draftProject: OpenCodeProject?,
    val outboxDraftID: String?,
    /** A draft whose outbox already created its session: the screen becomes that session. */
    val draftBecameSession: OpenCodeSession?,
)

/**
 * Binds the outbox and failed sends to persistence, restores the screen's draft, and seeds unsent
 * sends from a previous process into [PromptOutbox] (held ones also into [UndeliveredPrompts] as
 * “Couldn’t send message”), each under its original message and new-session IDs. Sends the
 * transcript already has ([existingMessageIDs]: prompt IDs in the items, less optimistic ones) are
 * settled. A draft restores only its own lane ([outboxDraftID]): a new draft starts blank, and the
 * others wait in the session list ([unsentDrafts]).
 *
 * [makeJob] is the session area's `restoredDeliveryJob`: the delivery job for the entry's
 * existing session ID, or a new session in `newSessionDirectory`, with `reconcileFirst: true`.
 */
fun restorePersistedComposer(
    serverID: String,
    readsOnView: Boolean,
    sessionComposer: DraftState,
    session: OpenCodeSession?,
    draftProject: OpenCodeProject?,
    projects: List<OpenCodeProject>,
    outboxDraftID: String?,
    existingMessageIDs: Set<String>,
    persistence: ComposerPersistence,
    outbox: PromptOutbox,
    undelivered: UndeliveredPrompts,
    makeJob: (entry: RestoredOutboxEntry, sessionID: String?, newSessionDirectory: String) -> PromptOutbox.Job,
): PersistedComposerRestore {
    outbox.bind(persistence)
    undelivered.bind(persistence)
    var currentSession = session
    var currentProject = draftProject
    var currentOutboxDraftID = outboxDraftID
    var became: OpenCodeSession? = null
    var restoredDraft: RestoredComposerDraft? = null
    var composer = sessionComposer
    if (readsOnView && sessionComposer.isEmpty && persistence.sharedDraft(serverID, session?.id) == null) {
        persistence.loadDraft(serverID, session?.id)?.let { restored ->
            restoredDraft = restored
            composer = restored.draft
            val project = restored.project
            if (currentSession == null && project != null) {
                currentProject = projects.firstOrNull { it.directory == project.directory } ?: project
            }
        }
    }
    val entries = persistence.loadOutbox(serverID, currentSession?.id)
    if (entries.isEmpty()) return PersistedComposerRestore(restoredDraft, currentSession, currentProject, currentOutboxDraftID, null)
    val settledIDs = HashSet<String>()
    for (entry in entries) {
        val id = entry.pending.messageID
        if (id in existingMessageIDs) {
            settledIDs += id
            continue
        }
        val alreadyInOutbox = outbox.contains(id)
        if (currentSession == null) {
            // A draft shows only its own sends: a new one starts blank.
            if (became == null && (entry.draftID ?: id) != currentOutboxDraftID) continue
            // The prompt it was created with names it until the server does.
            val firstPrompt = SessionTitle.preview(entry.pending.text)
            val created = if (alreadyInOutbox) null else entry.createdSession?.learningFirstPrompt(firstPrompt) ?: entry.sessionID?.let { sessionID ->
                OpenCodeSession(
                    id = sessionID,
                    serverTitle = SessionTitle.placeholder,
                    projectID = currentProject?.id ?: "",
                    directory = entry.directory ?: currentProject?.directory ?: "",
                    updatedAt = entry.updatedAt,
                    firstPrompt = firstPrompt,
                )
            }
            if (created != null) {
                became = created
                currentSession = created
                currentOutboxDraftID = null
                persistence.promoteDraftOutboxToSession(serverID, entry.draftID, created.id)
                val nilDraft = persistence.loadDraft(serverID, null)
                if (nilDraft != null) {
                    persistence.clearDraft(serverID, null, false)
                    if (persistence.loadDraft(serverID, created.id) == null) persistence.scheduleDraft(serverID, created.id, null, nilDraft.draft)
                } else if (!composer.isEmpty) {
                    persistence.clearDraft(serverID, null, false)
                    persistence.scheduleDraft(serverID, created.id, null, composer)
                }
            } else {
                if (entry.createdSession != null || entry.sessionID != null) continue
                val directory = entry.directory
                if (!directory.isNullOrEmpty()) currentProject = OpenCodeProject.folder(directory, projects)
            }
        } else if (entry.draftID != null) {
            persistence.promoteDraftOutboxToSession(serverID, entry.draftID, currentSession.id)
        }
        val effectiveSessionID = currentSession?.id
        if (entry.isHeld) {
            val existing = undelivered.entries(serverID, effectiveSessionID)
            if (existing.none { it.pending.messageID == id }) {
                undelivered.keep(entry.pending, localized(R.string.composer_send_failed), serverID, effectiveSessionID)
            }
        }
        if (!alreadyInOutbox) {
            val targetSessionID = entry.sessionID ?: entry.createdSession?.id ?: currentSession?.id
            val job = makeJob(entry, targetSessionID, entry.directory ?: currentProject?.directory ?: "")
            outbox.seedPersisted(entry, job)
        }
    }
    if (settledIDs.isNotEmpty()) {
        persistence.settlePrompts(settledIDs)
        undelivered.resolve(settledIDs)
        outbox.settle(settledIDs)
    }
    return PersistedComposerRestore(restoredDraft, currentSession, currentProject, currentOutboxDraftID, became)
}

/**
 * A session family's drafts: read and written through [ComposerPersistence] when the screen reads
 * on view, else kept in [catchUpDrafts] (a Catch Up card, whose drafts persist only once promoted).
 */
class FamilyDrafts(
    val serverID: String,
    val readsOnView: Boolean,
    catchUpDrafts: Map<String, DraftState>,
    private val persistence: ComposerPersistence,
) {
    var catchUpDrafts: Map<String, DraftState> = catchUpDrafts
        private set

    operator fun get(sessionID: String): DraftState? {
        if (!readsOnView) return catchUpDrafts[sessionID]
        if (persistence.sharedDraft(serverID, sessionID) != null) return null
        return persistence.loadDraft(serverID, sessionID)?.draft
    }

    operator fun set(sessionID: String, value: DraftState?) {
        if (!readsOnView) {
            catchUpDrafts = if (value != null && !value.isEmpty) catchUpDrafts + (sessionID to value) else catchUpDrafts - sessionID
            return
        }
        if (value != null && !value.isEmpty) {
            persistence.scheduleDraft(serverID, sessionID, null, value)
        } else {
            persistence.clearDraft(serverID, sessionID, false)
        }
    }

    fun getOrDefault(sessionID: String, defaultValue: () -> DraftState): DraftState = get(sessionID) ?: defaultValue()

    val keys: List<String>
        get() = if (!readsOnView) catchUpDrafts.keys.toList() else persistence.draftSessionIDs(serverID)
}

/**
 * Shares waiting for a composer, by target, read and written through [ComposerPersistence].
 * [liveTargets] are composers on screen with a draft, whose persisted draft isn't read back as a
 * share.
 */
class SharedDrafts(
    val serverID: String,
    val projects: List<OpenCodeProject>,
    val liveTargets: Set<SharedDraftTarget>,
    private val persistence: ComposerPersistence,
) {
    val isEmpty: Boolean get() = persistence.sharedDrafts(serverID).isEmpty()

    val keys: Set<SharedDraftTarget> get() = persistence.sharedDrafts(serverID).keys

    operator fun get(target: SharedDraftTarget): SharedComposerDraft? =
        persistence.sharedDraft(serverID, target.sessionID)
            ?: if (target in liveTargets) null else persistence.loadDraft(serverID, target.sessionID)?.let(::SharedComposerDraft)

    operator fun set(target: SharedDraftTarget, value: SharedComposerDraft?) {
        val project = if (target == SharedDraftTarget.NewSession) {
            value?.projectID?.let { id -> projects.firstOrNull { it.id == id } }
        } else {
            null
        }
        persistence.setSharedDraft(serverID, target.sessionID, project, value)
    }

    /** What a new share appends to. */
    fun getOrDefault(target: SharedDraftTarget, defaultValue: () -> SharedComposerDraft): SharedComposerDraft {
        val existing = persistence.sharedDraftForAppend(serverID, target.sessionID, target in liveTargets)
        return if (existing.isEmpty) defaultValue() else existing
    }

    fun removeValue(target: SharedDraftTarget): SharedComposerDraft? = persistence.takeSharedDraft(serverID, target.sessionID)
}

/** A New Session draft keeps its project. */
fun ComposerPersistence.parkPersistedDraft(serverID: String, composer: DraftState, sessionID: String?, draftProject: OpenCodeProject?) =
    parkDraft(serverID, sessionID, if (sessionID == null) draftProject else null, composer)

/** Syncs the absorbed draft for a detail that reads on view (and no Catch Up is open). */
fun ComposerPersistence.syncAbsorbedDraft(serverID: String, session: OpenCodeSession?, draftProject: OpenCodeProject?, composer: DraftState) {
    if (composer.isEmpty) {
        clearDraft(serverID, session?.id, false)
    } else {
        scheduleDraft(serverID, session?.id, if (session == null) draftProject else null, composer)
    }
}
