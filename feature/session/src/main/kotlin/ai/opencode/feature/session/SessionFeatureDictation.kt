package ai.opencode.feature.session

import ai.opencode.feature.composer.DraftState
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.map
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DictationHoldRelease
import ai.opencode.server.model.OpenCodeDelivery

// The mic and dictation. `:feature:composer`'s `DictationFeature` owns the recording's state; this
// file is the session's glue.

/** The mic and dictation. Null: the action passes on. */
internal fun SessionFeature.reduceDictation(state: State, action: Action): Reduced<State, Action>? {
    if (action == Action.SceneEnteredBackground) return sceneEnteredBackground(state)
    if (action is Action.DeferredSendReady) return sendComposer(action.delivery, isDeferred = true, state = state)
    val dictationAction = (action as? Action.Dictation)?.action ?: return null
    return when (dictationAction) {
        DictationFeature.Action.MicTapped -> {
            if (state.isDictating) {
                haptics.play(Haptic.tap)
                finishDictation(state, send = null)
            } else if (state.isFinishingDictation || state.callOwnsAudio) {
                // A tap while the engine is still finalizing is almost always a double-tap on stop;
                // restarting would race the pending teardown. A call owns the audio session while it lasts.
                Reduced(state)
            } else {
                haptics.play(Haptic.tap)
                startDictation(state)
            }
        }

        DictationFeature.Action.MicPressBegan -> micPressBegan(state)

        DictationFeature.Action.MicPressReleased -> micPressReleased(state)

        DictationFeature.Action.MicPressCancelled -> {
            if (state.dictationHold == null || !(state.isDictating || state.isFinishingDictation)) {
                Reduced(state)
            } else {
                haptics.play(Haptic.cancel)
                // The draft was never touched, so stopping is the whole cancel.
                stopDictation(state.withDictationHold(null))
            }
        }

        is DictationFeature.Action.MicPressArmed,
        DictationFeature.Action.MicPressInterrupted,
        DictationFeature.Action.MicPressLocked,
        DictationFeature.Action.InsertTapped,
        DictationFeature.Action.StopRequested,
        DictationFeature.Action.SendTapped,
        DictationFeature.Action.QueueTapped,
        DictationFeature.Action.Ready,
        -> Reduced(state)

        is DictationFeature.Action.Updated -> {
            if (dictationAction.recording != state.dictationRecording || !state.hasLiveDictation) {
                Reduced(state)
            } else {
                var next = state.showDictation(dictationAction.transcript)
                // A held mic's words stay in its bar until it stops, so a cancel leaves the answer
                // exactly as it was.
                if (next.dictationHold == null) next = next.applyBarEdit()
                Reduced(next)
            }
        }

        is DictationFeature.Action.Stopped ->
            if (dictationAction.recording != state.dictationRecording) {
                sendingDictationStopped(dictationAction.errorMessage, dictationAction.recording, state)
            } else {
                dictationStopped(dictationAction.errorMessage, state)
            }
    }
}

private fun SessionFeature.micPressBegan(state: State): Reduced<State, Action> {
    // A call owns the audio session while it lasts.
    if (state.isDictating || state.callOwnsAudio) return Reduced(state)
    var next = state
    val phase = next.dictationPhase
    if (phase is DictationFeature.Phase.Finishing) {
        // Let go to send and pressed again before its last words landed: that message finalizes
        // and goes out on its own, with its draft, and this press records the next one into an
        // empty composer.
        val send = phase.send
        val hold = next.dictationHold
        if (send == null || hold == null || next.sendingDictation != null || next.isAnsweringRequest ||
            next.pendingFiles.any { it.isUploading }
        ) {
            return Reduced(next)
        }
        val draft = next.draftState.copy(text = hold.text)
        next = next.withSendingDictation(
            DictationFeature.Sending(
                recording = next.dictationRecording,
                draft = draft,
                delivery = send,
                base = next.dictationBase,
                sessionID = next.session?.id,
            ),
        )
        next = next.withDictationRecording(next.dictationRecording + 1).withDraftState(DraftState())
    }
    haptics.play(Haptic.tap)
    val start = startDictation(next)
    return Reduced(start.state.withDictationHold(DictationFeature.Hold(text = start.state.draft)), start.effect)
}

private fun SessionFeature.micPressReleased(state: State): Reduced<State, Action> {
    val hold = state.dictationHold
    if (hold == null || hold.isLocked) return Reduced(state)
    if (!state.isSceneActive) {
        // The app is leaving (or the notification shade came down) and the system ended the touch:
        // that's not letting go to send. Recording carries on hands-free, and the bar waits for
        // Send when the user is back.
        return Reduced(state.withDictationHold(hold.copy(isLocked = true, armed = null)))
    }
    when (hold.armed) {
        DictationFeature.Hold.Arm.Lock -> {
            // Stays armed: the bar knows it was slid open, not tapped.
            haptics.play(Haptic.selection)
            return Reduced(state.withDictationHold(hold.copy(isLocked = true)))
        }
        DictationFeature.Hold.Arm.Cancel -> return Reduced(state, Effect.send(Action.micPressCancelled))
        null -> Unit
    }
    if (state.isPreparingDictation) {
        // Let go before the mic was ready: the permission prompt took the touch, or setup was slow.
        // Stopping now would abort setup, so recording goes on hands-free, as after a tap.
        return Reduced(state.withDictationHold(hold.copy(isLocked = true)))
    }
    if (state.dictationPhase != DictationFeature.Phase.Recording) return Reduced(state)
    haptics.play(Haptic.tap)
    return finishDictation(state, send = OpenCodeDelivery.Steer)
}

private fun SessionFeature.sceneEnteredBackground(state: State): Reduced<State, Action> {
    var next = state
    if (next.hasRenderedFirstContentFrame) next = next.noteLeaving()
    // Stop's request carries on under a background task: people tap Stop and lock the phone. Reads
    // stop here and run again on the return.
    next = next.copy(isBackgrounded = true)
    val quiet = quietServerPolling(next)
    next = quiet.state
    val stopsReads = Effect.merge(
        Effect.cancel(
            listOf(
                CancelID.InterruptSettle(next.runWorkGeneration),
                CancelID.RunEndCheck(next.runWorkGeneration),
            ),
        ),
        quiet.effect,
    )
    // (Send Now's wake read comes back with the return's run-state read.) Background audio keeps
    // the mic and the speech model running, so leaving changes nothing: the words keep collecting
    // and the bar is as it was on return. Only a send can't happen from here: if one was still
    // waiting on its last words, they land in the draft.
    next = next.updateDictation { it.enterBackground() }
    return Reduced(next, Effect.merge(stopsReads, syncAndFlushPersistedComposer(next)))
}

/** A recording let go to send while a newer one records: its last words landed. */
private fun SessionFeature.sendingDictationStopped(errorMessage: String?, recording: Int, state: State): Reduced<State, Action> {
    val sending = state.sendingDictation
    if (sending == null || sending.recording != recording) return Reduced(state)
    var next = state.withSendingDictation(null)
    if (next.isVoiceAvailable) next = next.withDictationEndedAt(date.now())
    if (errorMessage != null) {
        haptics.play(Haptic.error)
        return Reduced(next)
    }
    // Sent only into the session it was sent in, while the user is here to see it; otherwise its
    // words wait as a draft in that session. A request holding the composer can't take a message:
    // it waits as one.
    if (sending.sessionID != next.session?.id || !next.isSceneActive || sending.keepsAsDraft || next.isAnsweringRequest) {
        return keepUnsent(sending, next)
    }
    // Sent through the composer's own path, with the newer recording's composer set aside and left running.
    val composer = next.draftState
    val sent = sendComposer(sending.delivery, isDeferred = true, keepsDictation = true, state = next.withDraftState(sending.draft))
    return Reduced(sent.state.withDraftState(composer), sent.effect)
}

/** The live recording ended: its words land, and a send waiting on them goes. */
private fun SessionFeature.dictationStopped(errorMessage: String?, state: State): Reduced<State, Action> {
    if (!(state.isDictating || state.isFinishingDictation)) return Reduced(state)
    val phase = state.dictationPhase
    val delivery = (phase as? DictationFeature.Phase.Finishing)?.send
    var next = state.withDictationError(errorMessage)
    if (errorMessage != null) haptics.play(Haptic.error)
    // A held mic's words reach the draft only now: sent on success, kept for editing if the engine failed.
    val hold = next.dictationHold
    if (hold != null) next = next.withDictationHold(null).withDraft(hold.text)
    next = next.applyBarEdit()
    val stop = stopDictation(next)
    next = stop.state
    val hasPrompt = next.hasPromptContent || next.isAnsweringRequest
    // Never sent from the background: the words wait in the draft.
    if (delivery == null || errorMessage != null || !hasPrompt || !next.isSceneActive) return stop
    // Bound to the request it answered: if that one's gone by then, nothing is sent (never a
    // message draft that came back meanwhile).
    val requestID = next.dockRequestID
    if (next.isAnsweringRequest && requestID != null) {
        return Reduced(next, Effect.merge(stop.effect, Effect.send(Action.requestBarSent(requestID))))
    }
    next = next.withDictationSendPending(true)
    return Reduced(next, Effect.merge(stop.effect, Effect.send(Action.DeferredSendReady(delivery))))
}

private fun SessionFeature.startDictation(state: State): Reduced<State, Action> {
    val started = DictationFeature.start(state.dictation, draft = state.draft, dictation = dictation)
    return Reduced(state.copy(dictation = started.state), started.effect.map(Action::Dictation))
}

/**
 * Discards dictation. Tears down the mic at once, drops audio not yet transcribed, and cancels the
 * transcript stream so nothing else lands in the draft. For cancels, sends and manual edits, where
 * a late result would clobber what the user committed to. Safe to call unconditionally: a no-op
 * unless dictation is active.
 */
internal fun SessionFeature.stopDictation(state: State): Reduced<State, Action> {
    val endedAt = if (state.dictationPhase != DictationFeature.Phase.Idle && state.isVoiceAvailable) date.now() else null
    val stopped = DictationFeature.stop(state.dictation, endedAt = endedAt, dictation = dictation)
    return Reduced(state.copy(dictation = stopped.state), stopped.effect.map(Action::Dictation))
}

/**
 * Something else takes the mic (a call). Stops at once, keeping the words heard so far in the
 * draft, as an interruption does. Finishes once the recording is discarded and dictation has let go
 * of the audio session.
 */
internal fun SessionFeature.yieldDictation(state: State): Reduced<State, Action> {
    val hold = state.dictationHold
    val kept = if (hold != null) state.withDraft(hold.text) else state
    val stop = stopDictation(kept)
    return Reduced(stop.state, Effect.concatenate(stop.effect, releaseDictationHold(DictationHoldRelease.Call)))
}

/**
 * Ends a dictation audio session still held ready after a recording, if [reason] applies to it; a
 * recording still running or finalizing isn't held after it.
 */
internal fun SessionFeature.releaseDictationHold(reason: DictationHoldRelease): Effect<Action> {
    val dictation = dictation
    return Effect.run { dictation.releaseHold(reason) }
}

/**
 * Finishes dictation intentionally (mic tap). Stops capture but keeps the transcript stream alive
 * so the engine's finalized text — often the only accurate result — still lands in the draft before
 * the stream ends.
 */
private fun SessionFeature.finishDictation(state: State, send: OpenCodeDelivery?): Reduced<State, Action> {
    val finished = DictationFeature.finish(state.dictation, send = send, dictation = dictation)
    return Reduced(state.copy(dictation = finished.state), finished.effect.map(Action::Dictation))
}
