package ai.opencode.feature.drawer

import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.CancelID
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.session.WorkingRun
import ai.opencode.feature.session.dockRequestID
import ai.opencode.feature.session.isRunning
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes.ContentState.Phase
import ai.opencode.feature.system.liveactivity.SessionLiveActivity
import ai.opencode.feature.system.liveactivity.SessionLiveActivityClient
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/*
 * Session Live Activity (Experimental): a send from this phone starts the run's activity (Android:
 * an ongoing notification) while the app is on screen, and Home keeps it current while the app
 * runs. Away, the stream's half minute and one background wait are all it gets: there is no push,
 * so a run still going after that shows its last state until the app runs again.
 */

internal fun State.sessionActivityTitle(sessionID: String, fallback: String): String =
    sessions.firstOrNull { it.id == sessionID }?.title?.ifEmpty { null }
        ?: sessionDetail?.session?.takeIf { it.id == sessionID }?.title?.ifEmpty { null }
        ?: fallback

/** The tracked run as the activity shows it. */
val State.sessionActivityRun: SessionLiveActivity.Run?
    get() {
        val tracked = sessionActivity ?: return null
        val id = tracked.attributes.sessionID
        val detail = sessionDetail?.takeIf { it.session?.id == id }
        val title = sessionActivityTitle(id, tracked.content.title)
        // Over, with all it can show: nothing left to read.
        if (tracked.content.phase == Phase.failed || (tracked.content.phase == Phase.done && tracked.content.preview != null)) {
            return SessionLiveActivity.Run(
                title = title, isServerActive = false, isWorking = false, needsInput = false, preview = tracked.content.preview,
            )
        }
        val isServerActive = id in activeSessionIDs
        val waitedIdle = tracked.settledItems != null
        val isWorking = when {
            waitedIdle -> false
            // The open screen awaits the reply from the send on.
            detail != null -> detail.isRunning || isServerActive
            // Elsewhere, it works until the server has run it and stopped.
            else -> isServerActive || !tracked.sawServerRun
        }
        val needsInput = !waitedIdle && (id in attentionSessionIDs || detail?.dockRequestID != null)
        val run = SessionLiveActivity.Run(
            title = title, isServerActive = isServerActive, isWorking = isWorking, needsInput = needsInput, step = tracked.step,
        )
        if (isWorking || needsInput) return run
        val items = tracked.settledItems ?: detail?.items ?: transcriptCache[id] ?: return run
        return run.copy(
            outcome = WorkingRun.outcome(items)?.let { SessionLiveActivity.Outcome.valueOf(it.name) },
            preview = SessionLiveActivity.preview(items, proseLines = ::markdownProseLines),
        )
    }

/**
 * A reply's paragraph, list item, numbered item, blockquote and heading texts in order, for the
 * Done preview.
 */
internal fun markdownProseLines(markdown: String): Sequence<String> =
    OpenCodeMarkdownBlock.blocks(markdown, inlineCache = null).asSequence().mapNotNull { block ->
        when (val kind = block.kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text
            else -> null
        }
    }

/**
 * Whether a fast stream action can change what the followed run shows. Settled, only a missing Done
 * preview can still catch up, and only its own session's items bring it; failed or Done with a
 * preview, stream traffic changes nothing (a rename comes by the full path).
 */
fun State.streamMovesSessionActivity(action: Action): Boolean {
    val tracked = sessionActivity ?: return false
    if (!tracked.content.phase.isSettled) return true
    if (tracked.content.phase != Phase.done || tracked.content.preview != null) return false
    val id = tracked.attributes.sessionID
    return when (action) {
        is Action.ServerEvent -> when (val event = action.event) {
            is OpenCodeServerEvent.ContentBatch -> event.sessionID == id
            is OpenCodeServerEvent.MessageInvalidated -> event.sessionID == id
            else -> true
        }
        is Action.TranscriptPrefetched -> action.sessionID == id
        is Action.CachedTranscriptRefreshed -> action.sessionID == id
        is Action.SessionDetail -> sessionDetail?.session?.id == id
        else -> true
    }
}

private val sessionLiveActivityOn: Boolean
    get() = dependency(ExperimentalFeatures)(ExperimentalFeature.sessionLiveActivity)

/** An accepted send from the open screen (or the session a draft's send created) starts following its run, replacing any run before. */
internal fun HomeFeature.startSessionActivity(state: State, sessionID: String): HomeReduced {
    if (!sessionLiveActivityOn) return Reduced(state)
    val title = state.sessionActivityTitle(sessionID, fallback = localized(R.string.drawer_session_activity_new_session))
    val attributes = SessionActivityAttributes(serverKey = SessionDirectory.serverKey(state.server.persistenceID), sessionID = sessionID)
    val content = SessionLiveActivity.Content(title = title, phase = Phase.working, startedAt = dependency(DateClient).now())
    val client = dependency(SessionLiveActivityClient)
    return Reduced(
        state.copy(sessionActivity = SessionLiveActivity.Tracked(attributes = attributes, content = content)),
        Effect.merge(
            Effect.cancel(CancelID.SessionActivityWait),
            Effect.run { client.update(content, attributes) },
        ),
    )
}

/** The run's steps come from its live events, wherever the session is. */
internal fun State.notingSessionActivityStep(sessionID: String, events: List<OpenCodeLiveContentEvent>): State {
    val tracked = sessionActivity?.takeIf { it.attributes.sessionID == sessionID } ?: return this
    val step = SessionLiveActivity.step(events) ?: return this
    if (tracked.step == step) return this
    return copy(sessionActivity = tracked.copy(step = step))
}

/**
 * Leaving with the run still going: one long poll for its end inside the background time the
 * system gives, then a read for its reply.
 */
internal fun HomeFeature.waitToSettleSessionActivity(state: State): Effect<Action> {
    val tracked = state.sessionActivity
    if (!sessionLiveActivityOn || tracked == null || tracked.content.phase.isSettled || tracked.settledItems != null) return Effect.none()
    val server = state.server
    val sessionID = tracked.attributes.sessionID
    val openCodeClient = dependency(OpenCodeClient)
    val backgroundTask = dependency(BackgroundTaskClient)
    val clock = dependency(Clock)
    return Effect.run<Action> { send ->
        val end = backgroundTask.begin("Session activity")
        try {
            val idle = coroutineScope {
                val wait = async { succeeds { openCodeClient.waitForIdle(server, sessionID) } }
                val timer = async {
                    succeeds { clock.sleep(SessionLiveActivity.backgroundWait) }
                    false
                }
                val first = select { wait.onAwait { it }; timer.onAwait { it } }
                wait.cancel()
                timer.cancel()
                first
            }
            if (!idle || !currentCoroutineContext().isActive) return@run
            val items = try {
                openCodeClient.transcript(server, sessionID, SessionLiveActivity.SETTLED_READ_LIMIT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@run
            }
            send(Action.SessionActivitySettled(sessionID, items))
        } finally {
            end()
        }
    }.cancellable(CancelID.SessionActivityWait, cancelInFlight = true)
}

/** Rethrowing cancellation. */
private suspend inline fun succeeds(block: () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    false
}

/** Idle before the server ran it means the send hadn't started its run yet: not an end. */
internal fun State.notingSessionActivitySettled(sessionID: String, items: List<OpenCodeTranscriptItem>): State {
    val tracked = sessionActivity?.takeIf { it.attributes.sessionID == sessionID && it.sawServerRun } ?: return this
    return copy(sessionActivity = tracked.copy(settledItems = items))
}

/** The experiment switched off: the activity goes, and with it the tracking and any background wait. */
internal fun HomeFeature.stopSessionActivity(state: State): HomeReduced {
    if (state.sessionActivity == null) return Reduced(state)
    val client = dependency(SessionLiveActivityClient)
    return Reduced(
        state.copy(sessionActivity = null),
        Effect.merge(
            Effect.cancel(CancelID.SessionActivityWait),
            Effect.run { client.update(null, null) },
        ),
    )
}

/** Pushed only when what it shows changes. */
internal fun HomeFeature.syncSessionActivity(state: State): HomeReduced {
    var tracked = state.sessionActivity ?: return Reduced(state)
    if (!sessionLiveActivityOn) return stopSessionActivity(state)
    val run = state.sessionActivityRun
    if (run?.isServerActive == true) tracked = tracked.copy(sawServerRun = true)
    val content = SessionLiveActivity.content(run, tracked.content) ?: return stopSessionActivity(state)
    val changed = content != tracked.content
    tracked = tracked.copy(content = content)
    val next = if (state.sessionActivity != tracked) state.copy(sessionActivity = tracked) else state
    if (!changed) return Reduced(next)
    val attributes = tracked.attributes
    val client = dependency(SessionLiveActivityClient)
    return Reduced(next, Effect.run { client.update(content, attributes) })
}
