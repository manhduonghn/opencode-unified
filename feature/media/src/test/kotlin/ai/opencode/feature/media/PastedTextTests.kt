package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PastedTextThresholdTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun AgentTextDocument.lines() =
        (0 until displayLineCount).map { index -> lineRange(index).let { text.substring(it.first, it.last + 1) } }

    @Test
    fun plainDocumentSplitsLinesWithoutHighlighting() {
        assertEquals(listOf("one", "two", "", "four"), AgentTextDocument.plainText("one\ntwo\n\nfour\n").lines())
        assertEquals(1, AgentTextDocument.plainText("").lines().size)
    }
}
