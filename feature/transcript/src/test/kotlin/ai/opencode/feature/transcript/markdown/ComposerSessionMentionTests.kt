package ai.opencode.feature.transcript.markdown

import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.model.SessionLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `String($0.text.characters)` → [InlineText.text]. */
class ComposerSessionMentionTests {
    private fun mentionID(seed: String) = "ses_" + seed + "0".repeat(26 - seed.length)

    @Test
    fun sentSessionTokensRenderAsSessionLinks() {
        val id = mentionID("DrawerPolish")
        assertNull(SessionLink.marked(plain = "No mentions here"))
        val marked = SessionLink.marked(plain = "Ask @session:$id now")
        assertEquals(listOf(id), marked?.let { SessionLink.ids(it) })
        val presented = marked?.let { SessionLinkText.present(it, targets = mapOf(id to SessionLinkTarget.Session(title = "Drawer Polish", parentID = null))) }
        val shown = presented?.text?.text ?: ""
        assertEquals(listOf(id), presented?.ids)
        assertTrue(shown, shown.startsWith("Ask ") && shown.endsWith(" now"))
        assertTrue("The chip shows the session's title", shown.contains("Drawer\u00A0Polish"))
        assertFalse("The token itself never shows", shown.contains("@session:"))
        // The token holds `s_`, so Markdown's no-link fast path still takes the full scan.
        assertTrue(SessionLink.mayRenderID("Ask @session:$id now"))
        val markdown = SessionLink.mark(InlineText.plain("Ask @session:$id now"), markdown = "Ask @session:$id now")
        assertEquals(listOf(id), SessionLink.ids(markdown))
    }
}
