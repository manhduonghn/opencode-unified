package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

// Steering and queued messages, and the server's run state.

/** The inbox: queued and steering messages, and their deliveries. Null: the action passes on. */
internal fun SessionFeature.reducePendingInputs(state: State, action: Action): Reduced<State, Action>? {
    when (action) {
        is Action.InputQueueChanged -> {
            // Admission adds queued work; promotion moves it into history. Both land with one refresh
            // each — the transcript guard absorbs no-ops.
            val session = state.session ?: return Reduced(state)
            val next = action.deliveredID?.let { promoteDelivered(it, state) } ?: state
            return Reduced(
                next,
                Effect.merge(
                    fetchQueuedInputs(next.server, session.id),
                    Effect.send(Action.RefreshRequested(RefreshReason.Invalidation)),
                ),
            )
        }

        is Action.InputEnqueued -> {
            val session = state.session ?: return Reduced(state)
            val next = action.input?.let { showEnqueued(it, state) } ?: state
            return Reduced(next, fetchQueuedInputs(next.server, session.id))
        }

        is Action.PendingInputsRequested -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            return Reduced(state, fetchQueuedInputs(state.server, action.sessionID))
        }

        is Action.InputCancelled -> {
            val id = action.id
            var next = state
            if (id in next.pendingNoticeIDs) {
                next = next.copy(pendingNoticeIDs = next.pendingNoticeIDs - id, items = next.items.filterNot { it.id == id })
            }
            // Shown as delivered from its admission into an idle session, then withdrawn before the run took it.
            if (id in next.echoedPromptIDs) {
                next = next.copy(
                    echoedPromptIDs = next.echoedPromptIDs - id,
                    items = next.items.filterNot { TranscriptEntry.promptID(it) == id },
                )
            }
            next = removeQueuedInput(id, next).copy(
                promptStates = next.promptStates - id,
                optimisticPromptIDs = next.optimisticPromptIDs - id,
                cancelledInputIDs = next.cancelledInputIDs + id,
            )
            if (!next.isExternallyActive && !next.hasPendingLocalPrompt) {
                next = next.copy(isAwaitingReply = false)
                if (!next.isRunning) next = next.resetLiveProjection()
            }
            return Reduced(next)
        }

        is Action.PendingInputSendNowTapped -> {
            val id = action.id
            val session = state.session ?: return Reduced(state)
            val index = state.queuedInputs.indexOfFirst { it.id == id }
            if (index < 0) return Reduced(state)
            if (state.queuedInputs[index].delivery == OpenCodeDelivery.Steer) {
                // A steering message left behind when its run ended: it wakes the session now.
                if (state.isRunning || state.promptStates[id]?.admission == State.PromptState.Admission.sent) return Reduced(state)
                haptics.play(Haptic.tap)
                val next = state.copy(isAwaitingReply = true, deliveryOverrides = state.deliveryOverrides + (id to OpenCodeDelivery.Steer))
                    .supersedeRunWork()
                return Reduced(
                    next,
                    Effect.merge(
                        Effect.cancel(SessionFeature.CancelID.InboxFetch),
                        sendStrandedSteer(id, session.id, next.runWorkGeneration, server = next.server),
                    ),
                )
            }
            if (state.queuedInputs[index].delivery != OpenCodeDelivery.Queue) return Reduced(state)
            // It leaves the pill for a dim steering row in the transcript now; the inbox refetch
            // confirms the server's mode.
            var next = state.copy(
                queuedInputs = state.queuedInputs.toMutableList().also { it[index] = it[index].copy(delivery = OpenCodeDelivery.Steer) },
            )
            haptics.play(Haptic.tap)
            val precedingIDs = pendingPredecessors(next)
            val precedingMessages = SessionFeature.windowMessageIDs(next.items)
            next.pendingPromptPositions[id]?.let { position ->
                next = next.copy(
                    pendingPromptPositions = next.pendingPromptPositions + (
                        id to position.copy(
                            precedingPromptIDs = position.precedingPromptIDs + precedingIDs,
                            precedingMessageIDs = position.precedingMessageIDs + precedingMessages,
                            isBeforeWindow = false,
                        )
                        ),
                )
            }
            next = recordSteeringPredecessor(id, next)
            // A steer applies its pick now, whether its post is waiting, on its way, or done (the
            // server ignores the outbox applying it again).
            val pick = next.queuedPicks[id]
            val agentPick = next.queuedAgentPicks[id]
            next = next.copy(queuedPicks = next.queuedPicks - id).withQueuedAgentPicks(next.queuedAgentPicks - id)
            val change: Effect<Action>
            if (!next.isRunning && next.promptStates[id]?.admission != State.PromptState.Admission.sent) {
                // Parked with nothing running: steering it wakes the session, so it's Working at once,
                // and the server's word follows. Mid-run it joins that run, and Stop or a run-end read carry on.
                next = next.supersedeRunWork().copy(
                    isAwaitingReply = true,
                    deliveryOverrides = next.deliveryOverrides + (id to OpenCodeDelivery.Steer),
                )
                change = Effect.merge(
                    Effect.cancel(SessionFeature.CancelID.InboxFetch),
                    sendStrandedSteer(id, session.id, next.runWorkGeneration, fromQueue = true, server = next.server),
                )
            } else {
                val changed = changeDelivery(id, OpenCodeDelivery.Steer, OpenCodeDelivery.Queue, session.id, next)
                next = changed.state
                change = changed.effect
            }
            val effects = mutableListOf(change)
            if (pick != null && pick == next.pendingModel) effects += applyQueuedPick(pick, session.id, next.server)
            if (agentPick != null && agentPick == next.sendAgent) effects += applyQueuedAgentPick(agentPick, session.id, next.server)
            return Reduced(next, Effect.merge(effects))
        }

        is Action.PendingInputMoveToQueueTapped -> {
            val id = action.id
            val session = state.session ?: return Reduced(state)
            val index = state.queuedInputs.indexOfFirst { it.id == id }
            if (index < 0 || state.queuedInputs[index].delivery != OpenCodeDelivery.Steer) return Reduced(state)
            // It leaves the transcript for the queued pill now; the inbox refetch confirms the server's
            // mode. Queued, it may be overtaken by steering messages sent after it.
            var next = state.copy(
                queuedInputs = state.queuedInputs.toMutableList().also { it[index] = it[index].copy(delivery = OpenCodeDelivery.Queue) },
            )
            for (later in next.queuedInputs.drop(index + 1)) {
                if (later.delivery != OpenCodeDelivery.Steer || later.isCompaction || later.notice != null) continue
                val position = next.pendingPromptPositions[id] ?: continue
                next = next.copy(
                    pendingPromptPositions = next.pendingPromptPositions + (id to position.copy(precedingPromptIDs = position.precedingPromptIDs + later.id)),
                )
            }
            haptics.play(Haptic.tap)
            // Queued, it no longer switches the run; a waiting pick waits with it.
            next.pendingModel?.let { next = next.copy(queuedPicks = next.queuedPicks + (id to it)) }
            waitingAgent(next)?.let { next = next.withQueuedAgentPicks(next.queuedAgentPicks + (id to it)) }
            return changeDelivery(id, OpenCodeDelivery.Queue, OpenCodeDelivery.Steer, session.id, next)
        }

        is Action.PendingInputCancelTapped -> {
            val id = action.id
            val session = state.session ?: return Reduced(state)
            if (state.queuedInputs.none { it.id == id }) return Reduced(state)
            haptics.play(Haptic.tap)
            // Deleted, it leaves the inbox without a next run taking it.
            var next = state.copy(stopAttempt = state.stopAttempt?.let { it.copy(steering = it.steering - id) })
            // Still being sent: the row goes now, and the server's copy is cancelled once the send is
            // admitted (or the failure is dropped). One still waiting in the outbox is simply never posted.
            val unsent = next.promptStates[id]?.admission == State.PromptState.Admission.sent
            val withdrawn = unsent && promptOutbox.withdraw(id)
            val inFlight = unsent && !withdrawn
            if (withdrawn) next = releaseAutoBackgroundClaim(id, next)
            next = removeQueuedInput(id, next)
            if (!inFlight) next = next.copy(promptStates = next.promptStates - id, sentDeliveries = next.sentDeliveries - id)
            next = next.copy(
                deliveryOverrides = next.deliveryOverrides - id,
                optimisticPromptIDs = next.optimisticPromptIDs - id,
                echoedPromptIDs = next.echoedPromptIDs - id,
                cancelledInputIDs = next.cancelledInputIDs + id,
            )
            if (!next.isExternallyActive && !next.hasPendingLocalPrompt) {
                next = next.copy(isAwaitingReply = false)
                if (!next.isRunning) next = next.resetLiveProjection()
            }
            if (inFlight || withdrawn) return Reduced(next, Effect.cancel(SessionFeature.CancelID.InboxFetch))
            return Reduced(next, Effect.merge(Effect.cancel(SessionFeature.CancelID.InboxFetch), cancelOnServer(id, session.id, next.server)))
        }

        is Action.StrandedSteerSendFailed -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            var next = state.copy(deliveryOverrides = state.deliveryOverrides - action.inputID)
            // A queued message sent now goes back to the pill until the inbox read says otherwise.
            action.restoring?.let { next = restorePendingInputDelivery(it, action.inputID, next) }
            if (action.generation == next.runWorkGeneration && !next.isExternallyActive &&
                !(next.isSubmittingPrompt && next.failedSends.isEmpty())
            ) {
                next = next.copy(isAwaitingReply = false)
            }
            haptics.play(Haptic.error)
            next = appendInfo(next, localized(R.string.session_pending_send_now_failed_details, action.error.message))
            return Reduced(next, fetchQueuedInputs(next.server, action.sessionID))
        }

        is Action.PendingInputUpdateFailed -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            // Undo the optimistic change; a cancelled row returns with the next inbox read.
            var next = state.copy(deliveryOverrides = state.deliveryOverrides - action.inputID)
            val restoring = action.restoring
            next = if (restoring != null) {
                restorePendingInputDelivery(restoring, action.inputID, next)
            } else {
                next.copy(cancelledInputIDs = next.cancelledInputIDs - action.inputID)
            }
            val message = when (restoring) {
                null -> localized(R.string.session_pending_cancel_failed_details, action.error.message)
                OpenCodeDelivery.Queue -> localized(R.string.session_pending_send_now_failed_details, action.error.message)
                OpenCodeDelivery.Steer -> localized(R.string.session_pending_queue_failed_details, action.error.message)
            }
            haptics.play(Haptic.error)
            return Reduced(appendInfo(next, message))
        }

        // The pending-work poll slows to 12 s once the stream is healthy, so requests answered
        // elsewhere must not wait for it.
        Action.RequestsChanged -> return Reduced(state, refreshRequests(state))

        is Action.PendingInputsResponse -> return pendingInputsResponse(action.sessionID, action.inputs, state)

        is Action.RemotePromptMissing -> {
            if (state.session?.id != action.sessionID || state.promptStates[action.messageID] != null ||
                state.pendingPromptPositions[action.messageID]?.requestEpoch != action.requestEpoch
            ) {
                return Reduced(state)
            }
            // A missing message can be a stale inbox read, not a cancellation. Retire this
            // observation without poisoning later authoritative reads.
            return Reduced(removeQueuedInput(action.messageID, state))
        }

        is Action.PromptEchoResponse -> {
            val messageID = action.messageID
            if (state.session?.id != action.sessionID ||
                (state.promptStates[messageID]?.requestEpoch ?: state.pendingPromptPositions[messageID]?.requestEpoch) != action.requestEpoch ||
                messageID !in SessionFeature.promptIDs(action.items)
            ) {
                return Reduced(state)
            }
            // The per-message read can beat the newest window. Promote a prompt seen at this tail
            // atomically rather than removing its only row. If newer user messages have passed it, it
            // belongs to paged history.
            var next = state
            val position = next.pendingPromptPositions[messageID]
            val canAppend = position?.let { !it.isBeforeWindow && it.precedingPromptIDs.containsAll(SessionFeature.promptIDs(next.items)) } ?: false
            val appendEcho = canAppend && !SessionFeature.containsMessage(messageID, next.items)
            if (appendEcho) {
                val previousFirstID = next.visibleItems.firstOrNull()?.id
                next = next.copy(items = next.items + action.items).retainRenderedWindowStart(previousFirstID)
            }
            next = applyAuthoritativeMessage(action.items, messageID, next)
            next = reconcilePromptEchoes(action.items, next)
            // Retain this row through a window response captured before delivery.
            if (appendEcho) next = next.copy(echoedPromptIDs = next.echoedPromptIDs + messageID)
            next = settleFailedSends(SessionFeature.promptIDs(action.items), next)
            if (!next.isExternallyActive && !next.hasPendingLocalPrompt) {
                next = next.copy(isAwaitingReply = false)
                if (!next.isRunning && next.refresh?.followup != RefreshReason.RunState) next = next.resetLiveProjection()
            }
            return Reduced(next)
        }

        is Action.ExternalRunStateChanged -> {
            // Execution boundaries reconcile once and retune the loss-recovery watchdog; content
            // between them arrives through projection batches.
            val wasRunning = state.isRunning
            val wasActive = state.isExternallyActive
            var next = state.copy(isExternallyActive = action.isRunning)
            if (next.session == null) return Reduced(next)
            if (action.isRunning) {
                // A run started: whatever Stop or a read was doing is about an earlier one, and must never touch this run.
                if (!wasActive) next = next.supersedeRunWork()
                val watched = watchdog(next)
                return Reduced(watched.state, Effect.merge(Effect.send(Action.RefreshRequested(RefreshReason.RunState)), watched.effect))
            }
            // A message from here waiting in the inbox keeps Working up while the next run takes it.
            // After Stop only a steering message does: Stop leaves queued ones parked, so the stopped
            // state shows at once.
            if (!(if (next.isInterrupting) next.hasSteerOnItsWay else next.hasPendingLocalPrompt)) {
                next = next.copy(isAwaitingReply = false)
            }
            // Protect R1 before the effect queues its terminal read: R1's response can arrive between
            // this action and refreshRequested below.
            val needsRefresh = wasRunning || next.refresh?.reason != RefreshReason.RunState
            if (needsRefresh) next = next.copy(refresh = next.refresh?.copy(followup = RefreshReason.RunState))
            next = next.supersedeRunWork()
            val watched = watchdog(next)
            next = watched.state
            return Reduced(
                next,
                Effect.merge(
                    if (needsRefresh) Effect.send(Action.RefreshRequested(RefreshReason.RunState)) else Effect.none(),
                    watched.effect,
                    // A message from here waiting in the inbox keeps Working up while the next run
                    // takes it. If none does (a queued message after Stop, a steering one left
                    // behind), the server's word lets it go.
                    if (next.isAwaitingReply && !next.isSubmittingPrompt) confirmRunState(next, SessionFeature.runEndCheckDelay) else Effect.none(),
                ),
            )
        }

        is Action.RunStateRead -> {
            if (state.session?.id != action.sessionID || action.generation != state.runWorkGeneration) return Reduced(state)
            if (action.isActive) {
                // A run the stream didn't announce. Stop, if it's on its way, carries on.
                return if (state.isExternallyActive) Reduced(state) else Reduced(state, Effect.send(Action.ExternalRunStateChanged(true)))
            }
            // Nothing runs: what waits in the inbox (a queued message, a steering one left behind)
            // waits for the reader. Only a send still on its way keeps the session working. All in
            // this one action, so a run Stop ended leaves the Working line at once, as the stream's end does.
            var next = state.supersedeRunWork().copy(isExternallyActive = false)
            if (!(next.isSubmittingPrompt && next.failedSends.isEmpty())) next = next.copy(isAwaitingReply = false)
            return Reduced(
                next,
                Effect.merge(
                    Effect.send(Action.ExternalRunStateChanged(false)),
                    fetchQueuedInputs(next.server, action.sessionID),
                ),
            )
        }

        else -> return null
    }
}

private fun SessionFeature.pendingInputsResponse(sessionID: String, fetched: List<OpenCodePendingInput>, state: State): Reduced<State, Action> {
    if (state.session?.id != sessionID) return Reduced(state)
    var next = settleFailedSends(fetched.map { it.id }.toSet(), state)
    // A message already in the transcript, delivered or shown as a bubble ahead of its echo (a send,
    // a retry, a failure the server had after all), stays there: it never also becomes a pending row.
    val promoted = next.promotedPromptIDs.toSet() + SessionFeature.promptIDs(next.items)
    val admitted = fetched.filter { it.id !in promoted && it.id !in next.cancelledInputIDs }
    next = admitPendingNotices(admitted.mapNotNull { it.notice }, next)
    val inputs = admitted.filter { it.notice == null }
    val serverIDs = inputs.map { it.id }.toSet()
    val userInputs = inputs.filter { !it.isCompaction }
    val precedingIDs = SessionFeature.promptIDs(next.items).toMutableSet()
    val precedingMessages = SessionFeature.windowMessageIDs(next.items)
    for (input in userInputs) {
        precedingIDs += input.id
        val position = next.pendingPromptPositions[input.id]
        next = if (position == null) {
            val epoch = next.nextPromptRequestEpoch + 1
            next.copy(
                nextPromptRequestEpoch = epoch,
                pendingPromptPositions = next.pendingPromptPositions + (
                    input.id to State.PendingPromptPosition(
                        precedingPromptIDs = precedingIDs.toSet(),
                        precedingMessageIDs = precedingMessages,
                        requestEpoch = next.promptStates[input.id]?.requestEpoch ?: epoch,
                    )
                    ),
            )
        } else {
            // A prompt still listed in the inbox may follow any prompt that has reached the transcript since we first saw it.
            next.copy(
                pendingPromptPositions = next.pendingPromptPositions + (
                    input.id to position.copy(
                        precedingPromptIDs = position.precedingPromptIDs + precedingIDs,
                        precedingMessageIDs = position.precedingMessageIDs + precedingMessages,
                        isBeforeWindow = false,
                    )
                    ),
            )
        }
    }
    // Inbox reads can complete out of order with prompt admission and promotion. Keep every displayed
    // user prompt until the transcript echoes it; if it leaves inbox first, the queued→transcript
    // presentation remains one atomic reducer update rather than a disappearance. Compactions follow
    // the inbox directly; notices are transcript items (see `admitPendingNotices`).
    val pendingLocal = next.queuedInputs.filter {
        it.id !in serverIDs && (next.promptStates[it.id] != null || next.pendingPromptPositions[it.id] != null)
    }
    val byID = LinkedHashMap<String, OpenCodePendingInput>()
    for (input in inputs) byID[input.id] = input
    val previousIDs = next.queuedInputs.map { it.id }.toSet()
    val retainedIDs = pendingLocal.map { it.id }.toSet()
    var queued = next.queuedInputs.mapNotNull { byID[it.id] ?: if (it.id in retainedIDs) it else null } +
        inputs.filter { it.id !in previousIDs }
    // A delivery changed here holds until the server lists it.
    var overrides = next.deliveryOverrides
    for ((id, delivery) in next.deliveryOverrides) {
        if (byID[id]?.delivery == delivery) overrides = overrides - id
        val index = queued.indexOfFirst { it.id == id }
        if (index >= 0) queued = queued.toMutableList().also { it[index] = it[index].copy(delivery = delivery) }
    }
    next = next.copy(queuedInputs = queued, deliveryOverrides = overrides)
    // Only queued prompts may be overtaken by later steering prompts.
    for (input in userInputs) {
        if (input.delivery == OpenCodeDelivery.Steer) next = recordSteeringPredecessor(input.id, next)
    }
    val queuedIDs = next.queuedInputs.map { it.id }.toSet()
    next = next.copy(queuedInputImages = next.queuedInputImages.filterKeys { it in queuedIDs })
    // A busy session can move past a prompt before the newest-page read ever sees it. Confirm missing
    // admitted prompts by ID instead of keeping their dim queue rows forever. Never infer delivery
    // from an empty inbox alone (a pre-admission poll can arrive late).
    val missing = pendingLocal.mapNotNull { input ->
        val prompt = next.promptStates[input.id]
        if (prompt != null) {
            if (prompt.admission == State.PromptState.Admission.admitted) input.id to prompt.requestEpoch else null
        } else {
            next.pendingPromptPositions[input.id]?.let { input.id to it.requestEpoch }
        }
    }
    if (missing.isEmpty()) return Reduced(next, Effect.cancel(SessionFeature.CancelID.PromptEcho))
    val localIDs = next.promptStates.keys.toSet()
    val server = next.server
    val client = openCodeClient
    return Reduced(
        next,
        Effect.run<Action> { send ->
            for ((id, epoch) in missing.take(4)) {
                if (!currentCoroutineContext().isActive) return@run
                try {
                    val items = client.message(server, sessionID, id)
                    if (!currentCoroutineContext().isActive) return@run
                    send(Action.PromptEchoResponse(sessionID, id, epoch, items))
                } catch (error: OpenCodeClientError) {
                    // A remote cancellation may have happened while disconnected. Only an
                    // authoritative absence settles it, never a read failure or a local send whose
                    // admission may still be racing this poll.
                    if (currentCoroutineContext().isActive && id !in localIDs && error.statusCode == 404) {
                        send(Action.RemotePromptMissing(sessionID, id, epoch))
                    }
                }
            }
        }.cancellable(SessionFeature.CancelID.PromptEcho, cancelInFlight = true),
    )
}

/**
 * Applies a queued send's pick once its message starts or becomes a steer. A refusal leaves it
 * waiting, so the next send applies it (and fails, saying why, if it's still refused).
 */
internal fun SessionFeature.applyDueModelPick(state: State): Reduced<State, Action> {
    val pick = state.queuedPickDue ?: return Reduced(state)
    val next = state.copy(queuedPickDue = null)
    val session = next.session ?: return Reduced(next)
    return Reduced(next, applyQueuedPick(pick, session.id, next.server))
}

internal fun SessionFeature.applyQueuedPick(model: OpenCodeModelRef, sessionID: String, server: OpenCodeServer): Effect<Action> {
    val client = openCodeClient
    return Effect.run { send ->
        try {
            client.switchModel(server, sessionID, model)
            send(Action.ModelApplied(sessionID, model))
        } catch (error: OpenCodeClientError) {
            AppLog.send.notice { "Queued model pick not applied: ${error.logSummary}" }
        }
    }
}

private fun removeQueuedInput(id: String, state: State): State =
    state.copy(
        queuedPicks = state.queuedPicks - id,
        queuedInputs = state.queuedInputs.filterNot { it.id == id },
        pendingPromptPositions = state.pendingPromptPositions - id,
        queuedInputImages = state.queuedInputImages - id,
    ).withQueuedAgentPicks(state.queuedAgentPicks - id)

private fun SessionFeature.restorePendingInputDelivery(restoring: OpenCodeDelivery, inputID: String, state: State): State {
    var next = state
    val index = next.queuedInputs.indexOfFirst { it.id == inputID }
    if (index >= 0) next = next.copy(queuedInputs = next.queuedInputs.toMutableList().also { it[index] = it[index].copy(delivery = restoring) })
    if (restoring == OpenCodeDelivery.Queue) {
        next.pendingModel?.let { next = next.copy(queuedPicks = next.queuedPicks + (inputID to it)) }
        waitingAgent(next)?.let { next = next.withQueuedAgentPicks(next.queuedAgentPicks + (inputID to it)) }
    }
    return next
}

/**
 * Send Now and Move to Queue: the row has already moved. An admitted message is switched on the
 * server now; one still being sent, once its send is admitted. Either way, an inbox read already
 * under way can't move it back.
 */
private fun SessionFeature.changeDelivery(
    id: String,
    delivery: OpenCodeDelivery,
    previous: OpenCodeDelivery,
    sessionID: String,
    state: State,
): Reduced<State, Action> {
    var next = state.copy(deliveryOverrides = state.deliveryOverrides + (id to delivery))
    if (delivery != OpenCodeDelivery.Steer) next = releaseAutoBackgroundClaim(id, next)
    var backgroundKey: String? = null
    if (delivery == OpenCodeDelivery.Steer) {
        val (claimed, key) = claimAutoBackground(id, next)
        next = claimed
        backgroundKey = key
    }
    if (next.promptStates[id]?.admission == State.PromptState.Admission.sent) {
        // Still waiting in the outbox, it's posted the new way; already being posted, the server
        // hears about the change once it's admitted.
        if (promptOutbox.setDelivery(id, delivery, backgroundKey)) next = next.copy(sentDeliveries = next.sentDeliveries + (id to delivery))
        return Reduced(next, Effect.cancel(SessionFeature.CancelID.InboxFetch))
    }
    if (backgroundKey != null) next = next.copy(autoBackgroundClaims = next.autoBackgroundClaims - id)
    return Reduced(
        next,
        Effect.merge(
            Effect.cancel(SessionFeature.CancelID.InboxFetch),
            changeDeliveryOnServer(id, delivery, previous, sessionID, backgroundKey, next.server),
        ),
    )
}

internal fun SessionFeature.changeDeliveryOnServer(
    id: String,
    delivery: OpenCodeDelivery,
    previous: OpenCodeDelivery,
    sessionID: String,
    backgroundKey: String? = null,
    server: OpenCodeServer,
): Effect<Action> {
    val client = openCodeClient
    return Effect.run { send ->
        if (backgroundKey != null) {
            val error = try {
                client.moveToBackground(server, sessionID)
                null
            } catch (error: OpenCodeClientError) {
                error
            }
            send(Action.MoveToBackgroundFinished(sessionID, backgroundKey, error, isAutomatic = true))
        }
        try {
            client.setPendingInputDelivery(server, sessionID, id, delivery)
        } catch (error: OpenCodeClientError) {
            send(Action.PendingInputUpdateFailed(sessionID, id, previous, error))
        }
        send(Action.PendingInputsRequested(sessionID))
    }
}

internal fun SessionFeature.cancelOnServer(id: String, sessionID: String, server: OpenCodeServer): Effect<Action> {
    val client = openCodeClient
    return Effect.run { send ->
        try {
            client.cancelPendingInput(server, sessionID, id)
        } catch (error: OpenCodeClientError) {
            send(Action.PendingInputUpdateFailed(sessionID, id, null, error))
        }
        send(Action.PendingInputsRequested(sessionID))
    }
}

internal fun reconcilePromptEchoes(items: List<OpenCodeTranscriptItem>, state: State): State {
    val promptIDs = SessionFeature.promptIDs(items)
    var next = state
    // A queued send's message started: its pick applies now, if it still waits.
    for (id in promptIDs) {
        val pick = next.queuedPicks[id]
        if (pick != null) {
            next = next.copy(queuedPicks = next.queuedPicks - id)
            if (pick == next.pendingModel) next = next.copy(queuedPickDue = pick)
        }
        val agent = next.queuedAgentPicks[id]
        if (agent != null) {
            next = next.withQueuedAgentPicks(next.queuedAgentPicks - id)
            if (agent == next.sendAgent) next = next.withQueuedAgentPickDue(agent)
        }
    }
    next = next.copy(optimisticPromptIDs = next.optimisticPromptIDs - promptIDs, echoedPromptIDs = next.echoedPromptIDs - promptIDs)
    for (id in promptIDs) {
        val queued = next.queuedInputs.any { it.id == id }
        if (next.promptStates[id] == null && !queued) continue
        // A pending message delivered: a run took it, so Stop is over and no read about the run before it may act.
        if (queued) next = next.supersedeRunWork()
        next = next.copy(
            queuedInputs = next.queuedInputs.filterNot { it.id == id },
            pendingPromptPositions = next.pendingPromptPositions - id,
            queuedInputImages = next.queuedInputImages - id,
            promptStates = next.promptStates - id,
            sentDeliveries = next.sentDeliveries - id,
            deliveryOverrides = next.deliveryOverrides - id,
        )
        next = recordPromotion(id, next)
    }
    if (next.pendingNoticeIDs.isEmpty()) return next
    for (item in items) {
        if (item !is OpenCodeTranscriptItem.Notice || item.id !in next.pendingNoticeIDs) continue
        next = recordPromotion(item.id, next.copy(pendingNoticeIDs = next.pendingNoticeIDs - item.id))
    }
    return next
}

private fun recordPromotion(id: String, state: State): State {
    val promoted = state.promotedPromptIDs.filter { it != id } + id
    return state.copy(promotedPromptIDs = if (promoted.size > 32) promoted.takeLast(32) else promoted)
}

/**
 * A notice still in the inbox joins the transcript at the end of the turn it arrives in, rather
 * than as its own row, and stays there until history has it, even if the inbox empties first. An
 * inbox read that lags history never adds a delivered one again.
 */
private fun admitPendingNotices(notices: List<OpenCodeTranscriptItem>, state: State): State {
    val previousFirstID = state.visibleItems.firstOrNull()?.id
    var next = state
    for (notice in notices) {
        if (next.items.any { it.id == notice.id }) continue
        next = next.copy(items = next.items + notice, pendingNoticeIDs = next.pendingNoticeIDs + notice.id)
    }
    return next.retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
}

/**
 * A message admitted from outside this screen (the voice agent, another client) shows from its own
 * event, like a send from here. An inbox read can come back empty (a delivery at the same instant),
 * and the page read that has it can take seconds on a heavy session, while Working shows from the
 * run's start at once.
 */
private fun showEnqueued(input: OpenCodePendingInput, state: State): State {
    val id = input.id
    if (input.isCompaction || input.notice != null || state.promptStates[id] != null ||
        id in state.cancelledInputIDs || id in state.promotedPromptIDs ||
        SessionFeature.containsMessage(id, state.items) || state.queuedInputs.any { it.id == id }
    ) {
        return state
    }
    // Idle, the server delivers it at once and starts its run, as it does a send from here: it joins
    // the transcript now, ahead of Working, rather than waiting below Working as steering for the
    // delivery a moment later.
    if (!state.isRunning) return appendDelivered(input, state)
    val epoch = state.nextPromptRequestEpoch + 1
    var next = state.copy(queuedInputs = state.queuedInputs + input, nextPromptRequestEpoch = epoch)
    next = next.copy(
        pendingPromptPositions = next.pendingPromptPositions + (
            id to State.PendingPromptPosition(
                precedingPromptIDs = pendingPredecessors(next),
                precedingMessageIDs = SessionFeature.windowMessageIDs(next.items),
                requestEpoch = epoch,
            )
            ),
    )
    if (input.delivery == OpenCodeDelivery.Steer) next = recordSteeringPredecessor(id, next)
    return next
}

/**
 * The server moved a pending message into history: it takes its place in the transcript now, ahead
 * of the run it starts, as a page read would place it. It's kept like a per-message echo until a page has it.
 */
private fun promoteDelivered(id: String, state: State): State {
    val input = state.queuedInputs.firstOrNull { it.id == id } ?: return state
    if (input.isCompaction || input.notice != null || SessionFeature.containsMessage(id, state.items)) return state
    return appendDelivered(input, state)
}

/** A delivered message's rows as the server's decoder names them, at the transcript's end; kept like a per-message echo until a page has it. */
private fun appendDelivered(input: OpenCodePendingInput, state: State): State {
    val id = input.id
    val localImages = state.queuedInputImages[id]
    val rows = SessionFeature.promptRows(
        messageID = id, text = input.text, source = input.source,
        images = localImages?.takeIf { it.isNotEmpty() } ?: input.images,
        fileReferences = input.files, inlineFiles = input.inlineFiles, quotes = input.quotes,
    )
    if (rows.isEmpty()) return state
    val previousFirstID = state.visibleItems.firstOrNull()?.id
    var next = state.copy(items = state.items + rows).retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    next = reconcilePromptEchoes(rows, next)
    return next.copy(echoedPromptIDs = next.echoedPromptIDs + id)
}

internal fun pendingPredecessors(state: State): Set<String> =
    SessionFeature.promptIDs(state.items) + state.queuedInputs.mapNotNull { if (!it.isCompaction && it.notice == null) it.id else null }

internal fun SessionFeature.Companion.windowMessageIDs(items: List<OpenCodeTranscriptItem>): Set<String> =
    items.mapNotNullTo(HashSet()) {
        when (it) {
            is OpenCodeTranscriptItem.Info, is OpenCodeTranscriptItem.SendFailure,
            is OpenCodeTranscriptItem.Notice, is OpenCodeTranscriptItem.Undone,
            -> null
            else -> SessionFeature.messageID(it)
        }
    }

internal fun recordSteeringPredecessor(id: String, state: State): State {
    var positions = state.pendingPromptPositions
    for (input in state.queuedInputs) {
        if (input.delivery != OpenCodeDelivery.Queue) continue
        val position = positions[input.id] ?: continue
        positions = positions + (input.id to position.copy(precedingPromptIDs = position.precedingPromptIDs + id))
    }
    return if (positions === state.pendingPromptPositions) state else state.copy(pendingPromptPositions = positions)
}

/** Settlements are for recent messages: looked for from the end. */
internal fun SessionFeature.Companion.containsMessage(messageID: String, items: List<OpenCodeTranscriptItem>): Boolean {
    val rows = MessageRows(messageID)
    for (index in items.indices.reversed()) {
        if (rows.contains(items[index])) return true
    }
    return false
}

internal fun SessionFeature.Companion.item(item: OpenCodeTranscriptItem, messageID: String): Boolean =
    MessageRows(messageID).contains(item)

/**
 * One message's rows, matched without building a string for every row a scan of the transcript
 * passes.
 */
internal class MessageRows(val messageID: String) {
    private val prefix = "$messageID-"
    private val images = "$messageID-images"
    private val files = "$messageID-files"

    fun contains(item: OpenCodeTranscriptItem): Boolean = when (item) {
        is OpenCodeTranscriptItem.UserText -> item.id == messageID
        is OpenCodeTranscriptItem.Compaction -> item.id == messageID
        is OpenCodeTranscriptItem.ModelSwitch -> item.id == messageID
        is OpenCodeTranscriptItem.UserImages -> item.id == images
        is OpenCodeTranscriptItem.UserFiles -> item.id == files
        is OpenCodeTranscriptItem.Tool -> item.call.messageID == messageID || item.id == messageID || item.id.startsWith(prefix)
        is OpenCodeTranscriptItem.AssistantMarkdown, is OpenCodeTranscriptItem.Info, is OpenCodeTranscriptItem.Notice,
        is OpenCodeTranscriptItem.SendFailure, is OpenCodeTranscriptItem.RunError, is OpenCodeTranscriptItem.RunRetry,
        is OpenCodeTranscriptItem.Undone,
        -> item.id == messageID || item.id.startsWith(prefix)
    }
}
