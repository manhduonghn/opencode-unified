package ai.opencode.feature.transcript.markdown

import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkMenuText
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.feature.transcript.markdown.ui.InlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.MarkdownPresentation
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.PresentationOptions
import ai.opencode.feature.transcript.markdown.ui.PresentedText
import ai.opencode.feature.transcript.markdown.ui.SessionLinkContext
import ai.opencode.server.model.SessionLink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/*
 * `AttributedString(markdown:)` → [InlineMarkdownParser.parse].
 *
 * Dropped: `reinjectingAnEqualContextDoesNotRedrawItsReaders` (a `UIHostingController` redraw
 * count; Android's [SessionLinkContext] carries no closure, only targets and the row, so an equal
 * context is an equal value, which `aStreamedDeltaLeavesTheRowsLinkContextEqual` checks).
 */
class SessionLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    companion object {
        const val ID = "ses_ea0cb2871ffeNzq62aRonBKB2l"
        const val OTHER = "ses_e9ff44bffffe07ia9B2fLdhYb2"
    }

    private val style = MarkdownStyle(TranscriptLayoutTuning(), OpenCodeColors.resolve(AppTheme.System, isDark = false))

    private fun linkedIDs(markdown: String): List<String> =
        OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null).flatMap { it.sessionLinkIDs }

    private fun paragraph(markdown: String): InlineText {
        val kind = OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null).firstOrNull()?.kind
        return (kind as? Kind.Paragraph)?.text ?: run { fail("Not a paragraph"); throw AssertionError() }
    }

    /** Each run of one chip, once. */
    private fun chips(text: InlineText): List<SessionChip> {
        val out = mutableListOf<SessionChip>()
        var previous: SessionChip? = null
        for (run in text.runs) {
            val chip = run.sessionChip
            if (chip != null && chip != previous) out += chip
            previous = chip
        }
        return out
    }

    private fun present(
        inline: InlineText,
        targets: Map<String, SessionLinkTarget> = emptyMap(),
        linkStyle: InlineLinkStyle = InlineLinkStyle.Today,
    ): PresentedText = MarkdownPresentation.present(
        inline,
        PresentationOptions(style.bodyColor, style.bodySize, linkStyle = linkStyle),
        style,
        targets = targets,
        density = Density(1f),
        links = null,
        siteIcon = { null },
    )

    // region Parsing

    @Test fun recognizesCompleteIDsInInlineCodeAndText() {
        assertEquals(listOf(ID), linkedIDs("Open `$ID` now"))
        assertEquals(listOf(ID, OTHER), linkedIDs("See $ID, then $OTHER."))
        assertEquals(listOf(ID), linkedIDs("Tagged @session:$ID here"))
        assertEquals(listOf(ID), linkedIDs("- item `$ID`"))
        assertEquals(listOf(ID), linkedIDs("| a | b |\n|---|---|\n| `$ID` | x |"))
    }

    @Test fun leavesPartialAndEmbeddedIDsAlone() {
        val partial = ID.dropLast(1)
        assertTrue("An unfinished ID isn't a link", linkedIDs("Streaming `$partial`").isEmpty())
        assertTrue(linkedIDs("Streaming $partial").isEmpty())
        assertTrue("A path isn't a link", linkedIDs("path /tmp/$ID/log").isEmpty())
        assertTrue("Nor a longer identifier", linkedIDs("name x_$ID").isEmpty())
        assertTrue("Inline code links only when it is the ID", linkedIDs("call `open($ID)`").isEmpty())
        assertTrue("Never in a fence", linkedIDs("```\n$ID\n```").isEmpty())
        assertTrue(linkedIDs("[label](https://example.com/$ID)").isEmpty())
    }

    @Test fun aWindowsPathIsNotALink() {
        assertTrue("A backslash bounds a path like a slash", linkedIDs("log at C:\\$ID\\log").isEmpty())
        assertTrue(linkedIDs("dir \\$ID").isEmpty())
        assertTrue(linkedIDs("dir $ID\\").isEmpty())
    }

    @Test fun copiedMarkdownKeepsTheIDAsWritten() {
        val text = paragraph("Open `$ID` or $OTHER")
        assertEquals("Open `$ID` or $OTHER", OpenCodeMarkdownBlock.markdown(text))
    }

    // endregion

    // region Presentation

    @Test fun showsTheTitleOrAShortIDAndMapsArrivals() {
        val text = paragraph("Open `$ID` now")

        val resolving = required(SessionLinkText.present(text, emptyMap()))
        assertEquals("Open \u00A0\u2007\u00A0ses_ea0c…KB2l\u00A0\u202F now", resolving.text.text)
        assertEquals(listOf(ID), resolving.ids)
        assertEquals(listOf(SessionChip(ID, muted = true, isSubagent = false)), chips(resolving.text))

        val titled = required(SessionLinkText.present(text, mapOf(ID to SessionLinkTarget.Session("HOME", null))))
        assertEquals("Open \u00A0\u2007\u00A0HOME\u00A0\u202F now", titled.text.text)
        val link = titled.text.runs.firstNotNullOf { it.link }
        assertEquals(ID, SessionLink.id(link))
        assertEquals("HOME", SessionLink.title(link))
        assertFalse("A title reads as text", titled.text.runs.first { it.link != null }.code)
        // No run is link blue or filled. Android styles at presentation.
        val shown = present(text, mapOf(ID to SessionLinkTarget.Session("HOME", null)))
        assertTrue(
            "Not blue, no square fill",
            shown.text.spanStyles.all { it.item.color != style.colors.link && (it.item.background == Color.Unspecified) },
        )
        // " now" starts at 35 in the source and after the nine-character chip here.
        assertEquals(14, titled.offset(35))
        assertNull(titled.knownLength(40))

        val glyph = required(SessionLinkText.present(text, mapOf(ID to SessionLinkTarget.Session("Fix the build", "ses_parent"))))
        assertEquals("A chip never wraps", "Open \u00A0\u2007\u00A0Fix\u00A0the\u00A0build\u00A0\u202F now", glyph.text.text)
        assertEquals(listOf(SessionChip(ID, muted = false, isSubagent = true)), chips(glyph.text))
        assertNull(SessionLinkText.present(InlineText.plain("plain"), emptyMap()))
    }

    @Test fun longTitlesAreShortened() {
        val title = "a".repeat(60)
        assertEquals(SESSION_LINK_MAXIMUM_TITLE, SessionLinkTarget.Session(title, null).label(ID).length)
        assertEquals("New Session", SessionLinkTarget.Session(" ", null).label(ID))
        assertEquals("ses_ea0c…KB2l", SessionLinkTarget.Missing.label(ID))
    }

    @Test fun holdingOffersCopyIDAndVoiceOverOpensBySessionName() {
        val url = SessionLink.url(ID, "HOME")
        assertTrue(LinkMenuAction.offers(url))
        assertEquals(listOf(LinkMenuAction.CopySessionID), LinkMenuAction.actions(url, ExperimentalFeatures.testValue))
        assertEquals("Open session HOME", LinkMenuText.accessibilityName(LinkMenuAction.OpenSession, url, listOf(url)))
        assertEquals("Copy ID", LinkMenuText.accessibilityName(LinkMenuAction.CopySessionID, url, listOf(url)))
        val plain = present(InlineText.plain("x"), linkStyle = InlineLinkStyle.Address)
        assertTrue(plain.inlineContent.isEmpty() && plain.edits.isEmpty())
        val linked = present(InlineText(listOf(InlineRun("HOME", link = url))), linkStyle = InlineLinkStyle.Address)
        assertTrue("No site icon on a session link", linked.inlineContent.keys.none { it.startsWith("icon:") })
    }

    // endregion

    // region Hot paths

    @Test fun aStreamedDeltaLeavesTheRowsLinkContextEqual() {
        val targets = mapOf<String, SessionLinkTarget>(ID to SessionLinkTarget.Session("HOME", null))
        val first = SessionLinkContext(targets, row = "a")
        // Each reconfigure makes a new context; only targets and the row count.
        assertEquals(first, SessionLinkContext(targets, row = "a"))
        assertNotEquals(first, SessionLinkContext(targets, row = "b"))
        assertNotEquals(first, SessionLinkContext(mapOf(ID to SessionLinkTarget.Resolving), row = "a"))
    }

    @Test fun markingChecksTheSourceAndStillFindsEscapedIDs() {
        assertTrue(OpenCodeMarkdownBlock.uncachedInlineEntry("A **plain** reply about uses_ and cases").attributed.runs.all { it.link == null })
        val escaped = OpenCodeMarkdownBlock.uncachedInlineEntry("See ${ID.replace("_", "\\_")} now").attributed
        assertEquals("An escaped underscore renders the ID, so it links as before", listOf(ID), SessionLink.ids(escaped))
        assertEquals(listOf(ID), SessionLink.ids(OpenCodeMarkdownBlock.uncachedInlineEntry("See `$ID`").attributed))
    }

    @Test fun theSourceGuardNeverSkipsALinkTheFullScanFinds() {
        val tail = ID.drop("ses_".length)
        val rendering = listOf(
            "See ses&#95;$tail now", "See ses&lowbar;$tail", "See ses&#x5F;$tail.", "&#115;es_$tail",
            "See ses\\_$tail now", "`$ID`", "Open $ID or `$OTHER`",
        )
        // Foundation keeps inline HTML as text, so a comment joins nothing.
        val other = listOf(
            "se<!--x-->s_$tail",
            "A **plain** reply about uses_ and cases", "`ses&#95;$tail`", "ses_${tail.take(6)}**${tail.drop(6)}**",
            "**ses**_$tail", "[$ID](https://example.com)", "Fish &amp; chips \\* no IDs",
        )
        fun check(markdown: String): List<String> {
            val parsed = InlineMarkdownParser.parse(markdown)
            val fast = SessionLink.mark(parsed, markdown)
            val slow = SessionLink.markRuns(parsed)
            assertEquals(markdown, slow, fast)
            return SessionLink.ids(slow)
        }
        for (markdown in rendering + other) {
            assertEquals(markdown, !rendering.contains(markdown), check(markdown).isEmpty())
        }
        // Markup Markdown drops, at each junction of the prefix and around it.
        val dropped = listOf(
            "", "<!--x-->", "<b>", "</b>", "<b></b>", "*", "**", "_", "__", "~~", "`", "``", "[]()", "[](https://x)",
            "![](x)", "[^1]", "<https://x>", "&#95;", "&#115;", "\\", "\\_", "]",
        )
        for (a in dropped) {
            for (b in dropped) {
                val body = "se${a}s${b}_$tail"
                for (wrapped in listOf(body, "*$body*", "**$body**", "~~$body~~", "x $body y")) check(wrapped)
            }
        }
        assertFalse(SessionLink.mayRenderID("Plain reply with `code`, **bold** and [a link](https://example.com)"))
    }

    @Test fun onlyTextThatPresentsLinksCarriesChips() {
        for (markdown in listOf("Plain reply with [a link](https://example.com) and `code`", "Open `$ID` or $OTHER")) {
            val shown = present(paragraph(markdown))
            // `ArrivingText` trusts this to skip the chip scans.
            assertEquals(markdown, shown.sessionIDs.isNotEmpty(), shown.chips.isNotEmpty())
        }
    }

    // endregion
}

private fun <T : Any> required(value: T?): T {
    assertNotNull(value)
    return value!!
}
