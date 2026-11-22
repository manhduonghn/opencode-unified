package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.PageShareRequest

/**
 * One send on its way. Lives here because the outbox, persistence and undelivered prompts carry it.
 */
data class PendingPrompt(
    val messageID: String,
    val text: String,
    val attachments: List<OpenCodeOutgoingAttachment>,
    val files: List<ComposerFileAttachment> = emptyList(),
    val wasRunning: Boolean = false,
    /**
     * Voice owns its run separately; remembering its row must not keep a composer's
     * `isAwaitingReply` alive after the server reports idle.
     */
    val ownsRun: Boolean = true,
    val delivery: OpenCodeDelivery = OpenCodeDelivery.Steer,
    val requestEpoch: Int,
    /**
     * Applied (`POST …/model`) just before the prompt, as the TUI does: a pick made since the
     * last send, or a draft's model.
     */
    val model: OpenCodeModelRef? = null,
    /** Agent Selection: applied (`POST …/agent`) just before the prompt, like [model], when the session doesn't have it yet. */
    val agent: String? = null,
    /** Sent from the in-app browser's open page: read at delivery and folded into the body (`PromptPageContext`). */
    val page: PageShareRequest? = null,
    /** Structured autocomplete mentions folded into `PromptBody`. */
    val mentions: OpenCodeAutocompleteMentions? = null,
    /** Chosen autocomplete chips carried with the prompt for edit/restore. */
    val chips: List<ComposerAutocompleteChip> = emptyList(),
    val quotes: List<ComposerQuote> = emptyList(),
) {
    /** A queued send posted mid-run: the run it waits behind keeps its model. */
    val waitsBehindRun: Boolean get() = wasRunning && delivery == OpenCodeDelivery.Queue
}
