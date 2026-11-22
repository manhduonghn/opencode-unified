package ai.opencode.server.model

import ai.opencode.core.strings.localized
import ai.opencode.server.R

/**
 * The one rule for what a session is called, used by every surface (drawer rows, header, Session
 * sheet, Catch Up, switcher, notices, notifications, widgets, shortcuts, search, voice).
 *
 * A real server title always wins. Until the server names a session (free models never do), it
 * reads as its first prompt on one line; only when nothing is known does it read "Untitled session".
 * V2 omits `Session.Info.title` for an unnamed session; [placeholder] is what this app stores then
 * (and what snapshots cached by earlier builds hold), so it counts as no title too.
 */
object SessionTitle {
    /** What this app stores as an unnamed session's title: data, so it stays English. */
    const val placeholder = "Untitled session"

    /** What an unnamed session reads as when its first prompt isn't known. */
    val displayPlaceholder: String get() = localized(R.string.server_session_title_untitled)

    /** The most characters a first-prompt title keeps before it's cut (the ellipsis comes on top). */
    const val previewLimit = 60

    /** A cut lands on the last word boundary at or after this many characters; else mid-word. */
    private const val wordBoundaryMinimum = 40

    /**
     * What a session with [serverTitle] shows, given its [firstPrompt] as a [preview] (stored that
     * way, so reading a title never re-cuts it).
     */
    fun display(serverTitle: String?, firstPrompt: String?): String {
        val title = serverTitle?.trimmedWhitespace().orEmpty()
        if (!isPlaceholder(title)) return title
        return firstPrompt?.trimmedWhitespace()?.nonEmpty ?: displayPlaceholder
    }

    /** Whether [title] names nothing: empty, or the placeholder (in any case). */
    fun isPlaceholder(title: String?): Boolean {
        val trimmed = title?.trimmedWhitespace().orEmpty()
        return trimmed.isEmpty() || trimmed.equals(placeholder, ignoreCase = true) || trimmed.equals(displayPlaceholder, ignoreCase = true)
    }

    /**
     * [text] on one line: whitespace and newlines collapse to single spaces, and past [previewLimit]
     * characters it's cut at a word boundary where one is near (else mid-word, never inside a
     * surrogate pair) and gets "…". Null when nothing is left.
     */
    fun preview(text: String): String? {
        val line = text.split(whitespace).filter(String::isNotEmpty).joinToString(" ")
        if (line.isEmpty()) return null
        if (line.length <= previewLimit) return line
        var end = previewLimit
        if (Character.isHighSurrogate(line[end - 1])) end -= 1
        val space = line.lastIndexOf(' ', startIndex = end)
        if (space >= wordBoundaryMinimum) end = space
        return line.substring(0, end).trimEnd { it.isWhitespace() || it in trailingPunctuation } + "…"
    }

    /**
     * The first prompt [items] hold, as a preview, when they start at the session's beginning
     * ([reachesStart]: no earlier history to page). A window that doesn't reach the start would
     * name the session after whichever prompt it happens to begin with.
     */
    fun firstPrompt(items: List<OpenCodeTranscriptItem>, reachesStart: Boolean): String? {
        if (!reachesStart) return null
        val first = items.firstOrNull { it is OpenCodeTranscriptItem.UserText } as? OpenCodeTranscriptItem.UserText
        return first?.text?.let(::preview)
    }

    private val whitespace = Regex("[\\s\\u00A0\\u2028\\u2029]+")
    private const val trailingPunctuation = ",.;:—–-"
}

/** Whether the server hasn't named this session (its title may still come from its first prompt). */
val OpenCodeSession.isUntitled: Boolean get() = SessionTitle.isPlaceholder(serverTitle)

/**
 * This session knowing [preview] as its first prompt, if it's unnamed and didn't know one: the first
 * prompt a session is learned with never changes, so its title doesn't jump as history loads.
 */
fun OpenCodeSession.learningFirstPrompt(preview: String?): OpenCodeSession =
    if (preview == null || firstPrompt != null || !isUntitled) this else copy(firstPrompt = preview)

/** A fresh server copy of this session that keeps the first prompt [previous] already knew. */
fun OpenCodeSession.keepingFirstPrompt(previous: OpenCodeSession?): OpenCodeSession =
    if (previous == null || previous.id != id) this else learningFirstPrompt(previous.firstPrompt)

/** [keepingFirstPrompt] for a fresh list, against the copies [previous] held. */
fun List<OpenCodeSession>.keepingFirstPrompts(previous: Collection<OpenCodeSession>): List<OpenCodeSession> {
    if (previous.none { it.firstPrompt != null }) return this
    val known = previous.mapNotNull { session -> session.firstPrompt?.let { session.id to it } }.toMap()
    return map { it.learningFirstPrompt(known[it.id]) }
}
