package ai.opencode.feature.transcript.markdown

/**
 * TeX math in Markdown: inline `$…$`, `$$…$$` and `\(…\)` within text, and display `$$…$$` and
 * `\[…\]` on lines of their own. Code spans, code blocks, and escaped dollars are never math, and
 * neither is currency: an opening `$` is followed by a non-space, its closing `$` follows a
 * non-space and isn't followed by a digit, so “$5 and $10” stays text.
 */
object MathSyntax {
    /** How inline math was written, so copying a reply gives it back as is. */
    enum class Delimiter(val rawValue: String, val open: String, val close: String) {
        Dollar("dollar", "$", "$"),
        DoubleDollar("doubleDollar", "$$", "$$"),
        Parenthesis("parenthesis", "\\(", "\\)");

        /** Marks an inline run as math (its characters are the TeX source). */
        val languageIdentifier: String get() = "x-math-$rawValue"

        companion object {
            fun from(languageIdentifier: String?): Delimiter? {
                if (languageIdentifier == null || !languageIdentifier.startsWith("x-math-")) return null
                val raw = languageIdentifier.removePrefix("x-math-")
                return entries.firstOrNull { it.rawValue == raw }
            }
        }
    }

    data class InlineSpan(val source: String, val delimiter: Delimiter)

    /** Display math on lines of its own. [raw] is the lines as written, delimiters included; [end] the line after the closing delimiter. */
    data class Display(val source: String, val raw: String, val end: Int)

    /** Stands in for a span while Markdown parses the text around it: a word of letters and digits, so emphasis around it still applies. */
    fun placeholder(index: Int): String = "zqxmath${index}zqx"

    /** The text with each inline math span replaced by a placeholder, and the spans in order. Code spans and backslash escapes pass through as written. */
    fun extractInline(text: String): Pair<String, List<InlineSpan>> {
        if (!text.contains('$') && !text.contains("\\(")) return text to emptyList()
        val c = text
        val masked = StringBuilder(text.length)
        val spans = ArrayList<InlineSpan>()
        var index = 0

        fun math(start: Int, end: Int, delimiter: Delimiter) {
            masked.append(placeholder(spans.size))
            spans.add(InlineSpan(c.substring(start, end), delimiter))
        }

        while (index < c.length) {
            val character = c[index]
            if (character == '`') {
                val run = runLength('`', c, index)
                val close = closingBackticks(run, c, index + run)
                if (close != null) {
                    masked.append(c, index, close + run)
                    index = close + run
                } else {
                    repeat(run) { masked.append('`') }
                    index += run
                }
                continue
            }
            if (character == '\\' && index + 1 < c.length) {
                val close = if (c[index + 1] == '(') find("\\)", c, index + 2) else null
                if (close != null && isSource(c, index + 2, close)) {
                    math(index + 2, close, Delimiter.Parenthesis)
                    index = close + 2
                } else {
                    // An escape: `\$` is a dollar sign, never a delimiter.
                    masked.append(character).append(c[index + 1])
                    index += 2
                }
                continue
            }
            if (character == '$') {
                if (index + 1 < c.length && c[index + 1] == '$') {
                    val close = find("$$", c, index + 2)
                    if (close != null && isSource(c, index + 2, close)) {
                        math(index + 2, close, Delimiter.DoubleDollar)
                        index = close + 2
                    } else {
                        masked.append("$$")
                        index += 2
                    }
                    continue
                }
                val close = closingDollar(c, index + 1)
                if (close != null) {
                    math(index + 1, close, Delimiter.Dollar)
                    index = close + 1
                    continue
                }
            }
            masked.append(character)
            index += 1
        }
        return masked.toString() to spans
    }

    /**
     * Display math opening `lines[start]`: `$$` or `\[` alone or with the math, through the
     * line that closes it. Null until it closes, so a streaming equation reads as text, never
     * as a half-typeset one.
     */
    fun display(lines: List<String>, start: Int): Display? {
        val first = SwiftText.trimWhitespace(lines[start])
        val open: String
        val close: String
        if (first.startsWith("$$")) {
            open = "$$"; close = "$$"
        } else if (first.startsWith("\\[")) {
            open = "\\["; close = "\\]"
        } else {
            return null
        }
        val rest = first.substring(open.length)
        if (rest.endsWith(close)) {
            val source = rest.substring(0, rest.length - close.length)
            if (!isSource(source, 0, source.length)) return null
            return Display(SwiftText.trimWhitespace(source), first, start + 1)
        }
        // Closed later on this line, with text after it: inline, in a paragraph.
        if (rest.contains(close)) return null
        val source = arrayListOf(rest)
        val raw = arrayListOf(first)
        var index = start + 1
        // A display that never closes in a reasonable span is text, and so is one with a blank
        // line in it (an error in TeX too), so a stray `$$` never swallows the paragraphs after it.
        while (index < lines.size && index - start <= 80) {
            val line = SwiftText.trimWhitespace(lines[index])
            if (line.isEmpty()) return null
            raw.add(line)
            if (line.endsWith(close)) {
                source.add(line.substring(0, line.length - close.length))
                val joined = SwiftText.trimWhitespaceAndNewlines(source.joinToString("\n"))
                if (joined.isEmpty()) return null
                return Display(joined, raw.joinToString("\n"), index + 1)
            }
            if (line.contains(close)) return null
            source.add(line)
            index += 1
        }
        return null
    }

    // MARK: Scanning

    private fun runLength(mark: Char, c: String, start: Int): Int {
        var end = start
        while (end < c.length && c[end] == mark) end++
        return end - start
    }

    /** A code span closes on a run of exactly as many backticks. */
    private fun closingBackticks(count: Int, c: String, start: Int): Int? {
        var index = start
        while (index < c.length) {
            if (c[index] != '`') {
                index++
                continue
            }
            val run = runLength('`', c, index)
            if (run == count) return index
            index += run
        }
        return null
    }

    /** The first unescaped occurrence of [pattern]. */
    private fun find(pattern: String, c: String, start: Int): Int? {
        var index = start
        while (index + pattern.length <= c.length) {
            if (c[index] == '\\' && pattern[0] != '\\') {
                index += 2
                continue
            }
            if (c.regionMatches(index, pattern, 0, pattern.length)) return index
            index++
        }
        return null
    }

    /** The `$` closing one opened just before [start], by the currency rules. Only the next unescaped `$` can close it, and inline `$` math stays on one line. */
    private fun closingDollar(c: String, start: Int): Int? {
        if (start >= c.length || SwiftText.isCharacterWhitespace(c[start]) || c[start] == '$') return null
        var index = start
        while (index < c.length) {
            val character = c[index]
            if (character == '\\') {
                index += 2
                continue
            }
            if (isCharacterNewline(character)) return null
            if (character == '$') {
                if (SwiftText.isCharacterWhitespace(c[index - 1])) return null
                if (index + 1 < c.length && SwiftText.isNumber(c[index + 1])) return null
                return index
            }
            index++
        }
        return null
    }

    private fun isCharacterNewline(c: Char): Boolean = c in '\n'..'\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    private fun isSource(c: String, start: Int, end: Int): Boolean {
        for (i in start until end) if (!SwiftText.isCharacterWhitespace(c[i])) return true
        return false
    }
}
