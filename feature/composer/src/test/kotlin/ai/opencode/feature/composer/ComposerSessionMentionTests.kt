package ai.opencode.feature.composer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.chip
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.drawerPolish
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.mentionID
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleAgents
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleCommands
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleFiles
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.sampleSkills
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.session
import ai.opencode.feature.composer.ComposerAutocompleteFixtures.state
import ai.opencode.feature.composer.ComposerAutocompleteTestHost.Action
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * An extension of `ComposerAutocompleteTests` (session mentions: desktop beta `composer/model.ts`,
 * `session-match.ts`, `prompt-parts.ts`). The store test drives [ComposerAutocompleteTestHost] like
 * `ComposerAutocompleteTests`.
 */
class ComposerSessionMentionTests {
    @Test fun sessionTitleMatchingAndServerSearchFollowDesktop() {
        assertTrue(OpenCodeAutocompleteSession.matches(title = "Drawer polish pass", query = "pol"))
        assertTrue(OpenCodeAutocompleteSession.matches(title = "Drawer polish pass", query = "DRAWER pol"))
        assertTrue("A trailing space asks for one more title word", OpenCodeAutocompleteSession.matches(title = "Drawer polish pass", query = "drawer "))
        assertFalse(OpenCodeAutocompleteSession.matches(title = "Drawer", query = "drawer "))
        assertFalse("Words must be consecutive and in order", OpenCodeAutocompleteSession.matches(title = "Drawer polish pass", query = "polish drawer"))
        assertFalse("Each query word starts a title word", OpenCodeAutocompleteSession.matches(title = "Redrawer", query = "drawer"))
        assertEquals("drawer", OpenCodeAutocompleteSession.serverSearch("Drawer pol"))
        assertEquals("", OpenCodeAutocompleteSession.serverSearch(""))

        assertTrue(OpenCodeAutocompleteSession.isMentionable(mentionID("DrawerPolish")))
        assertFalse(OpenCodeAutocompleteSession.isMentionable("ses_short"))
        assertEquals("New Session", OpenCodeAutocompleteSession.of(id = "ses_x", title = "  ", project = "", updatedAt = Instant.MIN).title)
        assertEquals("@session:${mentionID("DrawerPolish")}", drawerPolish.sentToken)

        // Only the mention already open keeps single spaces in its query.
        assertEquals(
            AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "drawer pol", key = "at:0", utf16Start = 0, utf16End = 11),
            AutocompleteTrigger.detect("@drawer pol", openMentionKey = "at:0"),
        )
        assertNull(AutocompleteTrigger.detect("@drawer pol"))
        assertNull("A double space ends the mention", AutocompleteTrigger.detect("@drawer  pol", openMentionKey = "at:0"))
        assertNull(AutocompleteTrigger.detect("Check @drawer pol", openMentionKey = "at:0"))
        assertEquals("drawer pol", AutocompleteTrigger.detect("Check @drawer pol", openMentionKey = "at:6")?.query)
    }

    @Test fun sessionRowsSitBetweenSubagentsAndFilesWithDesktopLimits() {
        val sessions = (0 until 10).map { index ->
            OpenCodeAutocompleteSession.of(
                id = mentionID("Drawer$index"),
                title = "Drawer $index",
                project = "opencode-ios",
                updatedAt = Instant.ofEpochSecond(2_000L - index),
            )
        }
        var state = ComposerAutocompleteState(
            sessions = sessions,
            activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1),
        )
        assertEquals("5 recent sessions with an empty query", sessions.take(5), state.matchingSessions(state.activeTrigger!!))
        state = state.copy(activeTrigger = state.activeTrigger!!.copy(query = "drawer"))
        assertEquals("Up to 8 title matches", sessions.take(8), state.matchingSessions(state.activeTrigger!!))
        state = state.copy(activeTrigger = state.activeTrigger!!.copy(query = "drawer 3"))
        assertEquals(listOf(sessions[3]), state.matchingSessions(state.activeTrigger!!))

        val rows = AutocompleteFuzzy.displayRows(
            mode = AutocompleteTrigger.Mode.Mention,
            query = "",
            skills = listOf(sampleSkills[0]),
            agents = listOf(sampleAgents[0]),
            files = sampleFiles,
            commands = emptyList(),
            sessions = listOf(drawerPolish),
        )
        assertEquals(
            listOf(AutocompleteItem.Kind.Skill, AutocompleteItem.Kind.Agent, AutocompleteItem.Kind.Session, AutocompleteItem.Kind.File, AutocompleteItem.Kind.File),
            rows.map { it.kind },
        )
        val sessionRow = rows[2]
        assertEquals("session:${drawerPolish.id}", sessionRow.id)
        assertEquals("Drawer Polish", sessionRow.title)
        assertEquals("The project is the row's hint", "opencode-ios", sessionRow.label)
        assertEquals("@Drawer Polish", sessionRow.draftToken)
        assertEquals("Session, Drawer Polish, opencode-ios", sessionRow.accessibilityLabel)

        // Nothing fixed matches: sessions alone. A spaced query: sessions alone.
        val noFixed = AutocompleteFuzzy.displayRows(
            mode = AutocompleteTrigger.Mode.Mention, query = "drawer", skills = sampleSkills, agents = emptyList(), files = emptyList(),
            commands = emptyList(), sessions = listOf(drawerPolish),
        )
        assertEquals(listOf("session:${drawerPolish.id}"), noFixed.map { it.id })
        val spaced = AutocompleteFuzzy.displayRows(
            mode = AutocompleteTrigger.Mode.Mention, query = "kit m", skills = sampleSkills, agents = emptyList(), files = sampleFiles,
            commands = emptyList(), sessions = emptyList(),
        )
        assertTrue(spaced.isEmpty())
        // Commands never list sessions.
        val commands = AutocompleteFuzzy.displayRows(
            mode = AutocompleteTrigger.Mode.Command, query = "", skills = emptyList(), agents = emptyList(), files = emptyList(),
            commands = sampleCommands, sessions = listOf(drawerPolish),
        )
        assertFalse(commands.any { it.kind == AutocompleteItem.Kind.Session })
    }

    @Test fun typingAtListsRecentRootSessionsSearchesAsYouTypeAndPicksAChip() = runTest {
        val clock = TestClock()
        val searches = mutableListOf<String>()
        val project = OpenCodeProject(id = "proj_1", name = "opencode-ios", directory = session.directory)
        val current = OpenCodeSession(
            id = mentionID("Current"),
            serverTitle = "Drawer Current",
            projectID = "proj_1",
            directory = session.directory,
            updatedAt = Instant.ofEpochSecond(1_950),
        )
        fun payload(seed: String, title: String, projectID: String, directory: String, updated: Long) =
            OpenCodeSession(id = mentionID(seed), serverTitle = title, projectID = projectID, directory = directory, updatedAt = Instant.ofEpochSecond(updated))
        val polish = payload("DrawerPolish", "Drawer Polish", "proj_1", session.directory, 1_900)
        val voice = payload("Voice", "Voice Call Retries", "proj_2", "/Users/kit/code/opencode", 1_800)
        val gestures = payload("Gestures", "Drawer Gestures", "proj_1", session.directory, 1_700)
        val malformed = OpenCodeSession(
            id = "ses_short", serverTitle = "Drawer Broken", projectID = "proj_1", directory = session.directory,
            updatedAt = Instant.ofEpochSecond(1_990),
        )
        val all = listOf(current, malformed, polish, voice, gestures)
        val directory = session.directory

        val store = TestStore(
            state(session = current) {
                copy(projects = listOf(project)).autocomplete { copy(loadedCatalogDirectoryKey = directory, skills = sampleSkills) }
            },
            ComposerAutocompleteTestHost(),
            this,
        ) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                findFiles = { _, _, query, _ -> if (query.isEmpty()) sampleFiles else emptyList() },
                mentionSessions = { _, search, limit ->
                    assertEquals(50, limit)
                    searches += search
                    if (search.isEmpty()) all else all.filter { OpenCodeAutocompleteSession.matches(it.title, search) }
                },
            )
        }
        val polishRow = OpenCodeAutocompleteSession.of(polish, listOf(project))
        val voiceRow = OpenCodeAutocompleteSession.of(voice, listOf(project))
        val gesturesRow = OpenCodeAutocompleteSession.of(gestures, listOf(project))
        assertEquals("opencode-ios", polishRow.project)
        assertEquals("Outside known projects the folder names the project", "opencode", voiceRow.project)

        // `@` asks at once for recent root sessions.
        store.send(Action.Draft("@")) {
            it.copy(draft = "@").autocomplete {
                copy(
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "", key = "at:0", utf16Start = 0, utf16End = 1),
                    fileSearchGeneration = 1,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "",
                    sessionSearch = "",
                ).recomputeRows()
            }
        }
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "", sessions = all))) {
            // This session and incomplete IDs never show.
            it.autocomplete { copy(sessions = listOf(polishRow, voiceRow, gesturesRow), answeredSessionSearch = "").recomputeRows() }
        }
        clock.advance(120.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 1, directory = directory, query = "", files = sampleFiles))) {
            it.autocomplete { copy(isSearchingFiles = false, files = sampleFiles).recomputeRows() }
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
        store.send(Action.Draft("@drawer")) {
            it.copy(draft = "@drawer").autocomplete {
                copy(
                    activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "drawer", key = "at:0", utf16Start = 0, utf16End = 7),
                    fileSearchGeneration = 2,
                    isSearchingFiles = true,
                    lastFileSearchQuery = "drawer",
                    sessionSearch = "drawer",
                ).recomputeRows()
            }
        }
        assertEquals(listOf("session:${polish.id}", "session:${gestures.id}"), store.state.autocomplete.rows.map { it.id })
        clock.advance(99.milliseconds)
        assertEquals(listOf(""), searches)
        clock.advance(1.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.SessionsLoaded(search = "drawer", sessions = listOf(current, malformed, polish, gestures)))) {
            it.autocomplete { copy(answeredSessionSearch = "drawer") }
        }
        clock.advance(20.milliseconds)
        store.receive(Action.Autocomplete(ComposerAutocompleteAction.FilesLoaded(generation = 2, directory = directory, query = "drawer", files = emptyList()))) {
            it.autocomplete { copy(isSearchingFiles = false, files = emptyList()).recomputeRows() }
        }

        // A space keeps the mention open for multi-word titles, without a new search.
        store.send(Action.Draft("@drawer p")) {
            it.copy(draft = "@drawer p").autocomplete {
                copy(activeTrigger = AutocompleteTrigger(AutocompleteTrigger.Mode.Mention, query = "drawer p", key = "at:0", utf16Start = 0, utf16End = 9))
                    .recomputeRows()
            }
        }
        assertEquals(listOf("session:${polish.id}"), store.state.autocomplete.rows.map { it.id })
        assertEquals(listOf("", "drawer"), searches)

        // Picking inserts the `@<title>` chip.
        val expectedChip = chip(0, AutocompleteItem.session(polishRow))
        store.send(Action.Autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.copy(draft = "@Drawer Polish ").autocomplete {
                copy(
                    chips = listOf(expectedChip),
                    chooseGeneration = 1,
                    lastChosenChip = expectedChip,
                    lastChosenRowID = "session:${polish.id}",
                ).closePopover(dismissed = false)
            }
        }
        assertEquals("@Drawer Polish", expectedChip.token)
        assertEquals("@session:${polish.id}", expectedChip.sentToken)
        store.finish()
    }

    @Test fun draftTextMapsSentTokensBackToChipTitles() {
        val sessionChip = chip(1_201, AutocompleteItem.session(drawerPolish))
        val skillChip = chip(1_202, AutocompleteItem.skill(sampleSkills[0]))
        val draft = "Ask @Drawer Polish about @kit-motion"
        val augmentation = (ComposerAutocompleteDraftResolution.resolve(draft = draft, chips = listOf(sessionChip, skillChip)) as? ComposerAutocompleteDraftResolution.Prompt)
            ?.augmentation ?: return fail("Expected prompt augmentation")
        val sent = requireNotNull(augmentation.text)
        assertEquals("Ask @session:${drawerPolish.id} about @kit-motion", sent)
        assertEquals(draft, ComposerAutocompleteDraftResolution.draftText(sent = sent, chips = listOf(sessionChip, skillChip)))

        // Without a session chip the sent text is the draft.
        val plain = (ComposerAutocompleteDraftResolution.resolve(draft = "Use @kit-motion", chips = listOf(skillChip)) as? ComposerAutocompleteDraftResolution.Prompt)
            ?.augmentation ?: return fail("Expected prompt augmentation")
        assertNull(plain.text)
    }
}
