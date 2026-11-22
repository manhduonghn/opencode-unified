package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** Realtime and Grok: server VAD owns turns, while the local floor prevents
 * tool replies from talking over the user or audio still playing. */
class VoiceCallCoordinator(
    timing: VoiceCallTiming = VoiceCallTiming.live,
    diagnostics: VoiceCallDiagnostics = dependency(VoiceCallDiagnostics),
) : VoiceSessionCoordinator<RealtimeServerEvent, RealtimeCommand, Nothing>(timing, diagnostics) {
    override val defaultSessionLimit = 60.0 * 60
    override val defaultDiagnosticDomain = "RealtimeClient"
    override val expiredDiagnosticDomain = "OpenAIRealtime"
    override val canSwapToStandby get() = floor.floorIsFree
    private var apiKey = ""
    private var realtime: RealtimeClient? = null
    private var config: RealtimeSessionConfig? = null
    private var currentResponseID: String? = null
    private var cancelledResponseID: String? = null
    private var cancelledItemID: String? = null
    private var floor = VoiceTurnTaking(hasSession = false)
    private var serverReplyTimeout: Job? = null
    private var lastAssistantItemID: String? = null
    private var awaitingAnswer = false
    private var answeredTurn = false
    private var history = VoiceTurnHistory()
    private val openCalls = mutableSetOf<String>()
    private val orphanCalls = mutableSetOf<String>()
    private val undelivered = mutableListOf<String>()
    private var transcriptsOwed = 0
    private val heldDelegations = mutableListOf<Pair<VoiceCallEvent, Job>>()

    suspend fun start(apiKey: String, config: RealtimeSessionConfig, realtime: RealtimeClient, audio: VoiceAudioClient): Flow<VoiceCallEvent> = serialized {
        this.apiKey = apiKey; this.config = config; this.realtime = realtime
        startAudioAndSocket(config.isAtEar, audio)
    }

    override suspend fun completeFunctionImpl(callID: String, output: String) {
        val orphaned = orphanCalls.remove(callID)
        openCalls.remove(callID)
        val context = "Result of an earlier request, made before the call moved to this connection:\n$output"
        val socket = active
        if (orphaned || socket?.ready != true) { deliver(context); return }
        try { socket.connection.sendControl(RealtimeCommand.FunctionOutput(callID, output), "realtime.function_output") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { deliver(context); return }
        if (!finished) requestResponse()
    }
    override suspend fun closeDelegationImpl(callID: String, reason: VoiceDelegationCloseReason) {
        val wasOpen = openCalls.remove(callID)
        val wasOrphan = orphanCalls.remove(callID)
        val socket = active
        if (wasOpen && socket != null && socket.ready) {
            socket.connection.sendControlIfPossible(
                RealtimeCommand.FunctionOutput(callID, "{\"status\":\"${reason.rawValue}\"}"),
                "realtime.function_output",
            )
        }
        if (wasOpen || wasOrphan) discardStandbyAndRescheduleRotation()
    }
    override suspend fun setMutedImpl(muted: Boolean) {
        audio?.setMuted?.invoke(muted)
        if (!finished && muted && floor.userSpeaking) {
            floor = floor.owe().userStoppedSpeaking().first
            transcriptsOwed++
            control(RealtimeCommand.CommitAudio, "realtime.commit_audio")
            awaitServerReply()
        }
    }
    override suspend fun updateInstructions(full: String, delta: String?) = serialized {
        if (finished) return@serialized
        val updated = config?.copy(instructions = full) ?: return@serialized
        config = updated
        control(RealtimeCommand.SessionUpdate(updated), "realtime.session_update")
        if (!finished) standby?.connection?.sendControlIfPossible(RealtimeCommand.SessionUpdate(updated), "realtime.session_update")
        Unit
    }
    override suspend fun open(seeded: Boolean): VoiceCallSocket<RealtimeServerEvent, RealtimeCommand, Nothing> {
        val client = realtime ?: throw RealtimeClientError.Closed("The voice connection is closed.")
        val config = config ?: throw RealtimeClientError.Closed("The voice connection is closed.")
        return openSocket(seeded, { client.connect(apiKey, config) }) { connection ->
            val mark = history.mark
            connection.sendControl(RealtimeCommand.SystemMessage(history.seedText(openCalls.size + orphanCalls.size)), "realtime.seed")
            if (!running()) throw CancellationException()
            history = history.noteSeeded(mark)
        }
    }
    override suspend fun activate(socket: VoiceCallSocket<RealtimeServerEvent, RealtimeCommand, Nothing>) {
        val previous = active
        active = socket
        if (standby?.id == socket.id) standby = null
        retireSessionState()
        activateAudio(socket)
        if (previous != null && previous.id != socket.id) discard(previous)
        if (!finished && socket.ready && active?.id == socket.id) attached(socket)
    }
    override fun retireSessionState() {
        transcriptsOwed = 0
        releaseHeldDelegations()
        orphanCalls += openCalls; openCalls.clear()
        currentResponseID = null; cancelledResponseID = null; cancelledItemID = null; lastAssistantItemID = null
        serverReplyTimeout?.cancel()
        floor = floor.sessionChanged()
    }
    private suspend fun attached(socket: VoiceCallSocket<RealtimeServerEvent, RealtimeCommand, Nothing>) {
        noteSocketAttached()
        history.catchUp(socket.seeded)?.let { catchUp ->
            if (!socket.connection.sendControlIfPossible(RealtimeCommand.SystemMessage(catchUp.text), "realtime.catch_up")) return
            history = history.noteSeeded(catchUp.mark)
        }
        while (undelivered.isNotEmpty()) {
            if (finished || active?.id != socket.id) return
            val text = undelivered.removeAt(0)
            try { socket.connection.sendControl(RealtimeCommand.SystemMessage(text), "realtime.queued_context") }
            catch (error: CancellationException) { if (!finished) undelivered.add(0, text); throw error }
            catch (_: Exception) { if (!finished) undelivered.add(0, text); return }
        }
        if (finished || active?.id != socket.id) return
        if (awaitingAnswer) floor = floor.owe()
        awaitingAnswer = false
        active?.attached = true
        if (transition(floor.sessionConfirmed())) control(RealtimeCommand.ResponseCreate, "realtime.response_create")
        scheduleRotation(socket)
    }
    override suspend fun forwardCapture(chunk: ByteArray) {
        if (finished) return
        uplinkSendStarted()
        try { connection?.send?.invoke(RealtimeCommand.AppendAudio(chunk)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Audio chunks are best effort, never per-frame logged. */ }
        finally { uplinkSendFinished() }
    }
    private fun transition(next: Pair<VoiceTurnTaking, Boolean>): Boolean { floor = next.first; return next.second }
    private suspend fun control(command: RealtimeCommand, name: String) {
        if (!finished) connection?.sendControlIfPossible(command, name)
    }
    private suspend fun requestResponse() {
        if (finished) return
        if (active?.ready != true) { floor = floor.owe(); return }
        if (transition(floor.replyRequested())) control(RealtimeCommand.ResponseCreate, "realtime.response_create")
    }
    private fun awaitServerReply() {
        serverReplyTimeout?.cancel()
        serverReplyTimeout = scope.launch {
            clock.sleep(VoiceTurnTaking.serverReplyGrace)
            if (!finished && transition(floor.serverReplyTimedOut())) control(RealtimeCommand.ResponseCreate, "realtime.response_create")
        }
    }
    private suspend fun deliver(text: String) {
        if (finished) return
        val socket = active
        if (socket?.ready != true) { undelivered += text; floor = floor.owe(); return }
        try { socket.connection.sendControl(RealtimeCommand.SystemMessage(text), "realtime.context") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (!finished) { undelivered += text; floor = floor.owe() }; return }
        requestResponse()
    }
    override suspend fun handle(event: RealtimeServerEvent, socket: Int) {
        if (standby?.id == socket) {
            if (event is RealtimeServerEvent.SessionCreated) {
                standby?.ready = true; standby?.expiresAt = event.expiresAt; swapToStandby()
            }
            return
        }
        if (finished || active?.id != socket) return
        when (event) {
            is RealtimeServerEvent.SessionCreated -> {
                noteSessionConfirmed(event.expiresAt)
                active?.let { attached(it) }
                setLinkPoor(false)
            }
            is RealtimeServerEvent.LinkQuality -> setLinkPoor(event.poor)
            RealtimeServerEvent.Ignored -> Unit
            RealtimeServerEvent.SpeechStarted -> {
                serverReplyTimeout?.cancel(); answeredTurn = false
                val (next, bargeIn) = floor.userStartedSpeaking(); floor = next
                if (bargeIn.cancelsResponse) {
                    cancelledResponseID = currentResponseID
                    cancelledItemID = bargeIn.truncateItemID ?: lastAssistantItemID
                }
                currentResponseID = null
                val played = audio?.flush?.invoke() ?: 0
                voiceLog.info("Speech started; playing ${bargeIn.truncateItemID != null}, generating ${bargeIn.cancelsResponse}, heard $played ms")
                bargeIn.truncateItemID?.let { control(RealtimeCommand.Truncate(it, played), "realtime.truncate") }
                if (bargeIn.cancelsResponse) control(RealtimeCommand.ResponseCancel, "realtime.response_cancel")
                emit(VoiceCallEvent.UserSpeechStarted)
            }
            RealtimeServerEvent.SpeechStopped -> {
                transcriptsOwed++
                if (transition(floor.userStoppedSpeaking())) awaitServerReply()
                swapToStandby()
            }
            is RealtimeServerEvent.InputTranscript -> {
                if (event.final) {
                    if (event.text.isNotBlank() && !answeredTurn && !floor.responseActive) awaitingAnswer = true
                    history = history.append(VoiceTranscriptTail.Role.user, event.text)
                    transcriptsOwed = maxOf(0, transcriptsOwed - 1)
                }
                emit(VoiceCallEvent.UserTranscript(event.itemID, event.text, event.final))
                if (event.final && transcriptsOwed == 0) releaseHeldDelegations()
            }
            is RealtimeServerEvent.OutputAudio -> {
                if (cancelledResponseID != null && event.responseID == cancelledResponseID) return
                if (cancelledItemID != null && event.itemID == cancelledItemID) return
                event.itemID?.let { lastAssistantItemID = it }
                if (floor.audioInterrupted) return
                if (event.pcm.size >= 2) floor = floor.audioEnqueued(event.itemID)
                audio?.enqueue?.invoke(event.pcm, event.itemID)
            }
            is RealtimeServerEvent.OutputTranscriptDelta -> {
                event.itemID?.let { lastAssistantItemID = it }
                emit(VoiceCallEvent.AssistantDelta(event.itemID, event.delta))
            }
            is RealtimeServerEvent.OutputTranscriptDone -> {
                if (event.itemID == null || event.itemID != cancelledItemID) awaitingAnswer = false
                history = history.append(VoiceTranscriptTail.Role.assistant, event.text)
                emit(VoiceCallEvent.AssistantDone(event.itemID, event.text))
            }
            is RealtimeServerEvent.FunctionCall -> {
                openCalls += event.callID
                val call = VoiceCallEvent.FunctionCall(event.callID, event.name, event.arguments)
                if (event.name == "delegate" && transcriptsOwed > 0 && timing.realtimeTranscriptWait > Duration.ZERO) {
                    heldDelegations += call to scope.launch {
                        clock.sleep(timing.realtimeTranscriptWait)
                        transcriptsOwed = 0; releaseHeldDelegations()
                    }
                } else emit(call)
            }
            is RealtimeServerEvent.ResponseCreated -> {
                currentResponseID = event.id; lastAssistantItemID = null
                serverReplyTimeout?.cancel(); awaitingAnswer = false
                if (transition(floor.responseCreated())) {
                    cancelledResponseID = event.id; currentResponseID = null
                    control(RealtimeCommand.ResponseCancel, "realtime.response_cancel")
                } else answeredTurn = true
            }
            is RealtimeServerEvent.ResponseDone -> {
                if (event.id != cancelledResponseID) awaitingAnswer = false
                if (event.id == cancelledResponseID) {
                    cancelledResponseID = null; cancelledItemID = null
                    if (currentResponseID != null && currentResponseID != event.id) return
                }
                if (currentResponseID == event.id) currentResponseID = null
                if (transition(floor.responseDone())) control(RealtimeCommand.ResponseCreate, "realtime.response_create")
                swapToStandby()
            }
            is RealtimeServerEvent.Error -> {
                val error = event.error
                if (error.code == "conversation_already_has_active_response") { floor = floor.requestRejected(); return }
                when (error.disposition) {
                    RealtimeServerError.Disposition.harmless -> voiceLog.debug("Realtime race ignored: ${error.code ?: "no code"}")
                    RealtimeServerError.Disposition.sessionExpired -> socketLost(socket, VoiceSessionLoss.Expired(error.code ?: error.message, error.message))
                    RealtimeServerError.Disposition.fatal -> { emit(VoiceCallEvent.ServiceError(error.message)); finish(RealtimeClientError.Closed(error.message)) }
                }
            }
        }
    }
    private fun releaseHeldDelegations() {
        val held = heldDelegations.toList(); heldDelegations.clear()
        for ((event, deadline) in held) { deadline.cancel(); emit(event) }
    }
    override suspend fun handleAudio(event: VoiceAudioEvent) {
        when (event) {
            VoiceAudioEvent.playbackDrained -> {
                emit(VoiceCallEvent.AssistantPlaybackFinished)
                if (transition(floor.playbackDrained())) control(RealtimeCommand.ResponseCreate, "realtime.response_create")
                swapToStandby()
            }
            VoiceAudioEvent.interrupted -> { floor = floor.audioWasInterrupted(); emit(VoiceCallEvent.AudioInterrupted) }
            VoiceAudioEvent.resumed -> {
                emit(VoiceCallEvent.AudioResumed)
                if (transition(floor.audioResumed())) control(RealtimeCommand.ResponseCreate, "realtime.response_create")
            }
            VoiceAudioEvent.routeChanged -> emit(VoiceCallEvent.RouteChanged)
            VoiceAudioEvent.mediaServicesReset -> { emit(VoiceCallEvent.ServiceError("Audio was reset by the system. Start the call again.")); finish(null) }
        }
    }
    override fun finishing() { serverReplyTimeout?.cancel(); heldDelegations.forEach { it.second.cancel() }; heldDelegations.clear(); undelivered.clear() }
}
