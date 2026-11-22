package ai.opencode.feature.transcript

import ai.opencode.feature.transcript.user.TranscriptSendFailure
import ai.opencode.server.model.PastedTextSummary
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.Immutable

/**
 * What a transcript row or the list asks the session to do. iOS rows call
 * `send(SessionFeature.Action)` directly (one module); here `:feature:session` sits above, so
 * the list sends these and `SessionFeature` maps each one to the iOS action of the same name.
 */
sealed interface TranscriptAction {
    data object FirstContentFrameRendered : TranscriptAction
    data class TranscriptBrowsingChanged(val isBrowsing: Boolean) : TranscriptAction
    data class NewMessagesPassed(val generation: Int) : TranscriptAction
    data class NewMessagesFit(val generation: Int) : TranscriptAction
    data object ShowEarlierTapped : TranscriptAction
    data class CopyTextTapped(val text: String) : TranscriptAction
    data class QuoteTextTapped(val text: String) : TranscriptAction
    data class LinkMenu(val action: LinkMenuAction, val url: String) : TranscriptAction
    data class LinkTapped(val url: String) : TranscriptAction
    data class SessionLinksAppeared(val ids: List<String>) : TranscriptAction
    data class SubagentTapped(val sessionID: String) : TranscriptAction
    data object MoveToBackgroundTapped : TranscriptAction
    data class FailedSendMoveTapped(val messageID: String) : TranscriptAction
    data class FailedSendRetryTapped(val messageID: String) : TranscriptAction
    data class FailedSendEditTapped(val messageID: String) : TranscriptAction
    data class FailedSendDeleteTapped(val messageID: String) : TranscriptAction
    data class RunErrorActionTapped(val action: OpenCodeRunError.Action) : TranscriptAction
    data class RevertToHereTapped(val messageID: String) : TranscriptAction
    data object RedoTapped : TranscriptAction
    data class PendingInputSendNowTapped(val inputID: String) : TranscriptAction
    data class PendingInputMoveToQueueTapped(val inputID: String) : TranscriptAction
    data class PendingInputCancelTapped(val inputID: String) : TranscriptAction
    /** A file link or card was tapped (iOS `onOpenFile`): the session opens the media viewer. */
    data class OpenFile(val file: AgentFileReference) : TranscriptAction
    /** An image was tapped (iOS `onPreviewImage`): the session opens the zoom viewer. */
    data class PreviewImage(val preview: TranscriptImagePreview) : TranscriptAction
    /**
     * A sent message's Pasted Text card was tapped (iOS `PastedTextCard.open`): the session
     * presents the Pasted Text viewer, zooming out of the card ([sourceID]).
     */
    data class OpenPastedText(val text: String, val summary: PastedTextSummary, val sourceID: String) : TranscriptAction
}

/**
 * Value projection of a subagent session for a transcript tool row (iOS `TranscriptSubagent`),
 * so rows render from values without observing the session store.
 */
@Immutable
data class TranscriptSubagent(
    val title: String?,
    val agent: String?,
    val isKnown: Boolean,
    val isRunning: Boolean,
    val needsInput: Boolean,
)

/** What a linked session ID resolved to (iOS `SessionLinkTarget`). */
@Immutable
sealed interface SessionLinkTarget {
    /** Asked for, not answered yet: shows the short ID. */
    data object Resolving : SessionLinkTarget
    data class Session(val title: String, val parentID: String?) : SessionLinkTarget
    /** No such session on this server: stays the short ID. */
    data object Missing : SessionLinkTarget
}

/** The New divider, above the first row that arrived while the reader was away (iOS `NewMessagesPlacement`). */
@Immutable
data class NewMessagesPlacement(val entryID: String, val generation: Int) {
    companion object {
        /** The reader has read past the divider once its bottom is above the top of what they can see. */
        fun isPassed(dividerMaxY: Float, offsetY: Float, topInset: Float): Boolean = dividerMaxY <= offsetY + topInset
    }
}

/**
 * Everything the list draws (iOS `TranscriptListViewController.Configuration`). The session
 * builds it from its state; the list diffs it. Upward types iOS reads directly (the Working
 * line's `WorkingActivity`, the Since You Left row) come in as [TranscriptSlots] instead.
 */
@Immutable
data class TranscriptConfiguration(
    val server: OpenCodeServer? = null,
    val sessionID: String? = null,
    val directory: String? = null,
    val historyGeneration: Int = 0,
    val entries: List<TranscriptEntry> = emptyList(),
    val hasEarlierItems: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    val earlierHistoryError: String? = null,
    val isLoading: Boolean = false,
    /**
     * Android: the server's transcript is in and every earlier page is listed. Unlike
     * `!hasEarlierItems`, never true for a restored cache still waiting for its first read.
     */
    val listsAllHistory: Boolean = false,
    val showsEmptyState: Boolean = true,
    val isRunning: Boolean = false,
    /**
     * iOS `configuration.working?.handsOffToCopy`: the Working line is ending in the reply's copy
     * button, so the reply end that replaces it is already drawn and doesn't fade in.
     */
    val workingHandsOffToCopy: Boolean = false,
    val hasPendingRequests: Boolean = false,
    val showsMoveToBackground: Boolean = false,
    val actionableRunErrorID: String? = null,
    val actionableRunErrorActions: List<OpenCodeRunError.Action> = emptyList(),
    val subagents: Map<String, TranscriptSubagent> = emptyMap(),
    /** What the session IDs linked in replies name (`SessionLink`). */
    val sessionLinks: Map<String, SessionLinkTarget> = emptyMap(),
    val isDraft: Boolean = false,
    val queuedInputs: List<OpenCodeQueuedInput> = emptyList(),
    /** Why each failed send failed, by message ID; its bubble offers Retry. */
    val failedSends: Map<String, TranscriptSendFailure> = emptyMap(),
    /** A quiet line under a send that's taking a while, by message ID. */
    val deliveryNotes: Map<String, String> = emptyMap(),
    /** Messages waiting in the queued pill: a steering row that leaves for one flies into the pill. */
    val queuedPillTexts: Map<String, String> = emptyMap(),
    val sentItemID: String? = null,
    val sendGeneration: Int = 0,
    /** The New divider, above the first row that arrived while the reader was away. */
    val newMessages: NewMessagesPlacement? = null,
    /** `PerfTimeline`'s revision of the REST snapshot this was made from; null unless measuring. */
    val snapshotRevision: Int? = null,
) {
    /**
     * iOS `Configuration.differsOnlyInTailAssistantMarkdown(from:)`: while a run is active, only
     * the tail `AssistantMarkdown` entry's text changed (same entry ID, same surrounding rows and
     * list state).
     */
    fun differsOnlyInTailAssistantMarkdown(other: TranscriptConfiguration): Boolean {
        if (!isRunning || !other.isRunning || entries.isEmpty() || entries.size != other.entries.size) return false
        if (sessionID != other.sessionID ||
            historyGeneration != other.historyGeneration ||
            hasEarlierItems != other.hasEarlierItems ||
            isLoadingEarlier != other.isLoadingEarlier ||
            earlierHistoryError != other.earlierHistoryError ||
            isLoading != other.isLoading ||
            showsEmptyState != other.showsEmptyState ||
            workingHandsOffToCopy != other.workingHandsOffToCopy ||
            hasPendingRequests != other.hasPendingRequests ||
            showsMoveToBackground != other.showsMoveToBackground ||
            actionableRunErrorID != other.actionableRunErrorID ||
            actionableRunErrorActions != other.actionableRunErrorActions ||
            subagents != other.subagents ||
            sessionLinks != other.sessionLinks ||
            isDraft != other.isDraft ||
            queuedInputs != other.queuedInputs ||
            failedSends != other.failedSends ||
            deliveryNotes != other.deliveryNotes ||
            queuedPillTexts != other.queuedPillTexts ||
            sentItemID != other.sentItemID ||
            sendGeneration != other.sendGeneration ||
            newMessages != other.newMessages ||
            server != other.server ||
            directory != other.directory
        ) {
            return false
        }
        val lastIndex = entries.lastIndex
        val newLast = (entries[lastIndex] as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.AssistantMarkdown ?: return false
        val oldLast = (other.entries[lastIndex] as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.AssistantMarkdown ?: return false
        if (newLast.id != oldLast.id || newLast == oldLast) return false
        return entries.subList(0, lastIndex) == other.entries.subList(0, lastIndex)
    }
}
