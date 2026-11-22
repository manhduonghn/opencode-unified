package ai.opencode.feature.transcript.markdown

import ai.opencode.core.strings.localized
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionLink

// Marking session IDs in parsed inline Markdown. The URL/ID half is `:server`'s [SessionLink],
// which the link menu (`:feature:media`) reads too; these extend it so call sites read.

/** Marks the session IDs in inline Markdown freshly parsed from [markdown]. Text whose source can't render an ID returns as is. */
fun SessionLink.mark(text: InlineText, markdown: String): InlineText {
    if (!mayRenderID(markdown)) return text
    return markRuns(text)
}

/** The full scan: links every ID the rendered runs show. */
fun SessionLink.markRuns(text: InlineText): InlineText {
    var changed = false
    val out = ArrayList<InlineRun>(text.runs.size)
    for (run in text.runs) {
        if (run.link != null) {
            out.add(run)
            continue
        }
        if (run.code) {
            val id = if (run.languageIdentifier == null) exactID(run.text) else null
            if (id != null) {
                out.add(run.copy(link = url(id)))
                changed = true
            } else {
                out.add(run)
            }
            continue
        }
        val found = mentions(run.text)
        if (found.isEmpty()) {
            out.add(run)
            continue
        }
        changed = true
        var cursor = 0
        for (mention in found) {
            if (mention.range.first > cursor) out.add(run.copy(text = run.text.substring(cursor, mention.range.first)))
            out.add(run.copy(text = run.text.substring(mention.range.first, mention.range.last + 1), link = url(mention.id)))
            cursor = mention.range.last + 1
        }
        if (cursor < run.text.length) out.add(run.copy(text = run.text.substring(cursor)))
    }
    return if (changed) InlineText(out) else text
}

/** A user message's plain text with its session IDs marked (desktop's `sessionReferenceText`), or null when it mentions none. */
fun SessionLink.marked(plain: String): InlineText? {
    val found = mentions(plain)
    if (found.isEmpty()) return null
    val runs = ArrayList<InlineRun>()
    var cursor = 0
    for (mention in found) {
        if (mention.range.first > cursor) runs.add(InlineRun(plain.substring(cursor, mention.range.first)))
        runs.add(InlineRun(plain.substring(mention.range.first, mention.range.last + 1), link = url(mention.id)))
        cursor = mention.range.last + 1
    }
    if (cursor < plain.length) runs.add(InlineRun(plain.substring(cursor)))
    return InlineText(runs)
}

/** The session IDs linked in [text], once each, in order. */
fun SessionLink.ids(text: InlineText): List<String> {
    val seen = LinkedHashSet<String>()
    var previous: String? = null
    for (run in text.runs) {
        val link = run.link
        // `runs[\.link]` coalesces equal neighbours; ids dedupe anyway.
        if (link != null && link != previous) id(link)?.let(seen::add)
        previous = link
    }
    return seen.toList()
}

const val SESSION_LINK_MAXIMUM_TITLE = 40

fun sessionLinkTarget(session: OpenCodeSession): SessionLinkTarget =
    SessionLinkTarget.Session(session.title, session.parentID)

/** The chip's label: the title, or the short ID. */
fun SessionLinkTarget.label(id: String): String {
    if (this !is SessionLinkTarget.Session) return SessionLink.shortID(id)
    val trimmed = SwiftText.trimWhitespaceAndNewlines(title)
    if (trimmed.isEmpty()) return localized(R.string.transcript_session_link_new_session)
    return if (trimmed.length > SESSION_LINK_MAXIMUM_TITLE) trimmed.take(SESSION_LINK_MAXIMUM_TITLE - 1) + "…" else trimmed
}

/** The resolved title, or null while resolving or missing. */
val SessionLinkTarget.resolvedTitle: String?
    get() = if (this is SessionLinkTarget.Session) label("") else null

/**
 * One replacement in a text, in the original text's characters, so streamed arrival offsets map
 * through it.
 */
data class InlineTextEdit(val start: Int, val removed: Int, val inserted: Int) {
    companion object {
        /** Where a character offset in the original text lands after [edits]. */
        fun offset(original: Int, edits: List<InlineTextEdit>): Int {
            var delta = 0
            for (edit in edits) {
                if (original >= edit.start + edit.removed) {
                    delta += edit.inserted - edit.removed
                } else if (original > edit.start) {
                    return edit.start + delta + minOf(original - edit.start, edit.inserted)
                } else {
                    break
                }
            }
            return original + delta
        }
    }
}

/**
 * A reply text with its session links showing their titles (or short IDs). Offsets of streamed
 * arrivals map through [edits], as with link icons.
 */
data class SessionLinkText(val text: InlineText, val edits: List<InlineTextEdit>, val ids: List<String>) {
    fun offset(original: Int): Int = InlineTextEdit.offset(original, edits)

    fun knownLength(length: Int?): Int? = if (edits.isEmpty()) length else null

    companion object {
        /** Room for the glyph first, then the label. */
        const val leadingPad = "\u00A0\u2007\u00A0"
        const val trailingPad = "\u00A0\u202F"

        /**
         * Null when [text] links no session. Each link becomes a chip ([InlineRun.sessionChip]):
         * its title in the surrounding font, or the short ID muted in code.
         */
        fun present(text: InlineText, targets: Map<String, SessionLinkTarget>): SessionLinkText? {
            // Group equal-link neighbours as `runs[\.link]` does.
            data class Group(val start: Int, val end: Int, val id: String)
            val groups = ArrayList<Group>()
            var index = 0
            while (index < text.runs.size) {
                val link = text.runs[index].link
                var end = index + 1
                while (end < text.runs.size && text.runs[end].link == link) end++
                val id = link?.let(SessionLink::id)
                if (id != null && text.runs.subList(index, end).any { it.text.isNotEmpty() }) groups.add(Group(index, end, id))
                index = end
            }
            if (groups.isEmpty()) return null
            val result = ArrayList<InlineRun>()
            val edits = ArrayList<InlineTextEdit>()
            val ids = ArrayList<String>()
            var cursor = 0
            var offset = 0
            for (group in groups) {
                for (run in text.runs.subList(cursor, group.start)) {
                    offset += run.text.length
                    result.add(run)
                }
                val length = text.runs.subList(group.start, group.end).sumOf { it.text.length }
                val target = targets[group.id] ?: SessionLinkTarget.Resolving
                val piece = chip(group.id, target, text.runs[group.start])
                edits.add(InlineTextEdit(offset, length, piece.sumOf { it.text.length }))
                if (group.id !in ids) ids.add(group.id)
                result.addAll(piece)
                offset += length
                cursor = group.end
            }
            result.addAll(text.runs.subList(cursor, text.runs.size))
            return SessionLinkText(InlineText(result), edits, ids)
        }

        /** One chip: no-break padding in the surrounding font (room for the glyph first), then the label, all one `SessionChip`. */
        private fun chip(id: String, target: SessionLinkTarget, attributes: InlineRun): List<InlineRun> {
            val title = target.resolvedTitle
            val chip = when (target) {
                is SessionLinkTarget.Session -> SessionChip(id, muted = false, isSubagent = target.parentID != null)
                else -> SessionChip(id, muted = true, isSubagent = false)
            }
            val plain = attributes.copy(code = false, secondary = false, link = SessionLink.url(id, title), sessionChip = chip)
            var label = plain.copy(text = target.label(id).replace(" ", "\u00A0"))
            if (title == null) {
                // The short ID reads as code, muted: nothing named it yet.
                label = label.copy(code = true, secondary = true)
            }
            return listOf(plain.copy(text = leadingPad), label, plain.copy(text = trailingPad))
        }
    }
}

/** The session IDs this block links. */
val OpenCodeMarkdownBlock.sessionLinkIDs: List<String>
    get() = when (val kind = kind) {
        is OpenCodeMarkdownBlock.Kind.Paragraph -> SessionLink.ids(kind.text)
        is OpenCodeMarkdownBlock.Kind.NumberedItem -> SessionLink.ids(kind.text)
        is OpenCodeMarkdownBlock.Kind.ListItem -> SessionLink.ids(kind.text)
        is OpenCodeMarkdownBlock.Kind.Blockquote -> SessionLink.ids(kind.text)
        is OpenCodeMarkdownBlock.Kind.Heading -> SessionLink.ids(kind.text)
        is OpenCodeMarkdownBlock.Kind.Table -> (kind.header + kind.rows.flatten()).flatMap(SessionLink::ids)
        else -> emptyList()
    }

/** The session IDs a reply's blocks link, for resolving before its rows draw. */
fun sessionLinkIDs(blocks: List<OpenCodeMarkdownBlock>): List<String> = blocks.flatMap { it.sessionLinkIDs }
