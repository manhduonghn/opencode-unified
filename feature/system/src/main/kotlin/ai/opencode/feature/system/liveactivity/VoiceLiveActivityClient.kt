package ai.opencode.feature.system.liveactivity

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private typealias CallContentPhase = VoiceCallActivityAttributes.ContentState.Phase

/** How a voice call reads on the lock screen and in the status bar. */
object VoiceLiveActivity {
    typealias Content = VoiceCallActivityAttributes.ContentState

    /**
     * `VoiceFeature.State.Phase` (in `:feature:voice`, which this module can't see): the
     * session area maps the call's phase onto this.
     */
    enum class CallPhase { Connecting, Live, EndingAfterGoodbye, Ended, Failed, Dropped }

    /**
     * The part of a call the Live Update shows; observing only this keeps the call's captions
     * and audio traffic from re-running the mapping. Built from `VoiceFeature.State` by the
     * session area (`phase`, `isMuted`, `isReconnecting`, `target.server.id`,
     * `target.sessionID ?: ""`).
     */
    data class Call(
        val phase: CallPhase,
        val isMuted: Boolean,
        /** Already delayed: a reconnect shorter than a second never sets it. */
        val isReconnecting: Boolean,
        val serverID: String,
        val sessionID: String,
    )

    /**
     * Null when there is no call, including one the user or model hung up (which stays in
     * state, ended, while its bar sweeps out). Calls that stop any other way read “Call ended”
     * before dismissing.
     */
    fun content(call: Call?, previous: Content?, now: Instant): Content? {
        call ?: return null
        val phase = when (call.phase) {
            CallPhase.Connecting -> CallContentPhase.Connecting
            CallPhase.Live, CallPhase.EndingAfterGoodbye -> if (call.isReconnecting) CallContentPhase.Reconnecting else CallContentPhase.Live
            CallPhase.Ended -> return null
            CallPhase.Failed, CallPhase.Dropped -> CallContentPhase.Ended
        }
        val carriedStart = if (previous?.phase == CallContentPhase.Ended) null else previous?.startedAt
        val startedAt = carriedStart ?: if (phase == CallContentPhase.Live || phase == CallContentPhase.Reconnecting) now else null
        return Content(phase, call.isMuted, if (phase == CallContentPhase.Ended) previous?.startedAt else startedAt)
    }

    fun attributes(call: Call) = VoiceCallActivityAttributes(call.serverID, call.sessionID)
}

class VoiceLiveActivityClient(
    /**
     * Shows `content` in the call's one Live Update, starting it if needed; null dismisses it
     * at once and an ended state dismisses it after a moment. `attributes` are only read when
     * one starts.
     */
    val update: suspend (VoiceCallActivityAttributes.ContentState?, VoiceCallActivityAttributes?) -> Unit,
    /** Live Updates a previous launch left behind; no call survives a relaunch. */
    val endAll: suspend () -> Unit,
    /** Button taps from the Live Update. */
    val commands: () -> Flow<VoiceCallCommand>,
    /** A background Start Voice Call's Live Update, shown as Connecting before the app starts the call. False when it couldn't start. */
    val prestart: suspend (VoiceCallActivityAttributes) -> Boolean = { false },
    /** Waits up to `timeout` for the call to take over the prestarted Live Update, ending it and returning false if the call never starts. */
    val awaitCall: suspend (Duration) -> Boolean = { false },
) {
    companion object Key : DependencyKey<VoiceLiveActivityClient> {
        override val liveValue: VoiceLiveActivityClient by lazy {
            VoiceLiveActivityClient(
                update = { content, attributes -> LiveVoiceActivity.shared.enqueue(LiveVoiceActivity.Request.Update(content, attributes)) },
                endAll = { LiveVoiceActivity.shared.enqueue(LiveVoiceActivity.Request.EndAll) },
                commands = { LiveActivityCommands.voiceCall.stream() },
                prestart = { LiveVoiceActivity.shared.prestart(it) },
                awaitCall = { LiveVoiceActivity.shared.awaitCall(it) },
            )
        }

        override val testValue = VoiceLiveActivityClient(update = { _, _ -> }, endAll = {}, commands = { emptyFlow() })
    }
}

/** What a problem report says about Live Updates. */
data class LiveActivityStatus(val isEnabled: Boolean? = null, val last: String? = null) {
    val summary: String?
        get() {
            if (isEnabled == null && last == null) return null
            val enabled = when (isEnabled) {
                true -> "On"
                false -> "Off in Settings"
                null -> "Unknown"
            }
            return listOfNotNull(enabled, last).joinToString(" · ")
        }

    companion object {
        val current = AtomicReference(LiveActivityStatus())
    }
}

/**
 * Owns the one voice-call Live Update. Requests run in order, so an end never overtakes the update
 * before it. Every step it takes is recorded in `AppLog.voice`, so a report shows why one didn't
 * show.
 */
class LiveVoiceActivity(private val system: System) {
    typealias Content = VoiceCallActivityAttributes.ContentState

    /** A Live Update's state. */
    enum class State(val rawValue: String) {
        Pending("pending"), Active("active"), Stale("stale"), Ended("ended"), Dismissed("dismissed");

        /** False once it ended or the user dismissed it. */
        val isShowing: Boolean get() = this == Pending || this == Active || this == Stale
    }

    /** The notification system, by Live Update ID. */
    class System(
        val areActivitiesEnabled: () -> Boolean,
        /** Null once the system no longer knows the Live Update. */
        val state: (String) -> State?,
        /** Throws when it can't start. */
        val request: (VoiceCallActivityAttributes, Content, Instant) -> String,
        val update: suspend (String, Content, Instant) -> Unit,
        val end: suspend (String, Content) -> Unit,
        val endAll: suspend () -> Unit,
        val clock: Clock,
        /** The app's notifications switch in Settings, as it changes. */
        val enablementUpdates: () -> Flow<Boolean> = { emptyFlow() },
        /** The app coming to the foreground. */
        val foregroundEntries: () -> Flow<Unit> = { emptyFlow() },
        val stateUpdates: (String) -> Flow<State> = { emptyFlow() },
        /** One step on the diagnostics timeline. */
        val record: (name: String, detail: String) -> Unit = { _, _ -> },
        val report: (LiveActivityStatus) -> Unit = {},
    )

    sealed interface Request {
        data class Update(val content: Content?, val attributes: VoiceCallActivityAttributes?) : Request
        data object EndAll : Request
        /** The heartbeat: what shows now, with a later stale date. */
        data object Refresh : Request
        data class FinishEnded(val activityID: String, val content: Content) : Request
        /** Try again to start a call's Live Update that couldn't start. */
        data object Retry : Request
        data class EnablementChanged(val isEnabled: Boolean) : Request
        data class StateChanged(val activityID: String, val state: State) : Request
        /** A background intent's Connecting Live Update, before the call exists. */
        data class Prestart(val attributes: VoiceCallActivityAttributes) : Request
        /** The call never took the prestarted Live Update over. */
        data object PrestartExpired : Request
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner = LiveActivityRunner(system.clock, scope)
    private var activityID: String? = null
    /** The live call the Live Update shows; null once it ended. */
    private var content: Content? = null
    /** The call's attributes, kept to retry a start that failed. */
    private var attributes: VoiceCallActivityAttributes? = null
    /** The user swiped it away; don't bring it back during this call. */
    private var dismissedByUser = false
    /**
     * Why this call's Live Update couldn't start, and whether Settings allowed notifications
     * then. Updates don't ask again until that changes or the app comes back to the foreground.
     */
    private var failure: Pair<Boolean, String>? = null
    /** The phase last recorded, so the timeline gets phases, not every update. */
    private var recordedPhase: CallContentPhase? = null
    private var status = LiveActivityStatus()
    private var pendingEnd: Job? = null
    private var stateObservation: Job? = null
    /** A prestarted Live Update is waiting for its call's first update. */
    private var awaitsCall = false
    private var prestartStarted = false
    private val callWaiters = mutableListOf<CompletableDeferred<Boolean>>()

    val isHeartbeating: Boolean get() = runner.isHeartbeating

    init {
        val isEnabled = system.areActivitiesEnabled()
        noteEnabled(isEnabled)
        record("live_activity_enablement", if (isEnabled) "on" else "off")
        scope.launch { system.enablementUpdates().collect { enqueue(Request.EnablementChanged(it)) } }
        scope.launch { system.foregroundEntries().collect { enqueue(Request.Retry) } }
    }

    suspend fun enqueue(request: Request) = runner.enqueue { apply(request) }

    /** Shows Connecting for a call a background intent is about to start. A call already showing counts as started. */
    suspend fun prestart(attributes: VoiceCallActivityAttributes): Boolean {
        enqueue(Request.Prestart(attributes))
        return withContext(Dispatchers.Main.immediate) { prestartStarted }
    }

    /**
     * True once the call's first update reaches the prestarted Live Update (or nothing was
     * waiting for one); false, with it ended, after `timeout`.
     */
    suspend fun awaitCall(timeout: Duration): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!awaitsCall) return@withContext content != null
        val waiter = CompletableDeferred<Boolean>()
        callWaiters += waiter
        val expiry = scope.launch {
            system.clock.sleep(timeout)
            enqueue(Request.PrestartExpired)
        }
        try {
            waiter.await()
        } finally {
            expiry.cancel()
        }
    }

    private suspend fun apply(request: Request) {
        when (request) {
            Request.EndAll -> {
                endEverything()
                finishWaiting(callStarted = false)
            }
            is Request.Update -> {
                val content = request.content
                if (content == null) {
                    endEverything()
                    resetCall()
                    finishWaiting(callStarted = false)
                } else {
                    finishWaiting(callStarted = content.phase != CallContentPhase.Ended)
                    if (content.phase == CallContentPhase.Ended) end(content) else show(content, request.attributes)
                }
            }
            Request.Refresh -> {
                val content = content
                val id = activityID
                if (content == null || id == null || system.state(id)?.isShowing != true) {
                    runner.stopHeartbeat()
                    return
                }
                system.update(id, content, staleDate())
            }
            is Request.FinishEnded -> {
                // A rejoin took the Live Update back before this ran.
                if (activityID == request.activityID && content != null) return
                if (activityID == request.activityID) activityID = null
                system.end(request.activityID, request.content)
                record("live_activity_ended", "id=${request.activityID}")
            }
            Request.Retry -> if (failure != null) retry("foreground")
            is Request.EnablementChanged -> {
                noteEnabled(request.isEnabled)
                record("live_activity_enablement", if (request.isEnabled) "on" else "off")
                if (request.isEnabled && failure != null) retry("enabled")
            }
            is Request.Prestart -> {
                prestartStarted = false
                val id = activityID
                if (id != null && content != null && system.state(id)?.isShowing == true) {
                    prestartStarted = true
                    return
                }
                cancelPendingEnd()
                resetCall()
                attributes = request.attributes
                val connecting = Content(CallContentPhase.Connecting, isMuted = false, startedAt = null)
                content = connecting
                record("live_activity_prestart", "")
                start(connecting)
                if (activityID == null) {
                    content = null
                    resetCall()
                    return
                }
                prestartStarted = true
                awaitsCall = true
            }
            Request.PrestartExpired -> {
                if (!awaitsCall) return
                record("live_activity_prestart_expired", "")
                endEverything()
                resetCall()
                finishWaiting(callStarted = false)
            }
            is Request.StateChanged -> {
                record("live_activity_state", "${request.state.rawValue} id=${request.activityID}")
                // Gone while it showed this call: the user swiped it away, or the system took it.
                if (request.activityID != activityID || content == null || request.state.isShowing) return
                dismissedByUser = true
                activityID = null
                runner.stopHeartbeat()
                noteLast("${request.state.rawValue} during the call")
            }
        }
    }

    private suspend fun show(content: Content, attributes: VoiceCallActivityAttributes?) {
        // A rejoin while “Call ended” still shows takes the Live Update back.
        cancelPendingEnd()
        val wasShowingCall = this.content != null
        this.content = content
        if (attributes != null) this.attributes = attributes
        val id = activityID
        val state = id?.let(system.state)
        if (id != null && state != null && state.isShowing) {
            if (recordedPhase != content.phase) {
                recordedPhase = content.phase
                record("live_activity_update", "phase=${content.phase.rawValue} id=$id")
            }
            system.update(id, content, staleDate())
            startHeartbeat()
            return
        }
        // Gone while it showed this call: the user swiped it away. Gone after the call
        // ended, it was just over, and a rejoin starts afresh.
        if (id != null && wasShowingCall) {
            dismissedByUser = true
            val rawState = state?.rawValue ?: "gone"
            record("live_activity_dismissed", "state=$rawState id=$id")
            noteLast("$rawState during the call")
        }
        activityID = null
        runner.stopHeartbeat()
        if (dismissedByUser) return
        // Already refused for this call: ask again only once Settings changed.
        val failure = failure
        if (failure != null && failure.first == system.areActivitiesEnabled()) return
        start(content)
    }

    private suspend fun retry(reason: String) {
        val content = content ?: return
        if (content.phase == CallContentPhase.Ended || dismissedByUser) return
        if (activityID?.let { system.state(it)?.isShowing != true } == false) return
        record("live_activity_retry", reason)
        failure = null
        start(content)
    }

    /** Requests even when Settings reads as off: the request's error is the authority. */
    private fun start(content: Content) {
        val isEnabled = system.areActivitiesEnabled()
        noteEnabled(isEnabled)
        val attributes = attributes
        if (attributes == null) {
            record("live_activity_skipped", "no attributes")
            return
        }
        try {
            val id = system.request(attributes, content, staleDate())
            activityID = id
            failure = null
            recordedPhase = content.phase
            record("live_activity_started", "id=$id enabled=$isEnabled phase=${content.phase.rawValue}")
            noteLast("Started")
            observeState(id)
            startHeartbeat()
        } catch (error: Exception) {
            val description = describe(error)
            failure = isEnabled to description
            record("live_activity_failed", "enabled=$isEnabled error=$description")
            noteLast("Couldn’t start: $description")
            AppLog.voice.error("Couldn’t start the voice call activity: $description")
        }
    }

    /**
     * “Call ended” shows on the running Live Update, which ends a moment later. It times out
     * then too, in case the process dies before it can end it.
     */
    private suspend fun end(content: Content) {
        runner.stopHeartbeat()
        this.content = null
        resetCall()
        val id = activityID
        if (id == null || system.state(id)?.isShowing != true) {
            activityID = null
            return
        }
        record("live_activity_update", "phase=${content.phase.rawValue} id=$id")
        system.update(id, content, Instant.now().plusMillis(ENDED_LINGER.inWholeMilliseconds))
        cancelPendingEnd()
        pendingEnd = scope.launch {
            system.clock.sleep(ENDED_LINGER)
            if (!isActive) return@launch
            enqueue(Request.FinishEnded(id, content))
        }
    }

    private fun finishWaiting(callStarted: Boolean) {
        if (!awaitsCall) return
        awaitsCall = false
        val waiters = callWaiters.toList()
        callWaiters.clear()
        for (waiter in waiters) waiter.complete(callStarted)
    }

    /** What one call decided; the next call starts afresh. */
    private fun resetCall() {
        dismissedByUser = false
        failure = null
        attributes = null
        recordedPhase = null
    }

    private fun observeState(id: String) {
        stateObservation?.cancel()
        val updates = system.stateUpdates(id)
        stateObservation = scope.launch {
            updates.collect { enqueue(Request.StateChanged(id, it)) }
        }
    }

    private fun cancelPendingEnd() {
        pendingEnd?.cancel()
        pendingEnd = null
    }

    private suspend fun endEverything() {
        runner.stopHeartbeat()
        cancelPendingEnd()
        activityID?.let { record("live_activity_ended", "id=$it") }
        activityID = null
        content = null
        system.endAll()
    }

    /** Pushes the same state with a later stale date, so a call the app is still running never reads as ended. */
    private fun startHeartbeat() = runner.startHeartbeat { apply(Request.Refresh) }

    private fun record(name: String, detail: String) = system.record(name, detail)

    private fun noteEnabled(isEnabled: Boolean) {
        if (status.isEnabled == isEnabled) return
        status = status.copy(isEnabled = isEnabled)
        system.report(status)
    }

    private fun noteLast(last: String) {
        status = status.copy(last = last)
        system.report(status)
    }

    companion object {
        /** The Live Update reads as ended if the app stops refreshing it this long. */
        val STALE_INTERVAL: Duration = 15.minutes
        val HEARTBEAT_INTERVAL: Duration = 5.minutes
        val ENDED_LINGER: Duration = 3.seconds

        internal val shared: LiveVoiceActivity by lazy { LiveVoiceActivity(LiveUpdateSystem.voice(dependency(AppContext))) }

        /** A Live Update button with nothing listening: end what shows. */
        internal suspend fun endAllFromCommand() = shared.enqueue(Request.EndAll)

        fun describe(error: Throwable): String = (error as? LiveUpdateSystem.RequestError)?.reason ?: error::class.java.simpleName

        fun staleDate(): Instant = Instant.now().plusMillis(STALE_INTERVAL.inWholeMilliseconds)
    }
}

/**
 * Serializes Live Update requests and runs the 5-minute stale-date heartbeat while one is showing.
 */
class LiveActivityRunner(private val clock: Clock, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var heartbeat: Job? = null

    val isHeartbeating: Boolean get() = heartbeat != null

    suspend fun enqueue(step: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        mutex.withLock { step() }
    }

    fun startHeartbeat(refresh: suspend () -> Unit) {
        if (heartbeat != null) return
        heartbeat = scope.launch {
            while (isActive) {
                clock.sleep(LiveVoiceActivity.HEARTBEAT_INTERVAL)
                if (!isActive) return@launch
                enqueue(refresh)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }
}
