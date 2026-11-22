package ai.opencode.feature.session

import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.session.SessionFeature.State.EarlierHistory
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/** The New divider, earlier history, and transcript reads. Null: the action passes on. */
internal fun SessionFeature.reduceHistory(
    state: SessionFeature.State,
    action: Action,
): Reduced<SessionFeature.State, Action>? = when (action) {
    is Action.NewMessagesPassed -> Reduced(
        if (state.newMessages?.generation == action.generation) {
            state.copy(newMessages = null, newMessagesEarlierBaseline = null)
        } else {
            state
        },
    )


    is Action.NewMessagesFit -> {
        val mark = state.newMessages
        if (mark == null || mark.generation != action.generation) {
            Reduced(state)
        } else {
            var s = state.copy(
                newMessages = null,
                newMessagesEarlierBaseline = null,
                newMessagesFitThrough = mark.lastItemID ?: state.items.lastOrNull()?.id,
            )
            if (s.watchesTranscript) s = s.absorbFittingNewMessages()
            Reduced(s)
        }
    }

    is Action.TranscriptBrowsingChanged ->
        if (action.isBrowsing) {
            val s = if (state.browsingWindowStartID == null) state.copy(browsingWindowStartID = state.firstVisibleItemID) else state
            Reduced(s, Effect.cancel(CancelID.WindowReset))
        } else {
            val previousFirstID = state.visibleItems.firstOrNull()?.id
            val s = state.copy(browsingWindowStartID = null).retainRenderedWindowStart(previousFirstID)
            Reduced(s, scheduleWindowReset(s))
        }

    is Action.RenderedWindowSettled -> {
        if (state.browsingWindowStartID != null || !state.hasRenderedFirstContentFrame || state.isLoadingEarlier) {
            Reduced(state)
        } else {
            val limit = state.settledVisibleLimit
            Reduced(if (limit < state.visibleLimit) state.copy(visibleLimit = limit) else state)
        }
    }

    is Action.ShowEarlierTapped ->
        if (state.isLoadingEarlier || !state.hasEarlierItems || state.session == null) {
            Reduced(state)
        } else {
            val loaded = loadEarlierHistory(state.copy(visibleLimit = state.visibleLimit + 40).pinBrowsingWindowIfNeeded())
            // Paging back in is browsing: the window mustn't settle under it.
            Reduced(loaded.state, Effect.merge(Effect.cancel(CancelID.WindowReset), loaded.effect))
        }

    is Action.FillEarlierHistory -> loadEarlierHistory(state)

    Action.SessionMediaLoadEarlierTapped -> sessionMediaLoadEarlier(state)

    is Action.EarlierHistoryResponse -> followSessionMediaEarlier(earlierHistoryResponse(state, action))

    is Action.TranscriptResponse -> transcriptResponse(state, action)

    // Every other action matched a case earlier in the chain.
    else -> null
}

/**
 * Session Media lists every loaded row, so its Load Earlier always reads an older page: the
 * transcript's window first takes in every loaded row (only then does [loadEarlierHistory] read).
 */
private fun SessionFeature.sessionMediaLoadEarlier(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    val filter = state.sessionMediaFilter
    if (filter == null || state.session == null || state.isLoadingEarlier || state.earlierHistory == EarlierHistory.Exhausted) {
        AppLog.app.debug { "Session Media: load earlier ignored (open ${filter != null}, loading ${state.isLoadingEarlier}, phase ${earlierPhase(state)})" }
        return Reduced(state)
    }
    val baseline = SessionMedia.count(state, filter)
    val widened = state.copy(
        visibleLimit = maxOf(state.visibleLimit, state.items.size),
        earlierHistoryRetriesOnReconnect = false,
        sessionMediaNothingNew = false,
    ).pinBrowsingWindowIfNeeded()
    val loaded = loadEarlierHistory(widened)
    if (!loaded.state.isLoadingEarlier) {
        AppLog.app.debug { "Session Media: load earlier sent nothing (rows ${state.items.size}, phase ${earlierPhase(state)})" }
        return Reduced(loaded.state)
    }
    AppLog.app.debug { "Session Media: load earlier ${filter.id} from ${state.items.size} rows, $baseline items" }
    return Reduced(
        loaded.state.copy(sessionMediaEarlier = SessionMedia.EarlierSearch(filter, baseline, SessionMedia.EARLIER_PAGES_PER_TAP - 1)),
        Effect.merge(Effect.cancel(CancelID.WindowReset), loaded.effect),
    )
}

/**
 * After an older page lands for Session Media: done once its tab gained an item, history is
 * exhausted, or the page failed; otherwise the next page, up to [SessionMedia.EARLIER_PAGES_PER_TAP]
 * per tap, then it says nothing new turned up rather than looking like a tap that did nothing.
 */
private fun SessionFeature.followSessionMediaEarlier(reduced: Reduced<SessionFeature.State, Action>): Reduced<SessionFeature.State, Action> {
    val search = reduced.state.sessionMediaEarlier ?: return reduced
    val s = reduced.state
    if (s.isLoadingEarlier) return reduced
    fun done(nothingNew: Boolean) = Reduced(s.copy(sessionMediaEarlier = null, sessionMediaNothingNew = nothingNew), reduced.effect)
    if (s.sessionMediaFilter != search.filter) return done(false)
    if (s.earlierHistoryError != null || s.earlierHistoryRetriesOnReconnect) {
        AppLog.app.debug { "Session Media: load earlier failed (status ${if (s.earlierHistoryRetriesOnReconnect) "none" else "http"})" }
        return done(false)
    }
    val count = SessionMedia.count(s, search.filter)
    val exhausted = s.earlierHistory == EarlierHistory.Exhausted
    AppLog.app.debug { "Session Media: page landed, rows ${s.items.size}, ${search.filter.id} $count (was ${search.baseline}), pages left ${search.pagesLeft}, exhausted $exhausted" }
    if (count > search.baseline || exhausted) return done(false)
    if (search.pagesLeft <= 0) return done(true)
    val next = loadEarlierHistory(s.copy(visibleLimit = maxOf(s.visibleLimit, s.items.size), sessionMediaEarlier = search.copy(pagesLeft = search.pagesLeft - 1)))
    if (!next.state.isLoadingEarlier) return done(true)
    return Reduced(next.state, Effect.merge(reduced.effect, next.effect))
}

private fun earlierPhase(state: SessionFeature.State): String = when (state.earlierHistory) {
    EarlierHistory.UnknownEdge -> "unknown-edge"
    is EarlierHistory.SeekingOverlap -> "seeking"
    is EarlierHistory.Paging -> "paging"
    is EarlierHistory.Loading -> "loading"
    EarlierHistory.Exhausted -> "exhausted"
}

private fun SessionFeature.earlierHistoryResponse(
    state: SessionFeature.State,
    action: Action.EarlierHistoryResponse,
): Reduced<SessionFeature.State, Action> {
    val sessionID = action.sessionID
    val cursor = action.cursor
    val loading = state.earlierHistory as? EarlierHistory.Loading
    if (state.session?.id != sessionID || state.earlierHistoryGeneration != action.generation ||
        loading == null || loading.cursor != cursor
    ) {
        return Reduced(state)
    }
    val reachedLoadedItems = loading.reachedLoadedItems
    val retryPhase: EarlierHistory = if (reachedLoadedItems) EarlierHistory.Paging(cursor) else EarlierHistory.SeekingOverlap(cursor)
    var s = state.copy(earlierHistory = retryPhase)
    // Home caches the newest rows and the read watermark; an older page changes neither unless it
    // lands among them or moves the divider.
    val cachedTail = SessionFeature.cacheTailIDs(s)
    val previousMark = s.newMessages
    // A divider at the top of the window: the page puts seen rows above it, which moves Home's
    // seen watermark.
    val markWasFirst = previousMark != null && previousMark.itemID == s.items.firstOrNull()?.id
    // Only rows this page added can link sessions not looked up yet.
    var added: List<OpenCodeTranscriptItem> = emptyList()
    val page: OpenCodeTranscriptPage? = action.result.getOrNull()
    if (page == null) {
        val error = action.result.exceptionOrNull()?.asClientError() ?: OpenCodeClientError(localized(R.string.session_something_went_wrong))
        // No HTTP status means the request never reached the server. The connection state already
        // covers that; don't open a session on an error banner for a page nobody asked for yet.
        s = if (error.statusCode == null) s.copy(earlierHistoryRetriesOnReconnect = true) else s.copy(earlierHistoryError = error.message)
    } else {
        if (page.next != null && page.next == cursor) {
            return Reduced(s.copy(earlierHistoryError = localized(R.string.session_load_earlier_failed)))
        }
        val merged = SessionFeature.mergeOlderPage(
            page.items, s.items, before = s.earlierHistoryInsertionID, reachedLoadedItems = reachedLoadedItems,
        ) { it.id }
        if (merged != null) {
            if (merged != s.items) {
                added = page.items
                s = s.copy(items = merged).syncShellToolStatuses()
            }
            s = s.copy(
                earlierHistoryInsertionID = page.items.firstOrNull()?.id ?: s.earlierHistoryInsertionID,
                earlierHistory = page.next?.let { EarlierHistory.Paging(it) } ?: EarlierHistory.Exhausted,
            )
            if (added.isNotEmpty()) {
                s = s.recomputeNewMessagesAfterEarlierHistory()
            } else if (s.earlierHistory == EarlierHistory.Exhausted) {
                s = s.copy(newMessagesEarlierBaseline = null)
            }
        } else {
            // A busy session can advance past the initial window while paging starts. Walk back to
            // an overlap before treating unknown rows as older content.
            if (page.next == null && page.items.isNotEmpty()) {
                return Reduced(
                    s.copy(
                        earlierHistory = EarlierHistory.SeekingOverlap(null),
                        earlierHistoryError = localized(R.string.session_load_earlier_failed),
                    ),
                )
            }
            s = s.copy(earlierHistory = page.next?.let { EarlierHistory.SeekingOverlap(it) } ?: EarlierHistory.Exhausted)
        }
        s = s.copy(earlierHistoryError = null).pinBrowsingWindowIfNeeded()
    }
    // The first cursor page mostly overlaps the newest REST window. Keep filling the visible limit
    // the user already requested instead of relying on another top-edge callback during the
    // prepend transition.
    PerfTimeline.olderPageApplied(sessionID, exhausted = s.earlierHistory == EarlierHistory.Exhausted, failed = page == null)
    if (page == null) return Reduced(s)
    val mark = s.newMessages
    val publishes = mark != previousMark || SessionFeature.cacheTailIDs(s) != cachedTail ||
        (markWasFirst && mark != null && mark.itemID != s.items.firstOrNull()?.id)
    return Reduced(
        s,
        Effect.merge(
            sessionLinksIn(added),
            if (publishes) Effect.send(Action.AcceptedTranscriptUpdated(sessionID = sessionID, items = s.cacheableItems)) else Effect.none(),
            if (s.earlierHistory != EarlierHistory.Exhausted && s.visibleLimit >= s.items.size) Effect.send(Action.FillEarlierHistory) else Effect.none(),
        ),
    )
}

private fun SessionFeature.transcriptResponse(
    state: SessionFeature.State,
    action: Action.TranscriptResponse,
): Reduced<SessionFeature.State, Action> {
    val sessionID = action.sessionID
    if (state.session?.id != sessionID) return Reduced(state)
    val items = action.result.getOrNull()
    if (items == null) {
        val error = action.result.exceptionOrNull()?.asClientError() ?: OpenCodeClientError(localized(R.string.session_something_went_wrong))
        var s = state.copy(initialTranscriptRequestIsExternal = false, isLoading = false)
        if (s.cacheableItems.isEmpty() && !s.initialTranscriptIsFresh) s = s.copy(loadError = error.message)
        return Reduced(s, if (s.items.isEmpty()) Effect.send(Action.FirstContentFrameRendered) else Effect.none())
    }
    val completedInitialRequest = state.initialTranscriptRequestIsExternal
    var s = if (completedInitialRequest) state.copy(initialTranscriptIsFresh = true) else state
    s = acceptWindow(items, action.pageCursor, s)
    s = s.updateNewMessagesMark(settles = s.isVisible)
    return Reduced(
        s,
        Effect.merge(
            sessionLinksIn(items),
            Effect.send(Action.AcceptedTranscriptUpdated(sessionID = sessionID, items = s.cacheableItems)),
            // An empty transcript never renders a content frame of its own.
            if (completedInitialRequest && s.items.isEmpty()) Effect.send(Action.FirstContentFrameRendered) else Effect.none(),
        ),
    )
}

/**
 * O(loaded + page) reconciliation. Index IDs once, splice through integer links, then materialize
 * once. Older values never replace newer ones, and unknown rows after the last known anchor remain
 * ignored. Indexing newly inserted nodes also preserves the reverse-scan rule for repeated page
 * IDs. `null` means a disjoint page must keep seeking overlap.
 */
internal fun <Item, ID> SessionFeature.Companion.mergeOlderPage(
    page: List<Item>,
    existing: List<Item>,
    before: ID?,
    reachedLoadedItems: Boolean,
    id: (Item) -> ID,
): List<Item>? {
    val positions = HashMap<ID, Int>(existing.size + page.size)
    for ((index, item) in existing.withIndex()) positions.putIfAbsent(id(item), index)
    val pageIDs = page.map(id)
    if (pageIDs.none { it in positions }) {
        if (!reachedLoadedItems && existing.isNotEmpty()) return null
        if (page.isEmpty()) return existing
        val insertion = before?.let { positions[it] } ?: 0
        return existing.subList(0, insertion) + page + existing.subList(insertion, existing.size)
    }
    if (pageIDs.all { it in positions }) return existing
    val values = ArrayList(existing)
    val previous = ArrayList<Int>(existing.size + page.size).apply { for (index in existing.indices) add(index - 1) }
    val next = ArrayList<Int>(existing.size + page.size).apply { for (index in existing.indices) add(if (index + 1 < existing.size) index + 1 else -1) }
    var first = if (existing.isEmpty()) -1 else 0
    var insertion: Int? = before?.let { positions[it] }
    for (index in page.indices.reversed()) {
        val known = positions[pageIDs[index]]
        if (known != null) {
            insertion = known
        } else {
            val anchor = insertion ?: continue
            val node = values.size
            val predecessor = previous[anchor]
            values += page[index]
            previous += predecessor
            next += anchor
            if (predecessor < 0) first = node else next[predecessor] = node
            previous[anchor] = node
            positions[pageIDs[index]] = node
            // The old reverse loop inserted at the same numeric index, so the next unknown row
            // belongs immediately before this inserted row.
            insertion = node
        }
    }
    if (values.size == existing.size) return existing
    val result = ArrayList<Item>(values.size)
    var node = first
    while (node >= 0) {
        result += values[node]
        node = next[node]
    }
    return result
}

private fun SessionFeature.loadEarlierHistory(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    val sessionID = state.session?.id
    if (state.isLoadingEarlier || !state.hasEarlierItems || sessionID == null ||
        state.visibleLimit < state.items.size || state.earlierHistory == EarlierHistory.Exhausted
    ) {
        return Reduced(state)
    }
    val (cursor, reachedLoadedItems) = when (val phase = state.earlierHistory) {
        EarlierHistory.UnknownEdge -> null to false
        is EarlierHistory.SeekingOverlap -> phase.cursor to false
        is EarlierHistory.Paging -> phase.cursor to true
        is EarlierHistory.Loading, EarlierHistory.Exhausted -> return Reduced(state)
    }
    val s = state.copy(
        earlierHistory = EarlierHistory.Loading(cursor = cursor, reachedLoadedItems = reachedLoadedItems),
        earlierHistoryError = null,
    )
    PerfTimeline.olderPageRequested(sessionID)
    val server = s.server
    val generation = s.earlierHistoryGeneration
    val client = openCodeClient
    return Reduced(
        s,
        Effect.run<Action> { send ->
            val result = try {
                Result.success(client.historyPage(server, sessionID, cursor))
            } catch (error: OpenCodeClientError) {
                Result.failure(error)
            }
            if (!currentCoroutineContext().isActive) return@run
            send(Action.EarlierHistoryResponse(sessionID = sessionID, generation = generation, cursor = cursor, result = result))
        }.cancellable(CancelID.EarlierHistory, cancelInFlight = true),
    )
}

/** Drops the in-flight read and its resync cue. A late response no longer matches `refresh` and is rejected as stale. */
internal fun SessionFeature.abandonRefresh(state: SessionFeature.State): Reduced<SessionFeature.State, Action> = Reduced(
    state.copy(refresh = null, resync = null),
    Effect.cancel(listOf(CancelID.Freshness, CancelID.ResyncDelay, CancelID.ResyncMinimum)),
)

/** Bar drafts are keyed by request or "request/field" (`answerTarget`). */
internal fun SessionFeature.Companion.pruneAnswerDrafts(drafts: Map<String, String>, live: Set<String>): Map<String, String> {
    fun isLive(key: String): Boolean = key in live || key.indexOf('/').let { it >= 0 && key.substring(0, it) in live }
    return if (drafts.keys.any { !isLive(it) }) drafts.filterKeys(::isLive) else drafts
}

/** Drops entries whose keys are no longer in [live]. */
internal fun <V> SessionFeature.Companion.prune(values: Map<String, V>, live: Set<String>): Map<String, V> =
    if (values.keys.any { it !in live }) values.filterKeys { it in live } else values

/** A quick read never shows the cue; once shown it holds long enough to read as one breath rather than a flicker. */
internal val SessionFeature.Companion.resyncCueDelay get() = 350.milliseconds
internal val SessionFeature.Companion.resyncCueMinimum get() = 900.milliseconds

internal fun SessionFeature.beginResync(state: SessionFeature.State, refreshID: Int): Reduced<SessionFeature.State, Action> {
    if (state.session == null) return Reduced(state)
    // A read starting while the title breathes keeps it breathing: it now waits on this read, and
    // its minimum still counts from when it showed.
    val resync = state.resync
    if (resync != null && resync.isShown) {
        return Reduced(state.copy(resync = resync.copy(refreshID = refreshID, responseReceived = false)))
    }
    val clock = clock
    return Reduced(
        state.copy(resync = SessionFeature.State.ResyncState(refreshID = refreshID)),
        Effect.run<Action> { send ->
            clock.sleep(SessionFeature.resyncCueDelay)
            send(Action.ResyncDelayElapsed(refreshID = refreshID))
        }.cancellable(CancelID.ResyncDelay, cancelInFlight = true),
    )
}

internal fun SessionFeature.settleResync(state: SessionFeature.State, refreshID: Int): Reduced<SessionFeature.State, Action> {
    val resync = state.resync
    if (resync == null || resync.refreshID != refreshID) return Reduced(state)
    return if (!resync.isShown || resync.minimumElapsed) {
        Reduced(state.copy(resync = null), Effect.cancel(listOf(CancelID.ResyncDelay, CancelID.ResyncMinimum)))
    } else {
        Reduced(state.copy(resync = resync.copy(responseReceived = true)))
    }
}

/** The newest rows Home caches, past the prompts it leaves out. */
internal fun SessionFeature.Companion.cacheTailIDs(state: SessionFeature.State): List<String> {
    val excluded = state.optimisticPromptIDs.size + state.failedSends.size
    val count = SessionFeature.initialFetchLimit + 3 * excluded
    val start = maxOf(0, state.items.size - count)
    val ids = ArrayList<String>()
    for (index in start until state.items.size) ids += state.items[index].id
    return ids
}

/**
 * The window keeps the rows a returning reader glides past; once they've been at the end a moment
 * it lets go of the history paged in above (`RenderedWindowSettled`), so every later update works
 * on a screen or two of rows rather than everything they paged through. The wait runs on the clock
 * the glide runs on: slowed with animation slow motion.
 */
internal fun SessionFeature.scheduleWindowReset(state: SessionFeature.State): Effect<Action> {
    if (!sessionTimers.windowReset || state.visibleLimit <= SessionFeature.baseVisibleLimit) return Effect.none()
    val virtualSeconds = SessionFeature.windowResetDelay.inWholeMilliseconds / 1000.0
    val delay = (AnimationTimeScale.shared.wallSeconds(virtualSeconds) * 1000).roundToLong().milliseconds
    val clock = clock
    return Effect.run<Action> { send ->
        clock.sleep(delay)
        send(Action.RenderedWindowSettled)
    }.cancellable(CancelID.WindowReset, cancelInFlight = true)
}
