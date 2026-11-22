package ai.opencode.feature.composer

import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.util.UUID

/** A composer's draft. Lives here because [ComposerPersistence] stores it. */
data class DraftState(
    val text: String = "",
    val attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    val files: List<ComposerFileAttachment> = emptyList(),
    /** A shared location from the + menu, sent at the top of the prompt. */
    val location: SharedLocation? = null,
    val chips: List<ComposerAutocompleteChip> = emptyList(),
    val undoSnapshot: UndoSnapshot? = null,
    val quotes: List<ComposerQuote> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isEmpty() && attachments.isEmpty() && files.isEmpty() && location == null && undoSnapshot == null && quotes.isEmpty()

    fun appending(other: DraftState): DraftState {
        val joinedText = SharedComposerDraft.joined(text, other.text)
        val mergedChips = chips + other.chips.filter { chip -> chips.none { it.id == chip.id } }
        val restored = undoSnapshot?.restoredDraft
        return copy(
            text = joinedText,
            attachments = attachments + other.attachments,
            files = files + other.files,
            location = location ?: other.location,
            chips = mergedChips,
            quotes = quotes + other.quotes,
            undoSnapshot = if (restored != null && joinedText != restored) null else undoSnapshot,
        )
    }
}

/**
 * The composer as an undo/revert left it, plus the pre-undo snapshot Undo/Redo restores while the
 * reverted draft is untouched.
 */
@Serializable
data class UndoSnapshot(
    val restoredDraft: String,
    // These keys are required: decoding ignores property defaults.
    @Required val priorDraft: String = "",
    @Serializable(with = SharedLocationSerializer::class) val restoredLocation: SharedLocation? = null,
    @Serializable(with = SharedLocationSerializer::class) val priorLocation: SharedLocation? = null,
    @Required val attachmentIDs: Set<@Serializable(with = UUIDSerializer::class) UUID> = emptySet(),
    @Required val fileIDs: Set<@Serializable(with = UUIDSerializer::class) UUID> = emptySet(),
)

/**
 * What a share adds to one composer. Here because it carries composer types `:feature:system` can't
 * see.
 */
data class SharedComposerDraft(
    val text: String = "",
    val attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    /** Staged non-image files; they upload once the composer absorbs them. */
    val files: List<ComposerStagedFile> = emptyList(),
    /** Composer files parked when leaving a session in-process. */
    val parkedFiles: List<ComposerFileAttachment> = emptyList(),
    val location: SharedLocation? = null,
    val chips: List<ComposerAutocompleteChip> = emptyList(),
    val quotes: List<ComposerQuote> = emptyList(),
    /** Where a New Session draft should start. */
    val projectID: String? = null,
    /** Continue in OpenCode: the composer takes the keyboard when it opens. */
    val focusesComposer: Boolean = false,
    /** Send Later: sent as soon as the composer has it (unless something was already typed there), under the share sheet's message ID when it tried. */
    val send: Send? = null,
) {
    data class Send(
        val messageID: String? = null,
        /** A New Session the share sheet may already have created. */
        val sessionID: String? = null,
    )

    constructor(composer: DraftState, projectID: String? = null) : this(
        text = composer.text,
        attachments = composer.attachments,
        files = emptyList(),
        parkedFiles = composer.files,
        location = composer.location,
        chips = composer.chips,
        quotes = composer.quotes,
        projectID = projectID,
    )

    constructor(restored: RestoredComposerDraft) : this(restored.draft, restored.project?.id)

    val isEmpty: Boolean
        get() = text.isEmpty() && attachments.isEmpty() && files.isEmpty() && parkedFiles.isEmpty() && location == null && quotes.isEmpty()

    val draftState: DraftState
        get() = DraftState(text = text, attachments = attachments, files = parkedFiles, location = location, chips = chips, quotes = quotes)

    /**
     * Shares for one composer join into one message. Send Later shares still send (without
     * the IDs either tried: they're one message now), but never with a draft that waited
     * beside them, which stays a draft with the keyboard up.
     */
    fun appending(other: SharedComposerDraft): SharedComposerDraft {
        val wasEmpty = isEmpty
        val sending = send != null || other.send != null
        val allSend = (wasEmpty || send != null) && (other.isEmpty || other.send != null)
        val nextSend = if (!sending || !allSend) null else if (wasEmpty) other.send else Send()
        return copy(
            text = joined(text, other.text),
            attachments = attachments + other.attachments,
            files = files + other.files,
            parkedFiles = parkedFiles + other.parkedFiles,
            location = other.location ?: location,
            chips = chips + other.chips.filter { chip -> chips.none { it.id == chip.id } },
            quotes = quotes + other.quotes,
            projectID = other.projectID ?: projectID,
            focusesComposer = focusesComposer || other.focusesComposer || (sending && nextSend == null),
            send = nextSend,
        )
    }

    companion object {
        fun joined(first: String, second: String): String = when {
            first.isEmpty() -> second
            second.isEmpty() -> first
            else -> first + "\n\n" + second
        }
    }
}

/** Which composer a share lands in. */
sealed interface SharedDraftTarget {
    data class Session(val id: String) : SharedDraftTarget
    data object NewSession : SharedDraftTarget

    val sessionID: String?
        get() = (this as? Session)?.id

    companion object {
        operator fun invoke(sessionID: String?): SharedDraftTarget = sessionID?.let(::Session) ?: NewSession
    }
}

data class RestoredComposerDraft(
    val project: OpenCodeProject?,
    val draft: DraftState,
    val updatedAt: Instant,
)

data class RestoredOutboxEntry(
    val serverID: String,
    val sessionID: String?,
    val draftID: String?,
    val directory: String?,
    val newSessionID: String,
    val createdSession: OpenCodeSession?,
    val pending: PendingPrompt,
    val isHeld: Boolean,
    val backgroundKey: String?,
    val updatedAt: Instant,
)

/** The same keys, `capturedAt` in `JSONEncoder`'s date shape. */
internal object SharedLocationSerializer : KSerializer<SharedLocation> {
    @Serializable
    private class Surrogate(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double? = null,
        val place: String? = null,
        val address: String? = null,
        @Serializable(with = ComposerDateSerializer::class) val capturedAt: Instant,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SharedLocation) = encoder.encodeSerializableValue(
        Surrogate.serializer(),
        Surrogate(value.latitude, value.longitude, value.accuracy, value.place, value.address, value.capturedAt),
    )

    override fun deserialize(decoder: Decoder): SharedLocation {
        val s = decoder.decodeSerializableValue(Surrogate.serializer())
        return SharedLocation(s.latitude, s.longitude, s.accuracy, s.place, s.address, s.capturedAt)
    }
}
