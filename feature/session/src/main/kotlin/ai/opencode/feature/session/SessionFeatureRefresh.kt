package ai.opencode.feature.session

import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Send
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.AuthoritativeRefresh
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.feature.transcript.list.TranscriptScan
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.transcriptWithContextUsageAndCursor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Why an authoritative read runs. */
sealed interface RefreshReason {
    data object Open : RefreshReason
    data object Resume : RefreshReason
    data object Reconnect : RefreshReason
    data object RunState : RefreshReason
    data object Invalidation : RefreshReason
    data class Settlement(override val messageID: String) : RefreshReason
    data object Watchdog : RefreshReason
    data object Snapshot : RefreshReason

    val label: String
        get() = when (this) {
            Open -> "open"
            Resume -> "resume"
            Reconnect -> "reconnect"
            RunState -> "runState"
            Invalidation -> "invalidation"
            is Settlement -> "settlement"
            Watchdog -> "watchdog"
            Snapshot -> "snapshot"
        }

    val messageID: String? get() = null

    val requiresFollowup: Boolean
        get() = when (this) {
            Invalidation, is Settlement, RunState -> true
            else -> false
        }
}

/**
 * Reconciliation is only a watchdog when projection is healthy. Without a proven content event,
 * active sessions retain the short fallback cadence. [deadline] is a reading of the watchdog's
 * monotonic [ai.opencode.core.dependencies.Clock] (`Clock.now()`).
 */
data class WatchdogSchedule(
    val sessionID: String,
    val interval: Duration,
    val deadline: Duration,
)

/** (re)arms the reconciliation watchdog for the session's cadence. */
internal fun SessionFeature.watchdog(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, Action> {
    // Individual watchdog tests opt in through `sessionTimers` and inject a TestClock. Other
    // reducer tests do not inherit a perpetual timer.
    val watchdogClock = watchdogClock
    if (!sessionTimers.watchdog || watchdogClock == null) return Reduced(state)
    // No session (a draft), a server that isn't answering, an app in the background or a covered
    // screen: no reconciliation timer. A reconnect, the return or the uncover re-arms it.
    val session = state.session
    if (session == null || !state.pollsServer) {
        if (session != null && state.isPollingPaused && state.pausedWatchdogDeadline != null) {
            return stopWatchdog(state.copy(pausedWatchdogDeadline = watchdogClock.now() + watchdogInterval(state, session)))
        }
        return stopWatchdog(state)
    }
    val interval = watchdogInterval(state, session)
    val schedule = WatchdogSchedule(sessionID = session.id, interval = interval, deadline = watchdogClock.now() + interval)
    val previous = state.watchdogSchedule
    val scheduled = state.copy(watchdogSchedule = schedule)
    // Batches move the inactivity deadline without cancelling the sleeper. Only a session/cadence
    // transition replaces the outstanding effect.
    if (previous?.sessionID == schedule.sessionID && previous?.interval == interval) return Reduced(scheduled)
    val generation = scheduled.watchdogGeneration + 1
    return Reduced(scheduled.copy(watchdogGeneration = generation), sleepWatchdog(schedule, generation))
}

private fun watchdogInterval(state: SessionFeature.State, session: OpenCodeSession): Duration {
    val seconds = state.watchdogSeconds
    // Stable per-session jitter avoids synchronized clients while remaining deterministic within
    // one process and friendly to TestClock.
    val jitter = abs(session.id.hashCode() % maxOf(1, seconds / 5 + 1))
    return (seconds + jitter).seconds
}

internal fun SessionFeature.watchdogElapsed(
    state: SessionFeature.State,
    generation: Int,
): Reduced<SessionFeature.State, Action> {
    val watchdogClock = watchdogClock ?: return Reduced(state)
    var schedule = state.watchdogSchedule ?: return Reduced(state)
    if (generation != state.watchdogGeneration || schedule.sessionID != state.session?.id) return Reduced(state)
    var s = state
    val due = watchdogClock.now() >= schedule.deadline
    if (due) {
        schedule = schedule.copy(deadline = watchdogClock.now() + schedule.interval)
        s = s.copy(watchdogSchedule = schedule)
    }
    // If content arrived during the sleep, wait just its remaining quiet time. This keeps the exact
    // trailing inactivity deadline, not a rounded polling tick, while effect starts are bounded by
    // timer wakes.
    return Reduced(
        s,
        Effect.merge(
            if (due) Effect.send(Action.RefreshRequested(RefreshReason.Watchdog)) else Effect.none(),
            sleepWatchdog(schedule, generation),
        ),
    )
}

private fun stopWatchdog(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    var s = state
    if (s.watchdogSchedule != null) {
        s = s.copy(watchdogSchedule = null, watchdogGeneration = s.watchdogGeneration + 1)
    }
    return Reduced(s, Effect.cancel(CancelID.Watchdog))
}

/**
 * Stops every timed read of the server: the pending-work polls and the watchdog. Requests that
 * answer the person (replies, sends) carry on.
 */
internal fun quietServerPolling(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    val stopped = stopWatchdog(state.copy(pendingWorkPollSeconds = null))
    return Reduced(
        stopped.state,
        Effect.merge(
            Effect.cancel(CancelID.Requests),
            Effect.cancel(CancelID.Inbox),
            Effect.cancel(CancelID.InboxFetch),
            Effect.cancel(CancelID.RequestsRetry),
            Effect.cancel(CancelID.RequestsChanged),
            stopped.effect,
        ),
    )
}

/**
 * Back in front (or uncovered) with the server reachable, polls and the watchdog start again at once, and a
 * request read that was owed its retry gets it.
 */
internal fun SessionFeature.resumeServerPolling(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    if (!state.pollsServer || state.session == null) return Reduced(state)
    val watchdog = watchdog(state)
    if (!watchdog.state.hasRenderedFirstContentFrame) return watchdog
    val owesRetry = watchdog.state.requestFailureAwaitingReconnect && watchdog.state.requestLoadAttempt == null
    val polls = if (owesRetry) {
        startPendingWork(watchdog.state, attempt = RequestDockFeature.State.LoadAttempt.Reconnect)
    } else {
        pollPendingWork(watchdog.state)
    }
    return Reduced(polls.state, Effect.merge(watchdog.effect, polls.effect))
}

private fun SessionFeature.sleepWatchdog(schedule: WatchdogSchedule, generation: Int): Effect<Action> {
    val clock = watchdogClock ?: return Effect.none()
    return Effect.run<Action> { send ->
        // `sleep(until:)`: the deadline on the watchdog's monotonic clock.
        val remaining = schedule.deadline - clock.now()
        clock.sleep(if (remaining.isPositive()) remaining else Duration.ZERO)
        send(Action.WatchdogElapsed(generation))
    }.cancellable(CancelID.Watchdog, cancelInFlight = true)
}

/**
 * Permission/question prompts and queued input are both ephemeral server state that a dropped
 * event stream would desync, so one timer re-reads both while the session is open. (Re)starting it
 * reads at once and adopts [pendingWorkPollCadence]. Nothing reads while the server isn't
 * answering; in the background (a reconnect during a call) or under a cover it reads once, and the
 * return or the uncover starts the timer.
 */
internal fun SessionFeature.pollPendingWork(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, Action> {
    val session = state.session
    if (session == null || !state.requestsConnectionIsAvailable) {
        return Reduced(state.copy(pendingWorkPollSeconds = null))
    }
    val seconds = state.pendingWorkPollCadence
    val repeats = state.pollsServer
    val server = state.server
    val family = state.familySessions.map { it.id }
    val clock = watchdogClock ?: Clock.liveValue
    val requests = Effect.run<Action> { send ->
        while (currentCoroutineContext().isActive) {
            readPendingRequests(server, session, family, send)
            if (!repeats) return@run
            clock.sleep(seconds.seconds)
        }
    }.cancellable(CancelID.Requests, cancelInFlight = true)
    val inbox = Effect.run<Action> { send ->
        while (currentCoroutineContext().isActive) {
            send(Action.PendingInputsRequested(sessionID = session.id))
            if (!repeats) return@run
            clock.sleep(seconds.seconds)
        }
    }.cancellable(CancelID.Inbox, cancelInFlight = true)
    val pollSeconds = if (repeats) seconds else null
    return Reduced(state.copy(pendingWorkPollSeconds = pollSeconds), Effect.merge(requests, inbox))
}

internal fun SessionFeature.fetchQueuedInputs(server: OpenCodeServer, sessionID: String): Effect<Action> =
    Effect.run<Action> { send -> readQueuedInputs(server, sessionID, send) }
        .cancellable(CancelID.InboxFetch, cancelInFlight = true)

private suspend fun SessionFeature.readQueuedInputs(server: OpenCodeServer, sessionID: String, send: Send<Action>) {
    val inputs = try {
        openCodeClient.pendingInputs(server, sessionID)
    } catch (_: OpenCodeClientError) {
        return
    }
    if (!currentCoroutineContext().isActive) return
    send(Action.PendingInputsResponse(sessionID = sessionID, inputs = inputs))
}

internal fun SessionFeature.startPendingWork(
    state: SessionFeature.State,
    attempt: RequestDockFeature.State.LoadAttempt,
): Reduced<SessionFeature.State, Action> {
    if (state.session == null) return Reduced(state)
    return pollPendingWork(state.withRequestLoadAttempt(attempt).withRequestLoadError(null).withRequestArrivalsAreLive(false))
}

internal fun SessionFeature.retunePendingWork(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, Action> {
    val running = state.pendingWorkPollSeconds
    if (running == null || running == state.pendingWorkPollCadence) return Reduced(state)
    return pollPendingWork(state)
}

internal fun SessionFeature.pendingWorkAfterStreamConnected(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, Action> =
    if (state.pendingWorkPollSeconds == SETTLED_PENDING_WORK_SECONDS) pollPendingWork(state) else retunePendingWork(state)

internal fun SessionFeature.refreshRequests(state: SessionFeature.State): Effect<Action> {
    val session = state.session ?: return Effect.none()
    val server = state.server
    val family = state.familySessions.map { it.id }
    return Effect.run<Action> { send -> readPendingRequests(server, session, family, send) }
        .cancellable(CancelID.RequestsChanged, cancelInFlight = true)
}

/** Reads the requests pending anywhere in the selected session's family. */
private suspend fun SessionFeature.readPendingRequests(
    server: OpenCodeServer,
    session: OpenCodeSession,
    family: List<String>,
    send: Send<Action>,
) {
    val result = try {
        Result.success(openCodeClient.familyPendingRequests(server, family, session.directory))
    } catch (error: OpenCodeClientError) {
        Result.failure(error)
    }
    send(Action.PendingRequestsResponse(sessionID = session.id, result = result))
}

/** Sends the read receipt for an idle watermark the visible screen has just shown from an accepted authoritative read. */
internal fun SessionFeature.acknowledgeViewed(observed: OpenCodeSession, state: SessionFeature.State): Effect<Action> {
    val idle = observed.idleMilliseconds
    if (!state.isVisible || !state.readsOnView || idle == null ||
        idle <= (observed.viewedMilliseconds ?: -1) || idle <= (state.viewedIdle[observed.id] ?: -1)
    ) {
        return Effect.none()
    }
    val server = state.server
    val client = openCodeClient
    return Effect.run<Action> { send ->
        try {
            client.viewSession(server, observed.id, idle)
            send(Action.ViewAcknowledged(sessionID = observed.id, idle = idle))
        } catch (error: OpenCodeClientError) {
            AppLog.network.notice { "Couldn’t mark session viewed: ${error.logSummary}" }
        }
    }.cancellable(CancelID.ViewReceipt, cancelInFlight = true)
}

internal fun SessionFeature.authoritativeRefresh(
    server: OpenCodeServer,
    session: OpenCodeSession,
    requestID: Int,
    reason: RefreshReason,
    messageIsInWindow: Boolean,
    readsContext: Boolean,
): Effect<Action> {
    val client = openCodeClient
    val clock = clock
    suspend fun readContext(): OpenCodeContextReading =
        try { client.contextUsage(server, session.id) } catch (_: OpenCodeClientError) { OpenCodeContextReading.Inconclusive }
    suspend fun readSession(): OpenCodeSession? =
        try { client.getSession(server, session.id) } catch (_: OpenCodeClientError) { null }
    return Effect.run<Action> { send ->
        val result: Result<AuthoritativeRefresh> = try {
            val messageID = reason.messageID
            if (messageID != null && messageIsInWindow) {
                // A short trailing coalescing window absorbs text/tool/step endings for the same
                // durable assistant message into one authoritative GET.
                clock.sleep(200.milliseconds)
                coroutineScope {
                    // A settled message changes the context only when its step ended.
                    val reading = async { if (readsContext) readContext() else OpenCodeContextReading.Inconclusive }
                    val items = client.message(server, session.id, messageID)
                    Result.success(
                        AuthoritativeRefresh(
                            observed = session,
                            items = items,
                            scope = AuthoritativeRefresh.Scope.Message(messageID),
                            context = reading.await(),
                        ),
                    )
                }
            } else {
                coroutineScope {
                    val sessionRead = async { readSession() }
                    val loaded = client.transcriptWithContextUsageAndCursor(server, session.id, SessionFeature.initialFetchLimit)
                    val read = sessionRead.await()
                    Result.success(
                        AuthoritativeRefresh(
                            observed = read ?: session,
                            items = loaded.items,
                            scope = AuthoritativeRefresh.Scope.Page,
                            context = loaded.context,
                            observedIsRead = read != null,
                            pageCursor = loaded.pageCursor?.let { AuthoritativeRefresh.PageCursor(it.next, it.reachesStart, it.isComplete) },
                        ),
                    )
                }
            }
        } catch (error: OpenCodeClientError) {
            Result.failure(error)
        }
        if (!currentCoroutineContext().isActive) return@run
        send(Action.RefreshResponse(sessionID = session.id, requestID = requestID, result = result))
    }.cancellable(CancelID.Freshness)
}

internal fun SessionFeature.applyAuthoritative(
    items: List<OpenCodeTranscriptItem>,
    pageCursor: AuthoritativeRefresh.PageCursor? = null,
    state: SessionFeature.State,
    preserveProjection: Boolean,
): SessionFeature.State {
    var s = acceptWindow(items, pageCursor, state.copy(initialTranscriptIsFresh = true), preserveProjection)
    // Events received before this request began may describe an unfinished value absent from the
    // REST projection. Reapply by semantic part ID.
    if (s.liveText.isNotEmpty()) {
        val rows = ArrayList(s.items)
        var changed = false
        for ((id, text) in s.liveText) if (upsertLiveText(id, text, streaming = true, items = rows)) changed = true
        if (changed) s = s.copy(items = rows)
    }
    return s
}

/** Adopts a newest-messages window and settles the prompts it echoes. */
internal fun SessionFeature.acceptWindow(
    items: List<OpenCodeTranscriptItem>,
    pageCursor: AuthoritativeRefresh.PageCursor? = null,
    state: SessionFeature.State,
    preserveProjection: Boolean? = null,
): SessionFeature.State {
    var s = state
    // A newest window can pass a pending prompt using assistant messages alone. Keep that prompt in
    // paged history rather than appending its targeted echo below the newer replies. Compare
    // messages, not parts: replacing a projected part or removing a notice is not a window jump.
    val windowItemIDs = items.mapTo(HashSet()) { it.id }
    val windowIDs = SessionFeature.windowMessageIDs(items)
    if (s.pendingPromptPositions.isNotEmpty()) {
        s = s.copy(
            pendingPromptPositions = s.pendingPromptPositions.mapValues { (_, position) ->
                val before = windowIDs.isNotEmpty() && position.precedingMessageIDs.isNotEmpty() &&
                    windowIDs.none { it in position.precedingMessageIDs || it in position.precedingPromptIDs }
                if (position.isBeforeWindow == before) position else position.copy(isBeforeWindow = before)
            },
        )
    }
    val nonDurableIDs = s.projectedMessageIDs + s.echoedPromptIDs + s.optimisticPromptIDs + s.failedSends.ids
    val pendingNoticeIDs = s.pendingNoticeIDs
    fun isDurable(item: OpenCodeTranscriptItem): Boolean =
        !item.isInProgressText && !item.id.startsWith("info-") && item.id !in pendingNoticeIDs &&
            (nonDurableIDs.isEmpty() || SessionFeature.messageID(item) !in nonDurableIDs)
    // From the top until a durable row outside the window: in a long session that's the first
    // row, not every row.
    var hasDurableRows = false
    var allDurableRowsInWindow = true
    var scanned = 0
    for (item in s.items) {
        scanned += 1
        if (!isDurable(item)) continue
        hasDurableRows = true
        if (item.id !in windowItemIDs) {
            allDurableRowsInWindow = false
            break
        }
    }
    // The window's first row is near the end: looked for from there.
    val firstFetchedID = items.firstOrNull()?.id
    var firstFetchedIsDurable = false
    if (firstFetchedID != null) {
        for (index in s.items.indices.reversed()) {
            scanned += 1
            val item = s.items[index]
            if (item.id == firstFetchedID && isDurable(item)) {
                firstFetchedIsDurable = true
                break
            }
        }
    }
    TranscriptScan.record(scanned)
    val preserve = preserveProjection ?: (s.isRunning || s.refresh?.followup == RefreshReason.RunState)
    if (!preserve) s = s.resetLiveProjection()
    LabMarks.mark("session_data", sessionID = s.session?.id)
    s = s.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = null)
    val complete = pageCursor?.isComplete == true
    if (complete) {
        s = s.copy(
            earlierHistory = SessionFeature.State.EarlierHistory.Exhausted,
            earlierHistoryGeneration = if (s.isLoadingEarlier) s.earlierHistoryGeneration + 1 else s.earlierHistoryGeneration,
            earlierHistoryInsertionID = null,
            earlierHistoryError = null,
            earlierHistoryRetriesOnReconnect = false,
        )
    } else if (hasDurableRows && items.isNotEmpty() && !firstFetchedIsDurable) {
        // Reaching the oldest message once doesn't mean future refreshes can't skip an interval.
        // Reopen the cursor chain so browsing fills that gap.
        if (!allDurableRowsInWindow || s.earlierHistory != SessionFeature.State.EarlierHistory.UnknownEdge) {
            s = s.copy(earlierHistoryGeneration = s.earlierHistoryGeneration + 1)
        }
        s = s.copy(
            earlierHistory = SessionFeature.State.EarlierHistory.UnknownEdge,
            earlierHistoryInsertionID = null,
            earlierHistoryError = null,
        )
    }
    val previousFirstID = s.firstVisibleItemID
    val merged = SessionFeature.mergeTranscript(
        fetched = items,
        existing = s.items,
        optimisticPromptIDs = s.optimisticPromptIDs,
        projectedMessageIDs = if (preserve) s.projectedMessageIDs else emptySet(),
        echoedPromptIDs = s.echoedPromptIDs,
        pendingNoticeIDs = s.pendingNoticeIDs,
        complete = complete,
        preserveInProgressText = preserve,
    )
    if (!complete && !s.isLoadingEarlier && pageCursor != null && allDurableRowsInWindow && merged.firstOrNull()?.id == firstFetchedID) {
        val next = pageCursor.next
        s = if (next != null) {
            s.copy(earlierHistory = SessionFeature.State.EarlierHistory.Paging(next), earlierHistoryInsertionID = firstFetchedID)
        } else {
            s.copy(earlierHistory = SessionFeature.State.EarlierHistory.Exhausted, earlierHistoryInsertionID = null)
        }
    }
    s = reconcilePromptEchoes(items, s)
    if (merged != s.items) s = s.copy(items = merged).syncShellToolStatuses()
    s = s.retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    s = settleFailedSends(SessionFeature.promptIDs(items), s)
    // A window from the session's start names an unnamed session after its first prompt.
    if (pageCursor?.reachesStart == true) s = s.learningFirstPrompt(SessionTitle.firstPrompt(items, reachesStart = true))
    PerfTimeline.snapshotApplied(s.session?.id)?.let { s = s.copy(perfSnapshotRevision = it) }
    return s
}

/**
 * A send reported as failed reached the server after all: its failed state goes, here and in
 * `UndeliveredPrompts` (even if this screen never showed it). A transcript echo has already
 * replaced the bubble's rows; one still in the inbox keeps its bubble until its echo.
 */
internal fun SessionFeature.settleFailedSends(messageIDs: Set<String>, state: SessionFeature.State): SessionFeature.State {
    if (messageIDs.isEmpty()) return state
    undeliveredPrompts.resolve(messageIDs)
    val s = if (state.failedSends.any { it.id in messageIDs }) state.copy(failedSends = state.failedSends.filter { it.id !in messageIDs }) else state
    // A failed send the server had after all no longer holds back the rest.
    promptOutbox.settle(messageIDs)
    return s
}

internal fun SessionFeature.applyAuthoritativeMessage(
    fetched: List<OpenCodeTranscriptItem>,
    messageID: String,
    state: SessionFeature.State,
    preserveProjection: Boolean? = null,
): SessionFeature.State {
    LabMarks.mark("session_data", sessionID = state.session?.id)
    val rows = MessageRows(messageID)
    val indexes = state.items.indices.filter { rows.contains(state.items[it]) }
    val insertion = indexes.firstOrNull() ?: return state
    val last = indexes.last()
    // A durable boundary belongs to a part, not the whole message. A sibling tool can have started
    // before this read (so it is not in racedEvents) and still have no REST payload. Use the same
    // protection as page reads.
    val preserve = preserveProjection ?: (state.isRunning || state.refresh?.followup == RefreshReason.RunState)
    val replacement = if (preserve && messageID in state.projectedMessageIDs) {
        SessionFeature.mergeTranscript(fetched = fetched, existing = indexes.map { state.items[it] }, projectedMessageIDs = setOf(messageID))
    } else if (preserve) {
        SessionFeature.preservingInProgressText(fetched, indexes.map { state.items[it] })
    } else {
        fetched
    }
    var s = if (!preserve) state.resetLiveProjection() else state
    // A notice shown between this message's parts (a shell finishing after a read, with the next
    // paragraph streamed below it) stays after the part it followed, inside that Used N group.
    // Moved below the whole message it would be an Updates row of its own until a page read placed it.
    val notices = ArrayList<Pair<String, OpenCodeTranscriptItem>>()
    var part = s.items[insertion].id
    for (item in s.items.subList(insertion, last + 1)) {
        if (rows.contains(item)) part = item.id
        else if (item is OpenCodeTranscriptItem.Notice) notices += part to item
    }
    val noticeIDs = notices.mapTo(HashSet()) { it.second.id }
    val replacementIDs = replacement.mapTo(HashSet()) { it.id }
    val placed = replacement.flatMap { item -> listOf(item) + notices.filter { it.first == item.id }.map { it.second } } +
        notices.filter { it.first !in replacementIDs }.map { it.second }
    val previousFirstID = s.firstVisibleItemID
    // Check before replacing the list: equal remove/insert still invalidates transcript observers.
    // Comparing the whole span also detects interleaved non-message rows, whose existing
    // remove/insert semantics must be preserved.
    if (s.items.subList(insertion, last + 1) != placed) {
        val kept = s.items.filterNotTo(ArrayList()) { rows.contains(it) || it.id in noticeIDs }
        kept.addAll(minOf(insertion, kept.size), placed)
        s = s.copy(items = kept).syncShellToolStatuses()
    }
    s = reconcilePromptEchoes(fetched, s)
    val prefix = "$messageID-"
    if (s.liveText.keys.any { it.startsWith(prefix) }) {
        val rows = ArrayList(s.items)
        var changed = false
        for ((id, text) in s.liveText) if (id.startsWith(prefix) && upsertLiveText(id, text, streaming = true, items = rows)) changed = true
        if (changed) s = s.copy(items = rows)
    }
    return s.retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
}
