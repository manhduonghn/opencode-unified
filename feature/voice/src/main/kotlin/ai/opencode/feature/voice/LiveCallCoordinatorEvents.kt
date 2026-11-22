package ai.opencode.feature.voice

import kotlinx.coroutines.*
import kotlin.time.Duration
import ai.opencode.feature.voice.LiveCallCoordinator.Utterance

internal suspend fun LiveCallCoordinator.handleLiveEvent(event: LiveServerEvent, socket: Int) {
    if (standby?.id == socket) {
        when (event) {
            is LiveServerEvent.SessionStarted -> { standby?.ready = true; standby?.expiresAt = event.expiresAt; swapToStandby() }
            is LiveServerEvent.SessionClosed -> discardStandby()
            is LiveServerEvent.Error -> if (event.error.disposition != RealtimeServerError.Disposition.harmless) discardStandby()
            else -> Unit
        }
        return
    }
    if (finished || active?.id != socket) return
    when (event) {
        is LiveServerEvent.SessionStarted -> { noteSessionConfirmed(event.expiresAt); active?.let { attached(it) } }
        is LiveServerEvent.InputTranscriptDelta -> {
            if (event.delta.isEmpty()) return
            quietGate = quietGate.noteUserSpoke(clock.now())
            if (LiveCallCoordinator.hasWords(event.delta)) {
                lastUserWordsAt = clock.now()
                resumeUserWordWaiters()
                val reply = currentAssistantUtterance
                if (event.startMilliseconds != null && reply?.startMilliseconds != null && event.startMilliseconds < reply.startMilliseconds) {
                    currentAssistantUtterance = reply.copy(userWordsBefore = lastUserWordsAt)
                } else {
                    unbackedOutcomeTask?.cancel(); unbackedOutcomeTask = null
                }
            }
            // GPT-Live is full duplex: user speech and assistant speech can overlap.
            // A transcript fragment alone never cuts assistant playback (a
            // backchannel, or words from before the reply, mustn't); the user
            // talking over it on the microphone does (`yieldIfUserTalksOver`).
            yieldIfUserTalksOver(clock.now())
            currentUserUtterance?.let { utterance ->
                if (utterance.endMilliseconds != null && event.startMilliseconds != null) {
                    val gap = event.startMilliseconds - utterance.endMilliseconds
                    if (gap < 0 || gap > if (utterance.delegated) 200 else 2_500) currentUserUtterance = null
                } else if (utterance.delegated) currentUserUtterance = null
            }
            if (currentUserUtterance == null) {
                flushAcknowledgements()
                currentUserUtterance = Utterance("live-u-${nextUserUtteranceNumber++}", "", event.startMilliseconds, event.endMilliseconds)
            }
            currentUserUtterance = currentUserUtterance?.let { it.copy(rawText = it.rawText + event.delta, endMilliseconds = event.endMilliseconds ?: it.endMilliseconds) }
            currentUserUtterance?.let { utterance ->
                val trimmed = utterance.rawText.trim()
                if (trimmed.isNotEmpty()) {
                    history = history.upsert(utterance.id, VoiceTranscriptTail.Role.user, trimmed)
                    emit(VoiceCallEvent.UserTranscript(utterance.id, trimmed, true))
                }
            }
            if (timing.liveDelegationSettle > Duration.ZERO) pendingDelegationTasks.keys.toList().forEach { scheduleDelegation(it) }
        }
        is LiveServerEvent.OutputTranscriptDelta -> {
            // Speech the user talked over isn't heard, so it isn't shown either.
            if (event.delta.isEmpty() || droppingAssistantSpeech) return
            noteAssistantSpeech(assistantAudioPlaying)
            val previousEnd = currentAssistantUtterance?.endMilliseconds
            if (previousEnd != null && event.startMilliseconds != null && event.startMilliseconds - previousEnd >= 1_200) settleAssistantUtterance()
            if (currentAssistantUtterance == null) {
                if (currentUserUtterance?.delegated == true) currentUserUtterance = null
                currentAssistantUtterance = Utterance("live-a-${nextAssistantUtteranceNumber++}", "", event.startMilliseconds, event.endMilliseconds, userWordsBefore = lastUserWordsAt)
            }
            currentAssistantUtterance = currentAssistantUtterance?.let { it.copy(rawText = it.rawText + event.delta, endMilliseconds = event.endMilliseconds ?: it.endMilliseconds) }
            currentAssistantUtterance?.let { emit(VoiceCallEvent.AssistantDelta(it.id, event.delta)); acknowledgementProgressed(it) }
            scheduleAssistantSettle()
        }
        is LiveServerEvent.OutputAudio -> {
            if (event.pcm.size < 2 || audioInterrupted) return
            val isSpeech = PCM.hasSpeechEnergy(event.pcm)
            if (droppingAssistantSpeech) {
                // GPT-Live talks on after the user cut in: drop the rest of it, and
                // play again once its stream goes quiet.
                if (isSpeech) {
                    droppedQuietMilliseconds = 0
                } else {
                    droppedQuietMilliseconds += maxOf(1, event.pcm.size / 2 * 1_000 / PCM.sampleRate.toInt())
                    if (droppedQuietMilliseconds >= LiveCallCoordinator.droppedSpeechEndsAfterMilliseconds) droppingAssistantSpeech = false
                }
                return
            }
            if (isSpeech) {
                speechAudioHangover = LiveCallCoordinator.speechHangoverMilliseconds
                assistantAudioPlaying = true; noteAssistantSpeech(true, frame = true)
                audio?.enqueue?.invoke(event.pcm, currentAssistantUtterance?.id)
            } else if (speechAudioHangover > 0) {
                speechAudioHangover -= maxOf(1, event.pcm.size / 2 * 1_000 / PCM.sampleRate.toInt())
                audio?.enqueue?.invoke(event.pcm, currentAssistantUtterance?.id)
            }
        }
        is LiveServerEvent.DelegationCreated -> {
            val isServer = event.target == "responses" && delegatesToResponses
            if (event.id.isEmpty() || (event.target != "client" && event.target.isNotEmpty() && !isServer) || !seenDelegationIDs.add(event.id)) return
            if (isServer) { serverDelegationIDs += event.id; serverDelegations[event.id] = LiveCallCoordinator.ServerDelegation(socket) }
            lastDelegationCreatedAt = clock.now(); unbackedClaimTask?.cancel(); unbackedClaimTask = null
            delegationOffsets[event.id] = event.offsetMilliseconds
            if (timing.liveDelegationSettle > Duration.ZERO) scheduleDelegation(event.id) else delegationSettled(event.id)
        }
        is LiveServerEvent.SessionClosed -> when (event.reason) {
            LiveCloseReason.CloseRequested -> finish(null)
            LiveCloseReason.Expired -> socketLost(socket, VoiceSessionLoss.Expired(event.reason.rawValue))
            LiveCloseReason.Content -> {
                emit(VoiceCallEvent.ServiceError("A safety filter ended the voice call."))
                finish(RealtimeClientError.Closed("A safety filter ended the voice call."))
            }
            else -> socketLost(socket, VoiceSessionLoss.Closed(RealtimeClientError.Closed("Session closed (${event.reason.rawValue})")))
        }
        is LiveServerEvent.Error -> {
            val error = event.error
            if (delegatesToResponses && error.code == "responses_validation_failed") {
                voiceLog.error("GPT-Live refused the Responses delegation settings")
                callLogMarker("The voice service refused the delegator’s settings: ${error.message}")
                return
            }
            when (error.disposition) {
                RealtimeServerError.Disposition.harmless -> {
                    if (error.cutsOffOutputAudio && (assistantAudioPlaying || currentAssistantUtterance != null)) {
                        settleAssistantUtterance(cutOff = true); assistantAudioPlaying = false; noteAssistantSpeech(false)
                        speechAudioHangover = 0; audio?.flush?.invoke(); emit(VoiceCallEvent.AssistantPlaybackFinished)
                    }
                    voiceLog.debug("GPT-Live error ignored: ${error.code ?: "no code"}")
                }
                RealtimeServerError.Disposition.sessionExpired -> socketLost(socket, VoiceSessionLoss.Expired(error.code ?: error.message))
                RealtimeServerError.Disposition.fatal -> { emit(VoiceCallEvent.ServiceError(error.message)); finish(RealtimeClientError.Closed(error.message)) }
            }
        }
        is LiveServerEvent.ResponseEvent -> handleResponseEvent(event.delegationID, event.event)
        is LiveServerEvent.LinkQuality -> setLinkPoor(event.poor)
        is LiveServerEvent.InputAudioMuted, is LiveServerEvent.InputAudioUnmuted, is LiveServerEvent.ContextAppended,
        is LiveServerEvent.UsageUpdated, LiveServerEvent.Ignored -> Unit
    }
}
private fun LiveCallCoordinator.scheduleDelegation(id: String) {
    pendingDelegationTasks[id]?.cancel()
    pendingDelegationTasks[id] = scope.launch { clock.sleep(timing.liveDelegationSettle); if (!finished) delegationSettled(id) }
}
private fun LiveCallCoordinator.delegationSettled(id: String) {
    pendingDelegationTasks.remove(id)
    val offset = delegationOffsets.remove(id)
    if (finished) return
    currentUserUtterance = currentUserUtterance?.copy(delegated = true)
    // The request's words end here: later ones may be the user asking again.
    val now = clock.now(); lastDelegatedAt = now
    startProgressLine(id, now)
    flushAcknowledgements()
    if (id in serverDelegationIDs) {
        serverDelegationSettledAt[id] = now
        emit(VoiceCallEvent.ServerDelegation(id)); return
    }
    if (timing.liveAcknowledgementWait <= Duration.ZERO) { emitDelegation(id, null, settledAt = now); return }
    val acknowledgement = LiveCallCoordinator.Acknowledgement(offset, settledAt = now)
    acknowledgement.deadline = scope.launch {
        clock.sleep(timing.liveAcknowledgementWait)
        if (!finished && id in acknowledgements) emitDelegation(id, null)
    }
    acknowledgements[id] = acknowledgement
    currentAssistantUtterance?.let { acknowledgementProgressed(it) }
}
private fun LiveCallCoordinator.acknowledgementProgressed(utterance: Utterance, settled: Boolean = false) {
    for ((id, acknowledgement) in acknowledgements.toMap()) {
        if (acknowledgement.utteranceID == null) {
            val start = utterance.startMilliseconds ?: continue
            if (start < (acknowledgement.offset ?: Int.MIN_VALUE)) continue
            acknowledgement.utteranceID = utterance.id
        } else if (acknowledgement.utteranceID != utterance.id) continue
        val text = utterance.rawText.trim()
        if (settled || text.lastOrNull()?.let { it in ".!?…" } == true) emitDelegation(id, text)
    }
}
internal fun LiveCallCoordinator.flushAcknowledgements() { acknowledgements.keys.toList().forEach { emitDelegation(it, null) } }
private fun LiveCallCoordinator.emitDelegation(id: String, acknowledgement: String?, settledAt: Duration? = null) {
    val previous = acknowledgements.remove(id)
    previous?.deadline?.cancel()
    if (finished) return
    delegations[id] = LiveCallCoordinator.DelegationRecord(socketID = active?.id, claim = null, delegatedAt = previous?.settledAt ?: settledAt)
    val arguments = if (acknowledgement.isNullOrEmpty()) RealtimeJSONs.obj() else RealtimeJSONs.obj("text" to RealtimeJSONs.string(acknowledgement))
    emit(VoiceCallEvent.FunctionCall(id, "delegate", arguments))
}
private fun LiveCallCoordinator.scheduleAssistantSettle() {
    assistantSettleTask?.cancel()
    assistantSettleTask = scope.launch {
        clock.sleep(timing.liveTranscriptSettle)
        if (!finished && !assistantAudioPlaying && currentAssistantUtterance != null) {
            // Settling cancels this timer; clear its identity before any suspend.
            assistantSettleTask = null
            settleAssistantUtterance(); emit(VoiceCallEvent.AssistantPlaybackFinished); swapToStandby()
        }
    }
}
internal fun LiveCallCoordinator.settleAssistantUtterance(cutOff: Boolean = false) {
    assistantSettleTask?.cancel(); assistantSettleTask = null
    val utterance = currentAssistantUtterance ?: return
    currentAssistantUtterance = null
    acknowledgementProgressed(utterance, settled = true)
    val text = utterance.rawText.trim()
    if (text.isEmpty()) return
    history = history.upsert(utterance.id, VoiceTranscriptTail.Role.assistant, text, cutOff)
    emit(VoiceCallEvent.AssistantDone(utterance.id, text))
    watchForUnbackedClaim(text, utterance.userWordsBefore)
    if (!cutOff) watchForUnbackedOutcome(utterance, text)
}
private fun LiveCallCoordinator.userAsked(before: Duration?): Boolean {
    if (before == null) return false
    val last = lastDelegationCreatedAt ?: return true
    return before > last + timing.liveAnswerPressedAfter
}
private fun LiveCallCoordinator.watchForUnbackedClaim(text: String, userWordsBefore: Duration?) {
    if (timing.liveUnbackedClaimWait <= Duration.ZERO || finished || pendingDelegationTasks.isNotEmpty() || acknowledgements.isNotEmpty() ||
        !userAsked(userWordsBefore) || !VoiceActionClaim.isClaim(text)) return
    unbackedClaimTask?.cancel()
    unbackedClaimTask = scope.launch {
        clock.sleep(timing.liveUnbackedClaimWait); unbackedClaimTask = null
        if (finished || pendingDelegationTasks.isNotEmpty() || acknowledgements.isNotEmpty() || !userAsked(userWordsBefore)) return@launch
        val id = "client_claim_${nextClientDelegationNumber++}"
        val now = clock.now(); lastDelegationCreatedAt = now; lastDelegatedAt = now
        currentUserUtterance = currentUserUtterance?.copy(delegated = true)
        delegations[id] = LiveCallCoordinator.DelegationRecord(socketID = null, claim = text, delegatedAt = now)
        startProgressLine(id, now)
        callLogMarker("The voice agent said “$text” without delegating; delegated the request for it")
        emit(VoiceCallEvent.FunctionCall(id, "delegate", RealtimeJSONs.obj("text" to RealtimeJSONs.string(text))))
    }
}
/** A finished reply that states an outcome ("Done, it's sent.") with no
 * result behind it: unless the user speaks first, the call verifies it
 * silently and speaks only a correction (`VoiceActionClaim.verifyPrompt`). */
private fun LiveCallCoordinator.watchForUnbackedOutcome(utterance: Utterance, text: String) {
    if (timing.liveUnbackedClaimWait <= Duration.ZERO || finished || lastUserWordsAt != utterance.userWordsBefore ||
        !VoiceActionClaim.isOutcomeClaim(text) || !verifiedUtteranceIDs.add(utterance.id)) return
    unbackedOutcomeTask?.cancel()
    unbackedOutcomeTask = scope.launch {
        clock.sleep(timing.liveUnbackedClaimWait)
        unbackedOutcomeTimedOut(text, utterance.userWordsBefore)
    }
}
private fun LiveCallCoordinator.unbackedOutcomeTimedOut(claim: String, userWordsBefore: Duration?) {
    unbackedOutcomeTask = null
    if (finished || lastUserWordsAt != userWordsBefore) return
    val id = "client_verify_${nextClientDelegationNumber++}"
    delegations[id] = LiveCallCoordinator.DelegationRecord(socketID = null, claim = null, delegatedAt = clock.now())
    callLogMarker("The voice agent claimed an outcome (“$claim”); verifying silently")
    val question = if (userWordsBefore == null) null else history.turns.lastOrNull { it.role == VoiceTranscriptTail.Role.user }?.text
    val prompt = VoiceActionClaim.verifyPrompt(question, claim)
    emit(VoiceCallEvent.FunctionCall(id, "delegate", RealtimeJSONs.obj("text" to RealtimeJSONs.string(prompt))))
}
/**
 * One capture chunk's level. Transcript fragments arrive in bursts, a second or more apart while
 * the user talks on, so the microphone tells the quiet gate they're still talking. It counts only
 * while their words were transcribed recently, so a noise with no words holds nothing.
 */
internal suspend fun LiveCallCoordinator.noteMicLevel(level: Float) {
    val now = clock.now()
    if (level < timing.liveMicVoiceLevel) {
        val last = micVoiceLastAt
        if (last != null && now - last > LiveCallCoordinator.micVoiceGapTolerance) micVoiceStartedAt = null
        return
    }
    val last = micVoiceLastAt
    if (micVoiceStartedAt == null || last == null || now - last > LiveCallCoordinator.micVoiceGapTolerance) {
        micVoiceStartedAt = now
    }
    micVoiceLastAt = now
    val words = lastUserWordsAt
    if (words != null && now - words <= timing.liveMicVoiceWordWindow) {
        quietGate = quietGate.noteUserVoice(now)
    }
    yieldIfUserTalksOver(now)
}

/**
 * The user has been talking over the voice agent for `liveBargeIn`, on
 * the microphone, with words transcribed since its speech began: its
 * audio stops. GPT-Live doesn't always stop on its own.
 */
internal suspend fun LiveCallCoordinator.yieldIfUserTalksOver(now: Duration) {
    if (timing.liveBargeIn <= Duration.ZERO || !assistantAudioPlaying || droppingAssistantSpeech) return
    val started = micVoiceStartedAt ?: return
    val last = micVoiceLastAt ?: return
    if (now - last > LiveCallCoordinator.micVoiceGapTolerance || now - started < timing.liveBargeIn) return
    val words = lastUserWordsAt ?: return
    val speech = quietGate.assistantSpeechRunStartedAt ?: return
    if (words < speech) return
    droppingAssistantSpeech = true
    droppedQuietMilliseconds = 0
    settleAssistantUtterance(cutOff = true)
    assistantAudioPlaying = false
    noteAssistantSpeech(false)
    speechAudioHangover = 0
    audio?.flush?.invoke()
    emit(VoiceCallEvent.AssistantPlaybackFinished)
    callLogMarker("Stopped the voice agent: the user talked over it")
}

internal suspend fun LiveCallCoordinator.handleLiveAudio(event: VoiceAudioEvent) {
    when (event) {
        VoiceAudioEvent.playbackDrained -> {
            assistantAudioPlaying = false; noteAssistantSpeech(false); speechAudioHangover = 0
            if (currentAssistantUtterance != null) scheduleAssistantSettle()
            else { emit(VoiceCallEvent.AssistantPlaybackFinished); swapToStandby() }
        }
        VoiceAudioEvent.interrupted -> {
            audioInterrupted = true; assistantAudioPlaying = false; noteAssistantSpeech(false); speechAudioHangover = 0
            settleAssistantUtterance(cutOff = true); emit(VoiceCallEvent.AudioInterrupted)
        }
        VoiceAudioEvent.resumed -> { audioInterrupted = false; emit(VoiceCallEvent.AudioResumed); swapToStandby() }
        VoiceAudioEvent.routeChanged -> emit(VoiceCallEvent.RouteChanged)
        VoiceAudioEvent.mediaServicesReset -> { emit(VoiceCallEvent.ServiceError("Audio was reset by the system. Start the call again.")); finish(null) }
    }
}
