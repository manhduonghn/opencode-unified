package ai.opencode.feature.transcript.markdown

/**
 * Heals the end of the block that's still streaming, so a half-written marker never draws as
 * literal text and then flips into formatting: `**bold` shows as bold, `` `code `` as code,
 * `[label](https://…` as its label, and a trailing `**` with nothing after it yet is hidden. A port
 * of the desktop app's `patchmark` `heal.ts` (itself after Vercel streamdown's `remend`). Only the
 * live tail is healed; completed blocks parse as written. Every decision reads ASCII, so the scan
 * runs over UTF-16 chars as heal.ts does.
 */
object MarkdownTailHeal {
    /** Chars to drop from the right-trimmed end, then text to append. */
    data class Edit(val strip: Int, val append: String)

    /**
     * Heals the last block of a text segment (paragraph, list item, heading, quote or table
     * cell). A segment that ends in a blank line has nothing open: its last block is finished.
     */
    fun healLastBlock(segment: String): String {
        val start = lastInlineRunStart(segment) ?: return segment
        val contentStart = markerEnd(segment, start)
        val content = segment.substring(contentStart)
        val healed = heal(content)
        if (healed == content) return segment
        // Healed to nothing, the block would draw as its bare marker (a `-` or `##` paragraph):
        // hold it back like `holdingPendingMarker` does.
        if (contentStart > start && healed.all { SwiftText.isCharacterWhitespace(it) }) return segment.substring(0, start)
        return segment.substring(0, contentStart) + healed
    }

    /** The end of a list, heading or quote marker opening the line at [start]. */
    private fun markerEnd(segment: String, start: Int): Int {
        var index = start
        while (index < segment.length && (segment[index] == ' ' || segment[index] == '\t')) index++
        for (bullet in listOf("- ", "* ", "• ", "> ")) {
            if (segment.startsWith(bullet, index)) return index + bullet.length
        }
        if (segment.startsWith(">", index)) return index + 1
        var digits = 0
        while (index + digits < segment.length && SwiftText.isNumber(segment[index + digits])) digits++
        if (digits > 0 && segment.startsWith(". ", index + digits)) return index + digits + 2
        var hashes = 0
        while (index + hashes < segment.length && segment[index + hashes] == '#') hashes++
        if (hashes in 1..6 && index + hashes < segment.length && segment[index + hashes] == ' ') return index + hashes + 1
        return start
    }

    /** Where the last inline run starts, or null when there's nothing to heal. */
    fun lastInlineRunStart(segment: String): Int? {
        val lines = SwiftText.lines(segment)
        val starts = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            starts[i] = offset
            offset += lines[i].length + 1
        }
        val last = lines.last()
        val lastTrimmed = SwiftText.trimWhitespace(last)
        if (lastTrimmed.isEmpty()) return null
        var first = lines.size - 1
        // A table row heals only its last cell.
        if (lastTrimmed.startsWith("|")) {
            val bar = last.lastIndexOf('|')
            if (bar < 0) return null
            return starts[first] + bar + 1
        }
        val quoted = lastTrimmed.startsWith(">")
        while (first > 0) {
            val trimmed = SwiftText.trimWhitespace(lines[first])
            val above = SwiftText.trimWhitespace(lines[first - 1])
            if (quoted) {
                if (!above.startsWith(">")) break
                first--
                continue
            }
            if (startsBlock(trimmed)) break
            // A heading is one line; a table row or quote above starts its own block.
            if (above.isEmpty() || above.startsWith("#") || above.startsWith("|") || above.startsWith(">")) break
            first--
        }
        val head = SwiftText.trimWhitespace(lines[first])
        // Display math streams as text until it closes (docs/transcript.md).
        if (head.startsWith("$$") || head.startsWith("\\[")) return null
        return starts[first]
    }

    private fun startsBlock(trimmed: String): Boolean {
        if (trimmed.startsWith("#") || trimmed.startsWith(">") || trimmed.startsWith("|")) return true
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) return true
        var digits = 0
        while (digits < trimmed.length && SwiftText.isNumber(trimmed[digits])) digits++
        return digits > 0 && trimmed.startsWith(". ", digits)
    }

    /** Repairs incomplete inline markers at the end of [text] without touching closed spans. */
    fun heal(text: String): String {
        val edit = edit(text)
        if (edit.strip == 0 && edit.append.isEmpty()) return text
        val end = rtrimLength(text)
        return text.substring(0, maxOf(0, end - edit.strip)) + edit.append
    }

    // MARK: The scan (heal.ts `computeTailHeal`).

    private class Open(val ch: Char, var len: Int, /** Index just after the opening run. */ val after: Int, val start: Int)

    fun edit(input: String): Edit {
        var text = input.substring(0, rtrimLength(input))
        if (text.isEmpty()) return Edit(0, "")
        var heal = Edit(0, "")

        // 1. An unfinished link or image at the very end shows just its label.
        val lastOpen = text.lastIndexOf('[')
        if (lastOpen >= 0 && (lastOpen == 0 || text[lastOpen - 1] != '\\')) {
            val start = if (lastOpen > 0 && text[lastOpen - 1] == '!') lastOpen - 1 else lastOpen
            val match = linkTail(text, start)
            if (match != null) {
                heal = Edit(match.first, match.second)
                text = text.substring(0, text.length - match.first) + match.second
            }
        }

        // Not in heal.ts: a delimiter run at the very end after a space can neither open nor
        // close yet. Hide it, so `- **` or `text **` never shows its stars for a frame (or
        // closes an earlier run as `***`).
        val lastChar = text.lastOrNull()
        if (lastChar == '*' || lastChar == '_' || lastChar == '~') {
            var start = text.length
            while (start > 0 && text[start - 1] == lastChar) start--
            if (start == 0 || isSpace(text[start - 1])) {
                var cut = start
                while (cut > 0 && (text[cut - 1] == ' ' || text[cut - 1] == '\t')) cut--
                heal = compose(heal, Edit(text.length - cut, ""))
                text = text.substring(0, cut)
            }
        }

        // 2. Code spans, math, and emphasis delimiters.
        val n = text.length
        var codeOpenLen = -1
        var codeOpenStart = -1
        var wordHadEm = false
        val stack = ArrayList<Open>()
        val counts = HashMap<Char, Int>()
        fun push(open: Open) {
            counts[open.ch] = (counts[open.ch] ?: 0) + 1
            stack.add(open)
        }
        fun pop() {
            val open = stack.removeAt(stack.size - 1)
            counts[open.ch] = (counts[open.ch] ?: 0) - 1
        }
        fun at(index: Int): Char? = if (index in 0 until n) text[index] else null
        var i = 0
        while (i < n) {
            val c = text[i]
            if (codeOpenLen >= 0) {
                if (c == '`') {
                    var run = 1
                    while (i + run < n && text[i + run] == '`') run++
                    if (run == codeOpenLen) {
                        codeOpenLen = -1
                        codeOpenStart = -1
                    }
                    i += run
                } else {
                    i++
                }
                continue
            }
            if (isSpace(c)) {
                wordHadEm = false
                i++
                continue
            }
            if (c == '\\') {
                val length = parenMath(text, i)
                i += length ?: 2
                continue
            }
            if (c == '`') {
                var run = 1
                while (i + run < n && text[i + run] == '`') run++
                codeOpenLen = run
                codeOpenStart = i
                i += run
                continue
            }
            if (c == '$') {
                val length = dollarMath(text, i)
                i += length ?: 1
                continue
            }
            if (c == '*' || c == '_' || c == '~') {
                var run = 1
                while (i + run < n && text[i + run] == c) run++
                val prev = at(i - 1)
                val next = at(i + run)
                val isUnderscore = c == '_'
                val isSingleStar = c == '*' && run == 1
                val canOpen = !isSpace(next) &&
                    !(isUnderscore && isAlnum(prev)) &&
                    !(isSingleStar && isAlnum(prev) && !wordHadEm)
                val canClose = !isSpace(prev) && !(isUnderscore && isAlnum(next))
                if (c == '*' && (run >= 2 || canOpen)) wordHadEm = true
                if (c == '~' && run > 2) {
                    i += run
                    continue
                }
                var remaining = run
                if (canClose) {
                    while (remaining > 0 && stack.isNotEmpty()) {
                        if (stack.last().ch != c) {
                            if ((counts[c] ?: 0) == 0) break
                            while (stack.last().ch != c) pop()
                        }
                        if (stack.last().len <= remaining) {
                            remaining -= stack.last().len
                            pop()
                        } else {
                            stack.last().len -= remaining
                            remaining = 0
                        }
                    }
                }
                if (remaining > 0 && canOpen) {
                    val start = i + (run - remaining)
                    if (c == '~') {
                        push(Open(c, remaining, i + run, start))
                    } else if (remaining >= 3) {
                        push(Open(c, 2, i + run, start))
                        push(Open(c, 1, i + run, start + 2))
                    } else {
                        push(Open(c, remaining, i + run, start))
                    }
                }
                i += run
                continue
            }
            i++
        }

        var strip = 0
        val append = StringBuilder()
        var codeClosed = false
        var nonSpaceEnd = rtrimLength(text)

        if (codeOpenLen >= 0) {
            if (codeOpenStart + codeOpenLen >= nonSpaceEnd) {
                strip = n - codeOpenStart
            } else {
                repeat(codeOpenLen) { append.append('`') }
                codeClosed = true
            }
        }

        // Close (innermost first) markers with content after them; drop trailing markers that have none.
        var limit = n - strip
        fun clampNonSpaceEnd() {
            if (nonSpaceEnd > limit) {
                nonSpaceEnd = limit
                while (nonSpaceEnd > 0 && isSpace(text[nonSpaceEnd - 1])) nonSpaceEnd--
            }
        }
        clampNonSpaceEnd()
        for (open in stack.asReversed()) {
            if (open.start >= limit) continue
            if (open.after >= nonSpaceEnd) {
                if (append.isEmpty()) {
                    // A dangling opener at the end: hide it and the spaces before it.
                    var cut = open.start
                    while (cut > 0 && (text[cut - 1] == ' ' || text[cut - 1] == '\t')) cut--
                    strip = n - cut
                    limit = cut
                    clampNonSpaceEnd()
                }
                continue
            }
            repeat(open.len) { append.append(open.ch) }
        }

        // Closers hug the text: drop trailing spaces before appending.
        if (append.isNotEmpty() && !codeClosed) {
            var cut = n - strip
            while (cut > 0 && isSpace(text[cut - 1])) cut--
            strip = n - cut
        }

        return compose(heal, Edit(strip, append.toString()))
    }

    private fun compose(a: Edit, b: Edit): Edit {
        if (b.strip <= a.append.length) return Edit(a.strip, a.append.substring(0, a.append.length - b.strip) + b.append)
        return Edit(a.strip + (b.strip - a.append.length), b.append)
    }

    /** `(!?)\[([^\]\n]*)\]\(([^)\n]*)$` from [start]: its length and what replaces it. */
    private fun linkTail(text: String, start: Int): Pair<Int, String>? {
        var i = start
        val image = text[i] == '!'
        if (image) i++
        if (i >= text.length || text[i] != '[') return null
        i++
        val labelStart = i
        while (i < text.length && text[i] != ']' && text[i] != '\n') i++
        if (i + 1 >= text.length || text[i] != ']' || text[i + 1] != '(') return null
        val label = text.substring(labelStart, i)
        i += 2
        while (i < text.length) {
            if (text[i] == ')' || text[i] == '\n') return null
            i++
        }
        return (text.length - start) to (if (image) "" else label)
    }

    /** `\\\(((?:\\.|[^\\\n])*?)\\\)` anchored at [i]. */
    private fun parenMath(text: String, i: Int): Int? {
        if (i + 1 >= text.length || text[i] != '\\' || text[i + 1] != '(') return null
        var j = i + 2
        while (j < text.length) {
            if (text[j] == '\\' && j + 1 < text.length && text[j + 1] == ')') return j + 2 - i
            if (text[j] == '\\') {
                if (j + 1 >= text.length || text[j + 1] == '\n') return null
                j += 2
            } else if (text[j] == '\n') {
                return null
            } else {
                j++
            }
        }
        return null
    }

    /** `$$…$$` or `$…$` (heal.ts `reInlineDollarMath`) anchored at [i]. */
    private fun dollarMath(text: String, i: Int): Int? {
        val n = text.length
        if (i + 1 < n && text[i + 1] == '$') {
            // \$\$(?!\$)((?:\\.|[^\\\n])*?(?:\\.|[^\\\n$]))\$\$
            if (i + 2 < n && text[i + 2] == '$') return null
            var j = i + 2
            var units = 0
            var lastValid = false
            while (j < n) {
                if (units > 0 && lastValid && j + 1 < n && text[j] == '$' && text[j + 1] == '$') return j + 2 - i
                if (text[j] == '\\') {
                    if (j + 1 >= n || text[j + 1] == '\n') return null
                    lastValid = true
                    j += 2
                } else if (text[j] == '\n') {
                    return null
                } else {
                    lastValid = text[j] != '$'
                    j++
                }
                units++
            }
            return null
        }
        // \$(?=[^\s$])((?:\\.|[^\\\n$])*?(?:\\\S|[^\s\\$]))\$(?!\w)
        if (i + 1 >= n || isRegexSpace(text[i + 1]) || text[i + 1] == '$') return null
        var j = i + 1
        var lastValid = false
        while (j < n) {
            if (j > i + 1 && text[j] == '$' && lastValid && !(j + 1 < n && isWord(text[j + 1]))) return j + 1 - i
            if (text[j] == '\\') {
                if (j + 1 >= n || text[j + 1] == '\n') return null
                lastValid = !isRegexSpace(text[j + 1])
                j += 2
            } else if (text[j] == '\n' || text[j] == '$') {
                return null
            } else {
                lastValid = !isRegexSpace(text[j])
                j++
            }
        }
        return null
    }

    private fun isSpace(c: Char?): Boolean = c == null || c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isRegexSpace(c: Char): Boolean = c == ' ' || c in '\t'..'\r'

    private fun isAlnum(c: Char?): Boolean = c != null && (c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z')

    private fun isWord(c: Char): Boolean = isAlnum(c) || c == '_'

    private fun rtrimLength(text: String): Int {
        var end = text.length
        while (end > 0 && isSpace(text[end - 1])) end--
        return end
    }
}
