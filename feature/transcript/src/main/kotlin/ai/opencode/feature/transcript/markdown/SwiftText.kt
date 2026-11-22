package ai.opencode.feature.transcript.markdown

/**
 * `CharacterSet.whitespaces` (tab and Unicode `Zs`), `.whitespacesAndNewlines`,
 * `Character.isNumber`, and `split(separator: "\n")` on Characters (never inside `"\r\n"`).
 */
internal object SwiftText {
    /** `CharacterSet.whitespaces`: tab and the space separators (`Zs`). */
    fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** `CharacterSet.newlines`. */
    fun isNewline(c: Char): Boolean = c in '\n'..'\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    fun isWhitespaceOrNewline(c: Char): Boolean = isWhitespace(c) || isNewline(c)

    /** Any Unicode number (`Nd`, `Nl`, `No`). */
    fun isNumber(c: Char): Boolean = when (Character.getType(c)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    fun isCharacterWhitespace(c: Char): Boolean = c.isWhitespace() || c == '\u0085'

    /** Whether [c] joins the character before it into one grapheme (a combining mark or joiner). */
    fun extendsGrapheme(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> c == '\u200D' || c == '\u200C'
    }

    fun trimWhitespace(s: CharSequence): String = trim(s, ::isWhitespace)

    fun trimWhitespaceAndNewlines(s: CharSequence): String = trim(s, ::isWhitespaceOrNewline)

    fun trimNewlines(s: CharSequence): String = trim(s, ::isNewline)

    private inline fun trim(s: CharSequence, predicate: (Char) -> Boolean): String {
        var start = 0
        var end = s.length
        while (start < end && predicate(s[start])) start++
        while (end > start && predicate(s[end - 1])) end--
        return s.subSequence(start, end).toString()
    }

    /** `split(separator: "\n", omittingEmptySubsequences: false)` on Characters: `"\r\n"` stays whole. */
    fun lines(s: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        for (i in s.indices) {
            if (s[i] == '\n' && (i == 0 || s[i - 1] != '\r')) {
                result.add(s.substring(start, i))
                start = i + 1
            }
        }
        result.add(s.substring(start))
        return result
    }

    /** The UTF-8 length of [s] without allocating. */
    fun utf8Length(s: CharSequence, start: Int = 0, end: Int = s.length): Int {
        var count = 0
        var i = start
        while (i < end) {
            val c = s[i]
            count += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return count
    }

    /** The length of the common UTF-16 prefix of [a] and [b]. */
    fun commonPrefix(a: String, b: String): Int {
        val count = minOf(a.length, b.length)
        var index = 0
        while (index < count && a[index] == b[index]) index++
        return index
    }
}
