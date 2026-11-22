package ai.opencode.feature.transcript.selection

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.ui.InlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.AtomicRun
import ai.opencode.feature.transcript.markdown.ui.MathRenderer
import ai.opencode.feature.transcript.markdown.ui.PresentedText
import androidx.compose.ui.text.AnnotatedString
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Android differences:
 * - A Link Icon's slot draws one character, and a typeset inline formula draws its source under one
 *   placeholder, so the drawn counts differ; what each reads as is the same.
 */
// Robolectric: link presentation parses hosts with `android.net.Uri`; words come from ICU.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSelectionTests {
    @get:Rule val isolation = TestIsolationRule()

    companion object {
        val sessionID = "ses_" + "a1B2".repeat(6) + "c3"

        val markdown = """
## Heading with `code` inside

Plain **bold** and *italic* text with `inline code`, emoji 🎉👩‍💻, a link to https://github.com/anomalyco/opencode for reference, and $sessionID as a chip.

Inline math ${'$'}x^2 + y^2 = z^2${'$'} sits on the line with the words around it.

- A bullet that is long enough to wrap onto a second line at this width for sure.
1. A numbered item
- [x] A finished task

> A quote, set in from the margin.

```swift
let summary = SessionSummary(title: "Lab", count: 42) // a long line that scrolls sideways

print(summary)
```

| Name | Count |
| --- | --- |
| Apples | 3 |
| Pears with a long name | 12 |

${'$'}${'$'}\int_0^1 x\,dx${'$'}${'$'}

The end.
A second line in the same paragraph.
        """.trimIndent()
    }

    private val style = MarkdownStyle(TranscriptLayoutTuning(), OpenCodeColors.resolve(AppTheme.System, isDark = false))

    private fun context(): TranscriptSelectionContext = TranscriptSelectionContext(
        style = style,
        density = Density(1f),
        sessionTargets = mapOf(sessionID to SessionLinkTarget.Session(title = "Fix the composer", parentID = null)),
        linkStyle = InlineLinkStyle.Address,
        decoratesLinks = true,
    )

    private fun document(markdown: String, item: String = "reply"): TranscriptSelectionDocument =
        TranscriptSelectionDocument.make(listOf(TranscriptSelectionRow(item, OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null))), context())

    private fun range(text: String, document: TranscriptSelectionDocument): TextRange {
        val start = document.string.indexOf(text)
        assertTrue("\"$text\" is in the document", start >= 0)
        return TextRange(start, text.length)
    }

    @Test
    fun copyJoinsBlocksListsTablesAndCodeLikeTheirMarkdown() {
        val document = document(markdown)
        // Inside one block: just the words.
        assertEquals("bold and", document.copiedText(range("bold and", document)))
        // From mid-heading through the list: whole items keep their prefixes, blocks a blank line apart.
        val from = range("code inside", document).start
        val to = range("A numbered item", document).end
        val copied = document.copiedText(TextRange(from, to - from))
        assertTrue(copied, copied.startsWith("code inside\n\nPlain bold"))
        assertTrue("Session chips copy their titles", copied.contains("Fix the composer"))
        assertTrue("Inline math copies its source", copied.contains("\$x^2 + y^2 = z^2\$"))
        assertTrue(copied, copied.endsWith("\n\n- A bullet that is long enough to wrap onto a second line at this width for sure.\n1. A numbered item"))
        // A table: tabs between cells, rows on lines.
        val tableStart = range("Name", document).start
        val table = TextRange(tableStart, range("12", document).end - tableStart)
        assertEquals("Name\tCount\nApples\t3\nPears with a long name\t12", document.copiedText(table))
    }

    @Test
    fun quoteFencesCodeAndCopyLeavesItBare() {
        val document = document(markdown)
        val from = range("A quote, set in", document).start
        val to = range("let summary", document).end
        val selected = TextRange(from, to - from)
        assertEquals("A quote, set in from the margin.\n\nlet summary", document.copiedText(selected))
        assertEquals("A quote, set in from the margin.\n\n```swift\nlet summary\n```", document.quotedText(selected))
        // A display formula is one piece, read as written.
        val math = document.leaves.first { it.role == TranscriptSelectionLeaf.Role.Math }
        assertEquals("$$\\int_0^1 x\\,dx$$", math.text)
        assertEquals(1, math.segments.size)
        // Typeset, an image read as its source; untypeset (jlatexmath under Robolectric), the source as drawn.
        val typesets = MathRenderer.render("\\int_0^1 x\\,dx", 17f, display = true) != null
        assertEquals(typesets, math.segments.first().isAtomic)
        assertEquals(math.text, document.copiedText(TextRange(document.starts[document.leaves.indexOf(math)], math.length)))
    }

    @Test
    fun drawnImagesMapToWholePiecesOfText() {
        var document = document(markdown)
        // Link Icons: the slot draws a word joiner carrying the icon before the link, reading nothing.
        val linked = document.leaves.first { it.text.contains("github.com/") }
        val slot = linked.segments.first { it.isAtomic }
        assertEquals(1, slot.drawnCount)
        assertEquals(0, slot.textCount)
        val link = linked.text.indexOf("github.com/")
        assertEquals(link + 1, linked.drawnOffset(link))
        assertEquals(link, linked.textOffset(link + 1))
        assertTrue(linked.isLink(link + 1))
        // A typeset formula draws as one piece and reads its whole source.
        var math = document.leaves.first { it.text.contains("\$x^2") }
        if (math.segments.none { it.isAtomic }) {
            // jlatexmath can't typeset under Robolectric, so the formula draws as written; lay
            // out what `present` records for a typeset one (its source under one placeholder).
            assertNull(MathRenderer.render("x^2 + y^2 = z^2", 17f, display = false))
            val drawn = "Inline math x^2 + y^2 = z^2 sits on the line."
            val start = drawn.indexOf("x^2")
            val presented = PresentedText(
                AnnotatedString(drawn), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), true, null,
                atomics = listOf(AtomicRun(start, start + "x^2 + y^2 = z^2".length, "\$x^2 + y^2 = z^2\$")),
            )
            val leaf = TranscriptSelectionText.leaf(math.key, math.role, math.blockIndex, presented)
            val leaves = ArrayList(document.leaves)
            leaves[document.leaves.indexOf(math)] = leaf
            document = TranscriptSelectionDocument(leaves)
            math = leaf
        }
        val formula = math.segments.first { it.isAtomic }
        assertEquals("\$x^2 + y^2 = z^2\$".length, formula.textCount)
        assertEquals(formula.textStart, math.textOffset(formula.drawnStart))
        assertEquals(formula.textEnd, math.textOffset(formula.drawnEnd))
        // Its word is the whole formula.
        val word = document.wordRange(document.offset(document.leaves.indexOf(math), formula.textStart + 2))
        assertEquals("\$x^2 + y^2 = z^2\$", document.string.substring(word.start, word.end))
        // Emoji count in UTF-16, as the drawn layout does.
        assertEquals("🎉👩‍💻".length, range("🎉👩‍💻", document).length)
    }

    @Test
    fun aReplySpansItsRowsAcrossToolGroupsAndEndsWithItsTurn() {
        val tool = OpenCodeTranscriptItem.Notice(id = "n1", text = "Shell finished", isError = false)
        val entries = listOf(
            TranscriptEntry.UserGroup("u1", listOf(OpenCodeTranscriptItem.UserText("u1", "Go")), startsTurn = true),
            TranscriptEntry.Single(OpenCodeTranscriptItem.AssistantMarkdown("a1", "First part.")),
            TranscriptEntry.ToolGroup("t1", listOf(tool)),
            TranscriptEntry.Single(OpenCodeTranscriptItem.AssistantMarkdown("a2", "Second part.")),
            TranscriptEntry.ReplyEnd("reply-end-a2", listOf("First part.", "Second part.")),
            TranscriptEntry.UserGroup("u2", listOf(OpenCodeTranscriptItem.UserText("u2", "More")), startsTurn = true),
            TranscriptEntry.Single(OpenCodeTranscriptItem.AssistantMarkdown("a3", "Next turn.")),
        )
        val rows = TranscriptSelectionDocument.replyRows("a2", entries)
        assertEquals(listOf("a1", "a2"), rows.map { it.id })
        val document = TranscriptSelectionDocument.make(
            rows.map { TranscriptSelectionRow(it.id, OpenCodeMarkdownBlock.blocks(it.text, inlineCache = null)) },
            context(),
        )
        assertEquals("Tool chrome never joins the text", "First part.\n\nSecond part.", document.copiedText(TextRange(0, document.length)))
        assertEquals(listOf("a3"), TranscriptSelectionDocument.replyRows("a3", entries).map { it.id })
        assertTrue(TranscriptSelectionDocument.replyRows("missing", entries).isEmpty())
    }

    @Test
    fun autoscrollRunsOnlyNearTheVisibleEdgesAndEasesIn() {
        val top = 120f
        val bottom = 700f
        val scroller = TranscriptSelectionAutoscroller
        assertEquals("Nothing in the middle", 0f, scroller.velocity(400f, top, bottom))
        assertEquals(0f, scroller.velocity(top + scroller.edge + 1, top, bottom))
        val shallow = scroller.velocity(top + scroller.edge - 4, top, bottom)
        val deep = scroller.velocity(top + 4, top, bottom)
        assertTrue("Up, faster the deeper", shallow < 0 && deep < shallow)
        assertEquals("Past the edge (over the header): full speed", -scroller.maximumSpeed, scroller.velocity(top - 40, top, bottom))
        assertTrue(scroller.velocity(bottom - 4, top, bottom) > scroller.velocity(bottom - scroller.edge + 4, top, bottom))
        assertEquals("Over the composer or keyboard: full speed down", scroller.maximumSpeed, scroller.velocity(bottom + 80, top, bottom))
        assertEquals("Never past the list's ends", 0f, scroller.step(100f, -960f, 1f, 0f..500f))
        assertEquals(160f, scroller.step(100f, 120f, 0.5f, 0f..500f))
    }

    @Test
    fun theReplySelectsAcrossBlocksAsOneDocument() {
        val document = document("## Plan\n\nFirst paragraph here.\n\n- One item\n- Two items\n\n```swift\nlet x = 1\n```\n\nLast words.", item = "a1")
        val word = document.wordRange(document.starts[1])
        assertEquals("First", document.string.substring(word.start, word.end))
        val end = range("Last words", document).end
        assertEquals(
            "First paragraph here.\n\n- One item\n- Two items\n\nlet x = 1\n\nLast words",
            document.copiedText(TextRange(word.start, end - word.start)),
        )
        // The reply grows while selected: the selection's leaves keep their keys and offsets.
        val grown = TranscriptSelectionDocument.make(
            listOf(TranscriptSelectionRow("a1", OpenCodeMarkdownBlock.blocks("## Plan\n\nFirst paragraph here.\n\n- One item\n- Two items\n\n```swift\nlet x = 1\n```\n\nLast words.\n\nMore arrives.", inlineCache = null))),
            context(),
        )
        for ((index, leaf) in document.leaves.withIndex()) {
            assertEquals(index, grown.index(leaf.key))
        }
    }

    @Test
    fun selectionsNeverSplitAnEmoji() {
        val document = document("Done 👩‍💻\n\nNext 🎉 here.", item = "a1")
        val widened = document.composed(TextRange(document.starts[1] + "Next ".length + 1, 1))
        assertEquals("🎉", document.string.substring(widened.start, widened.end))
        val inside = document.starts[0] + "Done ".length + 2
        val boundary = document.nearestBoundary(inside)
        assertTrue(boundary == "Done ".length || boundary == document.leaves[0].length)
        assertFalse(boundary == inside)
        val word = document.wordRange(document.starts[0] + "Done ".length)
        assertNotNull(word)
        assertEquals("👩‍💻", document.string.substring(word.start, word.end))
    }

    @Test
    fun codeCopiesExactlyAndProseDropsDrawingCharacters() {
        val document = document("A\u00A0chip\u2060y line.\n\n```\nlet s = \"a\u00A0b\u2060c\"\n```")
        val all = TextRange(0, document.length)
        assertEquals("A chipy line.\n\nlet s = \"a\u00A0b\u2060c\"", document.copiedText(all))
    }
}
