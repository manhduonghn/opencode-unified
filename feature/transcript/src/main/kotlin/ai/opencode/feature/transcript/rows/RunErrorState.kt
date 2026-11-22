package ai.opencode.feature.transcript.rows

import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptItem

/**
 * The state lives in `:feature:session`, so these take the fields they read; the session's
 * `actionableRunErrorID`, `runErrorActions`, `editableLastMessage` and `hidingUndone` call them.
 * `restoredPrompt`, `settleRevertForNewPrompt` and `dropUndone` touch composer and session state
 * and stay with `:feature:session`.
 */
object RunErrorState {
    /**
     * The run error whose line offers its action: the newest turn's, while nothing runs or waits
     * to send. Prompting again (or anything newer in the turn) leaves the line as a quiet record.
     */
    fun actionableRunErrorID(
        items: List<OpenCodeTranscriptItem>,
        hasSession: Boolean,
        isRunning: Boolean,
        hasFailedSends: Boolean,
        hasQueuedInputs: Boolean,
        revertMessageID: String?,
    ): String? {
        if (!hasSession || isRunning || hasFailedSends || hasQueuedInputs || revertMessageID != null) return null
        val start = items.indexOfLast(::isUserItem).let { if (it >= 0) it + 1 else 0 }
        var found: String? = null
        for (item in items.subList(start, items.size)) {
            when (item) {
                // The idle message repeats its step's error: the step's is the row.
                is OpenCodeTranscriptItem.RunError -> if (found == null && !item.error.isStop) found = item.id
                is OpenCodeTranscriptItem.AssistantMarkdown, is OpenCodeTranscriptItem.Tool,
                is OpenCodeTranscriptItem.Compaction, is OpenCodeTranscriptItem.ModelSwitch,
                -> if (found != null) return null // The turn went on after the error.
                else -> continue
            }
        }
        return found
    }

    /** The actions the actionable run error's line and hold menu offer, its own first. */
    fun actionableRunErrorActions(
        items: List<OpenCodeTranscriptItem>,
        actionableRunErrorID: String?,
        canEditLastMessage: Boolean,
    ): List<OpenCodeRunError.Action> {
        val id = actionableRunErrorID ?: return emptyList()
        val error = (items.lastOrNull { it.id == id } as? OpenCodeTranscriptItem.RunError)?.error ?: return emptyList()
        return runErrorActions(error, canEditLastMessage)
    }

    /** The actions a run error's line and its hold menu offer now, its own first. */
    fun runErrorActions(error: OpenCodeRunError, canEditLastMessage: Boolean): List<OpenCodeRunError.Action> =
        error.actions.filter { it != OpenCodeRunError.Action.Edit || canEditLastMessage }

    /**
     * The last message the reader sent, when Edit can put it back in the composer whole: its words
     * only (an image or file couldn't come back).
     */
    fun editableLastMessage(
        items: List<OpenCodeTranscriptItem>,
        optimisticPromptIDs: Set<String>,
        isVoiceHandoff: (String) -> Boolean = { false },
    ): Pair<String, String>? {
        val index = items.indexOfLast(::isUserItem)
        if (index < 0) return null
        // An agent's message (a call's delegation, another session's) isn't the reader's to edit.
        val item = items[index] as? OpenCodeTranscriptItem.UserText ?: return null
        if (item.source != null || isVoiceHandoff(item.text) || item.id in optimisticPromptIDs) return null
        val attached = items.any {
            (it is OpenCodeTranscriptItem.UserImages && it.id == "${item.id}-images") ||
                (it is OpenCodeTranscriptItem.UserFiles && it.id == "${item.id}-files")
        }
        return if (attached) null else item.id to item.text
    }

    /** What the transcript shows: everything before a staged undo, then one quiet row offering Redo. */
    fun hidingUndone(items: List<OpenCodeTranscriptItem>, revertMessageID: String?): List<OpenCodeTranscriptItem> {
        val boundary = revertMessageID ?: return items
        val index = items.indexOfFirst { startsMessage(it, boundary) }
        if (index < 0) return items
        val hidden = items.subList(index, items.size).count { it is OpenCodeTranscriptItem.UserText }
        return items.subList(0, index) + OpenCodeTranscriptItem.Undone("undone-$boundary", maxOf(1, hidden))
    }

    /** Whether [item] is the first row of the prompt [messageID]. */
    fun startsMessage(item: OpenCodeTranscriptItem, messageID: String): Boolean = when (item) {
        is OpenCodeTranscriptItem.UserText -> item.id == messageID
        is OpenCodeTranscriptItem.UserImages -> item.id == "$messageID-images"
        is OpenCodeTranscriptItem.UserFiles -> item.id == "$messageID-files"
        else -> false
    }

    fun isUserItem(item: OpenCodeTranscriptItem): Boolean =
        item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages || item is OpenCodeTranscriptItem.UserFiles

    /** The MIME type of image bytes, by signature; JPEG when unknown. */
    fun imageMIME(data: ByteArray): String {
        fun startsWith(vararg bytes: Int) = data.size >= bytes.size && bytes.indices.all { data[it] == bytes[it].toByte() }
        if (startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png"
        if (startsWith(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (startsWith(0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (data.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() && data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        return "image/jpeg"
    }
}
