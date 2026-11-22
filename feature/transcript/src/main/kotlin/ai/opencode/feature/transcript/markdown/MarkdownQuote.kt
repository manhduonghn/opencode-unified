package ai.opencode.feature.transcript.markdown

/**
 * Formats selected assistant reply text as a Markdown blockquote and appends it to the composer
 * draft with a trailing blank line ready for the reply.
 */
object MarkdownQuote {
    fun blockquote(text: String): String? {
        val normalized = SwiftText.trimNewlines(normalizeNewlines(text))
        if (SwiftText.trimWhitespaceAndNewlines(normalized).isEmpty()) return null
        return normalized.split('\n').joinToString("\n") { "> $it" }
    }

    fun fencedCode(text: String, language: String?): String {
        val normalized = normalizeNewlines(text).trim('\n')
        val fenceLength = maxOf(3, longestBacktickRun(normalized) + 1)
        val fence = "`".repeat(fenceLength)
        val tag = SwiftText.trimWhitespaceAndNewlines(language ?: "")
        return "$fence$tag\n$normalized\n$fence"
    }

    fun append(blockquote: String, draft: String): String {
        val normalizedDraft = draft.replace("\r\n", "\n").replace("\r", "\n")
        if (SwiftText.trimWhitespaceAndNewlines(normalizedDraft).isEmpty()) return "$blockquote\n\n"
        val prefix = normalizedDraft.trimEnd('\n')
        return "$prefix\n\n$blockquote\n\n"
    }

    private fun normalizeNewlines(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n").replace("\u2028", "\n").replace("\u2029", "\n")

    private fun longestBacktickRun(text: String): Int {
        var best = 0
        var current = 0
        for (c in text) {
            if (c == '`') {
                current++
                if (current > best) best = current
            } else {
                current = 0
            }
        }
        return best
    }
}
