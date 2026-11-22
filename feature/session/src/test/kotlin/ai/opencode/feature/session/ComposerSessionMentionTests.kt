package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.AutocompleteItem
import ai.opencode.feature.composer.AutocompleteTrigger
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompleteChip
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.ComposerPersistenceStorage
import ai.opencode.feature.composer.OpenCodeAutocompleteSession
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.chip
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.makeStore
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleFiles
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleSkills
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.server
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.session
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeMention
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSkillMention
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PromptContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ComposerSessionMentionTests {
    private fun autocomplete(action: ComposerAutocompleteAction): Action = Action.Autocomplete(action)
    private fun draft(text: String): Action = Action.Binding(BindingAction.Draft(text))
    private fun SessionFeature.State.ac(transform: (ComposerAutocompleteState) -> ComposerAutocompleteState) = copy(autocomplete = transform(autocomplete))

    /** A complete generated session ID (`ses_` and 26 letters or digits). */
    private fun mentionID(seed: String) = "ses_" + seed + "0".repeat(26 - seed.length)

    private val drawerPolish: OpenCodeAutocompleteSession
        get() = OpenCodeAutocompleteSession(id = mentionID("DrawerPolish"), title = "Drawer Polish", project = "opencode-ios", updatedAt = Instant.ofEpochSecond(1_900))

    @Test fun typingAtListsRecentRootSessionsSearchesAsYouTypeAndPicksAChip() = sessionTest { h ->
        val clock = TestClock()
        val searches = LockIsolated(listOf<String>())
        val issues = LockIsolated(listOf<String>())
        val project = OpenCodeProject(id = "proj_1", name = "opencode-ios", directory = session.directory)
        val current = OpenCodeSession(
            id = mentionID("Current"), serverTitle = "Drawer Current", projectID = "proj_1",
            directory = session.directory, updatedAt = Instant.ofEpochSecond(1_950),
        )
        fun payload(seed: String, title: String, projectID: String, directory: String, updated: Long) = OpenCodeSession(
            id = mentionID(seed), serverTitle = title, projectID = projectID, directory = directory, updatedAt = Instant.ofEpochSecond(updated),
        )
        val polish = payload("DrawerPolish", "Drawer Polish", "proj_1", session.directory, 1_900)
        val voice = payload("Voice", "Voice Call Retries", "proj_2", "/Users/kit/code/opencode", 1_800)
        val gestures = payload("Gestures", "Drawer Gestures", "proj_1", session.directory, 1_700)
        val malformed = OpenCodeSession(
            id = "ses_short", serverTitle = "Drawer Broken", projectID = "proj_1", directory = session.directory,
            updatedAt = Instant.ofEpochSecond(1_990),
        )
        val all = listOf(current, malformed, polish, voice, gestures)

        val store = h.makeStore(
            state = h.state {
                SessionFeature.State.session(
                    server = server,
                    session = current,
                    cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_existing", text = "Earlier")),
                    initialTranscriptRequestIsExternal = true,
                )
            },
            configureState = {
                it.copy(projects = listOf(project)).ac { a -> a.copy(loadedCatalogDirectoryKey = session.directory, skills = sampleSkills) }
            },
            configureDependencies = {
                this[Clock] = clock
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    findFiles = { _, _, query, _ -> if (query.isEmpty()) sampleFiles else emptyList() },
                    mentionSessions = { _, search, limit ->
                        if (limit != 50) issues.append("limit $limit")
                        searches.append(search)
                        if (search.isEmpty()) all else all.filter { OpenCodeAutocompleteSession.matches(it.title, search) }
                    },
                )
            },
        )
        val polishRow = OpenCodeAutocompleteSession.of(polish, listOf(project))
        val voiceRow = OpenCodeAutocompleteSession.of(voice, listOf(project))
        val gesturesRow = OpenCodeAutocompleteSession.of(gestures, listOf(project))
        assertEquals("opencode-ios", polishRow.project)
        assertEquals("Outside known projects the folder names the project", "opencode", voiceRow.project)

        // `@` asks at once for recent root sessions.
        store.send(draft("@")) {
            it.withDraft("@").ac { a ->
                a.copy(
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1),
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "",
                    sessionSearch = "",
                ).recomputeRows()
            }
        }
        store.receive(autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "", sessions = all))) {
            // This session and incomplete IDs never show.
            it.ac { a -> a.copy(sessions = listOf(polishRow, voiceRow, gesturesRow), answeredSessionSearch = "").recomputeRows() }
        }
        clock.advance(120.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 1, directory = session.directory, query = "", files = sampleFiles))) {
            it.ac { a -> a.copy(isSearchingFiles = false, files = sampleFiles).recomputeRows() }
        }
        assertEquals(
            listOf(
                AutocompleteItem.Kind.Skill, AutocompleteItem.Kind.Skill, AutocompleteItem.Kind.Skill,
                AutocompleteItem.Kind.Session, AutocompleteItem.Kind.Session, AutocompleteItem.Kind.Session,
                AutocompleteItem.Kind.File, AutocompleteItem.Kind.File,
            ),
            store.state.autocomplete.rows.map { it.kind },
        )
        assertTrue(store.state.autocomplete.isOpen)

        // Typing narrows by title and searches the first word after 100 ms.
        store.send(draft("@drawer")) {
            it.withDraft("@drawer").ac { a ->
                a.copy(
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "drawer", key = "at:0", utf16Start = 0, utf16End = 7),
                    fileSearchGeneration = 2,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "drawer",
                    sessionSearch = "drawer",
                ).recomputeRows()
            }
        }
        assertEquals(listOf("session:${polish.id}", "session:${gestures.id}"), store.state.autocomplete.rows.map { it.id })
        clock.advance(99.milliseconds)
        assertEquals(listOf(""), searches.value)
        clock.advance(1.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "drawer", sessions = listOf(current, malformed, polish, gestures)))) {
            it.ac { a -> a.copy(answeredSessionSearch = "drawer") }
        }
        clock.advance(20.milliseconds)
        store.receive(autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 2, directory = session.directory, query = "drawer", files = emptyList()))) {
            it.ac { a -> a.copy(isSearchingFiles = false, files = emptyList()).recomputeRows() }
        }

        // A space keeps the mention open for multi-word titles, without a new search.
        store.send(draft("@drawer p")) {
            it.withDraft("@drawer p").ac { a ->
                a.copy(activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "drawer p", key = "at:0", utf16Start = 0, utf16End = 9)).recomputeRows()
            }
        }
        assertEquals(listOf("session:${polish.id}"), store.state.autocomplete.rows.map { it.id })
        assertEquals(listOf("", "drawer"), searches.value)

        // Picking inserts the `@<title>` chip.
        val expectedChip = chip(0, AutocompleteItem.session(polishRow))
        store.send(autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.withDraft("@Drawer Polish ").ac { a ->
                a.copy(
                    chips = listOf(expectedChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedChip,
                    lastChosenRowID = "session:${polish.id}",
                ).closePopover(dismissed = false)
            }
        }
        assertEquals("@Drawer Polish", expectedChip.token)
        assertEquals("@session:${polish.id}", expectedChip.sentToken)
        assertTrue(issues.value.isEmpty())
        store.finish()
    }

    @Test fun sendingASessionChipSendsDesktopsTokenAndShiftsLaterMentionSpans() = sessionTest { h ->
        val captured = LockIsolated<Pair<String, OpenCodeAutocompleteMentions?>?>(null)
        val sessionChip = chip(1_001, AutocompleteItem.session(drawerPolish))
        val skillChip = chip(1_002, AutocompleteItem.skill(sampleSkills[0]))
        val sentText = "Ask @session:${drawerPolish.id} about @kit-motion"

        val store = h.makeStore(
            exhaustive = false,
            configureState = { it.withDraft("Ask @Drawer Polish about @kit-motion").ac { a -> a.copy(chips = listOf(sessionChip, skillChip)) } },
            configureDependencies = {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    waitForIdle = { _, _ -> awaitCancellation() },
                    sendPrompt = { _, _, text, _, _, _, _ -> captured.setValue(text to PromptContext.mentions.current()) },
                )
            },
        )

        store.send(Action.SendTapped) {
            it.withDraft("").ac { a -> a.copy(chips = emptyList()) }
                .copy(sessionLinks = it.sessionLinks + (drawerPolish.id to SessionLinkTarget.Session(title = "Drawer Polish", parentID = null)))
        }
        store.receive(Action.PromptAdmitted::class)
        h.skipInFlightEffects()

        val sent = captured.value!!
        assertEquals(sentText, sent.first)
        val skillMention = OpenCodeMention(text = "@kit-motion", start = 50, end = 61)
        assertEquals(listOf(OpenCodeSkillMention(id = "kit-motion", mention = skillMention)), sent.second?.skills)
        assertEquals("@kit-motion", sentText.substring(50, 61))
        assertEquals(OpenCodeTranscriptItem.UserText(id = store.state.lastSentItemID ?: "", text = sentText), store.state.items.last())
    }

    @Test fun failedSessionMentionRetriesTheSameTextAndEditBringsTheChipBack() = sessionTest { h ->
        val sentTexts = LockIsolated(listOf<String>())
        val sessionChip = chip(1_101, AutocompleteItem.session(drawerPolish))
        val skillChip = chip(1_102, AutocompleteItem.skill(sampleSkills[0]))
        val sentText = "Ask @session:${drawerPolish.id} about @kit-motion"

        val store = h.makeStore(
            exhaustive = false,
            configureState = { it.withDraft("Ask @Drawer Polish about @kit-motion").ac { a -> a.copy(chips = listOf(sessionChip, skillChip)) } },
            configureDependencies = {
                this[Clock] = ImmediateClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                    sendPrompt = { _, _, text, _, _, _, _ ->
                        sentTexts.append(text)
                        throw OpenCodeClientError(message = "Couldn’t send prompt", statusCode = 400)
                    },
                )
            },
        )

        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        val failedID = store.state.failedSends.firstOrNull()?.id
        assertTrue("Expected a failed send", failedID != null)
        store.send(Action.FailedSendRetryTapped(failedID!!))
        store.receive(Action.PromptFailed::class)
        assertEquals("A retry resends the same words", listOf(sentText, sentText), sentTexts.value)

        val retriedID = store.state.failedSends.firstOrNull()?.id
        assertTrue("Expected the retry to fail", retriedID != null)
        store.send(Action.FailedSendEditTapped(retriedID!!))
        assertEquals("Edit shows the title again, not the token", "Ask @Drawer Polish about @kit-motion", store.state.draft)
        assertEquals(listOf(sessionChip, skillChip), store.state.autocomplete.chips)
        assertEquals(2, AutocompleteTrigger.resolvedChipRanges(store.state.draft, store.state.autocomplete.chips).size)
        h.skipInFlightEffects()
    }

    @Test fun sessionMentionRidesTheOutboxAcrossRelaunchAsItsToken() = sessionTest { h ->
        val tempDir = Files.createTempDirectory("ComposerSessionMentionTests-").toFile()
        try {
            val persistenceServer = OpenCodeServer(url = "http://localhost:9", username = "opencode", password = "pw")
            val persistenceSession = OpenCodeSession(
                id = "ses_1", serverTitle = "Durable drafts", projectID = "proj_1",
                directory = "/Users/kit/code/opencode", updatedAt = Instant.ofEpochSecond(1_000),
            )
            val firstID = "msg_00000000000100000000000000"
            val mentioned = OpenCodeAutocompleteSession(id = "ses_DrawerPolish00000000000000", title = "Drawer Polish", project = "OpenCode", updatedAt = Instant.ofEpochSecond(900))
            val sessionChip = ComposerAutocompleteChip(AutocompleteItem.session(mentioned), id = UUID.fromString("00000000-0000-0000-0000-000000000901"))
            val sentText = "Ask @session:${mentioned.id} first"
            val firstGate = CompletableDeferred<Unit>()
            val firstPersistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds)

            val initialState = h.state {
                SessionFeature.State.session(
                    server = persistenceServer,
                    session = persistenceSession,
                    cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_existing", text = "Earlier")),
                    initialTranscriptRequestIsExternal = true,
                )
            }.withDraft("Ask @Drawer Polish first").ac { a -> a.copy(chips = listOf(sessionChip)) }

            val firstStore = h.store(initialState) {
                this[UuidClient] = UuidClient.incrementing()
                this[Clock] = ImmediateClock()
                this[ComposerPersistence] = firstPersistence
                this[PromptOutbox] = PromptOutbox()
                this[UndeliveredPrompts] = UndeliveredPrompts()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    sendPrompt = { _, _, _, _, _, _, _ -> firstGate.await() },
                    waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") },
                )
            }
            firstStore.exhaustive = false

            firstStore.send(Action.Task)
            firstStore.send(Action.SendTapped)
            firstStore.send(Action.SceneEnteredBackground)

            val stateFile = File(tempDir, "composer-state.json")
            repeat(200) { if (!stateFile.exists()) kotlinx.coroutines.delay(10) }
            val frozenSnapshot = stateFile.readBytes()
            val outbox = firstPersistence.loadOutbox(persistenceServer.persistenceID, persistenceSession.id)
            assertEquals("The outbox keeps desktop's token, not the title", listOf(sentText), outbox.map { it.pending.text })
            assertEquals("and the chip, so Edit can show the title again", listOf(listOf(sessionChip)), outbox.map { it.pending.chips })

            firstGate.complete(Unit)
            firstStore.receive(Action.PromptAdmitted::class)
            firstStore.finish()
            stateFile.writeBytes(frozenSnapshot)

            // Relaunch: the resumed send carries the same token.
            val secondPersistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds)
            val relaunchedTexts = LockIsolated(listOf<String>())
            val secondStore = h.store(
                h.state {
                    SessionFeature.State.session(
                        server = persistenceServer,
                        session = persistenceSession,
                        cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_existing", text = "Earlier")),
                        initialTranscriptRequestIsExternal = true,
                    )
                },
            ) {
                this[UuidClient] = UuidClient.incrementing()
                this[Clock] = ImmediateClock()
                this[ComposerPersistence] = secondPersistence
                this[PromptOutbox] = PromptOutbox()
                this[UndeliveredPrompts] = UndeliveredPrompts()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                    pendingInputs = { _, _ -> emptyList() },
                    sendPrompt = { _, _, text, _, _, _, _ -> relaunchedTexts.append(text) },
                    waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") },
                )
            }
            secondStore.exhaustive = false

            secondStore.send(Action.Task)
            secondStore.receive(Action.PromptAdmitted::class)
            secondStore.finish()

            assertEquals(listOf(sentText), relaunchedTexts.value)
            assertTrue(secondStore.state.items.contains(OpenCodeTranscriptItem.UserText(id = firstID, text = sentText)))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
