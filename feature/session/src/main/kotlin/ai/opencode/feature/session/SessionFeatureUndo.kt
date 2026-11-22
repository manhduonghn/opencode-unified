package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.server.model.PastedTextSummary
import ai.opencode.feature.media.MediaReferenceLoadResult
import ai.opencode.feature.media.MediaReferenceClient
import ai.opencode.core.sharing.shared
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.core.store.map
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.UndoSnapshot
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerAutocompleteDraftResolution
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.PastedText
import java.util.UUID

// A send's draft back in the composer, and reverts.

/**
 * A send's words lead the draft (or follow it when reverting a turn), its attachments and files
 * join the composer's; a shared location comes back as its card.
 */
internal fun returnToComposer(
    pending: PendingPrompt,
    state: State,
    appendText: Boolean = false,
    preferReturnedLocation: Boolean = false,
): State {
    fun join(incoming: String, existing: String): String =
        if (appendText) SharedComposerDraft.joined(existing, incoming) else SharedComposerDraft.joined(incoming, existing)
    val next = state.applySessionComposer { composer ->
        // Session chips went as `@session:<id>`: their titles come back.
        val text = ComposerAutocompleteDraftResolution.draftText(sent = pending.text, chips = pending.chips)
        val shared = SharedLocation.parse(text)
        var updated = if (shared != null) {
            val (location, remainder) = shared
            composer.copy(
                location = if (preferReturnedLocation) location else composer.location ?: location,
                text = join(remainder, composer.text),
            )
        } else {
            composer.copy(text = join(text, composer.text))
        }
        updated = if (appendText) {
            updated.copy(attachments = updated.attachments + pending.attachments, files = updated.files + pending.files)
        } else {
            updated.copy(attachments = pending.attachments + updated.attachments, files = pending.files + updated.files)
        }
        val newChips = pending.chips.filter { chip -> updated.chips.none { it.id == chip.id } }
        updated.copy(chips = newChips + updated.chips)
    }
    return if (!appendText) next.clearEditedUndoDraft() else next
}

/** Stages a revert on the server before touching the transcript or composer, so a failure never leaves the transcript half-removed. */
internal fun SessionFeature.stageRevert(
    messageID: String,
    sessionID: String,
    restoring: PendingPrompt,
    state: State,
): Reduced<State, Action> {
    val server = state.server
    val client = openCodeClient
    val references = mediaReferences
    return Reduced(
        state.copy(isUndoingLastMessage = true),
        Effect.run<Action> { send ->
            try {
                client.stageRevert(server, sessionID, messageID)
            } catch (error: OpenCodeClientError) {
                send(Action.MessageReverted(sessionID, messageID, restoring, error))
                return@run
            }
            // A `media:<hash>` attachment comes back without its bytes: load them so Edit's
            // composer shows the image (or Pasted Text card) again.
            val hydrated = restoring.copy(attachments = restoring.attachments.map { attachment -> hydrated(attachment, server, references) })
            send(Action.MessageReverted(sessionID, messageID, hydrated, null))
        }.cancellable(SessionFeature.CancelID.Revert, cancelInFlight = true),
    )
}

private suspend fun hydrated(attachment: OpenCodeOutgoingAttachment, server: OpenCodeServer, references: MediaReferenceClient): OpenCodeOutgoingAttachment {
    val hash = attachment.mediaHash
    if (attachment.data.isNotEmpty() || hash == null) return attachment
    val data = (references.load(server, hash) as? MediaReferenceLoadResult.Loaded)?.data ?: return attachment
    val pastedText = attachment.pastedText ?: PastedText.text(attachment.mime, data)
    return OpenCodeOutgoingAttachment(
        id = attachment.id,
        data = data,
        mime = attachment.mime,
        name = attachment.name,
        pastedText = pastedText,
        pasted = if (attachment.pastedText == null && pastedText != null) PastedTextSummary(pastedText) else attachment.pasted,
        mediaHash = attachment.mediaHash,
        byteCount = attachment.byteCount ?: data.size.toLong(),
        videoDuration = attachment.videoDuration,
    )
}

internal fun SessionFeature.reportRevertError(error: OpenCodeClientError, state: State): Reduced<State, Action> {
    haptics.play(Haptic.error)
    val details = error.message.trim()
    return Reduced(appendInfo(state, if (details.isEmpty()) localized(R.string.session_revert_failed) else localized(R.string.session_revert_failed_details, details)))
}

/**
 * Puts a reverted message's text, location, images, pasted text cards, and file references back
 * in the composer (appended after any existing draft) and focuses the field.
 */
internal fun fillComposerDraft(pending: PendingPrompt, state: State, focus: Boolean = true): State {
    var next = state.clearUntouchedUndoDraft()
    val prior = next.sessionComposer
    next = returnToComposer(pending, next, appendText = true, preferReturnedLocation = true)
    next = next.withUndoSnapshot(
        UndoSnapshot(
            restoredDraft = next.parkedComposer?.text ?: next.draft,
            priorDraft = prior.text,
            restoredLocation = next.sessionComposer.location,
            priorLocation = prior.location,
            attachmentIDs = pending.attachments.mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() }.toSet(),
            fileIDs = pending.files.map { it.id }.toSet(),
        ),
    )
    if (focus && next.parkedComposer == null) next = next.copy(composerFocusRequest = next.composerFocusRequest + 1)
    return next
}
