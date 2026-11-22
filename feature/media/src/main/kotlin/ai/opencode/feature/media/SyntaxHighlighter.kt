package ai.opencode.feature.media

import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Lightweight regex-free tokenizer colouring fenced code blocks and the file viewer. Not a full
 * grammar: keywords, strings, comments, numbers, and capitalized type names cover the readability
 * win.
 *
 * Kotlin strings are UTF-16, so token ranges are UTF-16 offsets; every budget (the 128 KB prefix,
 * the 1,000-byte line cap, the safe prefix) is still counted in UTF-8 bytes. The scanner steps over
 * whole grapheme clusters (a letter keeps its combining marks), except that `\r\n` is two
 * characters here: a CRLF file's lines end where they should.
 *
 * Tokens are colour-free so cached results stay legible when the theme or appearance changes;
 * [annotated] colours them with a [Palette].
 */
object SyntaxHighlighter {
    /**
     * Lines longer than this render plain so minified JS/JSON, SVG paths, or base64 blobs never
     * stall token scanning during streaming or file open.
     */
    const val maxHighlightedLineUTF8Bytes = 1_000

    /** Overall byte budget per highlight call, shared by code blocks and the file viewer. */
    const val maxHighlightedUTF8Bytes = 128 * 1024

    enum class TokenKind { Keyword, String, Number, Comment, Type }

    /** One coloured run, `[start, end)` in UTF-16 offsets of the highlighted source. */
    @Immutable
    data class Token(val start: Int, val end: Int, val kind: TokenKind)

    /** The theme's syntax colours. */
    @Immutable
    data class Palette(
        val keyword: Color,
        val string: Color,
        val number: Color,
        val comment: Color,
        val type: Color,
    ) {
        fun color(kind: TokenKind): Color = when (kind) {
            TokenKind.Keyword -> keyword
            TokenKind.String -> string
            TokenKind.Number -> number
            TokenKind.Comment -> comment
            TokenKind.Type -> type
        }

        companion object {
            val current: Palette
                @Composable @ReadOnlyComposable get() {
                    val colors = OpenCodeTheme.colors
                    return Palette(colors.syntaxKeyword, colors.syntaxString, colors.syntaxNumber, colors.syntaxComment, colors.syntaxType)
                }
        }
    }

    /**
     * The tokens, and the prefix (ending just after a newline outside any token) that later text
     * can never recolour, so a streaming code block only rescans what follows it.
     */
    @Immutable
    data class Result(
        val tokens: List<Token>,
        val safePrefixUTF8Count: Int,
        val safePrefixTokens: List<Token>,
    )

    /** A previous [highlightIncremental] result and the source it scanned. */
    data class Previous(val source: String, val safePrefixUTF8Count: Int, val safePrefixTokens: List<Token>)

    /** [code] coloured with [palette], highlighting at most [maxUTF8Bytes] (snapped to a line). */
    fun highlight(
        code: String,
        language: String?,
        palette: Palette,
        maxUTF8Bytes: Int = maxHighlightedUTF8Bytes,
    ): AnnotatedString = annotated(code, tokens(code, language, maxUTF8Bytes), palette)

    /** The tokens [highlight] colours. */
    fun tokens(code: String, language: String?, maxUTF8Bytes: Int = maxHighlightedUTF8Bytes): List<Token> =
        highlightWithSafePrefix(code, language, maxUTF8Bytes, computeSafePrefix = false).tokens

    /** Colours [tokens] (from [tokens] or a [Result]) over [code]. */
    fun annotated(code: String, tokens: List<Token>, palette: Palette): AnnotatedString = buildAnnotatedString {
        append(code)
        for (token in tokens) {
            if (token.end > token.start && token.end <= code.length) addStyle(SpanStyle(color = palette.color(token.kind)), token.start, token.end)
        }
    }

    /**
     * Highlights up to [maxUTF8Bytes] of [source] (snapped to a line boundary) in one forward pass,
     * handing each token to [onToken] without collecting them.
     */
    fun applyHighlighting(
        source: String,
        maxUTF8Bytes: Int = maxHighlightedUTF8Bytes,
        language: String?,
        onToken: (start: Int, end: Int, kind: TokenKind) -> Unit,
    ) {
        if (source.isEmpty() || maxUTF8Bytes <= 0) return
        val endIndex = highlightEndIndex(source, maxUTF8Bytes)
        if (endIndex <= 0) return
        scanTokens(source, endIndex, Language(language), onToken = { start, end, kind -> if (end > start) onToken(start, end, kind) }, onSafeNewline = {})
    }

    /** Reuses [previous]'s safe prefix when [code] still starts with it, scanning only the rest. */
    fun highlightIncremental(
        code: String,
        language: String?,
        maxUTF8Bytes: Int = maxHighlightedUTF8Bytes,
        previous: Previous?,
    ): Result {
        if (previous != null && previous.safePrefixUTF8Count > 0) {
            val split = utf16Offset(code, previous.safePrefixUTF8Count)
            val previousSplit = utf16Offset(previous.source, previous.safePrefixUTF8Count)
            if (split != null && previousSplit != null && split == previousSplit && code.regionMatches(0, previous.source, 0, split)) {
                val suffix = code.substring(split)
                val remainingBudget = maxOf(0, maxUTF8Bytes - previous.safePrefixUTF8Count)
                val suffixResult = highlightWithSafePrefix(suffix, language, remainingBudget, computeSafePrefix = remainingBudget > 0)
                val shifted = suffixResult.tokens.map { it.copy(start = it.start + split, end = it.end + split) }
                val shiftedSafe = suffixResult.safePrefixTokens.map { it.copy(start = it.start + split, end = it.end + split) }
                return Result(
                    tokens = previous.safePrefixTokens + shifted,
                    safePrefixUTF8Count = previous.safePrefixUTF8Count + suffixResult.safePrefixUTF8Count,
                    safePrefixTokens = if (suffixResult.safePrefixUTF8Count > 0) previous.safePrefixTokens + shiftedSafe else previous.safePrefixTokens,
                )
            }
        }
        return highlightWithSafePrefix(code, language, maxUTF8Bytes, computeSafePrefix = true)
    }

    fun highlightWithSafePrefix(
        code: String,
        language: String?,
        maxUTF8Bytes: Int = maxHighlightedUTF8Bytes,
        computeSafePrefix: Boolean = true,
    ): Result {
        if (code.isEmpty() || maxUTF8Bytes <= 0) return Result(emptyList(), 0, emptyList())
        val endLimit = highlightEndIndex(code, maxUTF8Bytes)
        val tokens = ArrayList<Token>()
        var lastSafeNewlineEnd = -1
        scanTokens(
            code, endLimit, Language(language),
            onToken = { start, end, kind -> if (end > start) tokens += Token(start, end, kind) },
            onSafeNewline = { lastSafeNewlineEnd = it },
        )
        if (computeSafePrefix && lastSafeNewlineEnd >= 0) {
            val safeEnd = lastSafeNewlineEnd
            val utf8Count = utf8Length(code, 0, safeEnd)
            val safeTokens = if (safeEnd == code.length) tokens else tokens.filter { it.start < safeEnd }
            return Result(tokens, utf8Count, safeTokens)
        }
        return Result(tokens, 0, emptyList())
    }

    // region Budgets (UTF-8 bytes over UTF-16 offsets)

    /** The UTF-8 size of one UTF-16 unit; a surrogate pair's halves count 2 each (4 for the scalar). */
    private fun utf8Size(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 2
        else -> 3
    }

    private fun utf8Length(s: String, from: Int, to: Int): Int {
        var bytes = 0
        for (i in from until to) bytes += utf8Size(s[i])
        return bytes
    }

    /** The UTF-16 offset [utf8Count] bytes in, or null when that falls inside a scalar or past the end. */
    /** The UTF-16 offset that ends [utf8Count] UTF-8 bytes of [s], or null when it splits a scalar or runs past the end. */
    fun utf16Offset(s: String, utf8Count: Int): Int? {
        var bytes = 0
        var i = 0
        while (i < s.length && bytes < utf8Count) {
            bytes += utf8Size(s[i])
            i += 1
        }
        if (bytes != utf8Count) return null
        if (i in 1 until s.length && s[i].isLowSurrogate() && s[i - 1].isHighSurrogate()) return null
        return i
    }

    /** The end of the highlighted prefix: [maxUTF8Bytes] snapped back to a scalar, then to a line within 1,000 bytes. */
    private fun highlightEndIndex(code: String, maxUTF8Bytes: Int): Int {
        if (maxUTF8Bytes <= 0 || code.isEmpty()) return 0
        var bytes = 0
        var rawEnd = 0
        var exceeded = false
        while (rawEnd < code.length) {
            val c = code[rawEnd]
            val scalarBytes = if (c.isHighSurrogate() && rawEnd + 1 < code.length && code[rawEnd + 1].isLowSurrogate()) 4 else utf8Size(c)
            if (bytes + scalarBytes > maxUTF8Bytes) {
                exceeded = true
                break
            }
            bytes += scalarBytes
            rawEnd += if (scalarBytes == 4) 2 else 1
        }
        // Everything fits.
        if (!exceeded) return code.length
        var j = rawEnd
        var back = 0
        while (j > 0 && back < maxHighlightedLineUTF8Bytes) {
            val c = code[j - 1]
            if (c == '\n') return j
            back += utf8Size(c)
            j -= 1
        }
        return rawEnd
    }

    /** Past a line over [maxHighlightedLineUTF8Bytes] starting at [start], or null when it's short enough to scan. */
    private fun skipLineIfOverBudget(code: String, start: Int, endLimit: Int): Int? {
        var i = start
        var bytes = 0
        while (bytes < maxHighlightedLineUTF8Bytes + 1) {
            if (i >= endLimit) return null
            if (code[i] == '\n') return null
            bytes += utf8Size(code[i])
            i += 1
        }
        val newline = code.indexOf('\n', i)
        return if (newline in 0 until endLimit) newline + 1 else endLimit
    }

    // endregion

    // region Characters (grapheme clusters on code points)

    private fun isGraphemeExtender(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    }

    /** The index after the grapheme cluster at [index] (a scalar, its marks, and ZWJ sequences). */
    private fun next(code: String, index: Int): Int {
        var i = index + Character.charCount(code.codePointAt(index))
        while (i < code.length) {
            val cp = code.codePointAt(i)
            if (cp == 0x200D) {
                i += 1
                if (i < code.length) i += Character.charCount(code.codePointAt(i))
                continue
            }
            if (!isGraphemeExtender(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    private fun isNumber(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
            type == Character.LETTER_NUMBER.toInt() ||
            type == Character.OTHER_NUMBER.toInt()
    }

    private fun isHexDigit(cp: Int): Boolean =
        cp in '0'.code..'9'.code || cp in 'a'.code..'f'.code || cp in 'A'.code..'F'.code ||
            cp in 0xFF10..0xFF19 || cp in 0xFF21..0xFF26 || cp in 0xFF41..0xFF46

    private fun peek(code: String, index: Int, limit: Int): Int? {
        val n = next(code, index)
        return if (n < limit) code.codePointAt(n) else null
    }

    private fun lineEnd(code: String, from: Int): Int = code.indexOf('\n', from).let { if (it < 0) code.length else it }

    // endregion

    private inline fun scanTokens(
        code: String,
        endLimit: Int,
        language: Language,
        onToken: (start: Int, end: Int, kind: TokenKind) -> Unit,
        onSafeNewline: (Int) -> Unit,
    ) {
        var index = 0
        var atLineStart = true
        while (index < endLimit) {
            if (atLineStart) {
                val afterLongLine = skipLineIfOverBudget(code, index, endLimit)
                if (afterLongLine != null) {
                    if (afterLongLine > index && code[afterLongLine - 1] == '\n') onSafeNewline(afterLongLine)
                    index = afterLongLine
                    continue
                }
                atLineStart = false
            }

            val character = code.codePointAt(index)

            // Line comments
            if (character == '/'.code && language.slashComments && peek(code, index, endLimit) == '/'.code) {
                val end = minOf(lineEnd(code, index), endLimit)
                onToken(index, end, TokenKind.Comment)
                index = end
                continue
            }
            if (character == '#'.code && language.hashComments) {
                val end = minOf(lineEnd(code, index), endLimit)
                onToken(index, end, TokenKind.Comment)
                index = end
                continue
            }

            // Block comments
            if (character == '/'.code && language.slashComments && peek(code, index, endLimit) == '*'.code) {
                var end = advance(code, index, 2, endLimit)
                while (end < endLimit) {
                    if (code[end] == '*' && peek(code, end, endLimit) == '/'.code) {
                        end = advance(code, end, 2, endLimit)
                        break
                    }
                    end = next(code, end)
                }
                onToken(index, end, TokenKind.Comment)
                index = end
                if (index < endLimit) {
                    val afterLongLine = skipLineIfOverBudget(code, index, endLimit)
                    if (afterLongLine != null) {
                        if (afterLongLine > index && code[afterLongLine - 1] == '\n') onSafeNewline(afterLongLine)
                        index = afterLongLine
                        atLineStart = true
                    }
                }
                continue
            }

            // Strings
            if (character == '"'.code || (character == '\''.code && language.singleQuoteStrings)) {
                val quote = code[index]
                var end = next(code, index)
                while (end < endLimit && code[end] != quote && code[end] != '\n') {
                    if (code[end] == '\\' && next(code, end) < endLimit) end = next(code, end)
                    end = next(code, end)
                }
                if (end < endLimit && code[end] == quote) end = next(code, end)
                onToken(index, end, TokenKind.String)
                index = end
                continue
            }

            // Numbers
            if (isNumber(character)) {
                var end = next(code, index)
                while (end < endLimit) {
                    val cp = code.codePointAt(end)
                    if (!(isHexDigit(cp) || cp in NUMBER_MARKS)) break
                    end = next(code, end)
                }
                onToken(index, end, TokenKind.Number)
                index = end
                continue
            }

            // Identifiers, keywords, and types
            if (Character.isLetter(character) || character == '_'.code) {
                var end = next(code, index)
                while (end < endLimit) {
                    val cp = code.codePointAt(end)
                    if (!(Character.isLetter(cp) || isNumber(cp) || cp == '_'.code)) break
                    end = next(code, end)
                }
                val word = code.substring(index, end)
                if (word in language.keywords) {
                    onToken(index, end, TokenKind.Keyword)
                } else if (Character.isUpperCase(character)) {
                    onToken(index, end, TokenKind.Type)
                }
                index = end
                continue
            }

            val nextIndex = next(code, index)
            if (character == '\n'.code) {
                onSafeNewline(nextIndex)
                atLineStart = true
            }
            index = nextIndex
        }
    }

    private fun advance(code: String, index: Int, n: Int, limit: Int): Int {
        var i = index
        repeat(n) {
            if (i >= limit) return limit
            i = next(code, i)
        }
        return if (i > limit) limit else i
    }

    private val NUMBER_MARKS: Set<Int> = "xXoObB._".map { it.code }.toSet()

    private class Language(hint: String?) {
        val keywords: Set<String>
        val slashComments: Boolean
        val hashComments: Boolean
        val singleQuoteStrings: Boolean

        init {
            when (hint?.lowercase()) {
                "swift" -> {
                    keywords = swiftKeywords; slashComments = true; hashComments = false; singleQuoteStrings = false
                }
                "js", "jsx", "ts", "tsx", "javascript", "typescript" -> {
                    keywords = jsKeywords; slashComments = true; hashComments = false; singleQuoteStrings = true
                }
                "py", "python" -> {
                    keywords = pythonKeywords; slashComments = false; hashComments = true; singleQuoteStrings = true
                }
                "sh", "bash", "zsh", "shell", "yaml", "yml", "toml" -> {
                    keywords = shellKeywords; slashComments = false; hashComments = true; singleQuoteStrings = true
                }
                else -> {
                    keywords = allKeywords; slashComments = true; hashComments = true; singleQuoteStrings = true
                }
            }
        }

        companion object {
            val swiftKeywords: Set<String> = setOf(
                "func", "let", "var", "if", "else", "guard", "return", "for", "while", "switch", "case",
                "break", "continue", "struct", "class", "enum", "protocol", "extension", "import", "init",
                "self", "static", "private", "public", "internal", "final", "throws", "async", "await",
                "try", "catch", "do", "defer", "in", "where", "nil", "true", "false", "some", "any",
                "typealias", "inout", "mutating", "override", "default",
            )

            val jsKeywords: Set<String> = setOf(
                "function", "const", "let", "var", "if", "else", "return", "for", "while", "switch",
                "case", "break", "continue", "class", "extends", "import", "export", "from", "default",
                "new", "this", "static", "async", "await", "try", "catch", "finally", "throw", "typeof",
                "instanceof", "in", "of", "null", "undefined", "true", "false", "interface", "type",
                "enum", "implements", "readonly", "yield",
            )

            val pythonKeywords: Set<String> = setOf(
                "def", "class", "if", "elif", "else", "return", "for", "while", "break", "continue",
                "import", "from", "as", "with", "try", "except", "finally", "raise", "lambda", "pass",
                "yield", "global", "nonlocal", "in", "is", "not", "and", "or", "None", "True", "False",
                "async", "await", "self",
            )

            /** Unlabelled fences get every general-purpose language's keywords. */
            val allKeywords: Set<String> = swiftKeywords + jsKeywords + pythonKeywords

            val shellKeywords: Set<String> = setOf(
                "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
                "function", "local", "export", "return", "echo", "exit", "set", "source",
            )
        }
    }
}
