package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.CachedHomeSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The session title rule: a real title wins, else the first prompt, else the placeholder. */
class SessionTitleTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun session(title: String = SessionTitle.placeholder, firstPrompt: String? = null, id: String = "ses_a") =
        OpenCodeSession(id = id, serverTitle = title, projectID = "p", directory = "/p", updatedAt = Instant.EPOCH, firstPrompt = firstPrompt)

    @Test
    fun realTitleWinsOverFirstPrompt() {
        assertEquals("Fix login", SessionTitle.display("  Fix login ", "Please fix the login"))
        assertEquals("Fix login", session("Fix login", firstPrompt = "Please fix the login").title)
    }

    @Test
    fun placeholderOrMissingTitleShowsFirstPrompt() {
        for (title in listOf(null, "", "  \n", "Untitled session", "untitled SESSION")) {
            assertEquals("Please fix the login", SessionTitle.display(title, "Please fix the login"))
        }
        assertEquals("Please fix the login", session(firstPrompt = "Please fix the login").title)
    }

    @Test
    fun nothingKnownShowsPlaceholder() {
        assertEquals("Untitled session", SessionTitle.display(null, null))
        assertEquals("Untitled session", SessionTitle.display("", "   "))
        assertEquals("Untitled session", session().title)
        assertTrue(session().isUntitled)
        assertFalse(session("Named").isUntitled)
    }

    @Test
    fun previewCollapsesWhitespaceToOneLine() {
        assertEquals("Fix the bug in the login form", SessionTitle.preview("  Fix the bug\n\n in the\tlogin   form \n"))
        assertNull(SessionTitle.preview(" \n\t "))
    }

    @Test
    fun previewKeepsSixtyCharactersUncut() {
        val sixty = "a".repeat(50) + " bcdefghi" // 59 characters plus one
        val text = sixty + "j"
        assertEquals(60, text.length)
        assertEquals(text, SessionTitle.preview(text))
    }

    @Test
    fun longPreviewCutsAtWordBoundaryWithEllipsis() {
        val text = "Refactor the session drawer so that rows show the first prompt when the server has no title yet"
        val preview = SessionTitle.preview(text)!!
        assertEquals("Refactor the session drawer so that rows show the first…", preview)
        assertTrue(preview.length <= SessionTitle.previewLimit + 1)
    }

    @Test
    fun longWordCutsMidWord() {
        val text = "Look at " + "x".repeat(80)
        val preview = SessionTitle.preview(text)!!
        assertEquals("Look at " + "x".repeat(52) + "…", preview)
    }

    @Test
    fun cutDropsTrailingPunctuationAndNeverSplitsSurrogates() {
        val punctuated = "Please review these files carefully, one by one, and then, " + "afterwards report back"
        assertEquals("Please review these files carefully, one by one, and then…", SessionTitle.preview(punctuated))
        val emoji = "x".repeat(59) + "😀" + " tail"
        val cut = SessionTitle.preview(emoji)!!
        assertEquals("x".repeat(59) + "…", cut)
    }

    @Test
    fun firstPromptNeedsTranscriptFromTheStart() {
        val items = listOf(
            OpenCodeTranscriptItem.AssistantMarkdown("a0", "Hello"),
            OpenCodeTranscriptItem.UserText("u1", "Add dark\nmode"),
            OpenCodeTranscriptItem.UserText("u2", "Also light mode"),
        )
        assertEquals("Add dark mode", SessionTitle.firstPrompt(items, reachesStart = true))
        assertNull(SessionTitle.firstPrompt(items, reachesStart = false))
        assertNull(SessionTitle.firstPrompt(listOf(OpenCodeTranscriptItem.AssistantMarkdown("a0", "Hi")), reachesStart = true))
    }

    @Test
    fun onlyAnUnnamedSessionLearnsItsFirstPromptOnce() {
        val learned = session().learningFirstPrompt("First")
        assertEquals("First", learned.firstPrompt)
        assertSame(learned, learned.learningFirstPrompt("Second"))
        val named = session("Named")
        assertSame(named, named.learningFirstPrompt("First"))
        assertSame(learned, learned.learningFirstPrompt(null))
    }

    @Test
    fun freshServerCopiesKeepTheKnownFirstPrompt() {
        val known = session(firstPrompt = "First")
        val fresh = session()
        assertEquals("First", fresh.keepingFirstPrompt(known).firstPrompt)
        assertNull(fresh.keepingFirstPrompt(session(firstPrompt = "Other", id = "ses_b")).firstPrompt)
        // Once the server names it, the real title shows; the prompt it knew stays behind it.
        assertEquals("Login fix", session("Login fix").keepingFirstPrompt(known).title)
        val list = listOf(fresh, session(id = "ses_b")).keepingFirstPrompts(listOf(known))
        assertEquals(listOf("First", "Untitled session"), list.map { it.title })
    }

    @Test
    fun cachedSnapshotsKeepTheTitleKeyAndTheFirstPrompt() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val cache = CachedHomeSnapshot(
            serverID = "s",
            snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session(firstPrompt = "Add dark mode")), activeSessionIDs = emptySet()),
        )
        val encoded = json.encodeToString(CachedHomeSnapshot.serializer(), cache)
        val row = json.parseToJsonElement(encoded).jsonObject["snapshot"]!!.jsonObject["sessions"]!!.toString()
        assertTrue(row, row.contains("\"title\":\"Untitled session\""))
        assertEquals(cache, json.decodeFromString(CachedHomeSnapshot.serializer(), encoded))
        assertEquals("Add dark mode", json.decodeFromString(CachedHomeSnapshot.serializer(), encoded).snapshot.sessions.single().title)
        // A row cached by an earlier build has no first prompt and still decodes.
        val legacy = """{"id":"ses_a","title":"Fix it","projectID":"p","directory":"/p","updatedAt":"1970-01-01T00:00:00Z"}"""
        assertEquals("Fix it", json.decodeFromString(OpenCodeSession.serializer(), legacy).title)
    }
}
