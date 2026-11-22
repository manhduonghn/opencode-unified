package ai.opencode.feature.transcript.user

import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeQuote
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.Immutable

/**
 * One user message, whether still pending (steering or queued, from the inbox) or delivered (a
 * transcript user group). Both render through [TranscriptUserMessageView] so a pending row promotes
 * in place: the same view brightens and its label fades, instead of one row replacing another.
 */
@Immutable
data class TranscriptUserMessage(
    val id: String,
    val text: String,
    val images: List<OpenCodeImageAttachment>,
    val files: List<OpenCodeTranscriptFile>,
    /** Set while the message waits in the inbox. */
    val pending: OpenCodeDelivery?,
    /** Another session's agent or the parent agent sent it. */
    val source: OpenCodePromptSource? = null,
    val quotes: List<OpenCodeQuote> = emptyList(),
) {
    /**
     * The voice agent sent it on the user's behalf: a Voice message, with the prompt one long
     * press away.
     */
    val voiceDelegation: VoiceHandoff.Delegation?

    /** A location shared from the + menu, drawn as a card above the text. */
    val location: SharedLocation?

    /** The text to show: the prompt without its location block. */
    val displayText: String

    init {
        var parsed = VoiceHandoff.parse(text)
        if (parsed != null && files.any { it.name.startsWith("voice-call-") }) {
            parsed = parsed.copy(callLogAttached = true)
        }
        voiceDelegation = parsed
        val shared = SharedLocation.parse(text)
        location = shared?.first
        displayText = shared?.second ?: text
    }

    /**
     * An agent sent it: Voice and the delegator, another session, or a parent spawning a
     * subagent. Only these reveal on arrival (`AgentMessageReveal`); what's typed or dictated
     * here never does.
     */
    val isFromAgent: Boolean get() = voiceDelegation != null || source != null

    /**
     * Whether every attachment on this message can be restored to the composer without losing
     * inline binary files whose bytes aren't retained.
     */
    val canRestoreToComposer: Boolean
        get() = images.none { it.isUnavailable } &&
            files.none { it.isUnavailable || (it.text == null && it.path == null && it.mediaHash == null) }

    companion object {
        /** A user group made of exactly one prompt's text, images, and files. */
        fun from(entry: TranscriptEntry): TranscriptUserMessage? {
            if (entry !is TranscriptEntry.UserGroup) return null
            val id = entry.userMessageID ?: return null
            var text = ""
            var images: List<OpenCodeImageAttachment> = emptyList()
            var files: List<OpenCodeTranscriptFile> = emptyList()
            var source: OpenCodePromptSource? = null
            var quotes: List<OpenCodeQuote> = emptyList()
            for (item in entry.items) {
                if (TranscriptEntry.promptID(item) != id) return null
                when (item) {
                    is OpenCodeTranscriptItem.UserText -> { text = item.text; source = item.source; quotes = item.quotes }
                    is OpenCodeTranscriptItem.UserImages -> images = item.images
                    is OpenCodeTranscriptItem.UserFiles -> files = item.files
                    else -> return null
                }
            }
            return TranscriptUserMessage(id, text, images, files, pending = null, source = source, quotes = quotes)
        }

        /** A plain pending message; compaction and notices keep their own rows. */
        fun from(input: OpenCodeQueuedInput): TranscriptUserMessage? {
            if (input.isCompaction || input.notice != null) return null
            return TranscriptUserMessage(
                id = input.id,
                text = input.text,
                images = input.images,
                files = input.files.mapIndexed { index, file ->
                    OpenCodeTranscriptFile(id = "${input.id}-file-$index", name = file.name, mime = file.mime, path = file.path)
                } + input.input.inlineFiles,
                pending = input.delivery,
                source = input.input.source,
                quotes = input.input.quotes,
            )
        }
    }
}

/** The same glyphs as Send's menu: Steer and Queue. */
val OpenCodeDelivery.symbolName: String
    get() = when (this) {
        OpenCodeDelivery.Steer -> "arrow.turn.up.right"
        OpenCodeDelivery.Queue -> "text.badge.plus"
    }
