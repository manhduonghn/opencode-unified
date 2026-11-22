package ai.opencode.feature.composer

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.voice.VoiceLabEnvironment
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Typed drafts, unsent outbox lanes and failed sends that outlive the process, keyed by server
 * persistence ID and session ID (null: the New Session draft). Typing is debounced (500 ms) and
 * written at once by [flush]. Entries untouched for 30 days, or of a deleted session or server, are
 * removed with their blobs. Draft text is never logged.
 */
class ComposerPersistence(
    val identity: String = "",
    val loadDraft: (serverID: String, sessionID: String?) -> RestoredComposerDraft? = { _, _ -> null },
    val scheduleDraft: (serverID: String, sessionID: String?, project: OpenCodeProject?, draft: DraftState) -> Unit = { _, _, _, _ -> },
    val clearDraft: (serverID: String, sessionID: String?, discardFiles: Boolean) -> Unit = { _, _, _ -> },
    val draftSessionIDs: (serverID: String) -> List<String> = { emptyList() },
    val sharedDrafts: (serverID: String) -> Map<SharedDraftTarget, SharedComposerDraft> = { emptyMap() },
    val sharedDraft: (serverID: String, sessionID: String?) -> SharedComposerDraft? = { _, _ -> null },
    val sharedDraftForAppend: (serverID: String, sessionID: String?, isLiveOnScreen: Boolean) -> SharedComposerDraft = { _, _, _ -> SharedComposerDraft() },
    val setSharedDraft: (serverID: String, sessionID: String?, project: OpenCodeProject?, shared: SharedComposerDraft?) -> Unit = { _, _, _, _ -> },
    val takeSharedDraft: (serverID: String, sessionID: String?) -> SharedComposerDraft? = { _, _ -> null },
    val parkDraft: (serverID: String, sessionID: String?, project: OpenCodeProject?, composer: DraftState) -> Unit = { _, _, _, _ -> },
    val loadOutbox: (serverID: String, sessionID: String?) -> List<RestoredOutboxEntry> = { _, _ -> emptyList() },
    val recordOutbox: (
        serverID: String,
        sessionID: String?,
        draftID: String?,
        directory: String?,
        newSessionID: String,
        createdSession: OpenCodeSession?,
        pending: PendingPrompt,
        isHeld: Boolean,
        backgroundKey: String?,
    ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    val updateOutboxDelivery: (messageID: String, delivery: OpenCodeDelivery, backgroundKey: String?) -> Unit = { _, _, _ -> },
    val noteDraftSessionCreated: (serverID: String, draftID: String?, session: OpenCodeSession) -> Unit = { _, _, _ -> },
    val promoteDraftOutboxToSession: (serverID: String, draftID: String?, sessionID: String) -> Unit = { _, _, _ -> },
    val loadUndelivered: (serverID: String, sessionID: String?) -> List<UndeliveredPrompts.Entry> = { _, _ -> emptyList() },
    val markUndelivered: (pending: PendingPrompt, reason: String, serverID: String, sessionID: String?) -> Unit = { _, _, _, _ -> },
    val settlePrompts: (messageIDs: Set<String>) -> Unit = {},
    /** Emits each time the unsent outbox changes. */
    val outboxChanges: Flow<Unit> = emptyFlow(),
    /** A session's model pick and request answers saved before a relaunch ([SessionStatePersistence.kt]). */
    val loadSessionState: (serverID: String, sessionID: String) -> PersistedSessionState? = { _, _ -> null },
    val scheduleSessionState: (serverID: String, sessionID: String, update: PersistedSessionState.Update) -> Unit = { _, _, _ -> },
    val removeSession: (serverID: String, sessionID: String?) -> Unit = { _, _ -> },
    val removeServer: (serverID: String) -> Unit = {},
    val flush: suspend () -> Unit = {},
    val removeAll: () -> Unit = {},
    val retainedAttachmentBytes: () -> Int = { 0 },
) {
    companion object Key : DependencyKey<ComposerPersistence> {
        /** `filesDir/support`, where `TranscriptCachePersistence` also writes. */
        fun defaultDirectory(): File = File(dependency(AppContext).applicationContext.filesDir, "support")

        override val liveValue: ComposerPersistence by lazy {
            // A lab server run keeps drafts in memory unless asked to persist them.
            if (VoiceLabEnvironment.string("OPENCODE_LAB_SERVER_URL") != null && !VoiceLabEnvironment.flag("OPENCODE_LAB_PERSIST_COMPOSER")) {
                return@lazy inMemory()
            }
            if (VoiceLabEnvironment.flag("OPENCODE_LAB_RESET_COMPOSER_PERSISTENCE")) {
                File(defaultDirectory(), "composer-state.json").delete()
                File(defaultDirectory(), "ComposerAttachments").deleteRecursively()
            }
            make(ComposerPersistenceStorage.live(defaultDirectory()))
        }

        override val testValue: ComposerPersistence get() = inMemory()

        fun inMemory(debounce: Duration = 500.milliseconds, now: () -> Instant = { Instant.now() }): ComposerPersistence =
            make(ComposerPersistenceStorage.inMemory(), debounce, now)

        fun make(
            storage: ComposerPersistenceStorage,
            debounce: Duration = 500.milliseconds,
            now: () -> Instant = { Instant.now() },
        ): ComposerPersistence {
            val writer = ComposerPersistenceWriter(storage, debounce, now)
            return ComposerPersistence(
                identity = storage.identity,
                loadDraft = writer::loadDraft,
                scheduleDraft = writer::scheduleDraft,
                clearDraft = { serverID, sessionID, _ -> writer.clearDraft(serverID, sessionID) },
                draftSessionIDs = writer::draftSessionIDs,
                sharedDrafts = writer::sharedDrafts,
                sharedDraft = writer::sharedDraft,
                sharedDraftForAppend = writer::sharedDraftForAppend,
                setSharedDraft = writer::setSharedDraft,
                takeSharedDraft = writer::takeSharedDraft,
                parkDraft = writer::parkDraft,
                loadOutbox = writer::loadOutbox,
                recordOutbox = writer::recordOutbox,
                updateOutboxDelivery = writer::updateOutboxDelivery,
                noteDraftSessionCreated = writer::noteDraftSessionCreated,
                promoteDraftOutboxToSession = writer::promoteDraftOutboxToSession,
                loadUndelivered = writer::loadUndelivered,
                markUndelivered = writer::markUndelivered,
                settlePrompts = writer::settlePrompts,
                outboxChanges = writer.outboxChanges,
                loadSessionState = writer::loadSessionState,
                scheduleSessionState = writer::scheduleSessionState,
                removeSession = writer::removeSession,
                removeServer = writer::removeServer,
                // The write never blocks the caller's thread.
                flush = { withContext(Dispatchers.IO) { writer.flush() } },
                removeAll = writer::removeAll,
                retainedAttachmentBytes = writer::retainedAttachmentBytes,
            )
        }
    }
}
