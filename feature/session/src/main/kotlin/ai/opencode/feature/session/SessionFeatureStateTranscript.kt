package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeTranscriptItem

// The transcript window, what caches keep, and the loading and empty states.

/** The transcript as the server confirmed it, for caches: no message still in flight or failed. */
val State.cacheableItems: List<OpenCodeTranscriptItem> get() = items(optimisticPromptIDs + failedSends.ids)

/** Without failed sends' bubbles, which `restoreFailedSends` brings back. */
val State.itemsWithoutFailedSends: List<OpenCodeTranscriptItem> get() = items(failedSends.ids.toSet())

/** The first loaded row the transcript shows. */
val State.windowStart: Int
    get() {
        val latestStart = maxOf(0, items.size - visibleLimit)
        val start = minOf(latestStart, browsingStartIndex ?: latestStart)
        return TranscriptEntry.entryStart(start, items)
    }

val State.visibleItems: List<OpenCodeTranscriptItem>
    get() = hidingUndone(items.subList(minOf(windowStart, items.size), items.size))

val State.firstVisibleItemID: String?
    get() {
        if (session?.revertMessageID != null) return visibleItems.firstOrNull()?.id
        val start = windowStart
        return items.getOrNull(start)?.id
    }

/** Once drawn, keeps the window starting at the row it started at before an update. */
fun State.retainRenderedWindowStart(previousFirstID: String?): State {
    if (!hasRenderedFirstContentFrame || previousFirstID == null) return this
    val index = if (items.firstOrNull()?.id == previousFirstID) {
        0
    } else {
        items.indexOfFirst { it.id == previousFirstID }.takeIf { it >= 0 }
            ?: items.indexOfFirst {
                val messageID = SessionFeature.messageID(it)
                messageID == previousFirstID || previousFirstID.startsWith("$messageID-")
            }.takeIf { it >= 0 }
            ?: return this
    }
    // A row removed below it must not pull an earlier one into view: then exactly the rows from it on.
    val nextLimit = if (windowStart < index) items.size - index else maxOf(visibleLimit, items.size - index)
    return if (visibleLimit != nextLimit) copy(visibleLimit = nextLimit) else this
}

/**
 * The window a reader back at the end settles to: the last `settledWindowEntries` entries (at
 * least `baseVisibleLimit` rows) from the end, or from Revert to Here's boundary so the undone
 * turns stay folded under it; back to the start of the turn it would cut (a reply's Copy and link
 * previews take the whole turn), within `settledWindowCap` rows; and the New divider with the row
 * above it.
 */
val State.settledVisibleLimit: Int
    get() {
        val end = revertBoundaryIndex ?: items.size
        var start = end
        var entries = 0
        while (entries < SessionFeature.settledWindowEntries && start > 0) {
            start = TranscriptEntry.entryStart(start - 1, items)
            entries += 1
        }
        if (start < end && start > 0 && TranscriptEntry.promptID(items[start]) == null) {
            var prompt = -1
            for (index in start - 1 downTo 0) {
                if (TranscriptEntry.promptID(items[index]) != null) {
                    prompt = index
                    break
                }
            }
            if (prompt >= 0) {
                val cap = maxOf(0, items.size - SessionFeature.settledWindowCap)
                start = minOf(start, TranscriptEntry.entryStart(maxOf(prompt, cap), items))
            }
        }
        var limit = maxOf(SessionFeature.baseVisibleLimit, items.size - start)
        val mark = newMessages
        if (mark != null) {
            val index = items.indexOfLast { it.id == mark.itemID }
            if (index >= 0) limit = maxOf(limit, items.size - index + 1)
        }
        return limit
    }

/** Where Revert to Here's undone rows start, when they're loaded. */
val State.revertBoundaryIndex: Int?
    get() {
        val boundary = session?.revertMessageID ?: return null
        val rows = MessageRows(boundary)
        for (index in items.indices) {
            val item = items[index]
            val isPrompt = item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages ||
                item is OpenCodeTranscriptItem.UserFiles
            if (isPrompt && rows.contains(item)) return index
        }
        return null
    }

fun State.pinBrowsingWindowIfNeeded(): State {
    if (browsingWindowStartID == null) return this
    val start = windowStart
    val nextID = items.getOrNull(start)?.id ?: firstVisibleItemID
    return if (browsingWindowStartID != nextID) copy(browsingWindowStartID = nextID) else this
}

val State.hasEarlierItems: Boolean
    get() = items.size > visibleLimit ||
        // A restored cache can be browsed immediately, but do not start the cursor chain from its stale edge.
        (earlierHistory != State.EarlierHistory.Exhausted && !isLoading &&
            ((!hasRenderedFirstContentFrame &&
                (earlierHistory != State.EarlierHistory.UnknownEdge || items.isEmpty() ||
                    (refresh == null && items.size >= SessionFeature.initialFetchLimit))) ||
                initialTranscriptIsFresh) &&
            !(items.isEmpty() && initialTranscriptIsFresh && earlierHistory == State.EarlierHistory.UnknownEdge))

/** Whether a transcript request is in flight or the first load has not yet been attempted. */
val State.isTranscriptLoading: Boolean
    get() = isLoading || isLoadingEarlier ||
        (!initialTranscriptIsFresh &&
            (refresh != null || initialTranscriptRequestIsExternal || (!isDraft && nextRefreshID == 0 && loadError == null)) &&
            items.isEmpty())

/** Whether the empty-session placeholder may be shown. */
val State.showsEmptyState: Boolean
    get() = !isDraft && initialTranscriptIsFresh && items.isEmpty() && queuedInputs.isEmpty() &&
        !isRunning && !isTranscriptLoading && !hasEarlierItems && earlierHistoryError == null
