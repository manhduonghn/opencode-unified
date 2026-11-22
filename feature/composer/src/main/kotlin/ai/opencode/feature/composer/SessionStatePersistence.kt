package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * What a session keeps across relaunch besides its composer: the model picked for its next send,
 * and the pending requests' answers in progress.
 */
@Serializable
data class PersistedSessionState(
    val pendingModel: OpenCodeModelRef? = null,
    // These keys are required: decoding ignores property defaults.
    @Required val requests: RequestAnswers = RequestAnswers(),
) {
    @Serializable
    data class RequestAnswers(
        @Required val formAnswers: Map<String, Map<String, OpenCodeFormValue>> = emptyMap(),
        /** The bar's words per request or page (`answerDrafts`). */
        @Required val answerDrafts: Map<String, String> = emptyMap(),
        @Required val questionPageIndices: Map<String, Int> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = formAnswers.isEmpty() && answerDrafts.isEmpty() && questionPageIndices.isEmpty()
    }

    /**
     * A write: [requests] is null while the screen knows of no pending request (not loaded
     * yet, or all answered), which keeps what was saved.
     */
    data class Update(
        val pendingModel: OpenCodeModelRef? = null,
        val requests: RequestAnswers? = null,
    )

    val isEmpty: Boolean get() = pendingModel == null && requests.isEmpty
}

@Serializable
data class SessionStateRecord(
    val serverID: String,
    val sessionID: String,
    val state: PersistedSessionState,
    @Serializable(with = ComposerDateSerializer::class) val updatedAt: Instant,
)

/**
 * The state with the session's record written (or removed once empty), or null when nothing
 * changed.
 */
fun PersistedComposerState.applySessionState(
    update: PersistedSessionState.Update,
    serverID: String,
    sessionID: String,
    at: Instant,
): PersistedComposerState? {
    val records = sessionStates.orEmpty().toMutableList()
    val index = records.indexOfFirst { it.serverID == serverID && it.sessionID == sessionID }.takeIf { it >= 0 }
    val old = index?.let { records[it].state } ?: PersistedSessionState()
    val new = PersistedSessionState(pendingModel = update.pendingModel, requests = update.requests ?: old.requests)
    if (new == old) return null
    if (index != null) {
        if (new.isEmpty) records.removeAt(index) else records[index] = records[index].copy(state = new, updatedAt = at)
    } else {
        records += SessionStateRecord(serverID, sessionID, new, at)
    }
    return copy(sessionStates = records.ifEmpty { null })
}

/** The state without the matching records, or null when none matched. */
fun PersistedComposerState.removeSessionStates(shouldRemove: (SessionStateRecord) -> Boolean): PersistedComposerState? {
    val records = sessionStates ?: return null
    if (records.none(shouldRemove)) return null
    return copy(sessionStates = records.filterNot(shouldRemove).ifEmpty { null })
}

// Those reducers live in the session and drawer areas, above this module, so their logic is here as
// plain functions over values (like `ComposerPersistenceIntegration.kt`); those areas call them
// from their reducers.

/**
 * `answerDrafts` with the words on the bar now ([barText], the session's draft text), which are
 * only filed there when the bar moves to another page or request.
 */
fun <Draft> RequestDockFeature.State<Draft>.answerDraftsIncludingBar(barText: String): Map<String, String> {
    val shown = answerShownTarget
    if (parkedComposer == null || shown == null) return answerDrafts
    return answerDrafts + (shown to barText)
}

/**
 * What the persisted session state watches for changes. The session reducer compares the old and
 * new watch (`onChange`) and calls [reconcilePersistedSession].
 */
data class PersistedSessionWatch(
    val serverID: String,
    val sessionID: String?,
    val readsOnView: Boolean,
    val update: PersistedSessionState.Update,
) {
    companion object {
        /** [barText] is the session's draft text. */
        fun <Draft> of(
            serverID: String,
            session: OpenCodeSession?,
            readsOnView: Boolean,
            pendingModel: OpenCodeModelRef?,
            requestDock: RequestDockFeature.State<Draft>,
            barText: String,
        ): PersistedSessionWatch {
            val knowsRequests = requestDock.permissionRequests.isNotEmpty() || requestDock.formRequests.isNotEmpty()
            return PersistedSessionWatch(
                serverID = serverID,
                sessionID = session?.id,
                readsOnView = readsOnView,
                update = PersistedSessionState.Update(
                    pendingModel = pendingModel,
                    requests = if (knowsRequests) {
                        PersistedSessionState.RequestAnswers(
                            formAnswers = requestDock.formAnswers,
                            answerDrafts = requestDock.answerDraftsIncludingBar(barText),
                            questionPageIndices = requestDock.questionPageIndices,
                        )
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

/** The same screen's pick or answers changed. */
fun ComposerPersistence.reconcilePersistedSession(old: PersistedSessionWatch, current: PersistedSessionWatch) {
    val sessionID = current.sessionID ?: return
    if (!current.readsOnView || old.serverID != current.serverID || old.sessionID != sessionID) return
    scheduleSessionState(current.serverID, sessionID, current.update)
}

/**
 * Requests that just arrived take back the answers saved for them before a relaunch; the bar picks
 * its words up when it docks.
 */
fun <Draft> ComposerPersistence.restorePersistedAnswers(
    requestDock: RequestDockFeature.State<Draft>,
    serverID: String,
    session: OpenCodeSession?,
    readsOnView: Boolean,
): RequestDockFeature.State<Draft> {
    val sessionID = session?.id ?: return requestDock
    if (!readsOnView) return requestDock
    val saved = loadSessionState(serverID, sessionID)?.requests ?: return requestDock
    if (saved.isEmpty) return requestDock
    val formAnswers = requestDock.formAnswers.toMutableMap()
    val pages = requestDock.questionPageIndices.toMutableMap()
    for (form in requestDock.formRequests) {
        if (formAnswers[form.id] != null) continue
        saved.formAnswers[form.id]?.let { formAnswers[form.id] = it }
        if (pages[form.id] == null) saved.questionPageIndices[form.id]?.let { pages[form.id] = it }
    }
    var dock = requestDock.copy(formAnswers = formAnswers, questionPageIndices = pages)
    if (dock.isAnswering) return dock
    val drafts = RequestAnswering.pruneAnswerDrafts(saved.answerDrafts, dock.reviewRequestIDs.toSet())
    val restored = dock.answerDrafts.toMutableMap()
    for ((key, text) in drafts) if (restored[key] == null) restored[key] = text
    dock = dock.copy(answerDrafts = restored)
    return dock
}

/**
 * After a relaunch a session's waiting model pick comes back with it. A visit this launch already
 * carries the pick (or its absence), so [hasSnapshot] (`sessionSnapshots[session.id] != nil`) skips
 * it. Returns the pick for the screen's `currentModel` and `pendingModel`, or null to leave them.
 */
fun ComposerPersistence.restorePersistedPick(
    serverID: String,
    session: OpenCodeSession?,
    pendingModel: OpenCodeModelRef?,
    hasSnapshot: Boolean,
    models: List<OpenCodeModel>,
): OpenCodeModelRef? {
    if (session == null || pendingModel != null || hasSnapshot) return null
    val pick = loadSessionState(serverID, session.id)?.pendingModel ?: return null
    if (pick == session.model) return null
    if (models.isNotEmpty() && models.none { it.ref.sameModel(pick) }) return null
    return pick
}
