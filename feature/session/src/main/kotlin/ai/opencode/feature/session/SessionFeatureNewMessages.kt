package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State.EarlierHistory
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.NewMessagesEarlierBaseline
import ai.opencode.feature.transcript.list.NewMessagesFold
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.server.model.OpenCodeTranscriptItem

// They extend the session's state, so they live in this module; `NewMessagesMark` (with
// `firstUnseen`, `ownPrompts` and the span tally), `NewMessagesEarlierBaseline`, `NewMessagesFold`
// and the list's `NewMessagesPlacement` belong to `:feature:transcript`.

/** Rows kept above the New divider. */
val SessionFeature.State.Companion.newMessagesContext: Int get() = NewMessagesMark.NEW_MESSAGES_CONTEXT

/** What was on screen as the reader leaves, kept until the read that follows their return settles what's new. */
fun SessionFeature.State.noteLeaving(): SessionFeature.State {
    if (isDraft) return this
    val unreadMarkID = newMessages?.itemID
    val earlierBaseline = newMessagesEarlierBaseline
    // What it marked has been in front of the reader: the divider goes while they're away, so the
    // next one arrives fresh rather than moving.
    val s = copy(newMessages = null, newMessagesFitThrough = null, newMessagesEarlierBaseline = null)
    if (s.newMessagesBaseline != null) return s
    val unreadIndex = unreadMarkID?.let { id -> s.items.indexOfLast { it.id == id } } ?: -1
    return when {
        earlierBaseline != null -> s.copy(
            newMessagesBaseline = earlierBaseline.seen,
            newMessagesBaselineTimestamp = earlierBaseline.viewedMilliseconds,
            newMessagesUnread = earlierBaseline.unread,
            newMessagesWatched = earlierBaseline.watched,
        )
        unreadIndex >= 0 -> s.copy(
            newMessagesBaseline = SessionFeature.SwitchSnapshot.seenTail(s.items.subList(0, unreadIndex)),
            newMessagesBaselineTimestamp = null,
            newMessagesUnread = unreadIndex == 0,
            newMessagesWatched = emptySet(),
        )
        else -> s.copy(
            newMessagesBaseline = SessionFeature.SwitchSnapshot.seenTail(s.items),
            newMessagesBaselineTimestamp = null,
            newMessagesUnread = false,
            newMessagesWatched = emptySet(),
        )
    }
}

/** Opening a session: what the reader saw last time they were here, or, when this run hasn't shown it, the saved copy it opens with. */
fun SessionFeature.State.noteOpening(
    seen: Set<String>?,
    viewedMilliseconds: Long? = null,
    unread: Boolean = false,
): SessionFeature.State {
    if (isDraft) return this
    val baseline: Set<String>? = when {
        seen != null && seen.isNotEmpty() -> seen
        unread || viewedMilliseconds != null -> emptySet()
        seen != null -> seen
        else -> if (items.isEmpty()) null else items.mapTo(HashSet()) { it.id }
    }
    return copy(
        newMessagesBaseline = baseline,
        newMessagesBaselineTimestamp = viewedMilliseconds,
        newMessagesUnread = unread,
        newMessagesEarlierBaseline = null,
        newMessagesWatched = emptySet(),
    ).updateNewMessagesMark(settles = false)
}

// `noteOwnSend()` lives in `RunErrorState.kt` (group SEND).

/**
 * A mark that fit on screen showed at the bottom without the divider: in front of the reader, its
 * rows are seen, so only what comes after them can make a divider. (While away the list keeps
 * measuring the whole span: rows that each fit can add up to a backlog that doesn't.)
 */
fun SessionFeature.State.absorbFittingNewMessages(): SessionFeature.State {
    val through = newMessagesFitThrough ?: return this
    val s = copy(newMessagesFitThrough = null)
    val baseline = s.newMessagesBaseline ?: return s
    val end = s.items.indexOfFirst { it.id == through }
    if (end < 0) return s
    return s.copy(
        newMessagesBaseline = baseline + s.items.subList(0, end + 1).map { it.id },
        newMessagesBaselineTimestamp = null,
        newMessagesUnread = false,
    )
}

/**
 * Places the divider above the first row the reader hasn't seen. The read after their return
 * [settles] it: with nothing new the divider goes, and what arrives from then on arrives in front
 * of them. The list draws that divider only when what it marks doesn't fit on screen
 * (`newMessagesFit`); without one the mark is still placed, and is what lets a following reader
 * glide to what arrived instead of the rows landing in one frame. Only rows that [keptShapes]
 * (grew or were added, [NewMessagesFold.keepsShapes]) may reuse a fold found before.
 */
fun SessionFeature.State.updateNewMessagesMark(settles: Boolean, keptShapes: Boolean = false): SessionFeature.State {
    var s = if (keptShapes) this else copy(newMessagesFolded = null)
    val seen = s.newMessagesBaseline
    val watched = s.newMessagesWatched
    val viewedMilliseconds = s.newMessagesBaselineTimestamp
    val unread = s.newMessagesUnread
    fun settled(state: SessionFeature.State): SessionFeature.State =
        if (!settles) {
            state
        } else {
            state.copy(
                newMessagesBaseline = null,
                newMessagesBaselineTimestamp = null,
                newMessagesUnread = false,
                newMessagesWatched = emptySet(),
            )
        }
    if (seen == null) return settled(s)
    val itemID = NewMessagesMark.firstUnseen(s.items, seen, watched, viewedMilliseconds, unread)
    val index = itemID?.let { id -> s.items.indexOfLast { it.id == id } } ?: -1
    if (itemID != null && index >= 0) {
        val items = s.items
        val lastItemID = items.lastOrNull()?.id
        val moves = s.newMessages?.itemID != itemID || s.newMessages?.lastItemID != lastItemID
        // Everything new joined a group the reader already had on screen: no row to put the divider
        // above, so nothing is marked. A mark kept anyway would wake on the next row to stream in
        // front of them. A mark already on this row found its entry; rows arriving after it can't
        // fold it into an earlier one.
        val span = NewMessagesFold(itemID = itemID, lastItemID = lastItemID, count = items.size)
        if (moves && s.newMessages?.itemID != itemID && (s.newMessagesFolded == span || !s.startsAnEntry(index))) {
            return settled(s.copy(newMessages = null, newMessagesFolded = span))
        }
        // The divider and what's below it show, however many arrived, with the usual window's worth
        // above it: landing at the divider mustn't put the reader at the top, paging in more history
        // under them. Short of every loaded row while older history may still come: a window that
        // reaches them all asks for it. The context comes with the first frame only: rows added
        // above the reader once it's drawn would page in under them.
        val needed = items.size - index + 1
        val reach = items.size - index + SessionFeature.State.newMessagesContext
        val context = if (s.hasRenderedFirstContentFrame) {
            needed
        } else {
            minOf(if (s.earlierHistory == EarlierHistory.Exhausted) items.size else items.size - 1, reach)
        }
        val limit = maxOf(s.visibleLimit, needed, context)
        if (limit != s.visibleLimit) s = s.copy(visibleLimit = limit)
        if (moves) {
            // Measured again as a whole: an earlier fit says nothing about it.
            val generation = s.newMessagesGeneration + 1
            s = s.copy(
                newMessagesFitThrough = null,
                newMessagesGeneration = generation,
                newMessages = NewMessagesMark(itemID = itemID, lastItemID = lastItemID, generation = generation),
            )
        }
        if (settles) {
            val mayHaveEarlierUnseen = index == 0 &&
                s.earlierHistory != EarlierHistory.Exhausted &&
                (s.earlierHistory != EarlierHistory.UnknownEdge || items.size >= SessionFeature.initialFetchLimit)
            s = s.copy(
                newMessagesEarlierBaseline = if (mayHaveEarlierUnseen) {
                    NewMessagesEarlierBaseline(seen = seen, watched = watched, viewedMilliseconds = viewedMilliseconds, unread = unread)
                } else {
                    null
                },
            )
        }
    } else if (settles) {
        s = s.copy(newMessages = null, newMessagesEarlierBaseline = null)
    }
    return settled(s)
}

/** Recomputes a settled mark when an earlier history page prepends rows above a tail window that had overflowed the seen boundary. */
fun SessionFeature.State.recomputeNewMessagesAfterEarlierHistory(): SessionFeature.State {
    val earlier = newMessagesEarlierBaseline ?: return this
    return copy(
        newMessagesBaseline = earlier.seen,
        newMessagesWatched = earlier.watched,
        newMessagesBaselineTimestamp = earlier.viewedMilliseconds,
        newMessagesUnread = earlier.unread,
    ).updateNewMessagesMark(settles = true)
}

/** Whether some entry starts at or after the item at [index]: an item folded into a group that started earlier has no row of its own. */
private fun SessionFeature.State.startsAnEntry(index: Int): Boolean {
    // Groups never cross the reader's own message: build from the turn's.
    val turn = items.subList(0, index).indexOfLast { TranscriptEntry.promptID(it) != null }.coerceAtLeast(0)
    NewMessagesMark.recordSpan(items.size - turn)
    val ids = items.subList(index, items.size).mapTo(HashSet()) { it.id }
    return TranscriptEntry.build(items.subList(turn, items.size)).any { entry -> entry.itemIDs.firstOrNull()?.let { it in ids } ?: false }
}

/** Rows that streamed in while the reader watched, before the read after their return settled: seen, so the read never marks them. */
fun SessionFeature.State.noteWatched(previousIDs: Set<String>): SessionFeature.State {
    if (newMessagesBaseline == null) return this
    val added = items.filter { it.id !in previousIDs }.map { it.id }
    if (added.all { it in newMessagesWatched }) return this
    return copy(newMessagesWatched = newMessagesWatched + added)
}

fun SessionFeature.State.noteWatched(itemID: String): SessionFeature.State {
    if (newMessagesBaseline == null || itemID in newMessagesWatched) return this
    return copy(newMessagesWatched = newMessagesWatched + itemID)
}

/** The reader's own message (from another client, or spoken on a call) came in under the divider: like a send here, it reads what's above. */
fun SessionFeature.State.clearNewMessagesAfterOwnPrompt(): SessionFeature.State {
    val mark = newMessages ?: return this
    val start = items.indexOfFirst { it.id == mark.itemID }
    if (start < 0) return this
    val suffix = items.subList(start, items.size)
    NewMessagesMark.recordSpan(suffix.size)
    val isOwn = NewMessagesMark.ownPrompts(suffix)
    if (suffix.none(isOwn)) return this
    return noteOwnSend()
}

/**
 * Something came over the transcript (a subagent, its browser, Settings) or went: covered is
 * away, uncovered is back, with what the stream brought meanwhile already read in.
 */
fun SessionFeature.State.setTranscriptCovered(covered: Boolean): SessionFeature.State {
    if (covered == isTranscriptCovered) return this
    val s = copy(isTranscriptCovered = covered)
    if (!s.isVisible) return s
    return if (covered) {
        if (s.hasRenderedFirstContentFrame && s.isSceneActive) s.noteLeaving() else s
    } else {
        s.absorbFittingNewMessages().updateNewMessagesMark(settles = true)
    }
}

/** In front of the reader: on screen in an active foreground scene, with nothing over the transcript. */
val SessionFeature.State.watchesTranscript: Boolean
    get() = isVisible && isSceneActive && !isBackgrounded && !isTranscriptCovered
