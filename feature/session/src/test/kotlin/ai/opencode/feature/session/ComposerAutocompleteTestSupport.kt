package ai.opencode.feature.session

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.AutocompleteItem
import ai.opencode.feature.composer.ComposerAutocompleteChip
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import java.util.UUID

internal object ComposerAutocompleteTestSupport {
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

    /** The suffix's decimal digits as hex digits. */
    fun chip(suffix: Int, item: AutocompleteItem): ComposerAutocompleteChip =
        ComposerAutocompleteChip(item, id = UUID.fromString("00000000-0000-0000-0000-%012d".format(suffix)))

    fun Harness.makeStore(
        state: SessionFeature.State? = null,
        exhaustive: Boolean = true,
        configureState: (SessionFeature.State) -> SessionFeature.State = { it },
        configureDependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<SessionFeature.State, SessionFeature.Action> {
        val initial = configureState(
            state ?: this@makeStore.state {
                SessionFeature.State.session(
                    server = server,
                    session = session,
                    cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_existing", text = "Earlier")),
                    initialTranscriptRequestIsExternal = true,
                )
            },
        )
        val store = store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(2_000))
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[PromptOutbox] = PromptOutbox()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
            )
            configureDependencies()
        }
        store.exhaustive = exhaustive
        return store
    }
}
