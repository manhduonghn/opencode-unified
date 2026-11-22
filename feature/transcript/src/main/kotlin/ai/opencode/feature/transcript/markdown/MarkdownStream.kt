package ai.opencode.feature.transcript.markdown

import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.InlineCache

/**
 * Streaming Markdown that parses each completed block once. The text splits into units at points
 * where the parser's state is clean: a closed code fence, or a blank line followed by a line that
 * resets every open list. A unit is committed only once the chars after it prove its boundary, then
 * parsed once and kept; an update reuses every committed unit whose proof the new text still shares
 * and re-parses only the live tail, healing its last block ([MarkdownTailHeal]) while it streams.
 * Display math and code fences never span a boundary, so they're never cut.
 *
 * The result always equals [OpenCodeMarkdownBlock.blocks] of the same text when it isn't streaming.
 * Offsets are UTF-16 chars. Mutable and not thread-safe: the store copies a stream ([copy]) before
 * updating it.
 */
class MarkdownStream(
    private val inlineCache: InlineCache? = InlineCache.shared,
    parseUnit: ((String) -> List<InlineCache.SegmentItem>)? = null,
) {
    /** What the last update parsed: the guard tests' and metrics' evidence. */
    data class Cost(
        /** Units committed (and parsed) by this update, as char ranges. */
        val committed: List<IntRange> = emptyList(),
        /** The live tail re-parsed by this update. */
        val tail: IntRange = IntRange.EMPTY,
        /** Chars handed to the parser: new units plus the tail. */
        val parsed: Int = 0,
        /** Where this update's own work began: the end of the reused units. */
        val reused: Int = 0,
    )

    /** `[start, end)`; the chars before [proofEnd] decided the boundary. */
    private data class Unit(val start: Int, val end: Int, val proofEnd: Int, val items: List<InlineCache.SegmentItem>)

    private val parseUnit: (String) -> List<InlineCache.SegmentItem> = parseUnit ?: { text ->
        inlineCache?.parseCompletedSegment(text) ?: OpenCodeMarkdownBlock.parseTextSegment(text, null)
    }
    private var source = ""
    private var units = ArrayList<Unit>()
    private var committedBlocks = ArrayList<OpenCodeMarkdownBlock>()
    private var committedFiles = HashSet<String>()
    /** The open fence's highlight, reused as it grows and when it closes. */
    private var liveCode: OpenCodeMarkdownBlock.HighlightedCode? = null

    private var costCommitted = ArrayList<IntRange>()
    private var costParsed = 0
    var cost: Cost = Cost()
        private set

    /** An independent copy. */
    fun copy(): MarkdownStream {
        val copy = MarkdownStream(inlineCache, parseUnit)
        copy.source = source
        copy.units = ArrayList(units)
        copy.committedBlocks = ArrayList(committedBlocks)
        copy.committedFiles = HashSet(committedFiles)
        copy.liveCode = liveCode
        copy.cost = cost
        return copy
    }

    /** The length of the committed prefix. */
    val committedLength: Int get() = units.lastOrNull()?.end ?: 0

    /** The text of the last update. */
    val text: String get() = source

    /** How many leading blocks come from committed units. */
    val committedBlockCount: Int get() = committedBlocks.size

    /** How much of the last text [text] shares, and how many committed chars an update to it would keep. */
    fun reuse(text: String): Pair<Int, Int> {
        val text = OpenCodeMarkdownBlock.lineFeeds(text)
        val shared = SwiftText.commonPrefix(source, text)
        return shared to (units.lastOrNull { it.proofEnd <= shared }?.end ?: 0)
    }

    /** The blocks of [text]. [streaming] heals the live tail's last block. */
    fun update(text: String, streaming: Boolean): List<OpenCodeMarkdownBlock> {
        val text = OpenCodeMarkdownBlock.lineFeeds(text)
        costCommitted = ArrayList()
        costParsed = 0
        val shared = SwiftText.commonPrefix(source, text)
        var keep = units.size
        while (keep > 0 && units[keep - 1].proofEnd > shared) keep--
        if (keep < units.size) {
            units = ArrayList(units.subList(0, keep))
            committedBlocks = ArrayList()
            committedFiles = HashSet()
            for (unit in units) OpenCodeMarkdownBlock.appendSegmentItems(unit.items, committedBlocks, committedFiles)
        }
        source = text
        val reused = committedLength
        commit(text)
        val tail = text.substring(committedLength)
        val tailRange = committedLength until text.length
        val blocks = ArrayList(committedBlocks)
        val files = HashSet(committedFiles)
        OpenCodeMarkdownBlock.appendSegmentItems(parseTail(tail, streaming), blocks, files)
        cost = Cost(costCommitted, tailRange, costParsed, reused)
        return blocks
    }

    // MARK: Committing

    /** Commits every unit after the committed prefix whose boundary the chars prove. */
    private fun commit(s: String) {
        var position = committedLength
        while (position < s.length) {
            val fence = nextFence(s, position)
            val limit = fence?.start ?: s.length
            position = commitChunks(s, position, limit)
            // The segment ends at a fence only when the run is whole: a later char could still
            // lengthen it, or join it to a combining mark.
            if (fence == null || fence.unsafe || fence.runEnd >= s.length) return
            val start = fence.start
            if (start > position) {
                append(Unit(position, start, fence.runEnd + 1, parse(s, position, start)))
                position = start
            }
            // An open fence stays in the tail, which starts at its backticks.
            val close = nextFence(s, start + 3)
            if (close == null || close.unsafe || close.runEnd >= s.length) return
            val segment = s.substring(start + 3, close.start)
            append(Unit(start, close.start + 3, close.runEnd + 1, codeItems(segment, closed = true)))
            costParsed += close.start - start - 3
            position = close.start + 3
        }
    }

    /** Commits the chunks of the text segment from [start] that end at a boundary line wholly before [limit]; returns where the next one starts. */
    private fun commitChunks(s: String, start: Int, limit: Int): Int {
        var chunkStart = start
        var lineStart = start
        var previousBlank = false
        // A display-math opener since the last blank line: the lines after it may still belong
        // to it (`MathSyntax.display`).
        var mathOpen = false
        var index = start
        while (index < limit) {
            // Lines split on the Character "\n": never inside "\r\n".
            if (s[index] != '\n' || (index > 0 && s[index - 1] == '\r')) {
                index++
                continue
            }
            val blank = isBlank(s, lineStart, index)
            if (lineStart > chunkStart && !blank && !mathOpen && startsCleanly(s, lineStart, index, previousBlank)) {
                append(Unit(chunkStart, lineStart, index + 1, parse(s, chunkStart, lineStart)))
                chunkStart = lineStart
            }
            if (blank) mathOpen = false
            else if (opensMath(s, lineStart, index)) mathOpen = true
            previousBlank = blank
            index++
            lineStart = index
        }
        return chunkStart
    }

    private fun append(unit: Unit) {
        val stored = unit.copy(proofEnd = maxOf(unit.proofEnd, units.lastOrNull()?.proofEnd ?: 0))
        units.add(stored)
        costCommitted.add(stored.start until stored.end)
        OpenCodeMarkdownBlock.appendSegmentItems(stored.items, committedBlocks, committedFiles)
    }

    private fun parse(s: String, start: Int, end: Int): List<InlineCache.SegmentItem> {
        costParsed += end - start
        return parseUnit(s.substring(start, end))
    }

    // MARK: The tail

    /** The tail starts where the parser's state is clean, so it parses exactly as [OpenCodeMarkdownBlock.blocks] would parse it alone. */
    private fun parseTail(tail: String, streaming: Boolean): List<InlineCache.SegmentItem> {
        val segments = OpenCodeMarkdownBlock.codeFenceSegments(tail)
        val items = ArrayList<InlineCache.SegmentItem>()
        for ((index, segment) in segments.withIndex()) {
            val closed = index + 1 < segments.size
            if (index % 2 == 0) {
                val text = if (streaming && !closed) MarkdownTailHeal.healLastBlock(segment) else segment
                costParsed += text.length
                items += OpenCodeMarkdownBlock.parseTextSegment(text, inlineCache)
            } else {
                costParsed += segment.length
                items += codeItems(segment, closed)
            }
        }
        return items
    }

    private fun codeItems(segment: String, closed: Boolean): List<InlineCache.SegmentItem> {
        val (language, code) = OpenCodeMarkdownBlock.fence(segment)
        if (code.isEmpty()) return emptyList()
        if (closed && language?.lowercase() == "mermaid") {
            return listOf(InlineCache.SegmentItem.Block(OpenCodeMarkdownBlock.Kind.Mermaid(code), emptyList()))
        }
        val live = liveCode
        val highlighted = when {
            live != null && live.language == language && live.source == code -> live
            live != null && live.language == language && live.prefixMatches(code) -> OpenCodeMarkdownBlock.HighlightedCode(code, language, live)
            else -> OpenCodeMarkdownBlock.HighlightedCode(code, language)
        }
        if (!closed) liveCode = highlighted
        return listOf(InlineCache.SegmentItem.Block(OpenCodeMarkdownBlock.Kind.Code(highlighted), emptyList()))
    }

    // MARK: Scanning

    /**
     * Three backticks at [start], in a run ending at [runEnd]; or, when [unsafe], a backtick run
     * whose meaning depends on Unicode rules this scan doesn't model (a neighbouring non-ASCII
     * char), which stays in the tail for [OpenCodeMarkdownBlock.codeFenceSegments].
     */
    private class Fence(val start: Int, val runEnd: Int, val unsafe: Boolean)

    companion object {
        /** The first fence at or after [start], as `codeFenceSegments` would split. */
        private fun nextFence(s: String, start: Int): Fence? {
            val n = s.length
            var index = start
            while (index < n) {
                val c = s[index]
                // U+1FEF GREEK VARIA is canonically equivalent to "`".
                if (c == '\u1FEF') return Fence(index, index, unsafe = true)
                if (c != '`') {
                    index++
                    continue
                }
                var end = index
                while (end < n && s[end] == '`') end++
                if (end - index >= 3) {
                    val before = if (index > 0) s[index - 1].code else 0
                    val after = if (end < n) s[end].code else 0
                    return Fence(index, end, unsafe = before >= 0x80 || after >= 0x80)
                }
                index = end
            }
            return null
        }

        /** `trimmingCharacters(in: .whitespaces).isEmpty`. */
        private fun isBlank(s: String, start: Int, end: Int): Boolean {
            for (i in start until end) if (!SwiftText.isWhitespace(s[i])) return false
            return true
        }

        /**
         * Whether `parseTextSegment` reaches this line with nothing before it still open and
         * leaves the same state as a fresh parse would, so a unit can start here. After a blank
         * line: any unindented line that clears the list nesting (not math, an image, a quote or
         * a break), or a top-level list item. Otherwise: a top-level list item or a heading,
         * which flush the paragraph above and reset the nesting themselves.
         */
        private fun startsCleanly(s: String, start: Int, end: Int, afterBlank: Boolean): Boolean {
            val first = s[start]
            if (first == ' ' || first == '\t') return false
            // Cheap rejection of prose lines: only a heading or item can start here.
            if (!afterBlank && first.code < 0x80 && first != '#' && first != '-' && first != '*' && first !in '0'..'9') return false
            val trimmed = SwiftText.trimWhitespace(s.substring(start, end))
            if (trimmed.isEmpty() || OpenCodeMarkdownBlock.isThematicBreak(trimmed)) return false
            if (OpenCodeMarkdownBlock.isListItem(trimmed)) return true
            if (afterBlank) {
                return !trimmed.startsWith("$$") && !trimmed.startsWith("\\[") && !trimmed.startsWith("![") && !trimmed.startsWith(">")
            }
            if (!trimmed.startsWith("#")) return false
            val level = trimmed.takeWhile { it == '#' }.length
            val rest = trimmed.substring(level)
            return level <= 6 && (rest.startsWith(" ") || rest.startsWith("\t")) && SwiftText.trimWhitespace(rest).isNotEmpty()
        }

        /** A line `parseTextSegment` would try as display math. */
        private fun opensMath(s: String, start: Int, end: Int): Boolean {
            var index = start
            while (index < end && (s[index] == ' ' || s[index] == '\t')) index++
            if (index >= end) return false
            val first = s[index]
            if (first != '$' && first != '\\' && first.code < 0x80) return false
            val trimmed = SwiftText.trimWhitespace(s.substring(start, end))
            return trimmed.startsWith("$$") || trimmed.startsWith("\\[")
        }
    }
}
