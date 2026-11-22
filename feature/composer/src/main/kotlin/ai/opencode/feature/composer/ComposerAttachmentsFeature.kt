package ai.opencode.feature.composer

import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeUploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The composer's pending images, files and location. This reducer owns only Location and
 * inline-attachment removal; the session handles [Action.AttachmentAdded], [Action.FilesStaged],
 * uploads, retry and removal with the static effects here.
 */
class ComposerAttachmentsFeature : Reducer<ComposerAttachmentsFeature.State, ComposerAttachmentsFeature.Action> {

    data class State(
        val pendingAttachments: List<OpenCodeOutgoingAttachment> = emptyList(),
        /** Non-image files staged for upload or already uploaded for the next send. */
        val pendingFiles: List<ComposerFileAttachment> = emptyList(),
        /** A location from the + menu, waiting to go with the next message. */
        val pendingLocation: SharedLocation? = null,
        val pendingQuotes: List<ComposerQuote> = emptyList(),
        /** The + menu's Location is finding the phone. */
        val isLocating: Boolean = false,
        /** Why the last Location couldn't be shared, until dismissed. */
        val locationError: String? = null,
        /** Send was tapped while files were still uploading; the message goes with this delivery once they land. */
        val sendAfterUploads: OpenCodeDelivery? = null,
        /** Shared files absorbed into this composer whose uploads haven't started. */
        val unstartedUploadIDs: List<UUID> = emptyList(),
        /**
         * The composer as an undo/revert left it, plus the pre-undo snapshot that Undo/Redo
         * restores when the reverted draft is still untouched.
         */
        val undoSnapshot: UndoSnapshot? = null,
    ) {
        val isUploadingFiles: Boolean get() = pendingFiles.any { it.isUploading }
        val isWaitingForUploads: Boolean get() = sendAfterUploads != null
        val hasContent: Boolean
            get() = pendingAttachments.isNotEmpty() || pendingFiles.isNotEmpty() || pendingLocation != null || pendingQuotes.isNotEmpty()
    }

    sealed interface Outcome<out T, out E> {
        data class Success<T>(val value: T) : Outcome<T, Nothing>
        data class Failure<E>(val error: E) : Outcome<Nothing, E>
    }

    sealed interface Action {
        data class AttachmentAdded(val attachment: OpenCodeOutgoingAttachment) : Action
        /**
         * One the person already confirmed (Add answered the press), landing without a haptic
         * of its own: one haptic per Add, not one per photo.
         */
        data class AttachmentLanded(val attachment: OpenCodeOutgoingAttachment) : Action
        data class AttachmentRemoved(val index: Int) : Action
        data class FilesStaged(val files: List<ComposerStagedFile>) : Action
        data class FileUploadProgress(val id: UUID, val sent: Long) : Action
        data class FileUploadFinished(val id: UUID, val result: Outcome<String, OpenCodeClientError>) : Action
        data class FileRetryTapped(val id: UUID) : Action
        data class FileRemoved(val id: UUID) : Action
        /** The + menu's Location: find the phone and attach it to the composer. */
        data object LocationTapped : Action
        data class LocationResponse(val result: Outcome<SharedLocation, LocationClientError>) : Action
        data object LocationRemoved : Action
        data object LocationErrorDismissed : Action
        data class QuoteRemoved(val id: String) : Action
        data class QuoteCommented(val id: String, val comment: String) : Action
        data object LocationSettingsTapped : Action
        /** Starts uploads for shared files absorbed into the composer. */
        data object QueuedUploadsStarted : Action
    }

    private sealed interface CancelID {
        data object Location : CancelID
        data class Upload(val id: UUID) : CancelID
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        return when (action) {
            is Action.AttachmentRemoved -> {
                if (action.index !in state.pendingAttachments.indices) return Reduced(state)
                val next = state.copy(pendingAttachments = state.pendingAttachments.filterIndexed { i, _ -> i != action.index })
                haptics.play(Haptic.lightTap)
                Reduced(next)
            }

            Action.LocationTapped -> {
                if (state.isLocating) return Reduced(state)
                val location = dependency(LocationClient)
                val effect = Effect.run<Action> { send ->
                    try {
                        val found = location.current()
                        location.prefetchCompactSnapshot(found)
                        send(Action.LocationResponse(Outcome.Success(found)))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: LocationClientError) {
                        send(Action.LocationResponse(Outcome.Failure(error)))
                    } catch (_: Throwable) {
                        send(Action.LocationResponse(Outcome.Failure(LocationClientError.Unavailable)))
                    }
                }.cancellable(CancelID.Location, cancelInFlight = true)
                Reduced(state.copy(isLocating = true, locationError = null), effect)
            }

            is Action.LocationResponse -> {
                if (!state.isLocating) return Reduced(state)
                when (val result = action.result) {
                    is Outcome.Success -> {
                        haptics.play(Haptic.success)
                        Reduced(state.copy(isLocating = false, pendingLocation = result.value))
                    }
                    is Outcome.Failure -> {
                        haptics.play(Haptic.error)
                        Reduced(state.copy(isLocating = false, locationError = result.error.localizedDescription))
                    }
                }
            }

            Action.LocationRemoved ->
                Reduced(state.copy(isLocating = false, pendingLocation = null), Effect.cancel(CancelID.Location))

            Action.LocationErrorDismissed -> Reduced(state.copy(locationError = null))

            is Action.QuoteRemoved -> {
                Reduced(state.copy(pendingQuotes = state.pendingQuotes.filter { it.id != action.id }))
            }
            is Action.QuoteCommented -> {
                val comment = action.comment.trim().ifEmpty { null }
                Reduced(state.copy(pendingQuotes = state.pendingQuotes.map { if (it.id == action.id) it.copy(comment = comment) else it }))
            }

            Action.LocationSettingsTapped -> {
                val location = dependency(LocationClient)
                Reduced(state.copy(locationError = null), Effect.run { location.openSettings() })
            }

            is Action.AttachmentAdded,
            is Action.AttachmentLanded,
            is Action.FilesStaged,
            Action.QueuedUploadsStarted,
            is Action.FileUploadProgress,
            is Action.FileUploadFinished,
            is Action.FileRetryTapped,
            is Action.FileRemoved,
            -> Reduced(state)
        }
    }

    companion object {
        /** Reads a staged file the model takes inline (a PDF) and discards the staged copy. */
        fun readInlineFile(file: ComposerStagedFile): Effect<Action> = Effect.run { send ->
            val data = withContext(Dispatchers.IO) { runCatching { file.url.readBytes() }.getOrNull() }
            if (data != null) {
                send(Action.AttachmentAdded(OpenCodeOutgoingAttachment(id = file.id.toString(), data = data, mime = file.mime, name = file.name)))
            }
            withContext(Dispatchers.IO) { ComposerFileStaging.discard(file.url) }
        }

        fun upload(
            file: ComposerFileAttachment,
            server: OpenCodeServer,
            directory: String?,
            supportsFileUploads: Boolean,
            openCodeClient: OpenCodeClient,
        ): Effect<Action> {
            val id = file.id
            if (!supportsFileUploads) {
                return Effect.send(Action.FileUploadFinished(id, Outcome.Failure(OpenCodeClientError.uploadsUnsupported)))
            }
            val uploadFile = openCodeClient.uploadFile
            return Effect.run<Action> { send ->
                try {
                    var finished = false
                    uploadFile(server, directory, File(file.localPath), file.name)
                        .transformWhile { event ->
                            emit(event)
                            event !is OpenCodeUploadEvent.Finished
                        }
                        .collect { event ->
                            when (event) {
                                is OpenCodeUploadEvent.Progress -> send(Action.FileUploadProgress(id, event.sent))
                                is OpenCodeUploadEvent.Finished -> {
                                    finished = true
                                    // The server has its copy; drop ours even if the session changed.
                                    withContext(Dispatchers.IO) { ComposerFileStaging.discard(java.io.File(file.localPath)) }
                                    send(Action.FileUploadFinished(id, Outcome.Success(event.path)))
                                }
                            }
                        }
                    if (finished || !currentCoroutineContext().isActive) return@run
                    send(Action.FileUploadFinished(id, Outcome.Failure(OpenCodeClientError(message = "The upload ended early."))))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    if (!currentCoroutineContext().isActive) return@run
                    val failure = error as? OpenCodeClientError
                        ?: OpenCodeClientError(message = error.localizedMessage ?: error.toString())
                    send(Action.FileUploadFinished(id, Outcome.Failure(failure)))
                }
            }.cancellable(CancelID.Upload(id), cancelInFlight = true)
        }

        fun cancelUpload(id: UUID, discardPath: String): Effect<Action> = Effect.merge(
            Effect.cancel(CancelID.Upload(id)),
            Effect.run { withContext(Dispatchers.IO) { ComposerFileStaging.discard(java.io.File(discardPath)) } },
        )
    }
}
