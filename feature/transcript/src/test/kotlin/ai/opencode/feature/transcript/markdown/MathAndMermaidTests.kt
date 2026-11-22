package ai.opencode.feature.transcript.markdown

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.transcript.markdown.MathSyntax.Delimiter
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/*
 * Inline and display math in the block parser ([MathSyntax], [OpenCodeMarkdownBlock]) and Mermaid
 * fences becoming diagram blocks.
 *
 * Dropped: `mathSurvivesTheTranscriptCache`: a JSON round trip of parsed blocks; Android never
 * persists them (rows keep raw text and re-parse).
 */
class MathAndMermaidTests {
    @get:Rule val isolation = TestIsolationRule()

    private data class Span(val source: String, val delimiter: Delimiter)

    private fun paragraph(markdown: String): InlineText? =
        (OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null).firstOrNull()?.kind as? Kind.Paragraph)?.text

    private fun math(text: InlineText?): List<Span> =
        text?.runs?.mapNotNull { run -> Delimiter.from(run.languageIdentifier)?.let { Span(run.text, it) } } ?: emptyList()

    private fun kinds(markdown: String): List<String> = OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null).map {
        when (it.kind) {
            is Kind.Paragraph -> "paragraph"
            is Kind.Math -> "math"
            is Kind.Mermaid -> "mermaid"
            is Kind.Code -> "code"
            else -> "other"
        }
    }

    // Inline math

    @Test fun inlineMathInEachDelimiter() {
        val text = paragraph("Area \$A = \\pi r^2\$, \\(e^{i\\pi} + 1 = 0\\) and \$\$\\sum_i x_i\$\$ here.")
        assertEquals(
            listOf(
                Span("A = \\pi r^2", Delimiter.Dollar),
                Span("e^{i\\pi} + 1 = 0", Delimiter.Parenthesis),
                Span("\\sum_i x_i", Delimiter.DoubleDollar),
            ),
            math(text),
        )
        assertEquals("Area A = \\pi r^2, e^{i\\pi} + 1 = 0 and \\sum_i x_i here.", text?.text)
    }

    @Test fun currencyIsNotMath() {
        for (markdown in listOf(
            "Lunch was \$5 and coffee \$10.",
            "It costs \$5, \$10, or \$20.",
            "Between \$5 and \$ 10\$ is a gap.",
            "Prices: \$3.50 and \$4.",
            "A lone \$ sign.",
            "Pay \$5 now",
        )) {
            assertTrue(markdown, math(paragraph(markdown)).isEmpty())
        }
        assertEquals("Lunch was \$5 and coffee \$10.", paragraph("Lunch was \$5 and coffee \$10.")!!.text)
        // The closing rules still let math follow currency.
        assertEquals(listOf(Span("x", Delimiter.Dollar)), math(paragraph("\$5 and \$10 or \$x\$")))
    }

    @Test fun escapedDollarsAreText() {
        val text = paragraph("It costs \\\$5 and \\\$x\\\$ stays.")
        assertTrue(math(text).isEmpty())
        assertEquals("It costs \$5 and \$x\$ stays.", text?.text)
        // An escaped dollar inside math is part of its source.
        assertEquals(listOf(Span("a \\\$ b", Delimiter.Dollar)), math(paragraph("\$a \\\$ b\$")))
    }

    @Test fun codeSpansAndBlocksAreNeverMath() {
        val text = paragraph("Run `echo \$x\$` or ``a \$y\$ b`` then \$z\$.")
        assertEquals(listOf(Span("z", Delimiter.Dollar)), math(text))
        assertEquals("Run echo \$x\$ or a \$y\$ b then z.", text?.text)
        val code = (OpenCodeMarkdownBlock.blocks("```sh\necho \$HOME and \$\$\n\$x\$\n```", inlineCache = null).firstOrNull()?.kind as? Kind.Code)?.code
            ?: return fail("Expected a code block")
        assertEquals("echo \$HOME and \$\$\n\$x\$", code.source)
    }

    @Test fun markdownNeverReadsInsideMath() {
        assertEquals(
            listOf(Span("a*b*c", Delimiter.Dollar), Span("x_1 + y_2", Delimiter.Dollar)),
            math(paragraph("\$a*b*c\$ and \$x_1 + y_2\$")),
        )
        // Formatting around math still applies.
        val text = paragraph("**bold \$x\$** and *\$y\$*")
        assertEquals(listOf("x", "y"), math(text).map { it.source })
        assertEquals("bold x and y", text?.text)
    }

    @Test fun inlineMathInHeadingsListsAndTables() {
        val blocks = OpenCodeMarkdownBlock.blocks("## Solving \$x^2 = 4\$\n\n- root \$x = 2\$\n\n| a | b |\n|---|---|\n| \$\\alpha\$ | 1 |", inlineCache = null)
        val heading = blocks.getOrNull(0)?.kind as? Kind.Heading
        val item = blocks.getOrNull(1)?.kind as? Kind.ListItem
        val table = blocks.getOrNull(2)?.kind as? Kind.Table
        if (blocks.size != 3 || heading == null || item == null || table == null) return fail("Expected a heading, a list item and a table")
        assertEquals(listOf("x^2 = 4"), math(heading.text).map { it.source })
        assertEquals(listOf("x = 2"), math(item.text).map { it.source })
        assertEquals(listOf("\\alpha"), math(table.rows[0][0]).map { it.source })
    }

    @Test fun copyingKeepsTheOriginalDelimiters() {
        val markdown = "Area \$A = \\pi r^2\$, then \\(e^{i\\pi}\\) and \$\$\\sum x\$\$, plus \$5 and \$10 and **\$y\$**."
        assertEquals(markdown, OpenCodeMarkdownBlock.markdown(OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null)))
    }

    // Display math

    @Test fun displayMathBlocks() {
        val markdown = "Before\n\n\$\$\n\\int_0^1 x^2\\,dx = \\frac{1}{3}\n\$\$\n\nAfter\n\n\\[ e = mc^2 \\]\n\n\$\$x + y\$\$"
        val blocks = OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null)
        assertEquals(listOf("paragraph", "math", "paragraph", "math", "math"), kinds(markdown))
        val first = blocks[1].kind as? Kind.Math ?: return
        val second = blocks[3].kind as? Kind.Math ?: return
        val third = blocks[4].kind as? Kind.Math ?: return
        assertEquals("\\int_0^1 x^2\\,dx = \\frac{1}{3}", first.math.source)
        assertEquals("e = mc^2", second.math.source)
        assertEquals("x + y", third.math.source)
        assertEquals(markdown, OpenCodeMarkdownBlock.markdown(blocks))
    }

    @Test fun displayMathWithTextAfterItIsInline() {
        assertEquals(listOf("paragraph"), kinds("\$\$x\$\$, where x is big"))
        assertEquals(listOf("x"), math(paragraph("\$\$x\$\$, where x is big")).map { it.source })
        // A stray `$$` never swallows the paragraphs after it.
        assertEquals(listOf("paragraph", "paragraph", "paragraph"), kinds("\$\$ is how it starts\n\nSome text\n\nmore \$\$"))
    }

    /** Until `$$` closes, the equation streams as text; once it closes it stays an equation. */
    @Test fun streamingDisplayMathShowsTextUntilItCloses() {
        val markdown = "Text\n\n\$\$\n\\frac{a}{b}\n\$\$\n\nAfter"
        val closedAt = markdown.indexOf("}\n\$\$") + "}\n\$\$".length
        var wasMath = false
        for (length in 0..markdown.length) {
            val isMath = kinds(markdown.substring(0, length)).contains("math")
            assertEquals("length $length", length >= closedAt, isMath)
            if (wasMath) assertTrue("An equation never turns back into text, length $length", isMath)
            wasMath = isMath
        }
        // An unclosed equation reads as its source.
        assertEquals("\$\$\n\\frac{a}{b}", paragraph("\$\$\n\\frac{a}{b}")!!.text)
    }

    @Test fun streamingInlineMathIsTextUntilItCloses() {
        assertTrue(math(paragraph("The area \$A = \\pi r")).isEmpty())
        assertEquals(listOf("A = \\pi r^2"), math(paragraph("The area \$A = \\pi r^2\$")).map { it.source })
    }

    // Mermaid

    @Test fun closedMermaidFencesAreDiagrams() {
        val markdown = "Look:\n\n```mermaid\nflowchart TD\n  A --> B\n```\n\nDone"
        assertEquals(listOf("paragraph", "mermaid", "paragraph"), kinds(markdown))
        val blocks = OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null)
        val mermaid = blocks[1].kind as? Kind.Mermaid ?: return
        assertEquals("flowchart TD\n  A --> B", mermaid.source)
        assertEquals(markdown, OpenCodeMarkdownBlock.markdown(blocks))
        assertEquals(listOf("mermaid"), kinds("```Mermaid\ngraph LR\nA-->B\n```"))
        assertEquals(listOf("code"), kinds("```swift\nlet x = 1\n```"))
    }

    @Test fun streamingMermaidShowsCodeUntilTheFenceCloses() {
        val markdown = "```mermaid\nsequenceDiagram\n  A->>B: Hi\n```"
        for (length in 0 until markdown.length) {
            assertFalse("length $length", kinds(markdown.substring(0, length)).contains("mermaid"))
        }
        assertEquals(listOf("code"), kinds("```mermaid\nsequenceDiagram\n  A->>B: Hi"))
        assertEquals(listOf("mermaid"), kinds(markdown))
    }
}
