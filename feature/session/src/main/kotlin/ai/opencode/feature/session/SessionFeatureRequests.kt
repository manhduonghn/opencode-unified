package ai.opencode.feature.session

import ai.opencode.core.store.map
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.RequestAnswering
import ai.opencode.feature.composer.RequestReplies
import ai.opencode.feature.composer.RequestBar
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodePermissionRequest
import kotlin.time.Duration.Companion.seconds
import ai.opencode.core.sound.SoundEffect
import ai.opencode.core.experiments.ExperimentalFeature

// Pending requests (permissions, questions, forms).

/**
 * A permission or form awaiting an answer from some session. Kotlin can't add a supertype to
 * `:server` types, so each request is wrapped.
 */
sealed interface PendingRequest {
    val id: String
    val sessionID: String

    data class Permission(val request: OpenCodePermissionRequest) : PendingRequest {
        override val id: String get() = request.id
        override val sessionID: String get() = request.sessionID
    }

    data class Form(val request: OpenCodeFormRequest) : PendingRequest {
        override val id: String get() = request.id
        override val sessionID: String get() = request.sessionID
    }
}

/** The call's session and its title, for renames. */
data class VoiceSessionName(
    val sessionID: String?,
    val title: String?,
)

/** Pending requests. Null: the action passes on. */
internal fun SessionFeature.reduceRequests(state: State, action: Action): Reduced<State, Action>? {
    when (action) {
        is Action.PendingRequestsResponse -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            val result = action.result
            val requests = result.getOrNull()
            return if (requests != null) {
                pendingRequestsLoaded(requests, state)
            } else {
                val error = result.exceptionOrNull() as? OpenCodeClientError
                    ?: OpenCodeClientError(result.exceptionOrNull()?.message ?: OpenCodeClientError.UNREACHABLE_MESSAGE)
                pendingRequestsFailed(error, state)
            }
        }

        is Action.RequestsConnectionChanged -> {
            // A catalogue failure can land after SSE connected. Even a repeated
            // healthy snapshot earns one retry; the loading guard coalesces it.
            val models = if (action.isAvailable && state.modelsLoadError != null) loadModelsIfNeeded(state) else Reduced(state)
            if (state.requestsConnectionIsAvailable == action.isAvailable) return models
            var next = models.state.withRequestsConnectionIsAvailable(action.isAvailable)
                .copy(streamIsHealthy = action.isAvailable && state.streamIsHealthy)
                .withRequestLoadError(null)
            if (!action.isAvailable) {
                next = next.withRequestLoadAttempt(null).withRequestArrivalsAreLive(false)
                    .withRequestFailureAwaitingReconnect(true).copy(pendingWorkPollSeconds = null)
                // This read may predate the gap (including a local buffer overflow). Do not let
                // reconnect join it or replay its pre-gap deltas over REST.
                next = next.copy(liveText = emptyMap())
                val abandon = abandonRefresh(next)
                // Nothing here asks a server that isn't answering: retrying reads into a dead route
                // can stall a VPN tunnel. Home's connection loop finds it again, and the reconnect
                // re-reads.
                val quiet = quietServerPolling(abandon.state)
                return Reduced(quiet.state, Effect.merge(abandon.effect, quiet.effect))
            }
            val attempt = if (next.requestFailureAwaitingReconnect) {
                RequestDockFeature.State.LoadAttempt.Reconnect
            } else {
                RequestDockFeature.State.LoadAttempt.Initial
            }
            // Whatever failed for lack of a server gets one quiet second chance now that Home has heard from it again.
            val recovery = ArrayList<Effect<Action>>()
            if (next.earlierHistoryRetriesOnReconnect) {
                next = next.copy(earlierHistoryRetriesOnReconnect = false)
                recovery += Effect.send(Action.ShowEarlierTapped)
            }
            if (next.loadError != null && next.items.isEmpty() && next.session != null) {
                next = next.copy(loadError = null, isLoading = true)
                recovery += Effect.send(Action.RefreshRequested(RefreshReason.Reconnect))
            }
            val started = startPendingWork(next, attempt)
            val watched = watchdog(started.state)
            return Reduced(watched.state, Effect.merge(listOf(models.effect, started.effect, watched.effect) + recovery))
        }

        is Action.RequestDock -> return reduceRequestDock(action.action, state)

        else -> return null
    }
}

private fun SessionFeature.pendingRequestsLoaded(requests: OpenCodePendingRequests, state: State): Reduced<State, Action> {
    val reconciled = state.requestDock.reconciled(requests, state.familySessions.map { it.id }.toSet()) { dock ->
        restoringPersistedAnswers(dock, state)
    }
    var next = state.copy(requestDock = reconciled.state)
    if (reconciled.clearedSelection && next.activePicker == State.ActivePicker.requests) next = next.copy(activePicker = null)
    // Requests dock in the composer's place; nothing opens on its own. A catch-up card's waiting
    // requests were already there.
    val announcesArrivals = next.readsOnView || next.hasLoadedPendingRequests
    if (!next.readsOnView) next = next.copy(hasLoadedPendingRequests = true)
    val arrivedLive = next.requestArrivalsAreLive
    if (!arrivedLive) next = next.withRequestArrivalsAreLive(true)
    // A call's bar takes the dock's place and has its own cues.
    if (RequestAnswering.announcesArrival(reconciled.arrived, announcesArrivals, next.callOwnsAudio, next.isCovered, sceneWindow.isFront)) {
        haptics.play(Haptic.attention)
        // Never for what was already waiting when the screen opened or reconnected.
        if (arrivedLive && experimentalFeatures(ExperimentalFeature.soundEffects)) soundEffects.play(SoundEffect.needsInput)
    }
    return Reduced(next)
}

private fun SessionFeature.pendingRequestsFailed(error: OpenCodeClientError, state: State): Reduced<State, Action> {
    val attempt = state.requestLoadAttempt
    var next = state.withRequestLoadAttempt(null)
    if (!next.requestsConnectionIsAvailable) return Reduced(next)
    when (attempt) {
        RequestDockFeature.State.LoadAttempt.Initial -> {
            // A first failure while connected may be a warm-up hiccup. Retry once before treating it
            // as settled; a reconnect also retries (below).
            next = next.withRequestFailureAwaitingReconnect(true)
            if (!sessionTimers.requestRetry) return Reduced(next)
            val clock = clock
            return Reduced(
                next,
                Effect.run<Action> { send ->
                    clock.sleep(2.seconds)
                    send(Action.requestsAutoRetry)
                }.cancellable(SessionFeature.CancelID.RequestsRetry, cancelInFlight = true),
            )
        }
        RequestDockFeature.State.LoadAttempt.Reconnect, RequestDockFeature.State.LoadAttempt.Manual -> {
            next = next.withRequestFailureAwaitingReconnect(true).withRequestLoadError(error.message)
        }
        // Periodic projection failures stay quiet. Keep a previously settled failure visible until a
        // successful authoritative snapshot lands.
        null -> Unit
    }
    return Reduced(next)
}

private fun SessionFeature.reduceRequestDock(action: RequestDockFeature.Action, state: State): Reduced<State, Action> = when (action) {
    is RequestDockFeature.Action.ReviewRequestSelected,
    is RequestDockFeature.Action.QuestionPageChanged,
    is RequestDockFeature.Action.CollapseChanged,
    is RequestDockFeature.Action.FormValueChanged,
    -> Reduced(state)

    is RequestDockFeature.Action.SheetTapped -> {
        if (state.formRequests.none { it.id == action.id }) {
            Reduced(state)
        } else {
            Reduced(state.withSelectedRequestID(action.id).copy(activePicker = State.ActivePicker.requests))
        }
    }

    RequestDockFeature.Action.RefreshTapped -> {
        val next = state.withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Manual).withRequestLoadError(null)
        Reduced(next, refreshRequests(next))
    }

    RequestDockFeature.Action.AutoRetry -> {
        if (!state.requestsConnectionIsAvailable || state.requestLoadAttempt != null || !state.requestFailureAwaitingReconnect) {
            Reduced(state)
        } else {
            startPendingWork(state, RequestDockFeature.State.LoadAttempt.Reconnect)
        }
    }

    is RequestDockFeature.Action.PermissionReplyTapped ->
        dock(state, RequestReplies.replyPermission(state.requestDock, action.id, action.reply, action.message, state.server))

    is RequestDockFeature.Action.FormSubmitTapped ->
        dock(state, RequestReplies.submitForm(state.requestDock, action.id, state.server, state.effectiveDirectory))

    is RequestDockFeature.Action.FormCancelTapped ->
        dock(state, RequestReplies.cancelForm(state.requestDock, action.id, action.message, state.server, state.effectiveDirectory))

    RequestDockFeature.Action.DismissTapped -> dismissRequest(state)

    is RequestDockFeature.Action.BarSent -> {
        if (!state.isAnsweringRequest || state.dockRequestID != action.requestID) Reduced(state) else sendRequestBar(state)
    }

    // A choice changed: your own words became the answer, or were set aside.
    RequestDockFeature.Action.CustomMarkTapped -> Reduced(state.copy(requestDock = RequestReplies.customMarkTapped(state.requestDock, state.draft)))

    is RequestDockFeature.Action.ResponseFinished -> {
        val settled = RequestReplies.responseFinished(
            state.requestDock, action.sessionID, action.requestID, action.error, state.familySessions.map { it.id }.toSet(),
            dismissal = action.dismissal,
        )
        if (settled == null) {
            Reduced(state)
        } else {
            var next = state.copy(requestDock = settled.state)
            if (settled.clearedSelection && next.activePicker == State.ActivePicker.requests) next = next.copy(activePicker = null)
            Reduced(next, refreshRequests(next))
        }
    }
}

/** A dock reply flow's result, on the session. */
private fun dock(
    state: State,
    reduced: Reduced<RequestDockFeature.State<DraftState>, RequestDockFeature.Action>,
): Reduced<State, Action> = Reduced(state.copy(requestDock = reduced.state), reduced.effect.map(Action::RequestDock))

/** × (or TalkBack escape): cancels the docked form or rejects the docked permission, with the bar's words; a recording into the bar stops. */
private fun SessionFeature.dismissRequest(state: State): Reduced<State, Action> {
    if (state.dockRequestID == null || state.respondingRequestIDs.isNotEmpty()) return Reduced(state)
    // A held mic's words are in its bar until it stops.
    val reason = if (state.isAnsweringRequest) state.dictationHold?.text ?: state.draft else null
    var next = state
    var stop: Effect<Action> = Effect.none()
    if (next.isAnsweringRequest && next.hasLiveDictation) {
        val stopped = stopDictation(next)
        next = stopped.state
        stop = stopped.effect
    }
    val responded = dock(next, RequestReplies.dismissRequest(next.requestDock, reason, next.server, next.effectiveDirectory))
    return Reduced(responded.state, Effect.merge(stop, responded.effect))
}

/** Sends the bar while it answers a request: the next page, the answer, or Deny/Dismiss with its words as what to do instead. */
internal fun SessionFeature.sendRequestBar(state: State): Reduced<State, Action> {
    if (state.dockRequestID == null || state.respondingRequestIDs.isNotEmpty()) return Reduced(state)
    // Deny/Dismiss carries the bar's words, and stops a recording into it, as × does.
    if (state.requestBar is RequestBar.Decline) return dismissRequest(state)
    return dock(state, RequestReplies.sendRequestBar(state.requestDock, state.draft, reason = null, state.server, state.effectiveDirectory))
}

/**
 * Takes the composer for a request, moves the bar's words between pages and requests, and gives
 * the composer back. A recording into an answer never outlives it: it ends with the page or
 * request it was for.
 */
@Suppress("UNUSED_PARAMETER")
internal fun SessionFeature.reconcileAnswering(old: AnswerContext, state: State): Reduced<State, Action> {
    val new = state.answerContext
    if (new.wants && !state.isAnsweringRequest) return Reduced(state.beginAnswering())
    if (!state.isAnsweringRequest) return Reduced(state)
    if (!new.wants) {
        var next = state
        var stop: Effect<Action> = Effect.none()
        if (next.hasLiveDictation) {
            val stopped = stopDictation(next)
            next = stopped.state
            stop = stopped.effect
        }
        next = next.endAnswering()
        // A message that was waiting on its uploads goes now, if they're done.
        val sent = sendIfUploadsFinished(next)
        return Reduced(sent.state, Effect.merge(stop, sent.effect))
    }
    // Compared with what the bar shows, not the last context: a reduce nested in another sees the same change twice.
    if (state.answerShownTarget == new.target) return Reduced(state)
    var next = state
    // A hands-free recording's words so far stay with the page they were said on.
    val hold = next.dictationHold
    if (hold != null && hold.isLocked) next = next.withDraft(hold.text)
    var stop: Effect<Action> = Effect.none()
    if (next.hasLiveDictation) {
        val stopped = stopDictation(next)
        next = stopped.state
        stop = stopped.effect
    }
    return Reduced(next.moveAnswer(), stop)
}
