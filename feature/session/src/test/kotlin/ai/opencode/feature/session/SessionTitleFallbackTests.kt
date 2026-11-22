package ai.opencode.feature.session

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionTitle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The session screen names an unnamed session after its first prompt. */
class SessionTitleFallbackTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val untitled = OpenCodeSession(id = "ses_free", serverTitle = SessionTitle.placeholder, projectID = "p", directory = "/p", updatedAt = Instant.EPOCH)
    private val items = listOf(
        OpenCodeTranscriptItem.UserText("u1", "Add a dark mode\n\nto the settings screen"),
        OpenCodeTranscriptItem.AssistantMarkdown("a1", "Done."),
    )

    private fun state(session: OpenCodeSession, history: SessionFeature.State.EarlierHistory) =
        SessionFeature.State.session(server, session, cachedItems = items).copy(isLoading = false, earlierHistory = history)

    @Test
    fun transcriptFromTheStartNamesAnUnnamedSession() = runTest {
        val store = TestStore(state(untitled, SessionFeature.State.EarlierHistory.Exhausted), SessionFeature(), this)
        val learned = untitled.copy(firstPrompt = "Add a dark mode to the settings screen")
        store.send(SessionFeature.Action.AcceptedTranscriptUpdated(untitled.id, items)) {
            it.copy(session = learned, familySessions = listOf(learned))
        }
        assertEquals("Add a dark mode to the settings screen", store.state.session?.title)
        store.finish()
    }

    @Test
    fun shortWindowFromTheServerNamesAnUnnamedSession() {
        // V2 sends a `next` cursor even when nothing is older; a page shorter than its limit reaches the start.
        val start = state(untitled, SessionFeature.State.EarlierHistory.UnknownEdge)
        val accepted = SessionFeature().acceptWindow(items, SessionFeature.AuthoritativeRefresh.PageCursor("older", reachesStart = true), start)
        assertEquals("Add a dark mode to the settings screen", accepted.session?.title)
        assertEquals("Add a dark mode to the settings screen", accepted.familySessions.single().title)
        val partial = SessionFeature().acceptWindow(items, SessionFeature.AuthoritativeRefresh.PageCursor("older"), start)
        assertEquals("Untitled session", partial.session?.title)
    }

    @Test
    fun windowThatDoesNotReachTheStartLearnsNothing() = runTest {
        val store = TestStore(state(untitled, SessionFeature.State.EarlierHistory.Paging("cursor")), SessionFeature(), this)
        store.send(SessionFeature.Action.AcceptedTranscriptUpdated(untitled.id, items))
        assertEquals("Untitled session", store.state.session?.title)
        store.finish()
    }

    @Test
    fun namedSessionKeepsItsTitle() = runTest {
        val named = untitled.copy(serverTitle = "Dark mode")
        val store = TestStore(state(named, SessionFeature.State.EarlierHistory.Exhausted), SessionFeature(), this)
        store.send(SessionFeature.Action.AcceptedTranscriptUpdated(named.id, items))
        store.finish()
    }

    @Test
    fun serverTitleReplacesTheFirstPromptWhenItArrives() = runTest {
        val learned = untitled.copy(firstPrompt = "Add a dark mode to the settings screen")
        val store = TestStore(state(learned, SessionFeature.State.EarlierHistory.Exhausted), SessionFeature(), this)
        val renamed = learned.copy(serverTitle = "Dark mode settings")
        store.send(SessionFeature.Action.FamilySessionRenamed(learned.id, "Dark mode settings")) {
            it.copy(session = renamed, familySessions = listOf(renamed))
        }
        assertEquals("Dark mode settings", store.state.session?.title)
        store.finish()
    }

    @Test
    fun freshServerCopyKeepsTheFirstPrompt() = runTest {
        val learned = untitled.copy(firstPrompt = "Add a dark mode to the settings screen")
        val start = state(learned, SessionFeature.State.EarlierHistory.Exhausted)
        val observed = start.updateObservedSession(untitled.copy(updatedAt = Instant.ofEpochSecond(5)))
        assertEquals("Add a dark mode to the settings screen", observed.session?.title)
        assertEquals("Add a dark mode to the settings screen", observed.familySessions.single().title)
    }
}
