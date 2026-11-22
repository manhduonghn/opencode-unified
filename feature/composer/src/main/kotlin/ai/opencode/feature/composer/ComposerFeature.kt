package ai.opencode.feature.composer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.map
import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.modelpicker.ModelPickerEvent
import ai.opencode.feature.composer.modelpicker.ModelPickerState
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import ai.opencode.server.OpenCodeServerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * The composer's host reducer.
 *
 * Until the session area wires the composer pieces into its own reducer, this feature keeps the
 * composer usable on its own: the parent tells it which session it's on with [Action.Configure].
 * The session's real send path goes through [PromptOutbox] and the session's delivery job; this
 * host posts directly under [RetryPolicy.send] (same IDs, same retry policy) so the composer can be
 * exercised end to end.
 */
class ComposerFeature : Reducer<ComposerFeature.State, ComposerFeature.Action> {
    data class State(
        val server: OpenCodeServer? = null,
        val sessionID: String? = null,
        val directory: String? = null,
        /** The session and its subagents: requests from any of them dock here. */
        val familySessionIDs: Set<String> = emptySet(),
        val draft: String = "",
        val isFocused: Boolean = false,
        val attachments: ComposerAttachmentsFeature.State = ComposerAttachmentsFeature.State(),
        val requestDock: RequestDockFeature.State<DraftState> = RequestDockFeature.State(),
        val modelPicker: ModelPickerState = ModelPickerState(),
        /** The waiting pick, applied with `POST …/model` just before the next send. */
        val pendingModel: OpenCodeModelRef? = null,
        val isModelPickerOpen: Boolean = false,
        val isAttachmentMenuOpen: Boolean = false,
        val isSending: Boolean = false,
        val sendError: String? = null,
    ) {
        val isAnsweringRequest: Boolean get() = requestDock.isAnswering

        val canSend: Boolean
            get() = !isSending && (draft.isNotBlank() || attachments.pendingAttachments.isNotEmpty() || attachments.pendingLocation != null)
    }

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
        /** The session this composer belongs to (null `sessionID`: a draft). */
        data class Configure(val server: OpenCodeServer?, val sessionID: String?, val directory: String?) : Action
        data class SessionResolved(val directory: String, val familySessionIDs: Set<String>) : Action
        data class DraftChanged(val text: String) : Action
        data class FocusChanged(val isFocused: Boolean) : Action
        data object SendTapped : Action
        data class SendFinished(val error: OpenCodeClientError?) : Action
        data class PendingRequestsLoaded(val requests: OpenCodePendingRequests) : Action
        data class RequestDock(val action: RequestDockFeature.Action) : Action
        data class Attachments(val action: ComposerAttachmentsFeature.Action) : Action
        data object PlusTapped : Action
        data object AttachmentMenuDismissed : Action
        data object ModelChipTapped : Action
        data class ModelPicker(val event: ModelPickerEvent) : Action
        data class ModelsLoaded(val catalog: OpenCodeModelCatalog?, val error: String?) : Action
    }

    private enum class CancelID { Requests, Models, Send }

    private companion object {
        val PENDING_WORK_HEALTHY = 12.seconds
        val PENDING_WORK_UNHEALTHY = 2.seconds
    }

    private val attachmentsReducer = ComposerAttachmentsFeature()
    private val dockReducer = RequestDockFeature<DraftState>()

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val server = state.server ?: shared(ServerSharedKeys.savedServer).value
            Reduced(state.copy(server = server), startReads(state.copy(server = server)))
        }

        is Action.Configure -> {
            val next = state.copy(server = action.server ?: state.server, sessionID = action.sessionID, directory = action.directory)
            Reduced(next, startReads(next))
        }

        is Action.SessionResolved -> {
            val next = state.copy(directory = action.directory, familySessionIDs = action.familySessionIDs)
            Reduced(next, refreshRequests(next, watches = true))
        }

        is Action.DraftChanged -> {
            var next = state.copy(draft = action.text, sendError = null)
            if (next.isAnsweringRequest) next = next.copy(requestDock = next.requestDock.applyBarEdit(action.text))
            Reduced(next)
        }

        is Action.FocusChanged -> Reduced(state.copy(isFocused = action.isFocused))

        Action.SendTapped -> send(state)

        is Action.SendFinished -> {
            val haptics = dependency(HapticsClient)
            if (action.error != null) {
                haptics.play(Haptic.error)
                Reduced(state.copy(isSending = false, sendError = localized(R.string.composer_send_failed_with_reason, action.error.message)))
            } else {
                Reduced(state.copy(isSending = false, pendingModel = null))
            }
        }

        is Action.PendingRequestsLoaded -> {
            val family = state.familySessionIDs + setOfNotNull(state.sessionID)
            val reconciled = state.requestDock.reconciled(action.requests, family)
            var next = state.copy(requestDock = reconciled.state)
            if (reconciled.arrived && reconciled.state.hasPendingRequests) dependency(HapticsClient).play(Haptic.attention)
            next = handOver(next)
            Reduced(next)
        }

        is Action.RequestDock -> reduceDock(state, action.action)

        is Action.Attachments -> {
            val reduced = attachmentsReducer.reduce(state.attachments, action.action)
            val host = reduceAttachments(state.copy(attachments = reduced.state), action.action)
            Reduced(host.state, Effect.merge(reduced.effect.map(Action::Attachments), host.effect))
        }

        Action.PlusTapped -> Reduced(state.copy(isAttachmentMenuOpen = !state.isAttachmentMenuOpen))
        Action.AttachmentMenuDismissed -> Reduced(state.copy(isAttachmentMenuOpen = false))

        Action.ModelChipTapped -> {
            val next = state.copy(isModelPickerOpen = true)
            Reduced(next, loadModels(next))
        }

        is Action.ModelPicker -> reduceModelPicker(state, action.event)

        is Action.ModelsLoaded -> {
            val picker = state.modelPicker
            val models = action.catalog?.models ?: picker.models
            val current = picker.currentModel ?: action.catalog?.defaultModel?.ref
            Reduced(
                state.copy(
                    modelPicker = picker.copy(
                        models = models,
                        currentModel = current,
                        isLoadingModels = false,
                        modelsLoadError = action.error,
                    ),
                ),
            )
        }
    }

    /** The dock takes the composer while a request waits, and gives it back after. */
    private fun handOver(state: State): State {
        val dock = state.requestDock
        if (dock.hasPendingRequests && !dock.isAnswering) {
            val parked = DraftState(
                text = state.draft,
                attachments = state.attachments.pendingAttachments,
                files = state.attachments.pendingFiles,
                location = state.attachments.pendingLocation,
            )
            val (answering, text) = dock.beginAnswering(parked) ?: return state
            return state.copy(requestDock = answering, draft = text)
        }
        if (!dock.hasPendingRequests && dock.isAnswering) {
            val (ended, parked) = dock.endAnswering() ?: return state
            return state.copy(requestDock = ended, draft = parked.text)
        }
        if (dock.isAnswering) {
            val context = dock.answerContext(wants = true)
            if (context.target != dock.answerShownTarget) {
                val (moved, text) = dock.moveAnswer(state.draft)
                return state.copy(requestDock = moved, draft = text)
            }
        }
        return state
    }

    private fun reduceDock(state: State, action: RequestDockFeature.Action): Reduced<State, Action> {
        val server = state.server ?: return Reduced(state)
        val dock = state.requestDock
        val reply: Reduced<RequestDockFeature.State<DraftState>, RequestDockFeature.Action> = when (action) {
            is RequestDockFeature.Action.PermissionReplyTapped ->
                RequestReplies.replyPermission(dock, action.id, action.reply, action.message, server)
            is RequestDockFeature.Action.FormSubmitTapped -> RequestReplies.submitForm(dock, action.id, server, state.directory)
            is RequestDockFeature.Action.FormCancelTapped ->
                RequestReplies.cancelForm(dock, action.id, action.message, server, state.directory)
            RequestDockFeature.Action.DismissTapped ->
                RequestReplies.dismissRequest(dock, state.draft.ifBlank { null }, server, state.directory)
            is RequestDockFeature.Action.BarSent ->
                RequestReplies.sendRequestBar(dock, state.draft, state.draft.ifBlank { null }, server, state.directory)
            RequestDockFeature.Action.CustomMarkTapped -> Reduced(RequestReplies.customMarkTapped(dock, state.draft))
            is RequestDockFeature.Action.ResponseFinished -> {
                val settled = RequestReplies.responseFinished(dock, action.sessionID, action.requestID, action.error, state.familySessionIDs + setOfNotNull(state.sessionID), action.dismissal)
                    ?: return Reduced(state)
                val next = handOver(state.copy(requestDock = settled.state))
                return Reduced(next, refreshRequests(next))
            }
            RequestDockFeature.Action.RefreshTapped, RequestDockFeature.Action.AutoRetry -> return Reduced(state, refreshRequests(state))
            else -> dockReducer.reduce(dock, action)
        }
        val next = handOver(state.copy(requestDock = reply.state))
        return Reduced(next, reply.effect.map(Action::RequestDock))
    }

    private fun reduceModelPicker(state: State, event: ModelPickerEvent): Reduced<State, Action> {
        val picker = state.modelPicker
        val haptics = dependency(HapticsClient)
        fun pick(ref: OpenCodeModelRef, closes: Boolean): Reduced<State, Action> {
            if (picker.currentModel != ref) haptics.play(Haptic.selection)
            return Reduced(
                state.copy(
                    modelPicker = picker.copy(currentModel = ref, modelFilter = if (closes) "" else picker.modelFilter),
                    pendingModel = ref,
                    isModelPickerOpen = if (closes) false else state.isModelPickerOpen,
                ),
            )
        }
        return when (event) {
            is ModelPickerEvent.ModelPicked -> {
                val variant = picker.currentModel?.variant?.takeIf { it in event.model.variants }
                pick(event.model.ref(variant), closes = true)
            }
            is ModelPickerEvent.ModelPickedWithLevel ->
                pick(event.model.ref(event.level), closes = true)
            is ModelPickerEvent.ModelLevelPicked ->
                pick(event.model.ref(event.level), closes = false)
            is ModelPickerEvent.VariantPicked -> {
                val current = picker.currentModel ?: return Reduced(state)
                pick(current.copy(variant = event.variant), closes = false)
            }
            is ModelPickerEvent.FilterChanged -> Reduced(state.copy(modelPicker = picker.copy(modelFilter = event.text)))
            ModelPickerEvent.ShowAllModelsToggled -> Reduced(state.copy(modelPicker = picker.copy(showsAllModels = !picker.showsAllModels)))
            ModelPickerEvent.ModelListOpened -> Reduced(state, loadModels(state))
            ModelPickerEvent.ModelListClosed -> Reduced(state.copy(modelPicker = picker.copy(modelFilter = "", showsAllModels = false)))
            ModelPickerEvent.Dismiss -> Reduced(state.copy(isModelPickerOpen = false))
            else -> Reduced(state)
        }
    }

    /** The session's half of attachments. */
    private fun reduceAttachments(state: State, action: ComposerAttachmentsFeature.Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        val attachments = state.attachments
        fun updateFile(id: java.util.UUID, transform: (ComposerFileAttachment) -> ComposerFileAttachment): State =
            state.copy(attachments = attachments.copy(pendingFiles = attachments.pendingFiles.map { if (it.id == id) transform(it) else it }))
        fun upload(file: ComposerFileAttachment): Effect<Action> {
            val server = state.server ?: return Effect.none()
            return ComposerAttachmentsFeature.upload(file, server, state.directory, true, dependency(OpenCodeClient)).map(Action::Attachments)
        }
        return when (action) {
            is ComposerAttachmentsFeature.Action.AttachmentAdded -> {
                if (state.isAnsweringRequest) return Reduced(state)
                haptics.play(Haptic.selection)
                Reduced(state.copy(attachments = attachments.copy(pendingAttachments = attachments.pendingAttachments + action.attachment)))
            }
            is ComposerAttachmentsFeature.Action.FilesStaged -> {
                val files = action.files.map { ComposerFileAttachment(it) }
                if (files.isNotEmpty()) haptics.play(Haptic.selection)
                Reduced(
                    state.copy(attachments = attachments.copy(pendingFiles = attachments.pendingFiles + files)),
                    Effect.merge(files.map(::upload)),
                )
            }
            is ComposerAttachmentsFeature.Action.FileUploadProgress -> Reduced(
                updateFile(action.id) { if (it.isUploading) it.copy(status = ComposerFileAttachment.Status.Uploading(action.sent)) else it },
            )
            is ComposerAttachmentsFeature.Action.FileUploadFinished -> when (val result = action.result) {
                is ComposerAttachmentsFeature.Outcome.Success ->
                    Reduced(updateFile(action.id) { it.copy(status = ComposerFileAttachment.Status.Uploaded(result.value)) })
                is ComposerAttachmentsFeature.Outcome.Failure -> {
                    haptics.play(Haptic.error)
                    Reduced(updateFile(action.id) { it.copy(status = ComposerFileAttachment.Status.Failed(localized(R.string.composer_upload_failed))) })
                }
            }
            is ComposerAttachmentsFeature.Action.FileRetryTapped -> {
                val file = attachments.pendingFiles.firstOrNull { it.id == action.id && it.isFailed } ?: return Reduced(state)
                haptics.play(Haptic.tap)
                val retrying = file.copy(status = ComposerFileAttachment.Status.Uploading(0))
                Reduced(updateFile(action.id) { retrying }, upload(retrying))
            }
            is ComposerAttachmentsFeature.Action.FileRemoved -> {
                val file = attachments.pendingFiles.firstOrNull { it.id == action.id } ?: return Reduced(state)
                Reduced(
                    state.copy(attachments = attachments.copy(pendingFiles = attachments.pendingFiles - file)),
                    ComposerAttachmentsFeature.cancelUpload(file.id, file.localPath).map(Action::Attachments),
                )
            }
            else -> Reduced(state)
        }
    }

    private fun send(state: State): Reduced<State, Action> {
        if (state.isAnsweringRequest) {
            val id = state.requestDock.dockRequestID ?: return Reduced(state)
            return reduceDock(state, RequestDockFeature.Action.BarSent(id))
        }
        val server = state.server
        val sessionID = state.sessionID
        if (!state.canSend || server == null || sessionID == null) return Reduced(state)
        dependency(HapticsClient).play(Haptic.tap)
        val text = state.attachments.pendingLocation?.prompt(state.draft.trim()) ?: state.draft.trim()
        val attachments = state.attachments.pendingAttachments
        val files = state.attachments.pendingFiles.mapNotNull { it.reference }
        val model = state.pendingModel
        val messageID = dependency(ServerIDClient).messageID()
        val next = state.copy(
            draft = "",
            isSending = true,
            sendError = null,
            attachments = state.attachments.copy(pendingAttachments = emptyList(), pendingFiles = emptyList(), pendingLocation = null),
        )
        val effect = Effect.run<Action> { send ->
            val client = dependency(OpenCodeClient)
            val error = try {
                withRetryPolicy(RetryPolicy.send) {
                    if (model != null) client.switchModel(server, sessionID, model)
                    client.sendPrompt(server, sessionID, text, attachments, files, messageID, OpenCodeDelivery.Steer)
                }
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: OpenCodeClientError) {
                error
            }
            AppLog.send.info("composer_send id=$messageID attachments=${attachments.size} files=${files.size} ok=${error == null}")
            send(Action.SendFinished(error))
        }.cancellable(CancelID.Send)
        return Reduced(next, effect)
    }

    private fun startReads(state: State): Effect<Action> {
        val server = state.server
        val sessionID = state.sessionID
        // Requests are location-scoped: learn the session's directory first.
        if (state.directory == null && server != null && sessionID != null) {
            return Effect.run<Action> { send ->
                try {
                    val client = dependency(OpenCodeClient)
                    val session = client.getSession(server, sessionID)
                    val family = runCatching { client.sessionFamily(server, session).sessions.map { it.id } }.getOrDefault(emptyList())
                    send(Action.SessionResolved(session.directory, (family + sessionID).toSet()))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: OpenCodeClientError) {
                }
            }.cancellable(CancelID.Requests, cancelInFlight = true)
        }
        return refreshRequests(state, watches = true)
    }

    /**
     * The session's pending requests: read now, again whenever the server's event stream says a
     * request was asked or settled (`permission.*`/`form.*` →
     * [OpenCodeServerEvent.AttentionChanged]) or the stream (re)connects, and otherwise every
     * [PENDING_WORK_HEALTHY] while the stream is healthy or [PENDING_WORK_UNHEALTHY] while it
     * isn't. [watches] false reads once.
     */
    private fun refreshRequests(state: State, watches: Boolean = false): Effect<Action> {
        val server = state.server ?: return Effect.none()
        val sessionID = state.sessionID ?: return Effect.none()
        val directory = state.directory ?: ""
        val family = (state.familySessionIDs + sessionID).toList()
        suspend fun read(send: ai.opencode.core.store.Send<Action>) {
            try {
                send(Action.PendingRequestsLoaded(dependency(OpenCodeClient).familyPendingRequests(server, family, directory)))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: OpenCodeClientError) {
            }
        }
        if (!watches) return Effect.run<Action> { send -> read(send) }
        return Effect.run<Action> { send ->
            val client = dependency(OpenCodeClient)
            val isHealthy = AtomicBoolean(false)
            val changed = Channel<Unit>(Channel.CONFLATED)
            coroutineScope {
                launch {
                    while (true) {
                        try {
                            client.events(server).collect { event ->
                                when (event) {
                                    OpenCodeServerEvent.Connected -> {
                                        isHealthy.set(true)
                                        changed.trySend(Unit)
                                    }
                                    is OpenCodeServerEvent.AttentionChanged -> changed.trySend(Unit)
                                    else -> Unit
                                }
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                        }
                        isHealthy.set(false)
                        delay(PENDING_WORK_UNHEALTHY)
                    }
                }
                while (true) {
                    read(send)
                    withTimeoutOrNull(if (isHealthy.get()) PENDING_WORK_HEALTHY else PENDING_WORK_UNHEALTHY) { changed.receive() }
                }
            }
        }.cancellable(CancelID.Requests, cancelInFlight = true)
    }

    private fun loadModels(state: State): Effect<Action> {
        val server = state.server ?: return Effect.none()
        val directory = state.directory
        return Effect.run<Action> { send ->
            try {
                send(Action.ModelsLoaded(dependency(OpenCodeClient).modelCatalog(server, directory), null))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: OpenCodeClientError) {
                send(Action.ModelsLoaded(null, error.message))
            }
        }.cancellable(CancelID.Models, cancelInFlight = true)
    }
}
