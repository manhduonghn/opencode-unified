package ai.opencode.feature.transcript.list

import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.OpenCodeLiveContentEvent
import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicInteger

/**
 * What arrived while the reader was away: from the first row that wasn't on screen when they last
 * left the session. The list brings them to it at the end; a New divider sits above it when it
 * doesn't fit on screen.
 *
 * The session-state half is reducer logic in `:feature:session`; it builds marks with
 * [firstUnseen].
 */
@Immutable
data class NewMessagesMark(
    /** The first transcript item the reader hadn't seen. */
    val itemID: String,
    /** The last item it covered: more arriving below makes a new mark. */
    val lastItemID: String? = null,
    /** Changes whenever the divider moves to another item, so the list lands on each mark once. */
    val generation: Int,
) {
    companion object {
        /** Rows kept above the New divider. */
        const val NEW_MESSAGES_CONTEXT = 20

        /**
         * The first item in [items] that isn't in [seen], or null when nothing is new. Client-side
         * rows (`info-` notices, send failures) never count as new, nor do the reader's own
         * messages, from this phone or another client: like a chat app, sending one reads
         * everything above it. Messages an agent sent into the session (`source`) are news. Rows
         * in [watched] streamed in while the reader watched: seen, though rows a read brings in
         * above them may not have been. Rows minted at or before [viewedMilliseconds] (the
         * session's read watermark) are seen too.
         */
        fun firstUnseen(
            items: List<OpenCodeTranscriptItem>,
            seen: Set<String>,
            watched: Set<String> = emptySet(),
            viewedMilliseconds: Long? = null,
            unread: Boolean = false,
        ): String? {
            if (seen.isEmpty() && !unread && viewedMilliseconds == null) return null
            fun isSeen(item: OpenCodeTranscriptItem): Boolean {
                if (item.id in seen) return true
                if (viewedMilliseconds != null && TranscriptTimeStamps.isAtOrBefore(viewedMilliseconds, item.id) == true) return true
                return false
            }
            // After the last row they saw: older rows paged in or a notice placed in an earlier
            // turn aren't news. Found from the end, so a long session's read history is never
            // walked; only the tail after it is.
            val afterSeen = items.indexOfLast(::isSeen).let { if (it >= 0) it + 1 else 0 }
            TranscriptScan.record(items.size - afterSeen + if (afterSeen > 0) 1 else 0)
            val tail = items.subList(afterSeen, items.size)
            // A viewed time alone marks only if something after it carries a later time.
            if (seen.isEmpty() && !unread && viewedMilliseconds != null &&
                tail.none { TranscriptTimeStamps.isAtOrBefore(viewedMilliseconds, it.id) == false }
            ) {
                return null
            }
            val isOwn = ownPrompts(tail)
            val lastOwn = tail.indexOfLast(isOwn)
            val start = if (lastOwn >= 0) afterSeen + lastOwn + 1 else afterSeen
            return items.subList(start, items.size).firstOrNull { item ->
                if (isSeen(item) || item.id in watched || item.id.startsWith("info-")) return@firstOrNull false
                item !is OpenCodeTranscriptItem.SendFailure && item !is OpenCodeTranscriptItem.RunRetry &&
                    item !is OpenCodeTranscriptItem.Undone
            }?.id
        }

        /**
         * Whether a row is part of the reader's own message, from any client; a message an agent
         * sent into the session isn't theirs.
         */
        fun ownPrompts(items: Iterable<OpenCodeTranscriptItem>): (OpenCodeTranscriptItem) -> Boolean {
            val agentPrompts = items.mapNotNull { item ->
                (item as? OpenCodeTranscriptItem.UserText)?.takeIf { it.source != null }?.id
            }.toSet()
            return { item -> TranscriptEntry.promptID(item)?.let { it !in agentPrompts } ?: false }
        }

        /**
         * Counts the rows the mark's span reads (a turn rebuilt, the own-message check) while a
         * test holds it, so the test can prove a streamed row doesn't read the transcript again.
         */
        class Tally {
            private val value = AtomicInteger(0)
            val count: Int get() = value.get()
            fun add(rows: Int) {
                value.addAndGet(rows)
            }
        }

        @Volatile
        @PublishedApi
        internal var tally: Tally? = null

        /** Runs [block] with [tally] counting the span reads. */
        inline fun <T> withTally(tally: Tally, block: () -> T): T {
            val previous = this.tally
            this.tally = tally
            try {
                return block()
            } finally {
                this.tally = previous
            }
        }

        fun recordSpan(rows: Int) {
            tally?.add(rows)
        }

        /** Where the list draws the New divider: above the first entry holding an item from the mark onward. */
        fun resolve(mark: NewMessagesMark?, items: List<OpenCodeTranscriptItem>, entries: List<TranscriptEntry>): NewMessagesPlacement? {
            if (mark == null) return null
            val start = items.indexOfFirst { it.id == mark.itemID }
            if (start < 0) return null
            val newIDs = items.subList(start, items.size).map { it.id }.toSet()
            // An entry that starts with something new: a group already on screen stays above.
            var index = entries.indexOfFirst { e -> e.itemIDs.firstOrNull()?.let { it in newIDs } ?: false }
            if (index < 0) return null
            // A message dated with a time just above it: the divider goes above both.
            val previous = entries.getOrNull(index - 1)
            if (previous is TranscriptEntry.Single && previous.item is OpenCodeTranscriptItem.Info &&
                previous.item.id.startsWith(TranscriptTimeStamps.PREFIX)
            ) {
                index--
            }
            return NewMessagesPlacement(entries[index].id, mark.generation)
        }
    }
}

/**
 * Unseen baseline kept when a settled mark starts at the top of a paged window (`earlierHistory`
 * not exhausted), so loading an earlier page can move the divider to the true first unseen row.
 */
@Immutable
data class NewMessagesEarlierBaseline(
    val seen: Set<String>,
    val watched: Set<String>,
    val viewedMilliseconds: Long?,
    val unread: Boolean,
)

/**
 * New rows from [itemID] through [lastItemID] (of [count] rows) that start no entry of their own.
 */
@Immutable
data class NewMessagesFold(val itemID: String, val lastItemID: String?, val count: Int) {
    companion object {
        /**
         * Live events that only add text or tool input, never changing which rows start an entry
         * (a tool's name or status: a question answered joins the transcript), so a fold found
         * before them still holds.
         */
        fun keepsShapes(events: List<OpenCodeLiveContentEvent>): Boolean = events.all {
            when (it) {
                is OpenCodeLiveContentEvent.StepStarted, is OpenCodeLiveContentEvent.StepStreamed,
                is OpenCodeLiveContentEvent.StepEnded, is OpenCodeLiveContentEvent.TextStarted,
                is OpenCodeLiveContentEvent.TextDelta, is OpenCodeLiveContentEvent.TextEnded,
                is OpenCodeLiveContentEvent.ReasoningStarted, is OpenCodeLiveContentEvent.ReasoningDelta,
                is OpenCodeLiveContentEvent.ReasoningEnded, is OpenCodeLiveContentEvent.ToolInputDelta,
                is OpenCodeLiveContentEvent.ToolInputEnded,
                -> true
                is OpenCodeLiveContentEvent.ToolInputStarted, is OpenCodeLiveContentEvent.ToolCalled,
                is OpenCodeLiveContentEvent.ToolProgress, is OpenCodeLiveContentEvent.ToolSucceeded,
                is OpenCodeLiveContentEvent.ToolFailed,
                -> false
            }
        }
    }
}
