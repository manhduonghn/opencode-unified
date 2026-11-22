package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.ComposerAutocompleteChip
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.session.SessionFeature.State

// How a session's composer takes what was shared to the app (or set aside from another composer).
// Home (`:feature:drawer`) calls it when the composer on screen is the share's target.

/**
 * Shared text follows whatever is already typed; images join the tray. Files join as uploads that
 * `queuedUploadsStarted` sets going. While the bar answers a request, what was shared waits with
 * the parked message.
 */
fun State.absorb(shared: SharedComposerDraft): State {
    // Send Later sends only what it brought: a composer already holding words, files or a
    // recording keeps the share as a draft beside them.
    val composerWasEmpty = !hasPromptContent && !hasLiveDictation && parkedComposer == null
    val newFiles = shared.parkedFiles.map { file ->
        if (!file.isUploading) file else file.copy(status = ComposerFileAttachment.Status.Failed(localized(R.string.session_attachment_upload_failed)))
    } + shared.files.map { ComposerFileAttachment(it) }
    val parked = parkedComposer
    var next = if (parked != null) {
        withParkedComposer(
            parked.copy(
                text = SharedComposerDraft.joined(parked.text, shared.text),
                attachments = parked.attachments + shared.attachments,
                files = parked.files + newFiles,
                location = shared.location ?: parked.location,
                chips = parked.chips.appendingUnique(shared.chips),
            ),
        )
    } else {
        val typed = if (hasLiveDictation) {
            rebaseDictation(SharedComposerDraft.joined(composerWithoutDictation.text, shared.text))
        } else {
            withDraft(SharedComposerDraft.joined(draft, shared.text))
        }
        typed.withPendingAttachments(typed.pendingAttachments + shared.attachments)
            .withPendingFiles(typed.pendingFiles + newFiles)
            .withPendingLocation(shared.location ?: typed.pendingLocation)
            .let { it.copy(autocomplete = it.autocomplete.copy(chips = it.autocomplete.chips.appendingUnique(shared.chips))) }
    }
    next = next.clearEditedUndoDraft()
    next = next.withUnstartedUploadIDs(next.unstartedUploadIDs + shared.files.map { it.id })
    if (shared.focusesComposer || (shared.send != null && !composerWasEmpty)) {
        next = next.copy(composerFocusRequest = next.composerFocusRequest + 1)
    }
    val send = shared.send
    if (send != null && composerWasEmpty) next = next.copy(sharedSend = send)
    return next
}

/** Chips not already there, by ID. */
private fun List<ComposerAutocompleteChip>.appendingUnique(other: List<ComposerAutocompleteChip>): List<ComposerAutocompleteChip> {
    if (other.isEmpty()) return this
    val result = toMutableList()
    for (chip in other) if (result.none { it.id == chip.id }) result += chip
    return result
}
