package ai.opencode.core.diagnostics

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The performance baseline's end points as trace sections and, in a lab run, `perf_*` marks. On
 * only for a lab run or the `perf-signposts` extra; read per call because lab extras arrive after
 * process start.
 */
object PerfTimeline {
    /** The `perf-signposts` launch extra; set by `DevTools.handleLaunchIntent`. */
    @Volatile var signpostsRequested: Boolean = false

    val isEnabled: Boolean get() = LabMarks.isEnabled || signpostsRequested

    /**
     * Ties the sessions a transcript shows to its lifetime; the list calls [release] when it leaves
     * composition.
     */
    class RowsToken internal constructor() {
        internal val id = nextTokenID()

        fun release() = released(this)
    }

    /** A token for a new transcript while measuring, else null: nothing is allocated or locked for transcripts made while it's off. */
    fun makeRowsToken(): RowsToken? = if (isEnabled) RowsToken() else null

    // MARK: Reports

    fun appLaunched() {
        if (!isEnabled) return
        val interval = PerfTrace.begin("Cold Start")
        synchronized(lock) { state.cold = Interval(interval, now()) }
    }

    /** The reader tapped a session in the drawer. */
    fun switchStarted(sessionID: String) {
        if (!isEnabled) return
        val interval = PerfTrace.begin("Session Switch")
        val previous = synchronized(lock) {
            val previous = state.switching
            state.switching = Switch(sessionID, Interval(interval, now()))
            // A launch interrupted by a tap no longer measures a cold start.
            if (state.cold != null) state.coldAbandoned = true
            previous
        }
        if (previous != null && !previous.done) PerfTrace.end("Session Switch", previous.interval.signpost)
    }

    /**
     * A REST window for the session was applied. Returns the snapshot's revision for the
     * transcript's configuration; null when not measuring.
     */
    fun snapshotApplied(sessionID: String?): Int? {
        if (sessionID == null || !isEnabled) return null
        val (revision, waiting) = synchronized(lock) {
            state.fresh.applied(sessionID, now()) to state.fresh.isWaiting
        }
        if (waiting) wakeFrames()
        return revision
    }

    /** A transcript applied and laid out a configuration of the session made from snapshot [revision] (null: from no REST snapshot yet). */
    fun snapshotRendered(sessionID: String?, revision: Int?, token: RowsToken?) {
        if (sessionID == null || token == null) return
        val waiting = synchronized(lock) {
            state.fresh.rendered(sessionID, revision, token.id)
            state.fresh.isWaiting
        }
        if (waiting) wakeFrames()
    }

    /** A transcript laid out its first frame with rows for the session. */
    fun rowsShown(sessionID: String?, token: RowsToken?) {
        if (sessionID == null || token == null) return
        var rows: Interval? = null
        val waiting = synchronized(lock) {
            state.fresh.rowsShown(sessionID, token.id)
            if (!state.fresh.showsRows(sessionID)) return@synchronized false
            val switching = state.switching
            if (switching != null && switching.sessionID == sessionID && !switching.rowsShown) {
                switching.rowsShown = true
                rows = switching.interval
            }
            state.fresh.isWaiting
        }
        rows?.let { mark("perf_switch_rows", sessionID, since = it.start) }
        if (waiting) wakeFrames()
    }

    fun rowsHidden(token: RowsToken?) {
        token ?: return
        synchronized(lock) { state.fresh.rowsHidden(token.id) }
    }

    /**
     * The transcript went on screen: its rows count from now, and the latest one to appear shows
     * the active session.
     */
    fun transcriptAppeared(token: RowsToken?) {
        token ?: return
        val waiting = synchronized(lock) {
            state.fresh.appeared(token.id)
            state.fresh.isWaiting
        }
        if (waiting) wakeFrames()
    }

    /** The transcript left the screen: it no longer completes anything. */
    fun transcriptDisappeared(token: RowsToken?) {
        token ?: return
        synchronized(lock) { state.fresh.disappeared(token.id) }
    }

    private fun released(token: RowsToken) {
        synchronized(lock) { state.fresh.released(token.id) }
    }

    /** The session card started or stopped moving (the drawer's spring). */
    fun cardMoving(moving: Boolean) {
        if (!isEnabled) return
        val finished = synchronized(lock) {
            state.cardMoving = moving
            val switching = state.switching
            if (moving || switching == null || !switching.waitsForCard || switching.done) return@synchronized null
            switching.done = true
            switching
        }
        finished?.let(::finishSwitch)
    }

    /** The live event stream ended. */
    fun streamLost() {
        if (!isEnabled) return
        val begins = synchronized(lock) {
            state.reconnectedAt = null
            state.reconnect == null
        }
        if (!begins) return
        val interval = PerfTrace.begin("Reconnect")
        synchronized(lock) { state.reconnect = Interval(interval, now()) }
    }

    fun streamConnected() {
        if (!isEnabled) return
        val lost = synchronized(lock) {
            val reconnect = state.reconnect ?: return@synchronized null
            state.reconnectedAt = now()
            reconnect
        }
        lost?.let { mark("perf_stream_reconnected", null, since = it.start) }
    }

    /** An older page of the session's history was requested. */
    fun olderPageRequested(sessionID: String) {
        if (!isEnabled) return
        val (beginsPage, beginsHistory) = synchronized(lock) {
            (sessionID !in state.olderPage) to (sessionID !in state.history)
        }
        val page = if (beginsPage) PerfTrace.begin("Older Page") else null
        val history = if (beginsHistory) PerfTrace.begin("Long Session Load") else null
        synchronized(lock) {
            if (page != null) state.olderPage[sessionID] = Interval(page, now())
            if (history != null) {
                // Measured from the tap that opened it, when it was one.
                val opened = state.switching?.takeIf { it.sessionID == sessionID }?.interval?.start
                state.history[sessionID] = Interval(history, opened ?: now())
            }
        }
    }

    /** The older page's answer was applied; [exhausted] when it was the oldest. */
    fun olderPageApplied(sessionID: String, exhausted: Boolean, failed: Boolean) {
        if (!isEnabled) return
        main.post {
            val (page, history) = synchronized(lock) {
                state.olderPage.remove(sessionID) to (if (exhausted) state.history.remove(sessionID) else null)
            }
            if (page != null) {
                PerfTrace.end("Older Page", page.signpost)
                if (!failed) mark("perf_older_page", sessionID, since = page.start, values = mapOf("exhausted" to if (exhausted) 1 else 0))
            }
            if (history != null) {
                PerfTrace.end("Long Session Load", history.signpost)
                mark("perf_history_complete", sessionID, since = history.start)
            }
        }
    }

    // MARK: Fresh frames

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Counts Choreographer frames while a snapshot waits for an on-screen transcript: a handful per REST read, never otherwise. */
    private fun wakeFrames() {
        if (Looper.myLooper() == Looper.getMainLooper()) PerfFrames.wake() else main.post { PerfFrames.wake() }
    }

    /** One display frame; false once nothing waits on screen. */
    internal fun frameTick(): Boolean {
        val now = now()
        val (shown, waiting) = synchronized(lock) { state.fresh.nextFrame() to state.fresh.isWaiting }
        for ((sessionID, applied) in shown) freshRendered(sessionID, applied, now)
        return waiting
    }

    private fun freshRendered(sessionID: String, applied: Long, now: Long) {
        val outcome = synchronized(lock) {
            val outcome = FreshOutcome()
            state.cold?.let { cold ->
                state.cold = null
                if (state.coldAbandoned) outcome.coldAbandoned = cold else outcome.cold = cold
            }
            val switching = state.switching
            if (switching != null && switching.sessionID == sessionID && !switching.freshShown) {
                switching.freshShown = true
                outcome.firstTimeline = switching.interval
                if (state.cardMoving) {
                    switching.waitsForCard = true
                } else {
                    switching.done = true
                    outcome.switchDone = switching
                }
            }
            // A reconnect ends on the session in front, not one kept behind it.
            val reconnect = state.reconnect
            val connected = state.reconnectedAt
            if (reconnect != null && connected != null && applied >= connected && state.fresh.activeSessionID == sessionID) {
                state.reconnect = null
                state.reconnectedAt = null
                outcome.reconnect = reconnect to connected
            }
            outcome
        }
        PerfTrace.instant("Timeline Fresh")
        outcome.cold?.let { cold ->
            PerfTrace.end("Cold Start", cold.signpost)
            val values = mutableMapOf("sinceInitMs" to milliseconds(cold.start, now))
            processStartMs()?.let { values["ms"] = it }
            LabMarks.mark("perf_cold_first_timeline", sessionID = sessionID, values = values)
            LabMarks.mark("perf_first_timeline", sessionID = sessionID, values = mapOf("ms" to (values["ms"] ?: values.getValue("sinceInitMs"))))
        }
        outcome.coldAbandoned?.let { PerfTrace.end("Cold Start", it.signpost) }
        outcome.firstTimeline?.let { mark("perf_first_timeline", sessionID, since = it.start) }
        outcome.switchDone?.let(::finishSwitch)
        outcome.reconnect?.let { (reconnect, connected) ->
            PerfTrace.end("Reconnect", reconnect.signpost)
            mark("perf_reconnect_fresh", sessionID, since = reconnect.start, values = mapOf("afterConnectMs" to milliseconds(connected, now)))
        }
    }

    private fun finishSwitch(switching: Switch) {
        PerfTrace.end("Session Switch", switching.interval.signpost)
        mark("perf_switch_complete", switching.sessionID, since = switching.interval.start)
    }

    // MARK: State

    /** An open trace section: its [PerfTrace] cookie and its start (`System.nanoTime`). */
    private data class Interval(val signpost: Int, val start: Long)

    private class Switch(val sessionID: String, val interval: Interval) {
        var rowsShown = false
        var freshShown = false
        var waitsForCard = false
        var done = false
    }

    private class FreshOutcome {
        var cold: Interval? = null
        var coldAbandoned: Interval? = null
        var firstTimeline: Interval? = null
        var switchDone: Switch? = null
        var reconnect: Pair<Interval, Long>? = null
    }

    private class State {
        var cold: Interval? = null
        var coldAbandoned = false
        var switching: Switch? = null
        val fresh = PerfFreshTracker()
        var cardMoving = false
        var reconnect: Interval? = null
        var reconnectedAt: Long? = null
        val olderPage = HashMap<String, Interval>()
        val history = HashMap<String, Interval>()
    }

    private val lock = Any()
    private val state = State()
    private val tokens = AtomicInteger(0)

    private fun nextTokenID(): Int = tokens.incrementAndGet()

    private fun now(): Long = System.nanoTime()

    private fun mark(name: String, sessionID: String?, since: Long, values: Map<String, Int> = emptyMap()) {
        if (!LabMarks.isEnabled) return
        LabMarks.mark(name, sessionID = sessionID, values = values + ("ms" to milliseconds(since, now())))
    }

    private fun milliseconds(start: Long, end: Long): Int = ((end - start) / 1_000_000.0).roundToInt()

    /** Since the kernel started this process. */
    private fun processStartMs(): Int? =
        (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()).takeIf { it >= 0 }?.toInt()
}

/**
 * The Choreographer frames behind [PerfTimeline]'s rendered frames, running only while a snapshot
 * waits for an on-screen transcript. Main thread.
 */
private object PerfFrames : Choreographer.FrameCallback {
    private var running = false

    fun wake() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (PerfTimeline.frameTick()) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }
}

/**
 * A snapshot counts at the first frame after an on-screen transcript reported its revision
 * rendered. Not thread-safe: the timeline holds its lock around every call.
 */
class PerfFreshTracker {
    data class Pending(val revision: Int, val applied: Long)

    data class Transcript(
        var sessionID: String? = null,
        var hasRows: Boolean = false,
        var visible: Boolean = false,
        /** When it last appeared, to tell which transcript is in front. */
        var appearance: Int = 0,
        /** The revision of the configuration it last applied and laid out, and the frame count when it did. */
        var rendered: Int = 0,
        var renderedFrame: Int = 0,
    )

    /** Sessions whose newest applied snapshot isn't on screen yet. */
    val pending = HashMap<String, Pending>()
    val transcripts = HashMap<Int, Transcript>()
    var frame = 0
        private set
    private var revision = 0
    private var appearances = 0

    /** A snapshot of the session was applied; returns its revision. */
    fun applied(sessionID: String, time: Long): Int {
        revision += 1
        pending[sessionID] = Pending(revision, time)
        return revision
    }

    fun rendered(sessionID: String, revision: Int?, token: Int) {
        val transcript = transcripts.getOrPut(token) { Transcript() }
        transcript.sessionID = sessionID
        transcript.rendered = revision ?: 0
        transcript.renderedFrame = frame
    }

    fun rowsShown(sessionID: String, token: Int) {
        val transcript = transcripts.getOrPut(token) { Transcript() }
        transcript.sessionID = sessionID
        transcript.hasRows = true
    }

    fun rowsHidden(token: Int) {
        transcripts[token]?.hasRows = false
    }

    fun appeared(token: Int) {
        appearances += 1
        val transcript = transcripts.getOrPut(token) { Transcript() }
        transcript.visible = true
        transcript.appearance = appearances
    }

    fun disappeared(token: Int) {
        transcripts[token]?.visible = false
    }

    fun released(token: Int) {
        transcripts.remove(token)
    }

    /** A transcript on screen has laid out the session's rows. */
    fun showsRows(sessionID: String): Boolean = transcripts.values.any { it.visible && it.hasRows && it.sessionID == sessionID }

    /** The session of the transcript that appeared last and is still on screen. */
    val activeSessionID: String? get() = transcripts.values.filter { it.visible }.maxByOrNull { it.appearance }?.sessionID

    /** A pending snapshot's session is on screen, so frames are worth counting. */
    val isWaiting: Boolean get() = pending.keys.any(::showsRows)

    /** One display frame: the sessions whose pending snapshot it shows, with when each was applied. */
    fun nextFrame(): List<Pair<String, Long>> {
        frame += 1
        val shown = pending.filter { (sessionID, snapshot) ->
            transcripts.values.any {
                it.visible && it.hasRows && it.sessionID == sessionID && it.rendered >= snapshot.revision && it.renderedFrame < frame
            }
        }
        for (sessionID in shown.keys) pending.remove(sessionID)
        return shown.map { it.key to it.value.applied }.sortedBy { it.first }
    }
}
