package ai.opencode.feature.composer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * A `/command` that failed and the draft to restore. The draft is the session area's
 * `SessionFeature.State.DraftState` (a type above this module), so it travels as [D] and the
 * session reducer reads it back.
 */
data class ComposerCommandFailure<out D>(val error: OpenCodeClientError, val draft: D)

sealed interface ComposerCommandResult {
    data class Success(val sessionID: String) : ComposerCommandResult
    data class Failure(val failure: ComposerCommandFailure<*>) : ComposerCommandResult
}

/** Durable composer autocomplete state owned by `SessionFeature.State`. */
data class ComposerAutocompleteState(
    val skills: List<OpenCodeAutocompleteSkill> = emptyList(),
    val agents: List<OpenCodeAutocompleteAgent> = emptyList(),
    val commands: List<OpenCodeAutocompleteCommand> = emptyList(),
    val files: List<OpenCodeAutocompleteFile> = emptyList(),

    val loadedCatalogDirectoryKey: String? = null,
    val loadedCommandsDirectoryKey: String? = null,
    val loadingCatalogDirectoryKey: String? = null,
    val loadingCommandsDirectoryKey: String? = null,
    val isLoadingCatalog: Boolean = false,
    val isLoadingCommands: Boolean = false,
    val isSearchingFiles: Boolean = false,
    val fileSearchGeneration: Int = 0,
    val lastFileSearchQuery: String? = null,
    /** Root sessions learned while mentioning, newest first, without this one. */
    val sessions: List<OpenCodeAutocompleteSession> = emptyList(),
    /** The server search last asked for (the query's first word); null once closed. */
    val sessionSearch: String? = null,
    /** The server search last answered, so rows know when a search is pending. */
    val answeredSessionSearch: String? = null,

    val activeTrigger: AutocompleteTrigger? = null,
    val dismissedKey: String? = null,
    val caretUTF16Offset: Int? = null,
    val caretDraftUTF16Length: Int? = null,
    val rows: List<AutocompleteItem> = emptyList(),
    val selectedRowID: String? = null,
    val userMovedSelection: Boolean = false,

    val chips: List<ComposerAutocompleteChip> = emptyList(),
    /** Bumped when a row is chosen so the motion controller launches its chip flight. */
    val chooseGeneration: Int = 0,
    val lastChosenChip: ComposerAutocompleteChip? = null,
    val lastChosenRowID: String? = null,
) {
    val isOpen: Boolean get() = activeTrigger != null && rows.isNotEmpty()

    fun recomputeRows(): ComposerAutocompleteState {
        val trigger = activeTrigger ?: return copy(rows = emptyList(), selectedRowID = null, userMovedSelection = false)
        val nextRows = AutocompleteFuzzy.displayRows(
            mode = trigger.mode,
            query = trigger.query,
            skills = skills,
            agents = agents,
            files = files,
            commands = commands,
            sessions = matchingSessions(trigger),
        )
        val current = selectedRowID
        if (userMovedSelection && current != null && nextRows.any { it.id == current && it.isSelectable }) {
            return copy(rows = nextRows)
        }
        return copy(rows = nextRows, userMovedSelection = false, selectedRowID = nextRows.firstOrNull { it.isSelectable }?.id)
    }

    /** Desktop's mention rows: 5 recent sessions, or 8 whose titles match. */
    fun matchingSessions(trigger: AutocompleteTrigger): List<OpenCodeAutocompleteSession> {
        if (trigger.mode != AutocompleteTrigger.Mode.Mention) return emptyList()
        val query = trigger.query
        if (query.isEmpty()) return sessions.take(5)
        return sessions.asSequence().filter { OpenCodeAutocompleteSession.matches(it.title, query) }.take(8).toList()
    }

    /**
     * A mention whose query holds a space is prose once nothing matches and no session
     * search is still on its way (desktop closes it then too).
     */
    val isProseWithoutMatches: Boolean
        get() {
            val trigger = activeTrigger ?: return false
            if (trigger.mode != AutocompleteTrigger.Mode.Mention || !trigger.query.any { it.isWhitespace() } || rows.isNotEmpty()) return false
            return answeredSessionSearch == OpenCodeAutocompleteSession.serverSearch(trigger.query)
        }

    /** Folds a search's sessions into what's known, newest first. */
    fun learnSessions(found: List<OpenCodeAutocompleteSession>): ComposerAutocompleteState {
        val byID = LinkedHashMap<String, OpenCodeAutocompleteSession>()
        for (session in sessions) byID.putIfAbsent(session.id, session)
        for (session in found) {
            val known = byID[session.id]
            if (known != null && known.updatedAt > session.updatedAt) continue
            byID[session.id] = session
        }
        val sorted = byID.values.sortedWith(
            compareByDescending<OpenCodeAutocompleteSession> { it.updatedAt }.thenByDescending { it.id },
        )
        return copy(sessions = sorted)
    }

    fun pruneChips(draft: String): ComposerAutocompleteState {
        if (chips.isEmpty()) return this
        val validIDs = AutocompleteTrigger.resolvedChipRanges(draft, chips).map { it.chip.id }.toSet()
        if (validIDs.size == chips.size) return this
        return copy(chips = chips.filter { it.id in validIDs })
    }

    fun closePopover(dismissed: Boolean = false): ComposerAutocompleteState {
        val key = activeTrigger?.key
        return copy(
            dismissedKey = if (dismissed && key != null) key else dismissedKey,
            activeTrigger = null,
            rows = emptyList(),
            selectedRowID = null,
            userMovedSelection = false,
            isSearchingFiles = false,
            lastFileSearchQuery = null,
            // Closing calls the search off, so reopening asks again.
            sessionSearch = null,
        )
    }

    fun setCaret(offset: Int?, draftLength: Int): ComposerAutocompleteState =
        if (offset != null && offset < draftLength) {
            copy(caretUTF16Offset = max(0, offset), caretDraftUTF16Length = draftLength)
        } else {
            copy(caretUTF16Offset = null, caretDraftUTF16Length = null)
        }

    fun finishCatalogLoading(directoryKey: String): ComposerAutocompleteState =
        if (loadingCatalogDirectoryKey == null || loadingCatalogDirectoryKey == directoryKey) {
            copy(isLoadingCatalog = false, loadingCatalogDirectoryKey = null)
        } else {
            this
        }

    fun finishCommandsLoading(directoryKey: String): ComposerAutocompleteState =
        if (loadingCommandsDirectoryKey == null || loadingCommandsDirectoryKey == directoryKey) {
            copy(isLoadingCommands = false, loadingCommandsDirectoryKey = null)
        } else {
            this
        }

    fun clearForSend(): ComposerAutocompleteState = closePopover(dismissed = false).copy(
        dismissedKey = null,
        caretUTF16Offset = null,
        caretDraftUTF16Length = null,
        chips = emptyList(),
        lastChosenChip = null,
        lastChosenRowID = null,
    )
}

/** Embedded in `SessionFeature.Action.autocomplete`. */
sealed interface ComposerAutocompleteAction {
    data class CatalogLoaded(
        val directoryKey: String,
        val skills: List<OpenCodeAutocompleteSkill>,
        val agents: List<OpenCodeAutocompleteAgent>,
    ) : ComposerAutocompleteAction
    data class CatalogFailed(val directoryKey: String) : ComposerAutocompleteAction
    data class CommandsLoaded(val directoryKey: String, val commands: List<OpenCodeAutocompleteCommand>) : ComposerAutocompleteAction
    data class CommandsFailed(val directoryKey: String) : ComposerAutocompleteAction
    data class FilesLoaded(
        val generation: Int,
        val directory: String,
        val query: String,
        val files: List<OpenCodeAutocompleteFile>,
    ) : ComposerAutocompleteAction
    data class FilesFailed(val generation: Int) : ComposerAutocompleteAction
    data class SessionsLoaded(val search: String, val sessions: List<OpenCodeSession>) : ComposerAutocompleteAction
    data class SessionsFailed(val search: String) : ComposerAutocompleteAction
    data class CaretChanged(val offset: Int?) : ComposerAutocompleteAction

    /**
     * A keystroke in the field: the new draft, and the caret when the edit left it somewhere the
     * draft's change doesn't account for.
     */
    data class TextEdited(val text: String, val caret: CaretUpdate) : ComposerAutocompleteAction
    data class MoveHighlight(val delta: Int) : ComposerAutocompleteAction
    data object ConfirmSelection : ComposerAutocompleteAction
    data class RowChosen(val id: String) : ComposerAutocompleteAction
    data object Dismissed : ComposerAutocompleteAction
    data class ChipDeleted(val chipID: UUID) : ComposerAutocompleteAction
    data class CommandFinished(val result: ComposerCommandResult) : ComposerAutocompleteAction

    /** Where an edit left the caret, as [CaretChanged] would have reported it. */
    sealed interface CaretUpdate {
        /** Where the draft's change moves it, or a composition is still open. */
        data object Unchanged : CaretUpdate
        data class Moved(val offset: Int?) : CaretUpdate
    }
}

/**
 * The slice of the session's state the autocomplete reducer reads. The session area builds it from
 * its state (`draft`, `server`, `effectiveDirectory`, `isAnsweringRequest`, `session?.id`,
 * `projects`) before each call.
 */
data class ComposerAutocompleteHost(
    val draft: String,
    val server: OpenCodeServer,
    val effectiveDirectory: String?,
    val isAnsweringRequest: Boolean = false,
    val sessionID: String? = null,
    val projects: List<OpenCodeProject> = emptyList(),
)

/**
 * What the session reducer must apply to `SessionFeature.State` after a
 * [ComposerAutocompleteAction.CommandFinished].
 */
sealed interface ComposerCommandOutcome {
    /**
     * `if state.session != nil { state.pendingModel = nil }` (the refresh and idle wait are in the
     * effect).
     */
    data object Succeeded : ComposerCommandOutcome

    /**
     * `state.isAwaitingReply = false; state.keepAsDraft(failure.draft); state.dictationError =
     * failure.error.message` (the error haptic and log already happened).
     */
    data class Failed(val failure: ComposerCommandFailure<*>) : ComposerCommandOutcome
}

/** The result of one autocomplete reduce: new autocomplete state, the (possibly edited) draft, and the effect. */
data class ComposerAutocompleteOutcome<A>(
    val autocomplete: ComposerAutocompleteState,
    val draft: String,
    val effect: Effect<A>,
    val command: ComposerCommandOutcome? = null,
)

/**
 * The dependencies, cancel IDs and parent actions every autocomplete reducer call takes. [embed]
 * wraps an autocomplete action into the parent's (`SessionFeature.Action.autocomplete`);
 * [sendTapped], [refreshRequestedOpen] and [replyCompleted] are the session actions the effects
 * send (`.sendTapped`, `.refreshRequested(.open)`, `.replyCompleted(sessionID:)`).
 */
class ComposerAutocompleteEnvironment<A>(
    val client: OpenCodeClient,
    val clock: Clock,
    val uuid: UuidClient,
    val haptics: HapticsClient,
    val cancelCatalogID: Any,
    val cancelCommandsID: Any,
    val cancelFilesID: Any,
    val cancelSessionsID: Any,
    val cancelAutoRunID: Any,
    val cancelCommandWaitID: Any,
    val embed: (ComposerAutocompleteAction) -> A,
    val sendTapped: () -> A,
    val refreshRequestedOpen: () -> A,
    val replyCompleted: (sessionID: String) -> A,
)

/** Pure reducer helpers for Composer Autocomplete, called from `SessionFeature`. */
object ComposerAutocompleteReducer {
    val fileSearchDebounce: Duration = 120.milliseconds
    const val fileSearchLimit = 20
    /** Desktop waits this long before a typed session search; an empty one goes at once. */
    val sessionSearchDebounce: Duration = 100.milliseconds
    const val sessionSearchLimit = 50

    fun directoryKey(directory: String?): String = directory ?: ""

    private fun <A> outcome(state: ComposerAutocompleteState, draft: String, effect: Effect<A>) =
        ComposerAutocompleteOutcome(state, draft, effect)

    private suspend fun stillActive(): Boolean = currentCoroutineContext().isActive

    fun <A> draftChanged(
        autocomplete: ComposerAutocompleteState,
        host: ComposerAutocompleteHost,
        env: ComposerAutocompleteEnvironment<A>,
        isCaretUpdate: Boolean = false,
    ): ComposerAutocompleteOutcome<A> {
        var state = autocomplete
        val draft = host.draft
        if (host.isAnsweringRequest) {
            if (state.activeTrigger != null || state.rows.isNotEmpty()) state = state.closePopover()
            return outcome(state, draft, Effect.cancel(env.cancelAutoRunID))
        }

        val newLength = draft.length
        val caret = state.caretUTF16Offset
        if (!isCaretUpdate && caret != null) {
            val oldLength = state.caretDraftUTF16Length ?: newLength
            state = state.setCaret(min(max(0, caret + newLength - oldLength), newLength), newLength)
        }
        state = state.pruneChips(draft)

        val openMention = state.activeTrigger?.let { if (it.mode == AutocompleteTrigger.Mode.Mention) it.key else null }
        val detected = AutocompleteTrigger.detect(
            draft = draft,
            chips = state.chips,
            caretUTF16Offset = state.caretUTF16Offset,
            openMentionKey = openMention,
        )
        if (state.dismissedKey != null && detected?.key != state.dismissedKey) {
            state = state.copy(dismissedKey = null)
        }

        if (detected == null || detected.key == state.dismissedKey) {
            return close(state, draft, env)
        }
        val trigger: AutocompleteTrigger = detected

        val previousTrigger = state.activeTrigger
        state = state.copy(activeTrigger = trigger)
        if (previousTrigger?.mode != trigger.mode || previousTrigger?.key != trigger.key) {
            state = state.copy(userMovedSelection = false)
        }
        state = state.recomputeRows()

        val server = host.server
        val directory = host.effectiveDirectory
        val dirKey = directoryKey(directory)
        val client = env.client
        val clock = env.clock
        val effects = mutableListOf<Effect<A>>(Effect.cancel(env.cancelAutoRunID))

        when (trigger.mode) {
            AutocompleteTrigger.Mode.Mention -> {
                if (state.isProseWithoutMatches) return close(state, draft, env)
                val search = OpenCodeAutocompleteSession.serverSearch(trigger.query)
                if (state.sessionSearch != search) {
                    state = state.copy(sessionSearch = search)
                    val limit = sessionSearchLimit
                    effects += Effect.run<A> { send ->
                        if (search.isNotEmpty()) clock.sleep(sessionSearchDebounce)
                        try {
                            val found = client.mentionSessions(server, search, limit)
                            if (!stillActive()) return@run
                            send(env.embed(ComposerAutocompleteAction.SessionsLoaded(search, found)))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            if (!stillActive()) return@run
                            send(env.embed(ComposerAutocompleteAction.SessionsFailed(search)))
                        }
                    }.cancellable(env.cancelSessionsID, cancelInFlight = true)
                }

                if (state.loadedCatalogDirectoryKey != dirKey &&
                    (!state.isLoadingCatalog || state.loadingCatalogDirectoryKey != dirKey)
                ) {
                    state = state.copy(isLoadingCatalog = true, loadingCatalogDirectoryKey = dirKey)
                    effects += Effect.run<A> { send ->
                        val (skills, agents) = coroutineScope {
                            val fetchedSkills = async { attempt { client.skills(server, directory) } }
                            val fetchedAgents = async { attempt { client.agents(server, directory) } }
                            fetchedSkills.await() to fetchedAgents.await()
                        }
                        if (!stillActive()) return@run
                        if (skills != null || agents != null) {
                            send(env.embed(ComposerAutocompleteAction.CatalogLoaded(dirKey, skills ?: emptyList(), agents ?: emptyList())))
                        } else {
                            send(env.embed(ComposerAutocompleteAction.CatalogFailed(dirKey)))
                        }
                    }.cancellable(env.cancelCatalogID, cancelInFlight = true)
                }

                if (!directory.isNullOrEmpty()) {
                    val query = trigger.query
                    if (query.any { it.isWhitespace() }) {
                        // Only session titles hold spaces, so a spaced query skips files.
                        if (state.isSearchingFiles) {
                            state = state.copy(isSearchingFiles = false, lastFileSearchQuery = null)
                            effects += Effect.cancel(env.cancelFilesID)
                        }
                    } else if (state.lastFileSearchQuery != query || !state.isSearchingFiles && previousTrigger == null) {
                        val generation = state.fileSearchGeneration + 1
                        state = state.copy(fileSearchGeneration = generation, isSearchingFiles = true, lastFileSearchQuery = query)
                        val limit = fileSearchLimit
                        effects += Effect.run<A> { send ->
                            clock.sleep(fileSearchDebounce)
                            if (!stillActive()) return@run
                            try {
                                val files = client.findFiles(server, directory, query, limit)
                                if (!stillActive()) return@run
                                send(env.embed(ComposerAutocompleteAction.FilesLoaded(generation, directory, query, files)))
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                if (!stillActive()) return@run
                                send(env.embed(ComposerAutocompleteAction.FilesFailed(generation)))
                            }
                        }.cancellable(env.cancelFilesID, cancelInFlight = true)
                    }
                }
            }

            AutocompleteTrigger.Mode.Command -> {
                if (state.isSearchingFiles) {
                    state = state.copy(isSearchingFiles = false, lastFileSearchQuery = null)
                    effects += Effect.cancel(env.cancelFilesID)
                }
                if (state.sessionSearch != null) {
                    state = state.copy(sessionSearch = null)
                    effects += Effect.cancel(env.cancelSessionsID)
                }
                if (state.loadedCommandsDirectoryKey != dirKey &&
                    (!state.isLoadingCommands || state.loadingCommandsDirectoryKey != dirKey)
                ) {
                    state = state.copy(isLoadingCommands = true, loadingCommandsDirectoryKey = dirKey)
                    effects += Effect.run<A> { send ->
                        try {
                            val commands = client.commands(server, directory)
                            if (!stillActive()) return@run
                            send(env.embed(ComposerAutocompleteAction.CommandsLoaded(dirKey, commands)))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            send(env.embed(ComposerAutocompleteAction.CommandsFailed(dirKey)))
                        }
                    }.cancellable(env.cancelCommandsID, cancelInFlight = true)
                }
            }
        }

        return outcome(state, draft, Effect.merge(effects))
    }

    /** The value, or null on any failure but cancellation. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /** Closes the popover and calls off the lookups it started. */
    private fun <A> close(
        autocomplete: ComposerAutocompleteState,
        draft: String,
        env: ComposerAutocompleteEnvironment<A>,
        dismissed: Boolean = false,
    ): ComposerAutocompleteOutcome<A> {
        var state = autocomplete
        val wasSearchingFiles = state.isSearchingFiles
        val wasSearchingSessions = state.sessionSearch != null
        if (!dismissed || state.activeTrigger != null) state = state.closePopover(dismissed)
        return outcome(
            state,
            draft,
            Effect.merge(
                if (wasSearchingFiles) Effect.cancel(env.cancelFilesID) else Effect.none(),
                if (wasSearchingSessions) Effect.cancel(env.cancelSessionsID) else Effect.none(),
                Effect.cancel(env.cancelAutoRunID),
            ),
        )
    }

    fun <A> reduce(
        action: ComposerAutocompleteAction,
        autocomplete: ComposerAutocompleteState,
        host: ComposerAutocompleteHost,
        env: ComposerAutocompleteEnvironment<A>,
    ): ComposerAutocompleteOutcome<A> {
        val state = autocomplete
        val draft = host.draft
        return when (action) {
            is ComposerAutocompleteAction.CatalogLoaded -> {
                val finished = state.finishCatalogLoading(action.directoryKey)
                if (directoryKey(host.effectiveDirectory) != action.directoryKey) return outcome(finished, draft, Effect.none())
                val next = finished.copy(
                    loadedCatalogDirectoryKey = action.directoryKey,
                    skills = action.skills,
                    agents = action.agents.filter { !it.hidden && it.mode != "primary" },
                ).recomputeRows()
                outcome(next, draft, Effect.none())
            }

            is ComposerAutocompleteAction.CatalogFailed ->
                outcome(state.finishCatalogLoading(action.directoryKey), draft, Effect.none())

            is ComposerAutocompleteAction.CommandsLoaded -> {
                val finished = state.finishCommandsLoading(action.directoryKey)
                if (directoryKey(host.effectiveDirectory) != action.directoryKey) return outcome(finished, draft, Effect.none())
                val next = finished.copy(loadedCommandsDirectoryKey = action.directoryKey, commands = action.commands).recomputeRows()
                outcome(next, draft, Effect.none())
            }

            is ComposerAutocompleteAction.CommandsFailed ->
                outcome(state.finishCommandsLoading(action.directoryKey), draft, Effect.none())

            is ComposerAutocompleteAction.FilesLoaded -> {
                val trigger = state.activeTrigger
                if (action.generation != state.fileSearchGeneration ||
                    host.effectiveDirectory != action.directory ||
                    trigger?.mode != AutocompleteTrigger.Mode.Mention ||
                    trigger.query != action.query
                ) {
                    return outcome(state, draft, Effect.none())
                }
                outcome(state.copy(isSearchingFiles = false, files = action.files).recomputeRows(), draft, Effect.none())
            }

            is ComposerAutocompleteAction.FilesFailed -> {
                if (action.generation != state.fileSearchGeneration) return outcome(state, draft, Effect.none())
                outcome(state.copy(isSearchingFiles = false), draft, Effect.none())
            }

            is ComposerAutocompleteAction.SessionsLoaded -> {
                if (action.search != state.sessionSearch) return outcome(state, draft, Effect.none())
                val currentID = host.sessionID
                val found = action.sessions
                    .map { OpenCodeAutocompleteSession.of(it, host.projects) }
                    .filter { OpenCodeAutocompleteSession.isMentionable(it.id) && it.id != currentID }
                val next = state.learnSessions(found).copy(answeredSessionSearch = action.search).recomputeRows()
                if (next.isProseWithoutMatches) return close(next, draft, env)
                outcome(next, draft, Effect.none())
            }

            is ComposerAutocompleteAction.SessionsFailed -> {
                if (action.search != state.sessionSearch) return outcome(state, draft, Effect.none())
                val next = state.copy(answeredSessionSearch = action.search)
                if (next.isProseWithoutMatches) return close(next, draft, env)
                outcome(next, draft, Effect.none())
            }

            // `SessionFeature` takes the draft first (`draftEdited`), then the caret.
            is ComposerAutocompleteAction.TextEdited -> outcome(state, draft, Effect.none())

            is ComposerAutocompleteAction.CaretChanged ->
                draftChanged(state.setCaret(action.offset, draft.length), host, env, isCaretUpdate = true)

            is ComposerAutocompleteAction.MoveHighlight -> {
                if (!state.isOpen) return outcome(state, draft, Effect.none())
                val selectable = state.rows.filter { it.isSelectable }
                if (selectable.isEmpty()) return outcome(state, draft, Effect.none())
                val currentIndex = selectable.indexOfFirst { it.id == state.selectedRowID }.let { if (it < 0) 0 else it }
                // In bottom order (index 0 at the bottom nearest the composer), pressing ↑ (-1)
                // moves upward in visual space (to index + 1), and ↓ (+1) moves downward (to index - 1).
                val step = -action.delta
                val nextIndex = min(max(0, currentIndex + step), selectable.size - 1)
                val nextID = selectable[nextIndex].id
                var next = state.copy(userMovedSelection = true)
                if (next.selectedRowID != nextID) {
                    next = next.copy(selectedRowID = nextID)
                    env.haptics.play(Haptic.selection)
                }
                outcome(next, draft, Effect.none())
            }

            ComposerAutocompleteAction.ConfirmSelection, is ComposerAutocompleteAction.RowChosen -> {
                val targetID = if (action is ComposerAutocompleteAction.RowChosen) action.id else state.selectedRowID
                    ?: return outcome(state, draft, Effect.none())
                chooseRow(targetID, state, draft, env)
            }

            ComposerAutocompleteAction.Dismissed -> close(state, draft, env, dismissed = true)

            is ComposerAutocompleteAction.ChipDeleted -> {
                val index = state.chips.indexOfFirst { it.id == action.chipID }
                if (index < 0) return outcome(state, draft, Effect.none())
                val resolved = AutocompleteTrigger.resolvedChipRanges(draft, state.chips)
                var next = state.copy(chips = state.chips.filterIndexed { i, _ -> i != index })
                var nextDraft = draft
                val match = resolved.firstOrNull { it.chip.id == action.chipID }
                if (match != null) {
                    var removeEnd = match.end
                    if (removeEnd < draft.length && draft[removeEnd] == ' ') removeEnd += 1
                    nextDraft = draft.substring(0, match.start) + draft.substring(removeEnd)
                    next = next.setCaret(match.start, nextDraft.length)
                }
                draftChanged(next, host.copy(draft = nextDraft), env, isCaretUpdate = true)
            }

            is ComposerAutocompleteAction.CommandFinished -> when (val result = action.result) {
                is ComposerCommandResult.Success -> {
                    val server = host.server
                    val client = env.client
                    val sessionID = result.sessionID
                    ComposerAutocompleteOutcome(
                        state,
                        draft,
                        Effect.merge(
                            Effect.send(env.refreshRequestedOpen()),
                            Effect.run<A> { send ->
                                try {
                                    client.waitForIdle(server, sessionID)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Exception) {
                                    return@run
                                }
                                if (!stillActive()) return@run
                                send(env.replyCompleted(sessionID))
                            }.cancellable(env.cancelCommandWaitID, cancelInFlight = true),
                        ),
                        command = ComposerCommandOutcome.Succeeded,
                    )
                }

                is ComposerCommandResult.Failure -> {
                    env.haptics.play(Haptic.error)
                    AppLog.send.error { "command failed: ${result.failure.error.logSummary}" }
                    ComposerAutocompleteOutcome(state, draft, Effect.none(), command = ComposerCommandOutcome.Failed(result.failure))
                }
            }
        }
    }

    private fun <A> chooseRow(
        id: String,
        autocomplete: ComposerAutocompleteState,
        draft: String,
        env: ComposerAutocompleteEnvironment<A>,
    ): ComposerAutocompleteOutcome<A> {
        val trigger = autocomplete.activeTrigger ?: return outcome(autocomplete, draft, Effect.none())
        val item = autocomplete.rows.firstOrNull { it.id == id && it.isSelectable } ?: return outcome(autocomplete, draft, Effect.none())

        val start = trigger.utf16Start
        val end = trigger.utf16Start + max(0, trigger.utf16End - trigger.utf16Start)
        if (start < 0 || end > draft.length) return outcome(autocomplete, draft, Effect.none())

        val chip = ComposerAutocompleteChip(item = item, id = env.uuid.next())
        val replacement = "${chip.token} "
        val nextDraft = draft.substring(0, start) + replacement + draft.substring(end)
        val newCaret = start + replacement.length
        var state = autocomplete.setCaret(newCaret, nextDraft.length).copy(
            chips = autocomplete.chips + chip,
            chooseGeneration = autocomplete.chooseGeneration + 1,
            lastChosenChip = chip,
            lastChosenRowID = item.id,
        )

        val wasSearching = state.isSearchingFiles
        val wasSearchingSessions = state.sessionSearch != null
        state = state.closePopover(dismissed = false)
        env.haptics.play(Haptic.tap)
        val cancelEffect: Effect<A> = Effect.merge(
            if (wasSearching) Effect.cancel(env.cancelFilesID) else Effect.none(),
            if (wasSearchingSessions) Effect.cancel(env.cancelSessionsID) else Effect.none(),
        )
        if (item.kind == AutocompleteItem.Kind.Command && ComposerAutocompleteTunables.commandAction == AutocompleteCommandAction.Run) {
            val delay = max(0.01, ComposerAutocompleteTunables.runDelay).seconds
            val clock = env.clock
            return outcome(
                state,
                nextDraft,
                Effect.merge(
                    cancelEffect,
                    Effect.run<A> { send ->
                        clock.sleep(delay)
                        if (!stillActive()) return@run
                        send(env.sendTapped())
                    }.cancellable(env.cancelAutoRunID, cancelInFlight = true),
                ),
            )
        }
        return outcome(state, nextDraft, cancelEffect)
    }
}
