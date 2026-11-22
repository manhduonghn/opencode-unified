package ai.opencode.feature.transcript.selection

import ai.opencode.feature.media.SiteIcon
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.MarkdownQuote
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.SessionLinkText
import ai.opencode.feature.transcript.markdown.ui.InlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.InlineMath
import ai.opencode.feature.transcript.markdown.ui.MarkdownPresentation
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.MathRenderer
import ai.opencode.feature.transcript.markdown.ui.PresentationOptions
import ai.opencode.feature.transcript.markdown.ui.PresentedText
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.icu.text.BreakIterator
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density

/**
 * One selectable text in an assistant reply: a paragraph, heading, quote, list item, code block,
 * display formula, or one table cell ([part]).
 */
data class TranscriptSelectionLeafKey(val itemID: String, val blockID: Int, val part: Int = 0) {
    companion object {
        /** A table cell's part: row 0 is the header. */
        fun tablePart(row: Int, column: Int): Int = row * 1000 + column
    }
}

/**
 * A leaf's text as selection reads it, and how it maps onto the string Compose laid out
 * (`TextLayoutResult` offsets). The two differ only where the drawn text holds inline content: a
 * Link Icon's slot (one drawn character, nothing read) or a typeset formula (its source drawn as
 * one placeholder, read as its `$…$` source). Selection never splits either.
 */
data class TranscriptSelectionLeaf(
    val key: TranscriptSelectionLeafKey,
    val role: Role,
    /** Its block's place in the reply, so table cells and list items join right. */
    val blockIndex: Int,
    /** UTF-16, as Compose counts. */
    val text: String,
    val segments: List<Segment>,
    /** Link runs, in [text] offsets: a hold there opens the link's menu instead. */
    val links: List<IntRange> = emptyList(),
) {
    sealed interface Role {
        data object Prose : Role
        data object Heading : Role
        data object Quote : Role
        /** The Markdown prefix a copy of the whole item starts with (`- `, `1. `, `- [x] `). */
        data class ListItem(val prefix: String) : Role
        data class Code(val language: String?) : Role
        data class TableCell(val row: Int, val column: Int) : Role
        data object Math : Role
    }

    /**
     * A stretch of the drawn string ([drawnStart] until [drawnEnd]) and the text it reads as
     * ([textStart] until [textEnd]). Plain stretches map one to one; atomic ones are selected whole.
     */
    data class Segment(val drawnStart: Int, val drawnEnd: Int, val textStart: Int, val textEnd: Int, val isAtomic: Boolean) {
        val drawnCount: Int get() = drawnEnd - drawnStart
        val textCount: Int get() = textEnd - textStart
    }

    val length: Int get() = text.length
    val drawnLength: Int get() = segments.lastOrNull()?.drawnEnd ?: 0

    /** The text offset nearest a drawn character boundary. */
    fun textOffset(forDrawn: Int): Int {
        val segment = segments.firstOrNull { forDrawn < it.drawnEnd } ?: segments.lastOrNull() ?: return 0
        val local = (forDrawn - segment.drawnStart).coerceIn(0, segment.drawnCount)
        if (segment.isAtomic) {
            return if (local * 2 < segment.drawnCount) segment.textStart else segment.textEnd
        }
        return segment.textStart + minOf(local, segment.textCount)
    }

    /** The drawn character boundary for a text offset; inside an atomic stretch, its nearer end. */
    fun drawnOffset(forText: Int): Int {
        val segment = segments.firstOrNull { forText < it.textEnd } ?: segments.lastOrNull() ?: return 0
        val local = (forText - segment.textStart).coerceIn(0, segment.textCount)
        if (segment.isAtomic) {
            return if (local * 2 < segment.textCount) segment.drawnStart else segment.drawnEnd
        }
        return segment.drawnStart + local
    }

    /** Whether [offset] sits inside a link's text. */
    fun isLink(offset: Int): Boolean {
        for (link in links) {
            if (offset in link) return true
        }
        return false
    }
}

/** A half-open range of the document's UTF-16 offsets. */
data class TextRange(val start: Int, val length: Int) {
    val end: Int get() = start + length
}

/** Presentation inputs a reply's leaves depend on: what the rows were given. */
class TranscriptSelectionContext(
    val style: MarkdownStyle,
    val density: Density,
    val sessionTargets: Map<String, SessionLinkTarget> = emptyMap(),
    val linkStyle: InlineLinkStyle = InlineLinkStyle.Today,
    /** The list's link store is there: links get their icon slots (`siteIcon` in `BlockText`). */
    val decoratesLinks: Boolean = false,
    /** Whether a row's reply can still grow (its last block's open link keeps its URL). */
    val isGrowing: (String) -> Boolean = { false },
)

/** One reply row: its item and parsed blocks. */
data class TranscriptSelectionRow(val itemID: String, val blocks: List<OpenCodeMarkdownBlock>)

/** Builds leaves from the same presentation the views draw. */
object TranscriptSelectionText {
    /** The leaf for text drawn as [presented]. */
    fun leaf(key: TranscriptSelectionLeafKey, role: TranscriptSelectionLeaf.Role, blockIndex: Int, presented: PresentedText): TranscriptSelectionLeaf {
        val drawn = presented.text.text
        val isLinkDrawn = BooleanArray(drawn.length)
        for (link in presented.text.getLinkAnnotations(0, drawn.length)) {
            if (link.item !is LinkAnnotation.Clickable && link.item !is LinkAnnotation.Url) continue
            for (index in link.start until minOf(link.end, drawn.length)) {
                isLinkDrawn[index] = true
            }
        }
        val atomics = presented.atomics.sortedBy { it.start }
        val builder = Builder()
        var index = 0
        var nextAtomic = 0
        while (index < drawn.length) {
            val atomic = atomics.getOrNull(nextAtomic)
            if (atomic != null && atomic.start == index) {
                builder.atomic(atomic.end - atomic.start, atomic.text, isLinkDrawn[index])
                index = atomic.end
                nextAtomic++
                continue
            }
            // A plain stretch up to the next atomic run or the next change in being a link.
            val limit = atomic?.start ?: drawn.length
            val isLink = isLinkDrawn[index]
            var end = index + 1
            while (end < limit && isLinkDrawn[end] == isLink) {
                end++
            }
            builder.plain(drawn.substring(index, end), isLink)
            index = end
        }
        return builder.leaf(key, role, blockIndex)
    }

    /** A leaf whose drawn string is its text (code), or a single image read as [text] (a typeset display formula). */
    fun leaf(key: TranscriptSelectionLeafKey, role: TranscriptSelectionLeaf.Role, blockIndex: Int, plain: String, isImage: Boolean = false): TranscriptSelectionLeaf {
        val builder = Builder()
        if (isImage) {
            builder.atomic(1, plain, false)
        } else {
            builder.plain(plain, false)
        }
        return builder.leaf(key, role, blockIndex)
    }

    private class Builder {
        val text = StringBuilder()
        var drawn = 0
        val segments = ArrayList<TranscriptSelectionLeaf.Segment>()
        val links = ArrayList<IntRange>()

        fun plain(piece: String, isLink: Boolean) {
            val count = piece.length
            if (count == 0) return
            val length = text.length
            if (isLink) addLink(length, length + count)
            val last = segments.lastOrNull()
            if (last != null && !last.isAtomic) {
                segments[segments.size - 1] = last.copy(drawnEnd = drawn + count, textEnd = length + count)
            } else {
                segments += TranscriptSelectionLeaf.Segment(drawn, drawn + count, length, length + count, isAtomic = false)
            }
            text.append(piece)
            drawn += count
        }

        fun atomic(drawnCount: Int, piece: String, isLink: Boolean) {
            val length = text.length
            val read = piece.length
            if (isLink && read > 0) addLink(length, length + read)
            segments += TranscriptSelectionLeaf.Segment(drawn, drawn + drawnCount, length, length + read, isAtomic = true)
            text.append(piece)
            drawn += drawnCount
        }

        private fun addLink(start: Int, end: Int) {
            val last = links.lastOrNull()
            if (last != null && last.last + 1 == start) {
                links[links.size - 1] = last.first until end
            } else {
                links += start until end
            }
        }

        fun leaf(key: TranscriptSelectionLeafKey, role: TranscriptSelectionLeaf.Role, blockIndex: Int): TranscriptSelectionLeaf {
            val shown = if (segments.isEmpty()) listOf(TranscriptSelectionLeaf.Segment(0, 0, 0, 0, isAtomic = false)) else segments.toList()
            return TranscriptSelectionLeaf(key, role, blockIndex, text.toString(), shown, links.toList())
        }
    }
}

/**
 * One assistant reply as a single selectable document: every leaf of every reply row in the turn
 * (prose split by tool groups included), in reading order, one `\n` between leaves.
 */
class TranscriptSelectionDocument(val leaves: List<TranscriptSelectionLeaf>) {
    /** Each leaf's first offset in [string]. */
    val starts: List<Int>
    val string: String

    init {
        val starts = ArrayList<Int>()
        val joined = StringBuilder()
        for ((index, leaf) in leaves.withIndex()) {
            if (index > 0) joined.append("\n")
            starts += joined.length
            joined.append(leaf.text)
        }
        this.starts = starts
        this.string = joined.toString()
    }

    val length: Int get() = string.length

    /** The reply's rows in order, each once. */
    val itemIDs: List<String>

    // Read for every leaf on every highlight draw, so a lookup rather than a search.
    private val indexes: Map<TranscriptSelectionLeafKey, Int>

    init {
        val itemIDs = ArrayList<String>()
        val indexes = HashMap<TranscriptSelectionLeafKey, Int>()
        for ((index, leaf) in leaves.withIndex()) {
            if (leaf.key.itemID !in itemIDs) itemIDs += leaf.key.itemID
            indexes[leaf.key] = index
        }
        this.itemIDs = itemIDs
        this.indexes = indexes
    }

    fun index(key: TranscriptSelectionLeafKey): Int? = indexes[key]

    /** The leaf holding [offset] (the one before, on a separator) and the offset within it. */
    fun locate(offset: Int): Pair<Int, Int>? {
        if (leaves.isEmpty()) return null
        var low = 0
        var high = leaves.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low to (offset - starts[low]).coerceIn(0, leaves[low].length)
    }

    fun offset(leaf: Int, local: Int): Int = starts[leaf] + local.coerceIn(0, leaves[leaf].length)

    /** [range] widened to whole composed characters (an emoji, a letter and its accents). */
    fun composed(range: TextRange): TextRange {
        if (length == 0 || range.start >= length) return TextRange(minOf(range.start, length), 0)
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(string)
        val start = clusterStart(characters, range.start)
        val end = minOf(range.end, length)
        val widenedEnd = if (range.length == 0) clusterEnd(characters, start) else clusterEnd(characters, maxOf(end - 1, start))
        return TextRange(start, widenedEnd - start)
    }

    /** The word around [offset], or the character there when it isn't in one. */
    fun wordRange(offset: Int): TextRange {
        val (leafIndex, local) = locate(offset) ?: return TextRange(0, 0)
        val leaf = leaves[leafIndex]
        val text = leaf.text
        val start = starts[leafIndex]
        if (text.isEmpty()) return TextRange(start, 0)
        val at = minOf(local, text.length - 1)
        // A typeset formula or a slot is one piece.
        for (segment in leaf.segments) {
            if (segment.isAtomic && at >= segment.textStart && at < segment.textEnd) {
                return TextRange(start + segment.textStart, segment.textCount)
            }
        }
        val words = BreakIterator.getWordInstance()
        words.setText(text)
        var wordStart = words.first()
        var wordEnd = words.next()
        while (wordEnd != BreakIterator.DONE) {
            if (wordStart > at) break
            val isWord = words.ruleStatus != BreakIterator.WORD_NONE
            if (isWord && at >= wordStart && at < wordEnd) {
                return TextRange(start + wordStart, wordEnd - wordStart)
            }
            wordStart = wordEnd
            wordEnd = words.next()
        }
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(text)
        val clusterStart = clusterStart(characters, at)
        val clusterEnd = clusterEnd(characters, clusterStart)
        return TextRange(start + clusterStart, clusterEnd - clusterStart)
    }

    /** The composed character boundary nearest [offset] (never inside an emoji). */
    fun nearestBoundary(offset: Int): Int {
        if (offset <= 0 || offset >= length) return offset.coerceIn(0, length)
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(string)
        if (characters.isBoundary(offset)) return offset
        val before = characters.preceding(offset)
        val after = characters.following(offset)
        return if (offset - before <= after - offset) before else after
    }

    /**
     * What Copy puts on the clipboard: each leaf's selected text, list items with their Markdown
     * prefix when the selection covers more than one block, table cells by tab and row, blocks
     * by a blank line.
     */
    fun copiedText(range: TextRange): String = joined(range, quoting = false)

    /** What Quote hands the composer: as Copy, with code in its fence. */
    fun quotedText(range: TextRange): String = joined(range, quoting = true)

    private fun joined(range: TextRange, quoting: Boolean): String {
        val pieces = pieces(range)
        val blocks = HashSet<Int>()
        for (piece in pieces) {
            blocks += leaves[piece.first].blockIndex
        }
        val spansBlocks = blocks.size > 1
        val result = StringBuilder()
        var previous: TranscriptSelectionLeaf? = null
        for ((leafIndex, pieceRange) in pieces) {
            val leaf = leaves[leafIndex]
            var text = leaf.text.substring(pieceRange.start, pieceRange.end)
            val role = leaf.role
            if (role !is TranscriptSelectionLeaf.Role.Code) {
                text = readable(text)
            }
            if (role is TranscriptSelectionLeaf.Role.ListItem && spansBlocks && pieceRange.start == 0) {
                text = role.prefix + text
            }
            if (role is TranscriptSelectionLeaf.Role.Code && quoting) {
                text = MarkdownQuote.fencedCode(text, role.language)
            }
            if (previous != null) {
                result.append(separator(previous, leaf))
            }
            result.append(text)
            previous = leaf
        }
        return result.toString()
    }

    /** The selected stretch of each leaf [range] touches, in order. */
    fun pieces(range: TextRange): List<Pair<Int, TextRange>> {
        if (range.length <= 0) return emptyList()
        val first = locate(range.start) ?: return emptyList()
        val last = locate(range.end) ?: return emptyList()
        val pieces = ArrayList<Pair<Int, TextRange>>()
        for (index in first.first..last.first) {
            val lower = if (index == first.first) first.second else 0
            val upper = if (index == last.first) last.second else leaves[index].length
            if (upper <= lower) continue
            pieces += index to TextRange(lower, upper - lower)
        }
        return pieces
    }

    companion object {
        /**
         * Drawing-only characters out (never from code, which copies exactly): a session chip's
         * padding, the word joiners that keep a compact link on one line, no-break spaces.
         */
        fun readable(text: String): String = text
            .replace(SessionLinkText.leadingPad, "")
            .replace(SessionLinkText.trailingPad, "")
            .replace("\u2060", "")
            .replace("\u00A0", " ")

        private fun separator(first: TranscriptSelectionLeaf, second: TranscriptSelectionLeaf): String {
            val firstRole = first.role
            val secondRole = second.role
            if (first.blockIndex == second.blockIndex &&
                firstRole is TranscriptSelectionLeaf.Role.TableCell &&
                secondRole is TranscriptSelectionLeaf.Role.TableCell
            ) {
                return if (firstRole.row == secondRole.row) "\t" else "\n"
            }
            if (firstRole is TranscriptSelectionLeaf.Role.ListItem && secondRole is TranscriptSelectionLeaf.Role.ListItem) return "\n"
            return "\n\n"
        }

        private fun clusterStart(characters: BreakIterator, offset: Int): Int {
            if (characters.isBoundary(offset)) return offset
            return characters.preceding(offset)
        }

        private fun clusterEnd(characters: BreakIterator, offset: Int): Int {
            val next = characters.following(offset)
            return if (next == BreakIterator.DONE) offset else next
        }

        /**
         * The reply rows of the turn holding [itemID]: every assistant reply row from the turn's
         * start to its end (a prompt, the reply's end, a compaction, a model switch or an undo),
         * across tool groups.
         */
        fun replyRows(itemID: String, entries: List<TranscriptEntry>): List<OpenCodeTranscriptItem.AssistantMarkdown> {
            var turn = ArrayList<OpenCodeTranscriptItem.AssistantMarkdown>()
            var holds = false
            for (entry in entries) {
                val item = (entry as? TranscriptEntry.Single)?.item
                if (item is OpenCodeTranscriptItem.AssistantMarkdown) {
                    turn += item
                    if (item.id == itemID) holds = true
                    continue
                }
                val endsTurn = entry is TranscriptEntry.UserGroup ||
                    entry is TranscriptEntry.ReplyEnd ||
                    item is OpenCodeTranscriptItem.Compaction ||
                    item is OpenCodeTranscriptItem.ModelSwitch ||
                    item is OpenCodeTranscriptItem.Undone
                if (endsTurn) {
                    if (holds) return turn
                    turn = ArrayList()
                }
            }
            return if (holds) turn else emptyList()
        }

        /** The leaves `MarkdownBlocksView` draws for these rows, in order. */
        fun make(rows: List<TranscriptSelectionRow>, context: TranscriptSelectionContext): TranscriptSelectionDocument {
            val leaves = ArrayList<TranscriptSelectionLeaf>()
            var blockIndex = 0
            val style = context.style
            for (row in rows) {
                val growing = context.isGrowing(row.itemID)
                val blocks = ArrayList<OpenCodeMarkdownBlock>()
                for (block in row.blocks) {
                    if (block.kind !is OpenCodeMarkdownBlock.Kind.PullRequest) blocks += block
                }
                for ((index, block) in blocks.withIndex()) {
                    val tail = growing && index == blocks.size - 1
                    val key = TranscriptSelectionLeafKey(row.itemID, block.id)
                    fun prose(text: InlineText, role: TranscriptSelectionLeaf.Role): TranscriptSelectionLeaf {
                        val options = PresentationOptions(
                            foreground = style.bodyColor,
                            textSize = style.bodySize,
                            linkStyle = context.linkStyle,
                            isGrowing = tail,
                        )
                        // Only whether there's a store matters to the text (the icon draws in its slot).
                        var siteIcon: ((String) -> SiteIcon?)? = null
                        if (context.decoratesLinks) siteIcon = { null }
                        val presented = MarkdownPresentation.present(text, options, style, context.sessionTargets, context.density, null, siteIcon)
                        return TranscriptSelectionText.leaf(key, role, blockIndex, presented)
                    }
                    when (val kind = block.kind) {
                        is OpenCodeMarkdownBlock.Kind.Paragraph -> leaves += prose(kind.text, TranscriptSelectionLeaf.Role.Prose)
                        is OpenCodeMarkdownBlock.Kind.Heading -> leaves += prose(kind.text, TranscriptSelectionLeaf.Role.Heading)
                        is OpenCodeMarkdownBlock.Kind.Blockquote -> leaves += prose(kind.text, TranscriptSelectionLeaf.Role.Quote)
                        is OpenCodeMarkdownBlock.Kind.NumberedItem ->
                            leaves += prose(kind.text, TranscriptSelectionLeaf.Role.ListItem("${kind.marker} "))
                        is OpenCodeMarkdownBlock.Kind.ListItem -> {
                            val bullet = when (kind.marker) {
                                OpenCodeMarkdownBlock.doneTask -> "- [x] "
                                OpenCodeMarkdownBlock.openTask -> "- [ ] "
                                "•" -> "- "
                                else -> "${kind.marker} "
                            }
                            leaves += prose(kind.text, TranscriptSelectionLeaf.Role.ListItem("  ".repeat(kind.level) + bullet))
                        }
                        is OpenCodeMarkdownBlock.Kind.Code ->
                            leaves += TranscriptSelectionText.leaf(key, TranscriptSelectionLeaf.Role.Code(kind.code.language), blockIndex, kind.code.source)
                        is OpenCodeMarkdownBlock.Kind.Math -> {
                            val sizePx = InlineMath.fontSizePx(MarkdownStyle.bodyTextSize, context.density) * 1.15f
                            val typesets = MathRenderer.typesets(kind.math.source, display = true, fontSizePx = sizePx)
                            leaves += TranscriptSelectionText.leaf(key, TranscriptSelectionLeaf.Role.Math, blockIndex, kind.math.raw, isImage = typesets)
                        }
                        is OpenCodeMarkdownBlock.Kind.Table -> {
                            val tableRows = ArrayList<List<InlineText>>()
                            tableRows += kind.header
                            tableRows.addAll(kind.rows)
                            for ((rowIndex, cells) in tableRows.withIndex()) {
                                for ((column, cell) in cells.withIndex()) {
                                    val cellKey = TranscriptSelectionLeafKey(row.itemID, block.id, TranscriptSelectionLeafKey.tablePart(rowIndex, column))
                                    // `MarkdownTable.present`: no Link Icons, never a growing tail.
                                    val options = PresentationOptions(
                                        foreground = style.colors.text,
                                        textSize = OpenCodeTextStyles.subheadline.fontSize,
                                        tonesBody = false,
                                    )
                                    val presented = MarkdownPresentation.present(cell, options, style, context.sessionTargets, context.density, null, null)
                                    leaves += TranscriptSelectionText.leaf(cellKey, TranscriptSelectionLeaf.Role.TableCell(rowIndex, column), blockIndex, presented)
                                }
                            }
                        }
                        is OpenCodeMarkdownBlock.Kind.Divider,
                        is OpenCodeMarkdownBlock.Kind.PullRequest,
                        is OpenCodeMarkdownBlock.Kind.Files,
                        is OpenCodeMarkdownBlock.Kind.Image,
                        is OpenCodeMarkdownBlock.Kind.Mermaid,
                        -> Unit
                    }
                    blockIndex++
                }
            }
            return TranscriptSelectionDocument(leaves)
        }
    }
}
