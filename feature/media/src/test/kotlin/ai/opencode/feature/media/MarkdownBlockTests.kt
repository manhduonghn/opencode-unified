package ai.opencode.feature.media

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockTests {
    private val palette = SyntaxHighlighter.Palette(
        keyword = Color.Red, string = Color.Green, number = Color.Blue, comment = Color.Gray, type = Color.Magenta,
    )

    private fun coloredWords(highlighted: AnnotatedString): List<String> =
        highlighted.spanStyles.sortedBy { it.start }.filter { it.item.color != Color.Unspecified }
            .map { highlighted.text.substring(it.start, it.end) }

    @Test fun highlightingPreservesTextAndColorsKeywords() {
        val source = "// add\nfunc add(a: Int) -> Int { return a + 1 }"
        val highlighted = SyntaxHighlighter.highlight(source, "swift", palette)

        assertEquals(source, highlighted.text)
        val coloredWords = coloredWords(highlighted)
        assertTrue(coloredWords.contains("func"))
        assertTrue(coloredWords.contains("return"))
        assertTrue(coloredWords.contains("Int"))
        assertTrue(coloredWords.contains("// add"))
        assertTrue(coloredWords.contains("1"))
    }

    @Test fun highlightingHandlesStringsAndUnknownLanguage() {
        val source = "print(\"hi there\") # note"
        val highlighted = SyntaxHighlighter.highlight(source, null, palette)

        assertEquals(source, highlighted.text)
        val coloredWords = coloredWords(highlighted)
        assertTrue(coloredWords.contains("\"hi there\""))
        assertTrue(coloredWords.contains("# note"))
    }

    /** Each case in turn. */
    @Test fun highlightingKeepsUnicodeAndPartialTokenBoundaries() {
        val cases = listOf(
            Triple("swift", "🧑🏽‍💻 let café = \"e\u0301 🐈\" // τέλος\nreturn café", listOf("let", "\"e\u0301 🐈\"", "// τέλος", "return")),
            Triple("typescript", "const café = 'it\\'s 🐈'; /* 注釈 */ return 42", listOf("const", "'it\\'s 🐈'", "/* 注釈 */", "return", "42")),
            Triple("python", "def résumé():\n  return '👋' # 끝", listOf("def", "return", "'👋'", "# 끝")),
            Triple("swift", "let value = \"unfinished 🐈", listOf("let", "\"unfinished 🐈")),
            Triple("swift", "", emptyList()),
        )
        for ((language, source, tokens) in cases) {
            val highlighted = SyntaxHighlighter.highlight(source, language, palette)
            assertEquals(source, highlighted.text)
            assertEquals(source, tokens, coloredWords(highlighted))
        }
    }
}
