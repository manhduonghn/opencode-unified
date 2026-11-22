package ai.opencode.feature.composer

import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * The debounced writer behind [ComposerPersistence]: keeps the decoded state in memory, and writes
 * it [debounce] after the last change (typing never waits on disk), or at once on [flush]
 * (backgrounding).
 */
internal class ComposerPersistenceWriter(
    private val storage: ComposerPersistenceStorage,
    private val debounce: Duration,
    private val now: () -> Instant,
) {
    private data class DraftKey(val serverID: String, val sessionID: String?)

    private class WriterState {
        var loaded: PersistedComposerState? = null
        var dirty = false
        var generation = 0
        var pendingTask: Job? = null
        val attachmentCache = HashMap<UUID, ByteArray>()
        val pendingAttachmentWrites = HashMap<UUID, ByteArray>()
        val storedAttachmentIDs = HashSet<UUID>()
        var cleanedAttachmentIDs: Set<UUID>? = null
        val inMemoryFiles = HashMap<DraftKey, List<ComposerFileAttachment>>()
        val waitingShares = HashMap<DraftKey, SharedComposerDraft>()
    }

    private class Snapshot(
        val diskState: PersistedComposerState,
        val referenced: Set<UUID>,
        val writes: Map<UUID, ByteArray>,
        val needsCleaning: Boolean,
    )

    private val lock = Any()
    private val flushing = Any()
    private var state = WriterState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboxRevision = MutableStateFlow(0)

    /** Emits each time the unsent outbox changes. */
    val outboxChanges: Flow<Unit> get() = outboxRevision.drop(1).map { }

    fun loadDraft(serverID: String, sessionID: String?): RestoredComposerDraft? =
        synchronized(lock) { loadDraftLocked(serverID, sessionID) }

    fun draftSessionIDs(serverID: String): List<String> = synchronized(lock) {
        val current = ensureLoaded()
        val ids = LinkedHashSet<String>()
        for (record in current.drafts) if (record.serverID == serverID) record.sessionID?.let(ids::add)
        for (key in state.inMemoryFiles.keys) if (key.serverID == serverID) key.sessionID?.let(ids::add)
        ids.toList()
    }

    fun sharedDrafts(serverID: String): Map<SharedDraftTarget, SharedComposerDraft> = synchronized(lock) {
        ensureLoaded()
        state.waitingShares.filterKeys { it.serverID == serverID }.mapKeys { SharedDraftTarget(it.key.sessionID) }
    }

    fun sharedDraft(serverID: String, sessionID: String?): SharedComposerDraft? = synchronized(lock) {
        ensureLoaded()
        state.waitingShares[DraftKey(serverID, sessionID)]
    }

    fun sharedDraftForAppend(serverID: String, sessionID: String?, isLiveOnScreen: Boolean): SharedComposerDraft = synchronized(lock) {
        state.waitingShares[DraftKey(serverID, sessionID)]?.let { return it }
        if (!isLiveOnScreen) loadDraftLocked(serverID, sessionID)?.let { return SharedComposerDraft(it) }
        SharedComposerDraft()
    }

    fun setSharedDraft(serverID: String, sessionID: String?, project: OpenCodeProject?, shared: SharedComposerDraft?) {
        val generation = synchronized(lock) {
            val key = DraftKey(serverID, sessionID)
            if (shared == null || shared.isEmpty) {
                state.waitingShares.remove(key)
                return@synchronized null
            }
            val draft = shared.draftState
            val generation = if (draft.isEmpty) null else upsertDraftLocked(serverID, sessionID, project, draft)
            state.waitingShares[key] = shared
            generation
        }
        generation?.let(::scheduleWrite)
    }

    fun takeSharedDraft(serverID: String, sessionID: String?): SharedComposerDraft? = synchronized(lock) {
        ensureLoaded()
        state.waitingShares.remove(DraftKey(serverID, sessionID))
    }

    fun parkDraft(serverID: String, sessionID: String?, project: OpenCodeProject?, composer: DraftState) {
        val generation = synchronized(lock) {
            ensureLoaded()
            val key = DraftKey(serverID, sessionID)
            val waiting = state.waitingShares.remove(key)
            if (composer.isEmpty) {
                if (waiting != null && !waiting.isEmpty) {
                    val draft = waiting.draftState
                    val generation = if (draft.isEmpty) null else upsertDraftLocked(serverID, sessionID, project, draft)
                    state.waitingShares[key] = waiting
                    return@synchronized generation
                }
                return@synchronized clearDraftLocked(serverID, sessionID)
            }
            var merged = SharedComposerDraft(composer, project?.id)
            if (waiting != null) merged = merged.appending(waiting)
            var mergedDraft = merged.draftState
            val snapshot = composer.undoSnapshot
            if (snapshot != null && mergedDraft.text == snapshot.restoredDraft) mergedDraft = mergedDraft.copy(undoSnapshot = snapshot)
            val generation = upsertDraftLocked(serverID, sessionID, project, mergedDraft)
            if (waiting != null) state.waitingShares[key] = merged
            generation
        }
        generation?.let(::scheduleWrite)
    }

    fun scheduleDraft(serverID: String, sessionID: String?, project: OpenCodeProject?, draft: DraftState) {
        if (draft.isEmpty) {
            clearDraft(serverID, sessionID)
            return
        }
        synchronized(lock) { upsertDraftLocked(serverID, sessionID, project, draft) }?.let(::scheduleWrite)
    }

    fun clearDraft(serverID: String, sessionID: String?) {
        synchronized(lock) { clearDraftLocked(serverID, sessionID) }?.let(::scheduleWrite)
    }

    fun loadOutbox(serverID: String, sessionID: String?): List<RestoredOutboxEntry> = synchronized(lock) {
        val current = ensureLoaded()
        current.outbox.mapNotNull { record ->
            if (record.serverID != serverID) return@mapNotNull null
            if (sessionID != null) {
                if (record.sessionID != sessionID && record.createdSession?.id != sessionID) return@mapNotNull null
            } else {
                if (record.sessionID != null && record.draftID == null) return@mapNotNull null
            }
            makeRestoredOutboxEntry(record)
        }
    }

    fun recordOutbox(
        serverID: String,
        sessionID: String?,
        draftID: String?,
        directory: String?,
        newSessionID: String,
        createdSession: OpenCodeSession?,
        pending: PendingPrompt,
        isHeld: Boolean,
        backgroundKey: String?,
    ) {
        val timestamp = now()
        val outboxRecord = PersistedComposerState.OutboxRecord(
            serverID = serverID,
            sessionID = sessionID,
            draftID = if (sessionID == null) draftID else null,
            directory = directory,
            newSessionID = newSessionID,
            createdSession = createdSession,
            pending = makePendingPromptRecord(pending),
            isHeld = isHeld,
            backgroundKey = backgroundKey,
            updatedAt = timestamp,
        )
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            cacheAttachments(pending.attachments)
            val outbox = current.outbox.toMutableList()
            val index = outbox.indexOfFirst { it.pending.messageID == pending.messageID }
            if (index >= 0) {
                val existing = outbox[index]
                var merged = outboxRecord.copy(
                    directory = outboxRecord.directory ?: existing.directory,
                    createdSession = outboxRecord.createdSession ?: existing.createdSession,
                    sessionID = outboxRecord.sessionID ?: existing.sessionID,
                )
                merged = if (merged.sessionID != null) merged.copy(draftID = null) else merged.copy(draftID = merged.draftID ?: existing.draftID)
                if (existing == merged) return@synchronized null
                outbox[index] = merged
            } else {
                outbox += outboxRecord
            }
            commitLocked(current.copy(outbox = outbox))
        }
        generation?.let(::scheduleWrite)
    }

    fun updateOutboxDelivery(messageID: String, delivery: OpenCodeDelivery, backgroundKey: String?) {
        val timestamp = now()
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            val index = current.outbox.indexOfFirst { it.pending.messageID == messageID }
            if (index < 0) return@synchronized null
            val record = current.outbox[index]
            if (record.pending.delivery == delivery && record.backgroundKey == backgroundKey) return@synchronized null
            val outbox = current.outbox.toMutableList()
            outbox[index] = record.copy(pending = record.pending.copy(delivery = delivery), backgroundKey = backgroundKey, updatedAt = timestamp)
            commitLocked(current.copy(outbox = outbox))
        }
        generation?.let(::scheduleWrite)
    }

    fun noteDraftSessionCreated(serverID: String, draftID: String?, session: OpenCodeSession) {
        val timestamp = now()
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            var changed = false
            val outbox = current.outbox.map { record ->
                if (record.serverID != serverID) return@map record
                val matches = if (draftID != null) {
                    record.draftID == draftID || record.pending.messageID == draftID
                } else {
                    record.sessionID == null
                }
                if (!matches) return@map record
                changed = true
                record.copy(createdSession = session, sessionID = session.id, updatedAt = timestamp)
            }
            val draftMessageIDs = outbox.filter { it.serverID == serverID && it.sessionID == session.id }.map { it.pending.messageID }.toSet()
            val undelivered = current.undelivered.map { record ->
                if (record.serverID != serverID || record.sessionID != null || record.pending.messageID !in draftMessageIDs) return@map record
                changed = true
                record.copy(sessionID = session.id, updatedAt = timestamp)
            }
            if (!changed) return@synchronized null
            commitLocked(current.copy(outbox = outbox, undelivered = undelivered))
        }
        generation?.let(::scheduleWrite)
    }

    fun promoteDraftOutboxToSession(serverID: String, draftID: String?, sessionID: String) {
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            var changed = false
            val outbox = current.outbox.map { record ->
                val matches = record.serverID == serverID && (
                    record.sessionID == sessionID ||
                        record.createdSession?.id == sessionID ||
                        (draftID != null && (record.draftID == draftID || record.pending.messageID == draftID))
                    )
                if (!matches) return@map record
                if (draftID != null && record.draftID != null && record.draftID != draftID) return@map record
                if (record.draftID != null || record.sessionID != sessionID) {
                    changed = true
                    record.copy(draftID = null, sessionID = sessionID)
                } else {
                    record
                }
            }
            if (!changed) return@synchronized null
            commitLocked(current.copy(outbox = outbox))
        }
        generation?.let(::scheduleWrite)
    }

    fun loadUndelivered(serverID: String, sessionID: String?): List<UndeliveredPrompts.Entry> = synchronized(lock) {
        val current = ensureLoaded()
        current.undelivered.mapNotNull { record ->
            if (record.serverID != serverID || record.sessionID != sessionID) return@mapNotNull null
            UndeliveredPrompts.Entry(restorePendingPrompt(record.pending), record.reason)
        }
    }

    fun markUndelivered(pending: PendingPrompt, reason: String, serverID: String, sessionID: String?) {
        val timestamp = now()
        val record = PersistedComposerState.UndeliveredRecord(
            serverID = serverID,
            sessionID = sessionID,
            pending = makePendingPromptRecord(pending),
            reason = reason,
            updatedAt = timestamp,
        )
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            cacheAttachments(pending.attachments)
            val undelivered = current.undelivered.filterNot { it.pending.messageID == pending.messageID } + record
            val outbox = current.outbox.toMutableList()
            val outboxIndex = outbox.indexOfFirst { it.pending.messageID == pending.messageID }
            if (outboxIndex >= 0) {
                val existing = outbox[outboxIndex]
                outbox[outboxIndex] = existing.copy(isHeld = true, sessionID = sessionID ?: existing.sessionID, updatedAt = timestamp)
            }
            commitLocked(current.copy(outbox = outbox, undelivered = undelivered))
        }
        scheduleWrite(generation)
    }

    fun settlePrompts(messageIDs: Set<String>) {
        if (messageIDs.isEmpty()) return
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            val outbox = current.outbox.filterNot { it.pending.messageID in messageIDs }
            val undelivered = current.undelivered.filterNot { it.pending.messageID in messageIDs }
            if (outbox.size == current.outbox.size && undelivered.size == current.undelivered.size) return@synchronized null
            commitLocked(current.copy(outbox = outbox, undelivered = undelivered))
        }
        generation?.let(::scheduleWrite)
    }

    fun loadSessionState(serverID: String, sessionID: String): PersistedSessionState? = synchronized(lock) {
        ensureLoaded().sessionStates?.firstOrNull { it.serverID == serverID && it.sessionID == sessionID }?.state
    }

    fun scheduleSessionState(serverID: String, sessionID: String, update: PersistedSessionState.Update) {
        val timestamp = now()
        val generation = synchronized(lock) {
            val next = ensureLoaded().applySessionState(update, serverID, sessionID, timestamp) ?: return@synchronized null
            commitLocked(next)
        }
        generation?.let(::scheduleWrite)
    }

    fun removeSession(serverID: String, sessionID: String?) {
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            val key = DraftKey(serverID, sessionID)
            state.inMemoryFiles.remove(key)
            state.waitingShares.remove(key)
            val drafts = current.drafts.filterNot { it.serverID == serverID && it.sessionID == sessionID }
            val outbox = current.outbox.filterNot {
                it.serverID == serverID && (it.sessionID == sessionID || (it.createdSession?.id != null && it.createdSession.id == sessionID))
            }
            val undelivered = current.undelivered.filterNot { it.serverID == serverID && it.sessionID == sessionID }
            val removedState = current.removeSessionStates { it.serverID == serverID && it.sessionID == sessionID }
            if (drafts.size == current.drafts.size && outbox.size == current.outbox.size && undelivered.size == current.undelivered.size &&
                removedState == null
            ) {
                return@synchronized null
            }
            commitLocked((removedState ?: current).copy(drafts = drafts, outbox = outbox, undelivered = undelivered))
        }
        generation?.let(::scheduleWrite)
    }

    fun removeServer(serverID: String) {
        val generation = synchronized(lock) {
            val current = ensureLoaded()
            state.inMemoryFiles.keys.removeAll { it.serverID == serverID }
            state.waitingShares.keys.removeAll { it.serverID == serverID }
            val drafts = current.drafts.filterNot { it.serverID == serverID }
            val outbox = current.outbox.filterNot { it.serverID == serverID }
            val undelivered = current.undelivered.filterNot { it.serverID == serverID }
            val removedStates = current.removeSessionStates { it.serverID == serverID }
            if (drafts.size == current.drafts.size && outbox.size == current.outbox.size && undelivered.size == current.undelivered.size &&
                removedStates == null
            ) {
                return@synchronized null
            }
            commitLocked((removedStates ?: current).copy(drafts = drafts, outbox = outbox, undelivered = undelivered))
        }
        generation?.let(::scheduleWrite)
    }

    fun flush() {
        val task = synchronized(lock) { state.pendingTask.also { state.pendingTask = null } }
        task?.cancel()
        synchronized(flushing) {
            val snapshot = synchronized(lock) {
                val current = state.loaded
                if (!state.dirty || current == null) return
                takeSnapshot(current)
            }
            persist(snapshot)
        }
    }

    fun removeAll() {
        val task = synchronized(lock) {
            val task = state.pendingTask
            state = WriterState().also { it.loaded = PersistedComposerState() }
            outboxRevision.value += 1
            task
        }
        task?.cancel()
        storage.removeAll()
    }

    fun retainedAttachmentBytes(): Int = synchronized(lock) {
        var total = 0
        for (data in state.attachmentCache.values) total += data.size
        total
    }

    // Private helpers (call with the lock held)

    private fun ensureLoaded(): PersistedComposerState {
        state.loaded?.let { return it }
        val decoded = storage.readStateData()?.let { data ->
            runCatching { PersistedComposerState.json.decodeFromString(PersistedComposerState.serializer(), data.decodeToString()) }.getOrNull()
        } ?: PersistedComposerState()
        val pruned = decoded.prunedStale(now())
        val current = pruned ?: decoded
        state.loaded = current
        if (pruned != null) persist(takeSnapshot(current))
        return current
    }

    private fun commitLocked(next: PersistedComposerState): Int {
        if (state.loaded?.outbox != next.outbox) outboxRevision.value += 1
        state.loaded = next
        state.dirty = true
        state.generation += 1
        return state.generation
    }

    private fun loadDraftLocked(serverID: String, sessionID: String?): RestoredComposerDraft? {
        val current = ensureLoaded()
        val inMemoryFiles = state.inMemoryFiles[DraftKey(serverID, sessionID)]
        val record = current.drafts.firstOrNull { it.serverID == serverID && it.sessionID == sessionID }
        if (record != null) {
            val draft = DraftState(
                text = record.text,
                attachments = record.attachments.mapNotNull(::restoreAttachment),
                files = inMemoryFiles ?: record.files.map(::restoreUploadedFile),
                location = record.location,
                chips = record.chips,
                undoSnapshot = record.undoSnapshot,
                quotes = record.quotes,
            )
            if (draft.isEmpty) return null
            return RestoredComposerDraft(record.project, draft, record.updatedAt)
        }
        if (inMemoryFiles.isNullOrEmpty()) return null
        return RestoredComposerDraft(null, DraftState(files = inMemoryFiles), now())
    }

    private fun upsertDraftLocked(serverID: String, sessionID: String?, project: OpenCodeProject?, draft: DraftState): Int? {
        val current = ensureLoaded()
        val key = DraftKey(serverID, sessionID)
        state.waitingShares.remove(key)
        if (draft.files.isEmpty()) state.inMemoryFiles.remove(key) else state.inMemoryFiles[key] = draft.files
        val timestamp = now()
        val attachmentRecords = draft.attachments.map(PersistedComposerState::AttachmentRecord)
        val fileRecords = draft.files.mapNotNull(PersistedComposerState.UploadedFileRecord::of)
        val drafts = current.drafts.toMutableList()
        val index = drafts.indexOfFirst { it.serverID == serverID && it.sessionID == sessionID }
        if (index >= 0) {
            val existing = drafts[index]
            val effective = PersistedComposerState.DraftRecord(
                serverID = serverID,
                sessionID = sessionID,
                project = if (sessionID == null) project ?: existing.project else null,
                text = draft.text,
                attachments = attachmentRecords,
                files = fileRecords,
                location = draft.location,
                chips = draft.chips,
                undoSnapshot = draft.undoSnapshot,
                updatedAt = timestamp,
                quotes = draft.quotes,
            )
            if (existing.copy(updatedAt = timestamp) == effective) return null
            drafts[index] = effective
        } else {
            val record = PersistedComposerState.DraftRecord(
                serverID = serverID,
                sessionID = sessionID,
                project = if (sessionID == null) project else null,
                text = draft.text,
                attachments = attachmentRecords,
                files = fileRecords,
                location = draft.location,
                chips = draft.chips,
                undoSnapshot = draft.undoSnapshot,
                updatedAt = timestamp,
                quotes = draft.quotes,
            )
            if (!record.hasPersistedContent) return null
            drafts += record
        }
        cacheAttachments(draft.attachments)
        return commitLocked(current.copy(drafts = drafts))
    }

    private fun clearDraftLocked(serverID: String, sessionID: String?): Int? {
        val current = ensureLoaded()
        val key = DraftKey(serverID, sessionID)
        state.inMemoryFiles.remove(key)
        state.waitingShares.remove(key)
        val index = current.drafts.indexOfFirst { it.serverID == serverID && it.sessionID == sessionID }
        if (index < 0) return null
        val removed = current.drafts[index]
        val next = current.copy(drafts = current.drafts.filterIndexed { i, _ -> i != index })
        state.loaded = next
        if (!removed.hasPersistedContent) return null
        state.dirty = true
        state.generation += 1
        return state.generation
    }

    private fun cacheAttachments(attachments: List<OpenCodeOutgoingAttachment>) {
        for (attachment in attachments) {
            // A `media:<hash>` reference re-sent without its bytes has nothing to cache.
            if (attachment.data.isEmpty()) continue
            val id = PersistedComposerState.attachmentUUID(attachment.id)
            state.attachmentCache[id] = attachment.data
            if (id !in state.storedAttachmentIDs) state.pendingAttachmentWrites[id] = attachment.data
        }
    }

    private fun scheduleWrite(generation: Int) {
        synchronized(lock) {
            if (state.generation != generation) return
            state.pendingTask?.cancel()
            state.pendingTask = scope.launch {
                delay(debounce)
                flushIfGenerationMatches(generation)
            }
        }
    }

    private fun flushIfGenerationMatches(generation: Int) {
        synchronized(flushing) {
            val snapshot = synchronized(lock) {
                val current = state.loaded
                if (!state.dirty || state.generation != generation || current == null) return
                state.pendingTask = null
                takeSnapshot(current)
            }
            persist(snapshot)
        }
    }

    private fun takeSnapshot(current: PersistedComposerState): Snapshot {
        state.dirty = false
        val diskState = current.copy(drafts = current.drafts.filter { it.hasPersistedContent })
        val referenced = diskState.referencedAttachmentIDs
        return Snapshot(
            diskState = diskState,
            referenced = referenced,
            writes = state.pendingAttachmentWrites.filterKeys { it in referenced },
            needsCleaning = state.cleanedAttachmentIDs != referenced,
        )
    }

    private fun persist(snapshot: Snapshot) {
        val written = HashSet<UUID>()
        for ((id, data) in snapshot.writes) {
            if (storage.writeAttachment(id, data)) written += id
        }
        var saved = written.size == snapshot.writes.size
        if (saved && snapshot.diskState.isEmpty) {
            saved = storage.writeStateData(null)
        } else if (saved) {
            val encoded = runCatching { PersistedComposerState.json.encodeToString(PersistedComposerState.serializer(), snapshot.diskState) }.getOrNull()
            saved = encoded != null && storage.writeStateData(encoded.toByteArray(Charsets.UTF_8))
        }
        if (saved && snapshot.needsCleaning) storage.removeAttachments(snapshot.referenced)

        synchronized(lock) {
            state.storedAttachmentIDs += written
            state.pendingAttachmentWrites.keys -= written
            if (!saved) {
                state.dirty = true
                return
            }
            if (snapshot.needsCleaning) {
                state.cleanedAttachmentIDs = snapshot.referenced
                state.storedAttachmentIDs.retainAll(snapshot.referenced)
            }
            val referenced = state.loaded?.referencedAttachmentIDs ?: emptySet()
            state.attachmentCache.keys.retainAll(referenced)
            state.pendingAttachmentWrites.keys.retainAll(referenced)
            for (id in referenced) {
                if (id in state.storedAttachmentIDs || id in state.pendingAttachmentWrites) continue
                val data = state.attachmentCache[id] ?: continue
                state.pendingAttachmentWrites[id] = data
                state.dirty = true
            }
        }
    }

    private fun makePendingPromptRecord(pending: PendingPrompt) = PersistedComposerState.PendingPromptRecord(
        messageID = pending.messageID,
        text = pending.text,
        attachments = pending.attachments.map(PersistedComposerState::AttachmentRecord),
        files = pending.files.mapNotNull(PersistedComposerState.UploadedFileRecord::of),
        wasRunning = pending.wasRunning,
        delivery = pending.delivery,
        requestEpoch = pending.requestEpoch,
        model = pending.model,
        page = pending.page,
        mentions = pending.mentions,
        chips = pending.chips,
        quotes = pending.quotes,
    )

    private fun makeRestoredOutboxEntry(record: PersistedComposerState.OutboxRecord) = RestoredOutboxEntry(
        serverID = record.serverID,
        sessionID = record.sessionID,
        draftID = record.draftID,
        directory = record.directory,
        newSessionID = record.newSessionID,
        createdSession = record.createdSession,
        pending = restorePendingPrompt(record.pending),
        isHeld = record.isHeld,
        backgroundKey = record.backgroundKey,
        updatedAt = record.updatedAt,
    )

    private fun restorePendingPrompt(record: PersistedComposerState.PendingPromptRecord) = PendingPrompt(
        messageID = record.messageID,
        text = record.text,
        attachments = record.attachments.mapNotNull(::restoreAttachment),
        files = record.files.map(::restoreUploadedFile),
        wasRunning = record.wasRunning,
        delivery = record.delivery,
        requestEpoch = record.requestEpoch,
        model = record.model,
        page = record.page,
        mentions = record.mentions,
        chips = record.chips,
        quotes = record.quotes,
    )

    private fun restoreAttachment(record: PersistedComposerState.AttachmentRecord): OpenCodeOutgoingAttachment? {
        val data = state.attachmentCache[record.id]
            ?: storage.readAttachment(record.id)?.also {
                state.attachmentCache[record.id] = it
                state.storedAttachmentIDs += record.id
            }
            ?: if (record.mediaHash != null) ByteArray(0) else return null
        val pastedText = if (record.hasPastedText && data.isNotEmpty()) runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
        }.getOrNull() else null
        return OpenCodeOutgoingAttachment(
            id = record.id.toString(),
            data = data,
            mime = record.mime,
            name = record.name,
            pastedText = pastedText,
            pasted = record.pasted,
            mediaHash = record.mediaHash,
            byteCount = record.byteCount,
            videoDuration = record.videoDuration,
            localVideoURI = record.localVideoURI,
        )
    }

    /** An uploaded file never reads it again. */
    private fun restoreUploadedFile(record: PersistedComposerState.UploadedFileRecord) = ComposerFileAttachment(
        ComposerStagedFile(
            id = record.id,
            name = record.name,
            mime = record.mime,
            size = record.size,
            url = File(System.getProperty("java.io.tmpdir") ?: "/tmp", record.name),
        ),
        status = ComposerFileAttachment.Status.Uploaded(record.uploadedPath),
    )
}
