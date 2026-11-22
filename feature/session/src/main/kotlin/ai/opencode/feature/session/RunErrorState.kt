package ai.opencode.feature.session

import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.media.MediaReferenceClient
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.RunErrorState as TranscriptRunErrorState
import ai.opencode.feature.voice.VoiceHandoff
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText

// They extend `SessionFeature.State`, so they land in this module; the pure parts are
// `:feature:transcript`'s `RunErrorState`.

/** What the transcript shows: everything before a staged undo, then one quiet row offering Redo. */
fun State.hidingUndone(items: List<OpenCodeTranscriptItem>): List<OpenCodeTranscriptItem> =
    TranscriptRunErrorState.hidingUndone(items, session?.revertMessageID)

/**
 * The run error whose line offers its action: the newest turn's, while nothing runs or waits to
 * send. Prompting again (or anything newer in the turn) leaves the line as a quiet record.
 */
val State.actionableRunErrorID: String?
    get() {
        if (session == null || isRunning || failedSends.isNotEmpty() || queuedInputs.isNotEmpty() || session?.revertMessageID != null) return null
        val start = items.indexOfLast(::isUserItem).let { if (it >= 0) it + 1 else 0 }
        var found: String? = null
        for (item in items.subList(start, items.size)) {
            when (item) {
                // The idle message repeats its step's error: the step's is the row.
                is OpenCodeTranscriptItem.RunError -> if (found == null && !item.error.isStop) found = item.id
                // The turn went on after the error.
                is OpenCodeTranscriptItem.AssistantMarkdown, is OpenCodeTranscriptItem.Tool,
                is OpenCodeTranscriptItem.Compaction, is OpenCodeTranscriptItem.ModelSwitch,
                -> if (found != null) return null
                else -> Unit
            }
        }
        return found
    }

/** The actions the actionable run error's line and hold menu offer, its own first. */
val State.actionableRunErrorActions: List<OpenCodeRunError.Action>
    get() {
        val id = actionableRunErrorID ?: return emptyList()
        val item = items.lastOrNull { it.id == id } as? OpenCodeTranscriptItem.RunError ?: return emptyList()
        return runErrorActions(item.error)
    }

/** The actions a run error's line and its hold menu offer now, its own first. */
fun State.runErrorActions(error: OpenCodeRunError): List<OpenCodeRunError.Action> =
    error.actions.filter { it != OpenCodeRunError.Action.Edit || editableLastMessage != null }

data class EditableMessage(val id: String, val text: String)

/**
 * The last message the reader sent, when Edit can put it back in the composer whole: its words
 * only (an image or file couldn't come back).
 */
val State.editableLastMessage: EditableMessage?
    get() {
        val index = items.indexOfLast(::isUserItem)
        if (index < 0) return null
        // An agent's message (a call's delegation, another session's) isn't the reader's to edit.
        val item = items[index] as? OpenCodeTranscriptItem.UserText ?: return null
        if (item.source != null || VoiceHandoff.parse(item.text) != null || item.id in optimisticPromptIDs) return null
        val attached = items.any {
            (it is OpenCodeTranscriptItem.UserImages && it.id == "${item.id}-images") ||
                (it is OpenCodeTranscriptItem.UserFiles && it.id == "${item.id}-files")
        }
        return if (attached) null else EditableMessage(item.id, item.text)
    }

/**
 * Rebuilds one of the reader's delivered prompts ([messageID]) with its text, images, pasted text
 * cards, and uploaded file references so Revert to Here can put the whole message back in the
 * composer. Null when the message includes an inline binary file whose bytes aren't retained.
 */
fun State.restoredPrompt(messageID: String, uuid: UuidClient = dependency(UuidClient)): PendingPrompt? {
    if (messageID in optimisticPromptIDs) return null
    val boundary = session?.revertMessageID
    val boundaryIndex = boundary?.let { id -> items.indexOfFirst { startsMessage(it, id) } } ?: -1
    val visible = if (boundaryIndex >= 0) items.subList(0, boundaryIndex) else items
    var matched = false
    var text = ""
    var images: List<OpenCodeImageAttachment> = emptyList()
    var files: List<OpenCodeTranscriptFile> = emptyList()
    var source: OpenCodePromptSource? = null
    for (item in visible) {
        if (TranscriptEntry.promptID(item) != messageID) continue
        matched = true
        when (item) {
            is OpenCodeTranscriptItem.UserText -> {
                text = item.text
                source = item.source
            }
            is OpenCodeTranscriptItem.UserImages -> images = item.images
            is OpenCodeTranscriptItem.UserFiles -> files = item.files
            else -> return null
        }
    }
    if (!matched || source != null || VoiceHandoff.parse(text) != null) return null
    // A `media:<hash>` image or file restores from the memory cache, or empty with its hash for
    // `stageRevert` to load; one the server no longer has can't come back.
    val mediaReferences = dependency(MediaReferenceClient)
    val attachments = ArrayList<OpenCodeOutgoingAttachment>()
    for (image in images) {
        if (image.isUnavailable) return null
        val data = if (image.data.isEmpty()) image.mediaHash?.let(mediaReferences.cachedData) ?: ByteArray(0) else image.data
        if (data.isEmpty() && image.mediaHash == null) return null
        attachments += OpenCodeOutgoingAttachment(
            id = uuid.next().toString(), data = data, mime = image.mime ?: imageMIME(data),
            name = image.name, mediaHash = image.mediaHash, byteCount = image.byteCount,
        )
    }
    val composerFiles = ArrayList<ComposerFileAttachment>()
    for (file in files) {
        if (file.isUnavailable) return null
        val pasted = file.text
        val path = file.path
        val mediaHash = file.mediaHash
        when {
            pasted != null -> attachments += PastedText.attachment(pasted, id = uuid.next().toString()).withMediaHash(mediaHash)
            path != null -> composerFiles += ComposerFileAttachment(
                id = uuid.next(), name = file.name, mime = file.mime, size = 0, localPath = path,
                status = ComposerFileAttachment.Status.Uploaded(path),
            )
            mediaHash != null -> {
                val data = mediaReferences.cachedData(mediaHash) ?: ByteArray(0)
                val text = if (data.isNotEmpty()) PastedText.text(file.mime, data) else null
                attachments += if (text != null) {
                    PastedText.attachment(text, id = uuid.next().toString()).withMediaHash(mediaHash)
                } else {
                    OpenCodeOutgoingAttachment(
                        id = uuid.next().toString(), data = data, mime = file.mime, name = file.name,
                        mediaHash = mediaHash, byteCount = file.byteCount,
                    )
                }
            }
            else -> return null
        }
    }
    return PendingPrompt(messageID = messageID, text = text, attachments = attachments, files = composerFiles, requestEpoch = 0)
}

private fun OpenCodeOutgoingAttachment.withMediaHash(hash: String?): OpenCodeOutgoingAttachment =
    if (hash == null) this else OpenCodeOutgoingAttachment(
        id = id, data = data, mime = mime, name = name, pastedText = pastedText, pasted = pasted,
        mediaHash = hash, byteCount = byteCount, videoDuration = videoDuration,
    )

/** A new prompt, plain or command, ends any staged revert still in flight. Returns the state and the revert's cancellation. */
fun State.settleRevertForNewPrompt(): Pair<State, Effect<SessionFeature.Action>> =
    copy(isUndoingLastMessage = false).dropUndone() to Effect.cancel(SessionFeature.CancelID.Revert)

/** A new prompt makes an undo final: the server drops what it hid. */
fun State.dropUndone(): State {
    var next = withUndoSnapshot(null)
    val boundary = next.session?.revertMessageID ?: return next
    val index = next.items.indexOfFirst { startsMessage(it, boundary) }
    if (index >= 0) next = next.copy(items = next.items.subList(0, index)).syncShellToolStatuses()
    return next.setRevertMessageID(null)
}

/**
 * The reader sent a message: everything above it is read, so the divider goes and nothing waiting
 * to be settled counts as new.
 */
fun State.noteOwnSend(): State = copy(
    newMessages = null,
    newMessagesFitThrough = null,
    newMessagesBaseline = null,
    newMessagesBaselineTimestamp = null,
    newMessagesUnread = false,
    newMessagesEarlierBaseline = null,
    newMessagesWatched = emptySet(),
)

private fun startsMessage(item: OpenCodeTranscriptItem, messageID: String): Boolean = when (item) {
    is OpenCodeTranscriptItem.UserText -> item.id == messageID
    is OpenCodeTranscriptItem.UserImages -> item.id == "$messageID-images"
    is OpenCodeTranscriptItem.UserFiles -> item.id == "$messageID-files"
    else -> false
}

private fun isUserItem(item: OpenCodeTranscriptItem): Boolean =
    item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages || item is OpenCodeTranscriptItem.UserFiles

private fun imageMIME(data: ByteArray): String {
    fun startsWith(vararg bytes: Int) = data.size >= bytes.size && bytes.indices.all { (data[it].toInt() and 0xff) == bytes[it] }
    if (startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png"
    if (startsWith(0xFF, 0xD8, 0xFF)) return "image/jpeg"
    if (startsWith(0x47, 0x49, 0x46, 0x38)) return "image/gif"
    if (data.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
        (data[8].toInt() == 0x57 && data[9].toInt() == 0x45 && data[10].toInt() == 0x42 && data[11].toInt() == 0x50)
    ) {
        return "image/webp"
    }
    return "image/jpeg"
}
