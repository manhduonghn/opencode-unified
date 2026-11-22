package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.sound.SoundEffect
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.learningFirstPrompt
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.RetrySchedule
import ai.opencode.server.retry.retryVerdict
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Stop, session creation, and prompt delivery. Null: the action passes on. */
internal fun SessionFeature.reduceRunLifecycle(
    state: SessionFeature.State,
    action: Action,
): Reduced<SessionFeature.State, Action>? = when (action) {
    is Action.InterruptTapped -> interruptTapped(state)
    is Action.InterruptAcknowledged -> interruptAcknowledged(state, action)
    is Action.InterruptResumed -> {
        if (state.session?.id != action.sessionID || action.generation != state.runWorkGeneration || !state.isInterrupting) {
            Reduced(state)
        } else {
            val s = state.supersedeRunWork()
            // The stream missed the stop: read the stopped turn and the inbox.
            Reduced(
                s,
                Effect.merge(
                    if (s.isExternallyActive) Effect.send(Action.RefreshRequested(RefreshReason.RunState))
                    else Effect.send(Action.ExternalRunStateChanged(isRunning = true)),
                    fetchQueuedInputs(server = s.server, sessionID = action.sessionID),
                ),
            )
        }
    }
    is Action.InterruptFailed -> {
        if (state.session?.id != action.sessionID || action.generation != state.runWorkGeneration || !state.isInterrupting) {
            Reduced(state)
        } else {
            // Stop comes back for another try.
            haptics.play(Haptic.error)
            Reduced(appendInfo(state.copy(isInterrupting = false, stopAttempt = null), SessionFeature.stopFailedText))
        }
    }
    is Action.SessionCreated -> sessionCreated(state, action)
    is Action.PromptFailed -> promptFailed(state, action)
    is Action.PromptAdmitted -> promptAdmitted(state, action)
    is Action.ReplyCompleted ->
        // A successful idle wait is the same boundary as the SSE execution ending. Serialize its
        // final read behind any older refresh too.
        if (state.session?.id != action.sessionID || !state.isRunning) Reduced(state)
        else Reduced(state, Effect.send(Action.ExternalRunStateChanged(isRunning = false)))
    else -> null
}

private fun SessionFeature.interruptTapped(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    if (!state.isRunning || state.isInterrupting) return Reduced(state)
    // A draft has nothing running yet: Stop takes back what's on its way.
    val session = state.session ?: return stopDraft(state)
    // Stopping… shows at once; the run's end (or the next run, taking the steering messages)
    // clears it. A fresh generation names this Stop: nothing it does may touch a run that starts after it.
    var s = state.supersedeRunWork().copy(isInterrupting = true)
    haptics.play(Haptic.cancel)
    // Nothing still in the outbox is posted after Stop unless retried.
    s = holdStoppedSends(
        promptOutbox.stop(PromptOutbox.Lane.Session(session.id), serverKey = s.server.persistenceID),
        sessionID = session.id,
        state = s,
    )
    // Only steering messages the server holds: one still on its way isn't in the inbox, which
    // would read as a next run having taken it.
    val steering = s.queuedInputs.filter {
        it.delivery == OpenCodeDelivery.Steer && it.notice == null && !it.isCompaction &&
            s.promptStates[it.id]?.admission != SessionFeature.State.PromptState.Admission.sent
    }.mapTo(HashSet()) { it.id }
    s = s.copy(stopAttempt = SessionFeature.State.StopAttempt(steering = steering))
    return Reduced(s, interrupt(session.id, steering = steering, generation = s.runWorkGeneration, server = s.server))
}

private fun SessionFeature.interruptAcknowledged(
    state: SessionFeature.State,
    action: Action.InterruptAcknowledged,
): Reduced<SessionFeature.State, Action> {
    if (state.session?.id != action.sessionID || action.generation != state.runWorkGeneration || !state.isInterrupting) return Reduced(state)
    // Taken (or nothing was running when it landed, with the stream still saying it runs): reads
    // follow until the run ends, in the foreground.
    if (action.interrupted || state.isExternallyActive) {
        val s = state.copy(stopAttempt = state.stopAttempt?.copy(acknowledged = true))
        return Reduced(s, if (s.isBackgrounded) Effect.none() else settleStop(s, readingAtOnce = !action.interrupted))
    }
    // The server had nothing running (only this phone's sends made it look busy), so no run will
    // end to clear Stop.
    var s = state.supersedeRunWork()
    if (!s.hasSteerOnItsWay) s = s.copy(isAwaitingReply = false)
    // A steering message from here may hold Working; the server's word lets it go if no run takes it.
    return Reduced(
        s,
        if (s.isAwaitingReply && !s.isSubmittingPrompt && !s.isBackgrounded) confirmRunState(s, after = SessionFeature.runEndCheckDelay)
        else Effect.none(),
    )
}

private fun SessionFeature.sessionCreated(
    state: SessionFeature.State,
    action: Action.SessionCreated,
): Reduced<SessionFeature.State, Action> {
    // Heard once; a draft's Stop may already have moved here.
    if (state.session?.id == action.session.id) return Reduced(state)
    // A draft's first prompt names its session until the server does.
    val session = action.session.learningFirstPrompt(
        if (state.session == null) SessionTitle.firstPrompt(state.items, reachesStart = state.earlierHistory == SessionFeature.State.EarlierHistory.Exhausted) else null,
    )
    val draftID = state.outboxDraftID
    var s = state
    if (s.session == null) s = s.copy(draftBecameSessionID = session.id, initialTranscriptIsFresh = true)
    s = s.copy(outboxDraftID = null, session = session, familySessions = listOf(session), familyRootID = session.id)
    composerPersistence.promoteDraftOutboxToSession(s.server.persistenceID, draftID, session.id)
    // A session has its project: a Project sheet still up goes, and its folders with it once it
    // has slid away.
    s = if (s.activePicker == SessionFeature.State.ActivePicker.project) s.copy(activePicker = null) else s.copy(folderBrowser = null)
    // The draft's model waits until a send the server admits carries it: its first send may have
    // failed after creating the session, or another model was picked while it was on its way.
    s = s.copy(pendingModel = s.currentModel)
    s = adoptDraftAgent(session.id, s)
    // A message that failed before the session existed now belongs to it.
    for (failed in s.failedSends) {
        undeliveredPrompts.keep(failed.pending, reason = failed.reason, serverKey = s.server.persistenceID, sessionID = session.id)
    }
    // A call on the draft moves onto its session, as if it started there.
    val call: Effect<Action> = if (s.voice?.isOnDraft == true) Effect.send(Action.voicePresented(VoiceFeature.Action.DraftSessionCreated(session))) else Effect.none()
    val pending = startPendingWork(s, attempt = RequestDockFeature.State.LoadAttempt.Initial)
    return Reduced(pending.state, Effect.merge(pending.effect, call))
}

private fun SessionFeature.promptFailed(
    state: SessionFeature.State,
    action: Action.PromptFailed,
): Reduced<SessionFeature.State, Action> {
    if (!SessionFeature.ownsDelivery(sessionID = action.sessionID, messageID = action.pending.messageID, state = state)) return Reduced(state)
    // Its claim lapses with it; Retry claims afresh.
    var s = releaseAutoBackgroundClaim(action.pending.messageID, state)
    // A retry keeps the mode it was last given (Move to Queue, Send Now).
    var pending = action.pending
    s.deliveryOverrides[pending.messageID]?.let { pending = pending.copy(delivery = it) }
    s = s.copy(sentDeliveries = s.sentDeliveries - pending.messageID, deliveryOverrides = s.deliveryOverrides - pending.messageID)
    if (pending.messageID in s.cancelledInputIDs) {
        // Deleted while it was being sent: nothing to retry, and nothing behind it waits for it.
        s = s.copy(promptStates = s.promptStates - pending.messageID)
        undeliveredPrompts.resolve(setOf(pending.messageID))
        promptOutbox.withdraw(pending.messageID)
        if (!s.hasPendingLocalPrompt) s = s.copy(isAwaitingReply = false)
        return Reduced(s, publishCache(s))
    }
    s = markFailed(pending, reason = action.error.message, state = s)
    // Sends behind it wait for Retry or Delete, so nothing is working.
    if (!s.hasMovingLocalPrompt) s = s.copy(isAwaitingReply = false)
    haptics.play(Haptic.error)
    if (!s.callOwnsAudio && sceneWindow.isFront && experimentalFeatures(ExperimentalFeature.soundEffects)) {
        soundEffects.play(SoundEffect.error)
    }
    return Reduced(s, publishCache(s))
}

private fun SessionFeature.promptAdmitted(
    state: SessionFeature.State,
    action: Action.PromptAdmitted,
): Reduced<SessionFeature.State, Action> {
    val sessionID = action.sessionID
    val messageID = action.messageID
    if (!SessionFeature.ownsDelivery(sessionID = sessionID, messageID = messageID, state = state)) return Reduced(state)
    val promptState = state.promptStates[messageID]
    if (promptState?.requestEpoch != action.requestEpoch) return Reduced(state)
    ai.opencode.core.diagnostics.LabMarks.mark("prompt_admitted", sessionID = sessionID)
    val sent = state.sentDeliveries[messageID]
    var s = state.copy(
        promptStates = state.promptStates + (messageID to promptState.copy(admission = SessionFeature.State.PromptState.Admission.admitted)),
        sentDeliveries = state.sentDeliveries - messageID,
    )
    // Changes made while the send was in flight reach the server now.
    if (messageID in s.cancelledInputIDs) {
        s = s.copy(promptStates = s.promptStates - messageID)
        if (!s.isExternallyActive && !s.hasPendingLocalPrompt) s = s.copy(isAwaitingReply = false)
        return Reduced(s, cancelOnServer(messageID, sessionID = sessionID, server = s.server))
    }
    val wanted = s.deliveryOverrides[messageID]
    if (sent != null && wanted != null && wanted != sent) {
        // Switched to steering too late for the outbox: the claim moves the work here.
        var backgroundKey: String? = null
        if (wanted == OpenCodeDelivery.Steer) {
            backgroundKey = s.autoBackgroundClaims[messageID]
            s = s.copy(autoBackgroundClaims = s.autoBackgroundClaims - messageID)
        }
        return Reduced(
            s,
            changeDeliveryOnServer(messageID, delivery = wanted, previous = sent, sessionID = sessionID, backgroundKey = backgroundKey, server = s.server),
        )
    }
    // A queued send that landed after the run ended waits parked: it may hold Working only until
    // the server says nothing runs.
    if (sent == OpenCodeDelivery.Queue && !s.isExternallyActive && s.isAwaitingReply && !s.isSubmittingPrompt) {
        return Reduced(s, confirmRunState(s, after = SessionFeature.runEndCheckDelay))
    }
    return Reduced(s)
}

/**
 * A send that failed in a way that may pass keeps trying on its own (`RetryPolicy.send`: five
 * minutes of failures, at most 15 s apart, the window starting over when the app comes back from
 * the background); after that, it's offered for Retry.
 */
internal val SessionFeature.Companion.sendRetryPolicy: RetryPolicy get() = RetryPolicy.send
internal val SessionFeature.Companion.stoppedReason: String get() = localized(R.string.session_stopped_before_sent)
internal val SessionFeature.Companion.stopFailedText: String get() = localized(R.string.session_stop_failed)

/** Once the server has taken Stop, the session's state is read at these gaps in case the stream misses the run's end: about 15 s in all. */
internal val SessionFeature.Companion.interruptSettleDelays: List<Duration> get() = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds)

/** How long a run's end waits for the next run before asking the server. */
internal val SessionFeature.Companion.runEndCheckDelay: Duration get() = 1500.milliseconds

/**
 * What the server says a Stop came to: the run ended, or a next run took the steering messages
 * waiting at Stop; [Running] while the run goes on; null when the server couldn't be read.
 */
private sealed interface StopSettlement {
    data class Settled(val action: Action) : StopSettlement
    data object Running : StopSettlement
}

private suspend fun SessionFeature.stopSettlement(
    sessionID: String,
    steering: Set<String>,
    generation: Int,
    server: OpenCodeServer,
): StopSettlement? {
    val active = try { openCodeClient.isSessionActive(server, sessionID) } catch (_: OpenCodeClientError) { return null }
    if (!active) return StopSettlement.Settled(Action.RunStateRead(sessionID = sessionID, generation = generation, isActive = false))
    if (steering.isNotEmpty()) {
        val inbox = try { openCodeClient.pendingInputs(server, sessionID) } catch (_: OpenCodeClientError) { return null }
        val held = inbox.mapTo(HashSet()) { it.id }
        if (!held.containsAll(steering)) {
            return StopSettlement.Settled(Action.InterruptResumed(sessionID = sessionID, generation = generation))
        }
    }
    return StopSettlement.Running
}

/**
 * Stop's request. It owns its retries on the shared request policy (`RetryPolicy.request`, not a
 * send's longer window), with its client calls sent once inside, so they never nest. An interrupt
 * isn't safe to repeat blindly, so after a failure that may have reached the server it reads the
 * session first: a run that's over, or a next run that took the steering messages, means an
 * earlier try landed (its answer lost) and is never interrupted again; a read that fails waits for
 * the next retry instead of guessing. It holds a background task, so it carries on when the phone
 * is locked right after the tap. A newer generation (a new run, a send, a switch) cancels it.
 */
internal fun SessionFeature.interrupt(
    sessionID: String,
    steering: Set<String>,
    generation: Int,
    server: OpenCodeServer,
): Effect<Action> {
    val environment = networkPath.retryEnvironment
    val answered = networkPath.serverAnswered
    val clock = clock
    val backgroundTask = backgroundTask
    val client = openCodeClient
    return Effect.run<Action> { send ->
        val endBackgroundTask = backgroundTask.begin("Stop session")
        try {
            withRetryPolicy(RetryPolicy.once) {
                val schedule = RetrySchedule(RetryPolicy.request, clock, environment)
                // Waits out the pause before the next try; false to stop trying.
                suspend fun waitToRetry(error: OpenCodeClientError): Boolean {
                    val pause = schedule.next(error.retryVerdict(RequestIdempotency.Idempotent)) ?: return false
                    val contact = server.persistenceID
                    val wake: (suspend () -> Unit)? = if (error.transport != null) ({ answered(contact) }) else null
                    return pause.run(wake)
                }
                var interrupted: Boolean? = null
                var failure: OpenCodeClientError? = null
                // Once any try may have landed, every retry asks first.
                var mayHaveLanded = false
                attempts@ while (true) {
                    val error = failure
                    if (error != null) {
                        mayHaveLanded = mayHaveLanded || error.mayHaveReachedServer
                        if (!waitToRetry(error)) break@attempts
                        if (mayHaveLanded) {
                            when (val settlement = stopSettlement(sessionID, steering, generation, server)) {
                                is StopSettlement.Settled -> {
                                    send(settlement.action)
                                    return@withRetryPolicy
                                }
                                StopSettlement.Running -> Unit
                                null -> continue@attempts
                            }
                        }
                    }
                    try {
                        interrupted = client.interrupt(server, sessionID)
                        break@attempts
                    } catch (caught: OpenCodeClientError) {
                        failure = caught
                    }
                }
                if (!currentCoroutineContext().isActive) return@withRetryPolicy
                val result = interrupted
                if (result == null) {
                    send(Action.InterruptFailed(sessionID = sessionID, generation = generation))
                    return@withRetryPolicy
                }
                send(Action.InterruptAcknowledged(sessionID = sessionID, generation = generation, interrupted = result))
            }
        } finally {
            endBackgroundTask()
        }
    }.cancellable(CancelID.Interrupt(generation), cancelInFlight = true)
}

/**
 * Once the server has taken Stop, the session is read until the run ends, in case the stream
 * misses it; never in the background, where it's cancelled and starts again on the return.
 * [readingAtOnce]: the server said nothing ran, so the first read doesn't wait.
 */
internal fun SessionFeature.settleStop(state: SessionFeature.State, readingAtOnce: Boolean = false): Effect<Action> {
    val sessionID = state.session?.id ?: return Effect.none()
    val attempt = state.stopAttempt ?: return Effect.none()
    val server = state.server
    val generation = state.runWorkGeneration
    val clock = clock
    return Effect.run<Action> { send ->
        // The reads are this loop's retries: each is sent once.
        withRetryPolicy(RetryPolicy.once) {
            val delays = (if (readingAtOnce) listOf(Duration.ZERO) else emptyList()) + SessionFeature.interruptSettleDelays
            for (delay in delays) {
                if (delay > Duration.ZERO) clock.sleep(delay)
                val settlement = stopSettlement(sessionID, attempt.steering, generation, server)
                if (settlement is StopSettlement.Settled) {
                    send(settlement.action)
                    return@withRetryPolicy
                }
            }
            send(Action.InterruptFailed(sessionID = sessionID, generation = generation))
        }
    }.cancellable(CancelID.InterruptSettle(generation), cancelInFlight = true)
}

/** Reads whether the session runs after [after], for this generation. */
internal fun SessionFeature.confirmRunState(state: SessionFeature.State, after: Duration): Effect<Action> {
    val sessionID = state.session?.id
    if (!sessionTimers.runEndCheck || sessionID == null) return Effect.none()
    val server = state.server
    val generation = state.runWorkGeneration
    val clock = clock
    val client = openCodeClient
    return Effect.run<Action> { send ->
        clock.sleep(after)
        val active = try { client.isSessionActive(server, sessionID) } catch (_: OpenCodeClientError) { return@run }
        send(Action.RunStateRead(sessionID = sessionID, generation = generation, isActive = active))
    }.cancellable(CancelID.RunEndCheck(generation), cancelInFlight = true)
}

/**
 * Steering a message the server already holds as steering is refused by older servers, so it goes
 * by way of the queue; steering it wakes the session. [fromQueue]: it's queued already, so it only
 * needs steering.
 */
internal fun SessionFeature.sendStrandedSteer(
    id: String,
    sessionID: String,
    generation: Int,
    fromQueue: Boolean = false,
    server: OpenCodeServer,
): Effect<Action> {
    val clock = clock
    val client = openCodeClient
    return Effect.run<Action> { send ->
        try {
            if (!fromQueue) client.setPendingInputDelivery(server, sessionID, id, OpenCodeDelivery.Queue)
            client.setPendingInputDelivery(server, sessionID, id, OpenCodeDelivery.Steer)
        } catch (error: OpenCodeClientError) {
            send(
                Action.StrandedSteerSendFailed(
                    sessionID = sessionID,
                    generation = generation,
                    inputID = id,
                    error = error,
                    restoring = if (fromQueue) OpenCodeDelivery.Queue else null,
                ),
            )
            return@run
        }
        send(Action.PendingInputsRequested(sessionID = sessionID))
        clock.sleep(SessionFeature.runEndCheckDelay)
        val active = try { client.isSessionActive(server, sessionID) } catch (_: OpenCodeClientError) { return@run }
        send(Action.RunStateRead(sessionID = sessionID, generation = generation, isActive = active))
    }.cancellable(CancelID.StrandedSteer(generation), cancelInFlight = true)
}
