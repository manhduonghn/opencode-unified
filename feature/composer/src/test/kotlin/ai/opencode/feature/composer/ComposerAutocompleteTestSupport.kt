package ai.opencode.feature.composer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.UUID

/**
 * A draft binding and the `.autocomplete` actions, each run through [ComposerAutocompleteReducer]
 * exactly as the session store hosts it (`SessionFeatureBindings.kt`: `draftChanged` on a draft
 * edit, `reduce` on an autocomplete action, the edited draft written back).
 */
class ComposerAutocompleteTestHost :
    Reducer<ComposerAutocompleteTestHost.State, ComposerAutocompleteTestHost.Action> {

    data class State(
        val server: OpenCodeServer,
        val directory: String?,
        val sessionID: String?,
        val projects: List<OpenCodeProject> = emptyList(),
        val draft: String = "",
        val autocomplete: ComposerAutocompleteState = ComposerAutocompleteState(),
    ) {
        val host: ComposerAutocompleteHost
            get() = ComposerAutocompleteHost(draft = draft, server = server, effectiveDirectory = directory, sessionID = sessionID, projects = projects)

        fun autocomplete(update: ComposerAutocompleteState.() -> ComposerAutocompleteState): State = copy(autocomplete = autocomplete.update())
    }

    sealed interface Action {
        data class Draft(val draft: String) : Action
        data class Autocomplete(val action: ComposerAutocompleteAction) : Action
        data object SendTapped : Action
        data object RefreshRequestedOpen : Action
        data class ReplyCompleted(val sessionID: String) : Action
    }

    private enum class CancelID { Catalog, Commands, Files, Sessions, AutoRun, CommandWait }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.Draft -> {
            val next = state.copy(draft = action.draft)
            apply(next, ComposerAutocompleteReducer.draftChanged(next.autocomplete, next.host, environment()))
        }
        is Action.Autocomplete -> apply(state, ComposerAutocompleteReducer.reduce(action.action, state.autocomplete, state.host, environment()))
        Action.SendTapped, Action.RefreshRequestedOpen, is Action.ReplyCompleted -> Reduced(state)
    }

    private fun apply(state: State, outcome: ComposerAutocompleteOutcome<Action>): Reduced<State, Action> =
        Reduced(state.copy(autocomplete = outcome.autocomplete, draft = outcome.draft), outcome.effect)

    private fun environment() = ComposerAutocompleteEnvironment<Action>(
        client = dependency(OpenCodeClient),
        clock = dependency(Clock),
        uuid = dependency(UuidClient),
        haptics = dependency(HapticsClient),
        cancelCatalogID = CancelID.Catalog,
        cancelCommandsID = CancelID.Commands,
        cancelFilesID = CancelID.Files,
        cancelSessionsID = CancelID.Sessions,
        cancelAutoRunID = CancelID.AutoRun,
        cancelCommandWaitID = CancelID.CommandWait,
        embed = Action::Autocomplete,
        sendTapped = { Action.SendTapped },
        refreshRequestedOpen = { Action.RefreshRequestedOpen },
        replyCompleted = { Action.ReplyCompleted(it) },
    )
}

object ComposerAutocompleteFixtures {
    val server = OpenCodeServer(url = "http://localhost:9", username = "opencode", password = "pw")
    val session = OpenCodeSession(
        id = "ses_autocomplete",
        serverTitle = "Composer Autocomplete",
        projectID = "proj_1",
        directory = "/Users/kit/code/opencode-ios",
        updatedAt = Instant.ofEpochSecond(1_000),
    )

    val sampleSkills = listOf(
        OpenCodeAutocompleteSkill(id = "kit-motion", name = "kit-motion", description = "Continuity, spring palette & tunables"),
        OpenCodeAutocompleteSkill(id = "puckett", name = "puckett", description = "Interface Craft alignment & edges"),
        OpenCodeAutocompleteSkill(id = "effect", name = "effect", description = "Effect v4 services, layers & schemas"),
    )

    val sampleAgents = listOf(
        OpenCodeAutocompleteAgent(id = "explore", name = "explore", description = "Fast codebase exploration", mode = "subagent", hidden = false),
        OpenCodeAutocompleteAgent(id = "general", name = "general", description = "Multi-step subagent work", mode = "subagent", hidden = false),
        OpenCodeAutocompleteAgent(id = "compaction", name = "compaction", description = "Internal compaction", mode = "subagent", hidden = true),
    )

    val sampleCommands = listOf(
        OpenCodeAutocompleteCommand(name = "review", description = "Review uncommitted or branch changes"),
        OpenCodeAutocompleteCommand(name = "compact", description = "Summarize earlier turns in this session"),
        OpenCodeAutocompleteCommand(name = "clear", description = "Start a fresh session in this folder"),
    )

    val sampleFiles = listOf(
        OpenCodeAutocompleteFile(
            relativePath = "OpenCode/SessionView.swift",
            absolutePath = "/Users/kit/code/opencode-ios/OpenCode/SessionView.swift",
            directory = "/Users/kit/code/opencode-ios",
        ),
        OpenCodeAutocompleteFile(
            relativePath = "OpenCode/SessionFeature.swift",
            absolutePath = "/Users/kit/code/opencode-ios/OpenCode/SessionFeature.swift",
            directory = "/Users/kit/code/opencode-ios",
        ),
    )

    /** A chip whose ID ends in [suffix], as `UUIDGenerator.incrementing` makes them. */
    fun chip(suffix: Long, item: AutocompleteItem) = ComposerAutocompleteChip(item = item, id = UUID(0, suffix))

    /** A complete generated session ID (`ses_` and 26 letters or digits). */
    fun mentionID(seed: String): String = "ses_" + seed + "0".repeat(26 - seed.length)

    val drawerPolish: OpenCodeAutocompleteSession
        get() = OpenCodeAutocompleteSession.of(
            id = mentionID("DrawerPolish"),
            title = "Drawer Polish",
            project = "opencode-ios",
            updatedAt = Instant.ofEpochSecond(1_900),
        )

    /** The sample session, an empty draft. */
    fun state(
        session: OpenCodeSession = this.session,
        configure: ComposerAutocompleteTestHost.State.() -> ComposerAutocompleteTestHost.State = { this },
    ): ComposerAutocompleteTestHost.State =
        ComposerAutocompleteTestHost.State(server = server, directory = session.directory, sessionID = session.id).configure()
}
