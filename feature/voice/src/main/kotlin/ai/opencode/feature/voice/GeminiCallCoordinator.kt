package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class GeminiCallCoordinator(
    timing: VoiceCallTiming = VoiceCallTiming.live,
    diagnostics: VoiceCallDiagnostics = dependency(VoiceCallDiagnostics),
) : VoiceSessionCoordinator<GeminiLiveServerEvent, GeminiLiveCommand, GeminiLiveContentTurn>(timing, diagnostics) {
    override val defaultSessionLimit = 15.0 * 60
    override val defaultDiagnosticDomain = "GeminiLiveClient"
    override val expiredDiagnosticDomain = "GeminiLive"
    override val canSwapToStandby get() = !assistantSpeaking && openCalls.isEmpty()
    private var apiKey = ""
    private var gemini: GeminiLiveClient? = null
    private var config: GeminiLiveSessionConfig? = null
    private val resampler = GeminiPCMResampler()
    private var history = VoiceTurnHistory()
    private val openCalls = mutableMapOf<String, String>()
    private val orphanCalls = mutableMapOf<String, String>()
    private val undelivered = mutableListOf<String>()
    private var currentUserItemID: String? = null
    private var currentUserText = ""
    private var currentAssistantItemID: String? = null
    private var currentAssistantText = ""
    private var assistantSpeaking = false
    private var nextItemSequence = 0
    private var attachingSocketID: Int? = null

    suspend fun start(apiKey: String, config: GeminiLiveSessionConfig, gemini: GeminiLiveClient, audio: VoiceAudioClient): Flow<VoiceCallEvent> = serialized {
        this.apiKey = apiKey; this.config = config; this.gemini = gemini
        startAudioAndSocket(config.isAtEar, audio)
    }
    override suspend fun open(seeded: Boolean): VoiceCallSocket<GeminiLiveServerEvent, GeminiLiveCommand, GeminiLiveContentTurn> {
        val client = gemini ?: throw RealtimeClientError.Closed("The voice connection is closed.")
        val config = config ?: throw RealtimeClientError.Closed("The voice connection is closed.")
        return openSocket(seeded, { client.connect(apiKey, config) })
    }
    override suspend fun activate(socket: VoiceCallSocket<GeminiLiveServerEvent, GeminiLiveCommand, GeminiLiveContentTurn>) {
        val previous = active
        active = socket
        if (standby?.id == socket.id) standby = null
        // Tool IDs are scoped to the connection, including a planned rotation.
        retireSessionState()
        activateAudio(socket)
        if (previous != null && previous.id != socket.id) discard(previous)
        if (!finished && socket.ready) attachActiveSocket()
    }
    override fun retireSessionState() {
        orphanCalls.putAll(openCalls); openCalls.clear()
        currentUserItemID = null; currentUserText = ""
        currentAssistantItemID = null; currentAssistantText = ""; assistantSpeaking = false
    }
    override suspend fun forwardCapture(chunk: ByteArray) {
        val socket = active?.takeIf { it.ready && it.attached } ?: return
        if (finished) return
        val pcm16k = resampler.downsample24kTo16k(chunk)
        if (pcm16k.isEmpty()) return
        uplinkSendStarted()
        try { socket.connection.send(GeminiLiveCommand.RealtimeInputAudio(pcm16k)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Best-effort audio path. */ }
        finally { uplinkSendFinished() }
    }
    override suspend fun handleAudio(event: VoiceAudioEvent) {
        when (event) {
            VoiceAudioEvent.playbackDrained -> { assistantSpeaking = false; emit(VoiceCallEvent.AssistantPlaybackFinished); swapToStandby() }
            VoiceAudioEvent.interrupted, VoiceAudioEvent.mediaServicesReset -> { assistantSpeaking = false; emit(VoiceCallEvent.AudioInterrupted) }
            VoiceAudioEvent.resumed -> emit(VoiceCallEvent.AudioResumed)
            VoiceAudioEvent.routeChanged -> emit(VoiceCallEvent.RouteChanged)
        }
    }
    override suspend fun handle(event: GeminiLiveServerEvent, socket: Int) {
        if (standby?.id == socket) {
            when (event) {
                GeminiLiveServerEvent.SetupComplete -> { standby?.ready = true; swapToStandby() }
                is GeminiLiveServerEvent.Error, is GeminiLiveServerEvent.GoAway -> discardStandbyAndRescheduleRotation()
                else -> Unit
            }
            return
        }
        if (finished || active?.id != socket) return
        when (event) {
            GeminiLiveServerEvent.SetupComplete -> { noteSessionConfirmed(null); attachActiveSocket() }
            GeminiLiveServerEvent.Interrupted -> { assistantSpeaking = false; audio?.flush?.invoke(); flushAssistantTextIfNeeded(); emit(VoiceCallEvent.UserSpeechStarted) }
            is GeminiLiveServerEvent.InputTranscription -> {
                if (currentUserItemID == null) {
                    currentUserItemID = "gemini-user-${++nextItemSequence}"
                    if (assistantSpeaking) { assistantSpeaking = false; audio?.flush?.invoke() }
                    emit(VoiceCallEvent.UserSpeechStarted)
                }
                currentUserText += event.text
                if (event.finished) flushUserTurnIfNeeded()
            }
            is GeminiLiveServerEvent.Audio -> { flushUserTurnIfNeeded(); val id = ensureAssistantItemID(); assistantSpeaking = true; audio?.enqueue?.invoke(event.pcm, id) }
            is GeminiLiveServerEvent.OutputTranscription -> { appendAssistantText(event.text); if (event.finished) flushAssistantTextIfNeeded() }
            is GeminiLiveServerEvent.ModelText -> appendAssistantText(event.text)
            is GeminiLiveServerEvent.FunctionCall -> {
                flushUserTurnIfNeeded(); openCalls[event.call.id] = event.call.name
                emit(VoiceCallEvent.FunctionCall(event.call.id, event.call.name, event.call.args))
            }
            is GeminiLiveServerEvent.ToolCallCancellation -> event.ids.forEach { openCalls.remove(it); orphanCalls.remove(it) }
            GeminiLiveServerEvent.TurnComplete -> { flushUserTurnIfNeeded(); flushAssistantTextIfNeeded(); swapToStandby() }
            is GeminiLiveServerEvent.GoAway -> { prepareStandby(); swapToStandby(force = true) }
            is GeminiLiveServerEvent.Error -> { emit(VoiceCallEvent.ServiceError(event.message)); finish(RealtimeClientError.Closed(event.message)) }
            GeminiLiveServerEvent.Unknown -> Unit
        }
    }
    override suspend fun completeFunctionImpl(callID: String, output: String) {
        val orphanName = orphanCalls.remove(callID)
        val name = openCalls.remove(callID)
        val context = "Result of an earlier request, made before the call moved to this connection:\n$output"
        val socket = active
        if (orphanName != null || name == null || socket?.ready != true) { deliverContext(context, true); return }
        try {
            socket.connection.sendControl(GeminiLiveCommand.ToolResponse(listOf(GeminiLiveFunctionResponse(callID, name,
                RealtimeJSONs.obj("result" to RealtimeJSONs.string(output))))), "gemini.tool_response")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { deliverContext(context, true) }
    }
    override suspend fun closeDelegationImpl(callID: String, reason: VoiceDelegationCloseReason) {
        val activeName = openCalls.remove(callID)
        val wasOrphan = orphanCalls.remove(callID) != null
        val socket = active
        if (activeName != null && socket != null && socket.ready) {
            socket.connection.sendControlIfPossible(
                GeminiLiveCommand.ToolResponse(listOf(GeminiLiveFunctionResponse(
                    id = callID,
                    name = activeName,
                    response = RealtimeJSONs.obj("status" to RealtimeJSONs.string(reason.rawValue)),
                    scheduling = "SILENT",
                ))),
                "gemini.tool_response",
            )
        }
        if (activeName != null || wasOrphan) discardStandbyAndRescheduleRotation()
    }
    suspend fun announce(text: String) = serialized { if (!finished) deliverContext(text, true) }
    override suspend fun setMutedImpl(muted: Boolean) { audio?.setMuted?.invoke(muted); if (muted) flushUserTurnIfNeeded() }
    override suspend fun updateInstructions(full: String, delta: String?) = serialized {
        if (finished) return@serialized
        config = config?.copy(instructions = full) ?: return@serialized
        if (!delta.isNullOrEmpty()) deliverContext(delta, false)
    }
    private fun appendAssistantText(text: String) {
        flushUserTurnIfNeeded()
        val id = ensureAssistantItemID(); currentAssistantText += text
        emit(VoiceCallEvent.AssistantDelta(id, text))
    }
    private fun ensureAssistantItemID(): String = currentAssistantItemID ?: "gemini-assistant-${++nextItemSequence}".also { currentAssistantItemID = it }
    private fun flushUserTurnIfNeeded() {
        val id = currentUserItemID ?: return
        val text = currentUserText.trim(); currentUserItemID = null; currentUserText = ""
        if (text.isEmpty()) return
        history = history.upsert(id, VoiceTranscriptTail.Role.user, text)
        emit(VoiceCallEvent.UserTranscript(id, text, true))
    }
    private fun flushAssistantTextIfNeeded() {
        val id = currentAssistantItemID
        val text = currentAssistantText.trim(); currentAssistantItemID = null; currentAssistantText = ""
        if (id == null || text.isEmpty()) return
        history = history.upsert(id, VoiceTranscriptTail.Role.assistant, text)
        emit(VoiceCallEvent.AssistantDone(id, text))
    }
    private fun content(text: String, complete: Boolean) = GeminiLiveCommand.ClientContent(listOf(GeminiLiveContentTurn("user", text)), complete)
    private suspend fun attachActiveSocket() {
        val socket = active?.takeIf { it.ready && !it.attached && attachingSocketID != it.id } ?: return
        attachingSocketID = socket.id
        try {
            if (socket.seeded) {
                val mark = history.mark
                socket.connection.sendControl(content(history.seedText(), false), "gemini.seed")
                if (finished || active?.id != socket.id) return
                history = history.noteSeeded(mark)
            }
            while (undelivered.isNotEmpty()) {
                if (finished || active?.id != socket.id) return
                socket.connection.sendControl(content(undelivered.first(), true), "gemini.queued_context")
                if (finished || active?.id != socket.id) return
                undelivered.removeAt(0)
            }
            if (finished || active?.id != socket.id) return
            active?.attached = true; noteSocketAttached(resetLinkQuality = true)
            scheduleRotation(socket)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { socketLost(socket.id, VoiceSessionLoss.Closed(error)) }
        finally { if (attachingSocketID == socket.id) attachingSocketID = null }
    }
    private suspend fun deliverContext(text: String, turnComplete: Boolean) {
        if (finished) return
        val socket = active?.takeIf { it.ready && it.attached }
        if (socket == null) { undelivered += text; return }
        try { socket.connection.sendControl(content(text, turnComplete), "gemini.context") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (!finished) undelivered += text }
    }
    override fun finishing() { undelivered.clear() }
}
