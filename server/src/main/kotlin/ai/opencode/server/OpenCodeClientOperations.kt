package ai.opencode.server

import ai.opencode.core.strings.localized
import ai.opencode.server.live.TranscriptContextCapture
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PromptContext
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.retry.RetryBudget
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A voice call's steer moves the session's blocking shells to the background
 * first. Silent and tried once: the steer goes either way.
 */
suspend fun OpenCodeClient.moveToBackgroundBeforeSteer(server: OpenCodeServer, sessionID: String) {
    withRetryPolicy(RetryPolicy.once) {
        try { moveToBackground(server, sessionID) } catch (_: OpenCodeClientError) {}
    }
}

/** Whether the session holds this prompt, delivered or still in its inbox. Throws when it couldn't be read. */
suspend fun OpenCodeClient.holdsPrompt(server: OpenCodeServer, sessionID: String, messageID: String): Boolean {
    try {
        message(server, sessionID, messageID)
        return true
    } catch (_: OpenCodeClientError) {
    }
    return pendingInputs(server, sessionID).any { it.id == messageID }
}

/**
 * Sends a prompt under one ID for every attempt and, when its sending ends in doubt, reads it
 * back by that ID (once, briefly) before calling it a failure. Unseen, the error names the
 * prompt (`unconfirmedPromptID`) and says it may have arrived. Returns the prompt's ID.
 */
suspend fun OpenCodeClient.sendPromptConfirming(
    server: OpenCodeServer,
    sessionID: String,
    text: String,
    delivery: OpenCodeDelivery,
    files: List<OpenCodeFileReference> = emptyList(),
    attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    readBack: Duration = 1500.milliseconds,
    messageID: String? = null,
): String {
    val id = messageID ?: ServerIDClient.currentMessageID()
    val error = try {
        return PromptContext.messageID.withValue(id) {
            if (files.isEmpty() && attachments.isEmpty()) {
                sendDeliveredPrompt(server, sessionID, text, delivery)
            } else {
                sendPrompt(server, sessionID, text, attachments, files, id, delivery)
                id
            }
        }
    } catch (failure: OpenCodeClientError) {
        failure
    }
    if (!error.mayHaveReachedServer) throw error
    val held = RetryBudget.bounded(readBack) {
        withRetryPolicy(RetryPolicy.once) {
            try { holdsPrompt(server, sessionID, id) } catch (_: OpenCodeClientError) { null }
        }
    }
    if (held == true) return id
    // Not seen yet isn't proof it won't land: the server may still be admitting it.
    throw error.copy(unconfirmedPromptID = id, message = localized(R.string.server_error_prompt_unconfirmed))
}

/**
 * A server's Home snapshot with any unattributed active session parent chains resolved,
 * for other-server drawer lists and voice listings.
 */
suspend fun OpenCodeClient.completeHomeSnapshot(server: OpenCodeServer): OpenCodeHomeSnapshot {
    val snapshot = homeSnapshot(server)
    val roots = snapshot.sessions.map { it.id }.toSet()
    val knownParents = snapshot.activeSessionParents ?: emptyMap()
    val hasUnattributedActive = snapshot.activeSessionIDs.any { SessionActivity.root(it, knownParents) !in roots }
    if (!hasUnattributedActive) return snapshot
    val resolved = activeSessionParents(server, snapshot.activeSessionIDs, snapshot.sessions.map { it.id })
    if (resolved.isEmpty()) return snapshot
    return snapshot.copy(activeSessionParents = knownParents + resolved)
}

/** A transcript window with the context usage and next cursor of the same `/message` response. */
data class OpenCodeTranscriptWindow(
    val items: List<OpenCodeTranscriptItem>,
    val context: OpenCodeContextReading,
    /** Present when the window came from a decoded wire payload: `next` is the older page's cursor. */
    val pageCursor: PageCursor?,
) {
    /**
     * [reachesStart]: the window holds the session's first message. V2 sends a `next` cursor even
     * when nothing is older, so a page shorter than its limit says so too (it names an unnamed
     * session after its first prompt; paging still follows [next]).
     */
    data class PageCursor(val next: String?, val reachesStart: Boolean = next == null, val isComplete: Boolean = next == null)
}

/**
 * Fetches a transcript window and derives context usage from the first 8 messages of the same
 * response, so page refreshes never issue a second message fetch. When a test stubs
 * `transcript` without decoding a wire payload, falls back to `contextUsage`.
 */
suspend fun OpenCodeClient.transcriptWithContextUsageAndCursor(server: OpenCodeServer, sessionID: String, limit: Int): OpenCodeTranscriptWindow {
    val capture = TranscriptContextCapture()
    val items = TranscriptContextCapture.current.withValue(capture) { transcript(server, sessionID, limit) }
    capture.take()?.let { captured ->
        val next = captured.cursor?.next
        // Missing cursor metadata is sufficient for the existing paging/title heuristics, not for erasing rows.
        val pageCursor = OpenCodeTranscriptWindow.PageCursor(next, next == null || captured.messageCount < limit, captured.cursor != null && next == null)
        return OpenCodeTranscriptWindow(items, captured.reading, pageCursor)
    }
    val fallback = try { contextUsage(server, sessionID) } catch (_: OpenCodeClientError) { OpenCodeContextReading.Inconclusive }
    return OpenCodeTranscriptWindow(items, fallback, null)
}

/** [OpenCodeClient.replyForm] for a `"global"` (MCP elicitation) form routed by `location[directory]`. */
suspend fun OpenCodeClient.replyForm(
    server: OpenCodeServer,
    sessionID: String,
    formID: String,
    answer: Map<String, OpenCodeFormValue>,
    directory: String?,
) = PromptContext.formLocationDirectory.withValue(directory) { replyForm(server, sessionID, formID, answer) }

/** [OpenCodeClient.cancelForm] for a `"global"` form routed by `location[directory]`. */
suspend fun OpenCodeClient.cancelForm(server: OpenCodeServer, sessionID: String, formID: String, message: String?, directory: String?) =
    PromptContext.formLocationDirectory.withValue(directory) { cancelForm(server, sessionID, formID, message) }
