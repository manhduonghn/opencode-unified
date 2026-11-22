package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.feature.composer.AutocompleteItem
import ai.opencode.feature.composer.AutocompleteTrigger
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.chip
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.makeStore
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleAgents
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleCommands
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleFiles
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleSkills
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.server
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.session
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeFileMention
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeMention
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSkillMention
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PromptContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The `ImagePasteTextView` / `ComposerTextInput` caret checks of
 * `midDraftChipInsertAndChipDeletePreserveCaretOffset` (its store half is here).
 * `ComposerAutocompleteTunables.commandAction` is a constant, so
 * `caretAwareTriggerDetectionAndAutoRunCancellationWorkCleanly` can't switch it to `run`; its
 * auto-run step still checks that nothing sends.
 */
class ComposerAutocompleteTests {
    private fun autocomplete(action: ComposerAutocompleteAction): Action = Action.Autocomplete(action)
    private fun draft(text: String): Action = Action.Binding(BindingAction.Draft(text))
    private fun SessionFeature.State.ac(transform: (ComposerAutocompleteState) -> ComposerAutocompleteState) = copy(autocomplete = transform(autocomplete))

    private val visibleAgents = sampleAgents.filter { !it.hidden && it.mode != "primary" }

    @Test fun typingAtLoadsCatalogFiltersAndCancelsStaleFileSearches() = sessionTest { h ->
        val clock = TestClock()
        val findQueries = LockIsolated(listOf<String>())
        val sessionSearches = LockIsolated(listOf<String>())
        val issues = LockIsolated(listOf<String>())
        val staleGate = CompletableDeferred<Unit>()
        val sessionsGate = CompletableDeferred<Unit>()

        val store = h.makeStore(
            configureState = { it.copy(models = listOf(OpenCodeModel(modelID = "claude", providerID = "anthropic", name = "Claude"))) },
            configureDependencies = {
                this[Clock] = clock
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    mentionSessions = { _, search, limit ->
                        if (limit != 50) issues.append("limit $limit")
                        sessionSearches.append(search)
                        sessionsGate.await()
                        emptyList()
                    },
                    skills = { _, _ -> sampleSkills },
                    agents = { _, _ -> sampleAgents },
                    commands = { _, _ -> sampleCommands },
                    findFiles = { _, _, query, _ ->
                        findQueries.append(query)
                        if (query == "k") staleGate.await()
                        sampleFiles
                    },
                )
            },
        )

        val triggerAt = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1)
        store.send(draft("@")) {
            it.withDraft("@").ac { a ->
                a.copy(
                    activeTrigger = triggerAt,
                    isLoadingCatalog = true,
                    loadingCatalogDirectoryKey = session.directory,
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "",
                    sessionSearch = "",
                )
            }
        }

        store.receive(autocomplete(ComposerAutocompleteAction.CatalogLoaded(directoryKey = session.directory, skills = sampleSkills, agents = sampleAgents))) {
            it.ac { a ->
                a.copy(
                    isLoadingCatalog = false,
                    loadingCatalogDirectoryKey = null,
                    loadedCatalogDirectoryKey = session.directory,
                    skills = sampleSkills,
                    agents = visibleAgents,
                ).recomputeRows()
            }
        }
        sessionsGate.complete(Unit)
        store.receive(autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "", sessions = emptyList()))) {
            it.ac { a -> a.copy(answeredSessionSearch = "") }
        }

        clock.advance(120.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 1, directory = session.directory, query = "", files = sampleFiles))) {
            it.ac { a -> a.copy(isSearchingFiles = false, files = sampleFiles).recomputeRows() }
        }

        assertTrue(store.state.autocomplete.isOpen)
        assertFalse(store.state.autocomplete.rows.any { it.id == "agent:compaction" })

        // Type "@k", let the debounce fire so findFiles("k") is in flight, then type "@kit" before "k" completes.
        val triggerK = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "k", key = "at:0", utf16Start = 0, utf16End = 2)
        store.send(draft("@k")) {
            it.withDraft("@k").ac { a ->
                a.copy(activeTrigger = triggerK, fileSearchGeneration = 2, isSearchingFiles = true, lastFileSearchQuery = "k", sessionSearch = "k").recomputeRows()
            }
        }
        clock.advance(100.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "k", sessions = emptyList()))) {
            it.ac { a -> a.copy(answeredSessionSearch = "k") }
        }
        clock.advance(20.milliseconds)

        val triggerKit = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:0", utf16Start = 0, utf16End = 4)
        store.send(draft("@kit")) {
            it.withDraft("@kit").ac { a ->
                a.copy(activeTrigger = triggerKit, fileSearchGeneration = 3, isSearchingFiles = true, lastFileSearchQuery = "kit", sessionSearch = "kit").recomputeRows()
            }
        }
        staleGate.complete(Unit)
        clock.advance(100.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "kit", sessions = emptyList()))) {
            it.ac { a -> a.copy(answeredSessionSearch = "kit") }
        }
        clock.advance(20.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 3, directory = session.directory, query = "kit", files = sampleFiles))) {
            it.ac { a -> a.copy(isSearchingFiles = false, files = sampleFiles).recomputeRows() }
        }

        assertEquals(listOf("", "k", "kit"), findQueries.value)
        assertEquals(listOf("", "k", "kit"), sessionSearches.value)
        assertEquals("skill:kit-motion", store.state.autocomplete.rows.firstOrNull()?.id)

        // A non-matching token and a space wait for the session search, then close as prose.
        store.send(draft("@zzzznotamatch ")) {
            it.withDraft("@zzzznotamatch ").ac { a ->
                a.copy(
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "zzzznotamatch ", key = "at:0", utf16Start = 0, utf16End = 15),
                    sessionSearch = "zzzznotamatch",
                ).recomputeRows()
            }
        }
        assertFalse(store.state.autocomplete.isOpen)
        clock.advance(100.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "zzzznotamatch", sessions = emptyList()))) {
            it.ac { a -> a.copy(answeredSessionSearch = "zzzznotamatch").closePopover(dismissed = false) }
        }
        assertTrue(issues.value.isEmpty())
        store.finish()
    }

    @Test fun hardwareKeyboardNavigationChoosingRowAndDismissalWorkCleanly() = sessionTest { h ->
        val clock = TestClock()
        val store = h.makeStore(
            configureState = { it.ac { a -> a.copy(loadedCommandsDirectoryKey = session.directory, commands = sampleCommands) } },
            configureDependencies = { this[Clock] = clock },
        )

        val trigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Command, query = "", key = "slash:0", utf16Start = 0, utf16End = 1)
        store.send(draft("/")) { it.withDraft("/").ac { a -> a.copy(activeTrigger = trigger).recomputeRows() } }

        assertEquals("command:review", store.state.autocomplete.selectedRowID)
        // In bottom order, ↑ (-1) steps to index 1 ("command:compact"), and ↓ (+1) steps back to index 0 ("command:review").
        store.send(autocomplete(ComposerAutocompleteAction.MoveHighlight(-1))) {
            it.ac { a -> a.copy(userMovedSelection = true, selectedRowID = "command:compact") }
        }
        store.send(autocomplete(ComposerAutocompleteAction.MoveHighlight(1))) {
            it.ac { a -> a.copy(userMovedSelection = true, selectedRowID = "command:review") }
        }

        // Dismiss via Escape / outside tap.
        store.send(autocomplete(ComposerAutocompleteAction.Dismissed)) { it.ac { a -> a.closePopover(dismissed = true) } }
        assertFalse(store.state.autocomplete.isOpen)
        assertEquals("slash:0", store.state.autocomplete.dismissedKey)

        // Backspacing past the trigger clears dismissedKey.
        store.send(draft("")) { it.withDraft("").ac { a -> a.copy(dismissedKey = null) } }

        // Re-open "/" and confirm selection (Return/Tab) to insert a chip.
        store.send(draft("/rev")) {
            it.withDraft("/rev").ac { a ->
                a.copy(activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Command, query = "rev", key = "slash:0", utf16Start = 0, utf16End = 4)).recomputeRows()
            }
        }
        val expectedChip = chip(0, AutocompleteItem.command(sampleCommands[0]))
        store.send(autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.withDraft("/review ").ac { a ->
                a.copy(chips = listOf(expectedChip), chooseGeneration = 1, lastChosenChip = expectedChip, lastChosenRowID = "command:review")
                    .closePopover(dismissed = false)
            }
        }
        assertFalse(store.state.autocomplete.isOpen)
        assertEquals(1, store.state.autocomplete.chips.size)
        store.finish()
    }

    @Test fun sendingComposerWithChipsForwardsMentionsWithoutDuplicateFileAttachment() = sessionTest { h ->
        val capturedMentions = LockIsolated<OpenCodeAutocompleteMentions?>(null)
        val capturedFiles = LockIsolated(listOf<OpenCodeFileReference>())
        val capturedTexts = LockIsolated(listOf<String>())
        val skillChip = chip(301, AutocompleteItem.skill(sampleSkills[0]))
        val fileChip = chip(302, AutocompleteItem.file(sampleFiles[0]))

        val store = h.makeStore(
            exhaustive = false,
            configureState = { it.withDraft("Apply @kit-motion to @SessionView.swift").ac { a -> a.copy(chips = listOf(skillChip, fileChip)) } },
            configureDependencies = {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    waitForIdle = { _, _ -> awaitCancellation() },
                    sendPrompt = { _, _, text, _, files, _, _ ->
                        capturedMentions.setValue(PromptContext.mentions.current())
                        capturedFiles.setValue(files)
                        capturedTexts.append(text)
                    },
                )
            },
        )

        store.send(Action.SendTapped) { it.withDraft("").ac { a -> a.copy(chips = emptyList()) } }
        store.receive(Action.PromptAdmitted::class)
        h.skipInFlightEffects()

        assertEquals(listOf("Apply @kit-motion to @SessionView.swift"), capturedTexts.value)
        val mentions = capturedMentions.value
        assertEquals(
            listOf(OpenCodeSkillMention(id = "kit-motion", mention = OpenCodeMention(text = "@kit-motion", start = 6, end = 17))),
            mentions?.skills,
        )
        assertEquals(
            listOf(
                OpenCodeFileMention(
                    uri = "file:///Users/kit/code/opencode-ios/OpenCode/SessionView.swift",
                    name = "SessionView.swift",
                    description = "OpenCode/SessionView.swift",
                    mention = OpenCodeMention(text = "@SessionView.swift", start = 21, end = 39),
                ),
            ),
            mentions?.files,
        )
        assertTrue("@file chips travel only in mentions.files, not duplicated into pendingFiles/files", capturedFiles.value.isEmpty())
    }

    @Test fun sendingSlashCommandChipCallsSendCommandEndpoint() = sessionTest { h ->
        data class Command(val name: String, val text: String, val mentions: OpenCodeAutocompleteMentions)
        val capturedCommand = LockIsolated<Command?>(null)
        val commandChip = chip(401, AutocompleteItem.command(sampleCommands[0]))
        val skillChip = chip(402, AutocompleteItem.skill(sampleSkills[0]))

        val store = h.makeStore(
            exhaustive = false,
            configureState = { it.withDraft("  /review   check @kit-motion changes  ").ac { a -> a.copy(chips = listOf(commandChip, skillChip)) } },
            configureDependencies = {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    sendCommand = { _, _, name, text, mentions, _ -> capturedCommand.setValue(Command(name, text, mentions)) },
                    waitForIdle = { _, _ -> awaitCancellation() },
                )
            },
        )

        store.send(Action.SendTapped) { it.withDraft("").ac { a -> a.copy(chips = emptyList()) } }
        store.receive({ (it as? Action.Autocomplete)?.action is ComposerAutocompleteAction.CommandFinished }, "autocomplete.commandFinished")
        h.skipInFlightEffects()

        val sent = capturedCommand.value
        assertEquals("review", sent?.name)
        assertEquals("check @kit-motion changes", sent?.text)
        assertEquals(
            listOf(OpenCodeSkillMention(id = "kit-motion", mention = OpenCodeMention(text = "@kit-motion", start = 6, end = 17))),
            sent?.mentions?.skills,
        )
    }

    @Test fun mentionSpansMatchTrimmedLocationPrefixedSentText() = sessionTest { h ->
        val capturedText = LockIsolated<String?>(null)
        val capturedMentions = LockIsolated<OpenCodeAutocompleteMentions?>(null)
        val skillChip = chip(501, AutocompleteItem.skill(sampleSkills[0]))
        val fileChip = chip(502, AutocompleteItem.file(sampleFiles[0]))
        val location = SharedLocation(
            latitude = 37.7749, longitude = -122.4194, accuracy = 10.0,
            place = "San Francisco", address = "Market St", capturedAt = Instant.ofEpochSecond(1_500),
        )

        val store = h.makeStore(
            exhaustive = false,
            configureState = {
                it.withDraft("   Use @kit-motion on @SessionView.swift   ").withPendingLocation(location).ac { a -> a.copy(chips = listOf(skillChip, fileChip)) }
            },
            configureDependencies = {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    waitForIdle = { _, _ -> awaitCancellation() },
                    sendPrompt = { _, _, text, _, _, _, _ ->
                        capturedText.setValue(text)
                        capturedMentions.setValue(PromptContext.mentions.current())
                    },
                )
            },
        )

        store.send(Action.SendTapped) {
            it.withDraft("").withPendingLocation(null).ac { a -> a.copy(chips = emptyList()) }
        }
        store.receive(Action.PromptAdmitted::class)
        h.skipInFlightEffects()

        val sentText = capturedText.value
        val mentions = capturedMentions.value
        val skillSpan = mentions?.skills?.firstOrNull()?.mention
        val fileSpan = mentions?.files?.firstOrNull()?.mention
        assertTrue("Expected sent text and mentions", sentText != null && skillSpan != null && fileSpan != null)
        assertEquals("@kit-motion", sentText!!.substring(skillSpan!!.start, skillSpan.end))
        assertEquals("@SessionView.swift", sentText.substring(fileSpan!!.start, fileSpan.end))
    }

    @Test fun commandFailureRestoresDraftChipsAndAttachmentsAndUsesClientSessionID() = sessionTest { h ->
        val createdSessionID = LockIsolated<String?>(null)
        val commandChip = chip(601, AutocompleteItem.command(sampleCommands[0]))
        val skillChip = chip(602, AutocompleteItem.skill(sampleSkills[0]))
        val attachment = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000603",
            data = byteArrayOf(0x89.toByte(), 0x50),
            mime = "image/png",
            name = "shot.png",
        )
        val project = OpenCodeProject(id = "proj_1", name = "opencode-ios", directory = session.directory)

        val store = h.makeStore(
            state = h.state { SessionFeature.State.draft(server = server, draftProject = project, projects = listOf(project)) },
            exhaustive = false,
            configureState = {
                it.withDraft("/review check @kit-motion").withPendingAttachments(listOf(attachment)).ac { a -> a.copy(chips = listOf(commandChip, skillChip)) }
            },
            configureDependencies = {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    createSession = { _, directory, id ->
                        createdSessionID.setValue(id)
                        OpenCodeSession(id = id ?: "ses_fallback", serverTitle = "New Session", projectID = "proj_1", directory = directory, updatedAt = Instant.ofEpochSecond(2_000))
                    },
                    sendCommand = { _, _, _, _, _, _ -> throw OpenCodeClientError(message = "Couldn’t run command", statusCode = 400) },
                )
            },
        )

        store.send(Action.SendTapped) {
            it.withDraft("").withPendingAttachments(emptyList()).ac { a -> a.copy(chips = emptyList()) }.copy(isAwaitingReply = true)
        }
        store.receive(Action.SessionCreated::class)
        store.receive({ (it as? Action.Autocomplete)?.action is ComposerAutocompleteAction.CommandFinished }, "autocomplete.commandFinished") {
            it.copy(isAwaitingReply = false)
                .withDraft("/review check @kit-motion")
                .withPendingAttachments(listOf(attachment))
                .ac { a -> a.copy(chips = listOf(commandChip, skillChip)) }
        }
        assertEquals("Couldn’t run command", store.state.dictationError)
        h.skipInFlightEffects()

        assertEquals("createSession uses a non-nil client session ID", true, createdSessionID.value?.startsWith("ses_"))
    }

    @Test fun catalogAndCommandLoadsUseSeparateCancelIDsWithoutWedgingLoadingFlags() = sessionTest { h ->
        val clock = TestClock()
        val catalogGate = CompletableDeferred<Unit>()

        val store = h.makeStore(
            configureDependencies = {
                this[Clock] = clock
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    skills = { _, _ -> catalogGate.await(); sampleSkills },
                    agents = { _, _ -> catalogGate.await(); sampleAgents },
                    commands = { _, _ -> sampleCommands },
                    findFiles = { _, _, _, _ -> emptyList() },
                    mentionSessions = { _, _, _ -> awaitCancellation() },
                )
            },
        )

        // Start `@` catalog load.
        store.send(draft("@")) {
            it.withDraft("@").ac { a ->
                a.copy(
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1),
                    isLoadingCatalog = true,
                    loadingCatalogDirectoryKey = session.directory,
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "",
                    sessionSearch = "",
                )
            }
        }

        // Switch to `/` while catalog is still in flight; command load must not cancel catalog or wedge `isLoadingCatalog`; it calls off the session search.
        store.send(draft("/")) {
            it.withDraft("/").ac { a ->
                a.copy(
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Command, query = "", key = "slash:0", utf16Start = 0, utf16End = 1),
                    isSearchingFiles = false,
                    lastFileSearchQuery = null,
                    sessionSearch = null,
                    isLoadingCommands = true,
                    loadingCommandsDirectoryKey = session.directory,
                )
            }
        }

        store.receive(autocomplete(ComposerAutocompleteAction.CommandsLoaded(directoryKey = session.directory, commands = sampleCommands))) {
            it.ac { a ->
                a.copy(isLoadingCommands = false, loadingCommandsDirectoryKey = null, loadedCommandsDirectoryKey = session.directory, commands = sampleCommands).recomputeRows()
            }
        }

        catalogGate.complete(Unit)
        store.receive(autocomplete(ComposerAutocompleteAction.CatalogLoaded(directoryKey = session.directory, skills = sampleSkills, agents = sampleAgents))) {
            it.ac { a ->
                a.copy(
                    isLoadingCatalog = false,
                    loadingCatalogDirectoryKey = null,
                    loadedCatalogDirectoryKey = session.directory,
                    skills = sampleSkills,
                    agents = visibleAgents,
                ).recomputeRows()
            }
        }

        assertFalse(store.state.autocomplete.isLoadingCatalog)
        assertFalse(store.state.autocomplete.isLoadingCommands)
        store.finish()
    }

    @Test fun sessionSwitchRoundTripsChipsAndFailedSendRetryKeepsMentions() = sessionTest { h ->
        val persistence = ComposerPersistence.inMemory()
        val childSession = OpenCodeSession(
            id = "ses_child", serverTitle = "Child Subagent", projectID = session.projectID,
            directory = session.directory, updatedAt = Instant.ofEpochSecond(1_100), parentID = session.id,
        )
        val skillChip = chip(701, AutocompleteItem.skill(sampleSkills[0]))
        val attempts = LockIsolated(0)
        val retryMentions = LockIsolated<OpenCodeAutocompleteMentions?>(null)

        val store = h.makeStore(
            exhaustive = false,
            configureState = {
                it.copy(
                    familySessions = listOf(session, childSession),
                    familyTranscriptCache = it.familyTranscriptCache + (childSession.id to listOf(OpenCodeTranscriptItem.UserText(id = "msg_child", text = "Child"))),
                ).withDraft("Use @kit-motion ").ac { a ->
                    a.copy(chips = listOf(skillChip), loadedCatalogDirectoryKey = session.directory, skills = sampleSkills)
                }
            },
            configureDependencies = {
                this[ComposerPersistence] = persistence
                this[Clock] = ImmediateClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                    getSession = { _, id -> if (id == childSession.id) childSession else session },
                    waitForIdle = { _, _ -> awaitCancellation() },
                    sendPrompt = { _, _, _, _, _, _, _ ->
                        if (attempts.increment() == 1) throw OpenCodeClientError(message = "Couldn’t send prompt", statusCode = 400)
                        retryMentions.setValue(PromptContext.mentions.current())
                    },
                )
            },
        )

        // Switch to child session: autocomplete resets and draft clears.
        store.send(Action.FamilyPicked(childSession.id)) {
            it.copy(session = childSession, autocomplete = ComposerAutocompleteState()).withDraft("")
        }

        // Switch back to parent session: draft and chips round-trip from persistence.
        store.send(Action.FamilyPicked(session.id)) {
            it.copy(session = session, autocomplete = ComposerAutocompleteState(chips = listOf(skillChip))).withDraft("Use @kit-motion ")
        }

        // Send and fail on attempt 1, then retry and verify `mentions` is preserved on retry.
        store.send(Action.SendTapped) { it.withDraft("").ac { a -> a.copy(chips = emptyList()) } }
        store.receive(Action.PromptFailed::class)
        val failedID = store.state.failedSends.firstOrNull()?.id
        assertTrue("Expected failed send entry", failedID != null)
        store.send(Action.FailedSendRetryTapped(failedID!!))
        store.receive(Action.PromptAdmitted::class)
        h.skipInFlightEffects()

        assertEquals(
            listOf(OpenCodeSkillMention(id = "kit-motion", mention = OpenCodeMention(text = "@kit-motion", start = 4, end = 15))),
            retryMentions.value?.skills,
        )
    }

    @Test fun caretAwareTriggerDetectionAndAutoRunCancellationWorkCleanly() = sessionTest { h ->
        val clock = TestClock()
        val store = h.makeStore(
            configureState = {
                it.ac { a ->
                    a.copy(
                        loadedCatalogDirectoryKey = session.directory,
                        skills = sampleSkills,
                        loadedCommandsDirectoryKey = session.directory,
                        commands = sampleCommands,
                    )
                }
            },
            configureDependencies = {
                this[Clock] = clock
                this[OpenCodeClient] = this[OpenCodeClient].copy(findFiles = { _, _, _, _ -> emptyList() })
            },
        )

        // 1. Caret in the middle of "hello @kit world" (at offset 10) detects "@kit" and replaces only [6, 10).
        store.send(draft("hello @kit world")) { it.withDraft("hello @kit world") }
        store.send(autocomplete(ComposerAutocompleteAction.CaretChanged(10))) {
            it.ac { a ->
                a.copy(
                    caretUTF16Offset = 10,
                    caretDraftUTF16Length = 16,
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:6", utf16Start = 6, utf16End = 10),
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "kit",
                    sessionSearch = "kit",
                ).recomputeRows()
            }
        }
        val expectedSkillChip = chip(0, AutocompleteItem.skill(sampleSkills[0]))
        store.send(autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.withDraft("hello @kit-motion  world").ac { a ->
                a.copy(
                    caretUTF16Offset = 18,
                    caretDraftUTF16Length = 24,
                    chips = listOf(expectedSkillChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedSkillChip,
                    lastChosenRowID = "skill:kit-motion",
                ).closePopover(dismissed = false)
            }
        }

        // 2. A chosen command schedules nothing that sends once the draft changes (Android's commandAction is `fill`).
        store.send(autocomplete(ComposerAutocompleteAction.CaretChanged(null))) {
            it.ac { a -> a.copy(caretUTF16Offset = null, caretDraftUTF16Length = null) }
        }
        store.send(draft("/rev")) {
            it.withDraft("/rev").ac { a ->
                a.copy(
                    chips = emptyList(),
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Command, query = "rev", key = "slash:0", utf16Start = 0, utf16End = 4),
                ).recomputeRows()
            }
        }
        val expectedCommandChip = chip(1, AutocompleteItem.command(sampleCommands[0]))
        store.send(autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.withDraft("/review ").ac { a ->
                a.copy(
                    chips = listOf(expectedCommandChip),
                    chooseGeneration = 2,
                    lastChosenChip = expectedCommandChip,
                    lastChosenRowID = "command:review",
                ).closePopover(dismissed = false)
            }
        }

        // Edit draft before `runDelay` (0.35 s) expires; advancing the clock must not emit `.sendTapped`.
        store.send(draft("/review wait")) { it.withDraft("/review wait") }
        clock.advance(1.seconds)
        assertEquals("Cancelled auto-run did not send the draft", "/review wait", store.state.draft)
        store.finish()
    }

    /** The `SessionFeature` half: the caret offsets a mid-draft chip insert and delete leave in state. */
    @Test fun midDraftChipInsertAndChipDeletePreserveCaretOffset() = sessionTest { h ->
        val clock = TestClock()
        val store = h.makeStore(
            configureState = { it.ac { a -> a.copy(loadedCatalogDirectoryKey = session.directory, skills = sampleSkills) } },
            configureDependencies = {
                this[Clock] = clock
                this[OpenCodeClient] = this[OpenCodeClient].copy(findFiles = { _, _, _, _ -> emptyList() })
            },
        )

        // 1. Type mid-draft at offset 10 ("hello @kit| world") and confirm "@kit-motion".
        store.send(draft("hello @kit world")) { it.withDraft("hello @kit world") }
        store.send(autocomplete(ComposerAutocompleteAction.CaretChanged(10))) {
            it.ac { a ->
                a.copy(
                    caretUTF16Offset = 10,
                    caretDraftUTF16Length = 16,
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:6", utf16Start = 6, utf16End = 10),
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "kit",
                    sessionSearch = "kit",
                ).recomputeRows()
            }
        }

        val expectedSkillChip = chip(0, AutocompleteItem.skill(sampleSkills[0]))
        store.send(autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.withDraft("hello @kit-motion  world").ac { a ->
                a.copy(
                    caretUTF16Offset = 18,
                    caretDraftUTF16Length = 24,
                    chips = listOf(expectedSkillChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedSkillChip,
                    lastChosenRowID = "skill:kit-motion",
                ).closePopover(dismissed = false)
            }
        }

        // 2. Delete the mid-draft chip and verify the caret lands at removeRange.location (offset 6).
        store.send(autocomplete(ComposerAutocompleteAction.ChipDeleted(expectedSkillChip.id))) {
            it.withDraft("hello  world").ac { a -> a.copy(caretUTF16Offset = 6, caretDraftUTF16Length = 12, chips = emptyList()) }
        }
        store.finish()
    }
}
