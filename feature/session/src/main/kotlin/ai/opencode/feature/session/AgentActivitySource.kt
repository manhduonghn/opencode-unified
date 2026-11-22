package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeRunRetry
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Times are seconds on the wall clock (a `TimeInterval`); here [AgentActivityClock.now] is epoch
// seconds. Only differences are ever compared, so the epoch doesn't matter as long as one clock is
// used.

/** The clock activity glyphs and the ledger time events on. */
object AgentActivityClock {
    fun now(): Double = System.currentTimeMillis() / 1000.0

    fun of(instant: Instant): Double = instant.toEpochMilli() / 1000.0
}

/** What a glyph shows. The labels are the prototype's. */
enum class AgentActivity(val rawValue: String) {
    idle("idle"),
    startup("startup"),
    thinking("thinking"),
    longWork("longWork"),
    tool("tool"),
    streaming("streaming"),
    waiting("waiting"),
    handoff("handoff"),
    done("done"),
    failed("failed");

    val label: String
        get() = when (this) {
            idle -> localized(R.string.session_activity_idle)
            startup -> localized(R.string.session_activity_starting_up)
            thinking -> localized(R.string.session_activity_thinking)
            longWork -> localized(R.string.session_activity_long_work)
            tool -> localized(R.string.session_activity_tool_burst)
            streaming -> localized(R.string.session_activity_streaming_output)
            waiting -> localized(R.string.session_activity_waiting_for_input)
            handoff -> localized(R.string.session_activity_handing_off)
            done -> localized(R.string.session_activity_done)
            failed -> localized(R.string.session_activity_failed)
        }

    /** Done and Failed settle and hold; everything else keeps moving. */
    val isFinal: Boolean get() = this == done || this == failed

    companion object {
        fun of(rawValue: String): AgentActivity? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Tool calls and output chunks seen so far; a glyph reacts to the difference. */
data class AgentActivityCounts(val tool: Int = 0, val output: Int = 0) {
    fun since(old: AgentActivityCounts): AgentActivityCounts =
        AgentActivityCounts(tool = max(0, tool - old.tool), output = max(0, output - old.output))
}

/**
 * What the app knows about one subagent from its parent: the subagent tool call's phase, its
 * session, and whether it waits on the user.
 */
data class SubagentActivity(
    val sessionID: String?,
    val phase: Phase,
    val needsInput: Boolean = false,
) {
    enum class Phase {
        /** The call's input is still streaming, or its session isn't known yet. */
        spawning,
        running,
        done,
        failed,
        /** Not running: a session at rest, as the Session sheet shows it. */
        idle,
    }

    /**
     * The state at [now]. Done, Failed, Waiting for Input and Handing Off come straight from the
     * app's state; the rest are derived from the subagent's own live events
     * ([AgentActivityLedger]), which the app otherwise doesn't keep for sessions it isn't showing:
     * - Starting Up: its run started in the last 1.4 s.
     * - Handing Off: the call is still being written, or the subagent began spawning a subagent
     *   of its own in the last 2.6 s.
     * - Streaming Output: text arrived in the last 0.8 s.
     * - Tool Burst: a tool was called in the last 2 s.
     * - Long Work: running, with no live event for 8 s (one long step).
     * - Thinking: otherwise, reasoning included.
     */
    fun activity(now: Double): AgentActivity {
        when (phase) {
            Phase.failed -> return AgentActivity.failed
            Phase.done -> return AgentActivity.done
            Phase.spawning -> return if (needsInput) AgentActivity.waiting else AgentActivity.handoff
            Phase.idle -> return if (needsInput) AgentActivity.waiting else AgentActivity.idle
            Phase.running -> Unit
        }
        if (needsInput) return AgentActivity.waiting
        val sessionID = sessionID ?: return AgentActivity.handoff
        val record = AgentActivityLedger.shared.record(sessionID) ?: return AgentActivity.thinking
        record.spawnedAt?.let { if (now - it < 2.6) return AgentActivity.handoff }
        record.startedAt?.let { if (now - it < 1.4) return AgentActivity.startup }
        record.textAt?.let { if (now - it < 0.8) return AgentActivity.streaming }
        record.toolAt?.let { if (now - it < 2) return AgentActivity.tool }
        if (record.isActive && now - record.activityAt > 8) return AgentActivity.longWork
        return AgentActivity.thinking
    }

    val counts: AgentActivityCounts
        get() = sessionID?.let { AgentActivityLedger.shared.record(it)?.counts } ?: AgentActivityCounts()
}

/**
 * The main session's run, as the orb beside its Working line shows it: Starting Up as the run
 * begins, Thinking while it works, Waiting for Input on a pending request, and Done or Failed for
 * the brief flash after. While Stop is on its way the line reads Stopping… and the orb holds still.
 */
data class WorkingActivity(
    val phase: Phase = Phase.running,
    /** When this screen saw the run start ([AgentActivityClock] seconds); null when it opened onto one already going. */
    val startedAt: Double? = null,
    val needsInput: Boolean = false,
    /** Which cached orb draws it ([WorkingRun.key]). */
    val key: String? = null,
    /**
     * Done or Failed: when the flash began, and whether the line then hands off to the reply's
     * copy button (a turn with no reply text has none).
     */
    val finaleStartedAt: Double? = null,
    val handsOffToCopy: Boolean = false,
    val finaleDuration: Double = WorkingRun.finale,
    val handoffDuration: Double = WorkingRun.handoff,
    /** The server will retry the run's failed step: the line counts down to it. */
    val retry: OpenCodeRunRetry? = null,
    /**
     * Server Flow: the server dropped, so the run's state is only last known; the orb holds
     * still until it answers.
     */
    val isHeld: Boolean = false,
) {
    enum class Phase { running, stopping, done, failed }

    /** How far the handoff has gone (0…1): nothing during the flash. */
    fun handoff(now: Double): Double {
        val finaleStartedAt = finaleStartedAt ?: return 0.0
        return min(1.0, max(0.0, (now - finaleStartedAt - finaleDuration) / max(0.05, handoffDuration)))
    }

    val title: String get() = title(Instant.now())

    fun title(now: Instant): String {
        if (phase == Phase.stopping) return localized(R.string.session_working_stopping)
        if (needsInput) return localized(R.string.session_status_needs_input)
        // Past its time the attempt is under way, and the run reads as Working.
        val retry = retry
        if (phase == Phase.running && retry != null && retry.at.isAfter(now.minusSeconds(1))) return retry.title(now)
        return localized(R.string.session_status_working)
    }

    fun activity(now: Double): AgentActivity {
        when (phase) {
            Phase.done -> return AgentActivity.done
            Phase.failed -> return AgentActivity.failed
            Phase.stopping -> return AgentActivity.idle
            Phase.running -> Unit
        }
        if (needsInput) return AgentActivity.waiting
        if (startedAt != null && now - startedAt < STARTUP) return AgentActivity.startup
        return AgentActivity.thinking
    }

    companion object {
        /** Subagents' Starting Up lasts as long. */
        const val STARTUP: Double = 1.4

        /** The orb plays Done or Failed this much faster, so the flash lands inside the shorter finale, as the prototype's does. */
        val finaleRate: Double get() = 1.2 / max(0.05, WorkingRun.finale)

        /** The pending retry of the last turn's failed step, if the server set one. */
        fun retry(items: List<OpenCodeTranscriptItem>): OpenCodeRunRetry? {
            for (item in items.asReversed()) {
                when (item) {
                    is OpenCodeTranscriptItem.RunRetry -> return item.retry
                    is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.UserImages,
                    is OpenCodeTranscriptItem.UserFiles, is OpenCodeTranscriptItem.RunError -> return null
                    else -> continue
                }
            }
            return null
        }
    }
}

/**
 * The Working line's handoff to the reply's copy button, from the prototype: the
 * indicator blurs and shrinks toward the copy glyph's centre while the glyph sharpens in,
 * overlapping in the middle. Under Reduce Motion it's a cross-fade. Blur radii are in points (dp).
 */
data class WorkingHandoffPose(
    val indicatorOpacity: Double,
    val indicatorBlur: Double,
    val indicatorScale: Double,
    val copyOpacity: Double,
    val copyBlur: Double,
    val copyScale: Double,
) {
    companion object {
        const val BLUR: Double = 6.0

        fun of(progress: Double, handsOffToCopy: Boolean, reduceMotion: Boolean): WorkingHandoffPose {
            fun clamp(x: Double) = min(1.0, max(0.0, x))
            fun ease(x: Double): Double { val c = clamp(x); return c * c * (3 - 2 * c) }
            fun easeOut(x: Double) = 1 - (1 - clamp(x)).pow(3)
            val m = progress
            val out = clamp(m / 0.7)
            val into = if (handsOffToCopy) clamp((m - 0.25) / 0.75) else 0.0
            return if (reduceMotion) {
                WorkingHandoffPose(1 - out, 0.0, 1.0, into, 0.0, 1.0)
            } else {
                WorkingHandoffPose(
                    indicatorOpacity = 1 - ease(out), indicatorBlur = BLUR * out, indicatorScale = 1 - 0.5 * ease(out),
                    copyOpacity = easeOut(into), copyBlur = BLUR * (1 - into), copyScale = 0.6 + 0.4 * easeOut(into),
                )
            }
        }
    }
}

/**
 * The main session's run as its Working line tells it, recorded by `SessionFeature` in the action
 * that starts or ends the run, so the first frame after either is already right: a run this screen
 * saw start (its Starting Up), and once a run with a turn behind it ends, its brief Done or Failed
 * flash. Purely visual: sending and the composer read `isRunning`. It holds no clock; the view
 * times each event from when it first draws it and reports it played out, and lets it go on its
 * own if that never comes.
 *
 * Immutable: the mutating methods return the next value.
 */
data class WorkingRun(
    /** Numbers starts and finales; it carries over when a screen swaps details in place, so with the detail's navigation ID it names each one. */
    val counter: Int = 0,
    /** The run this screen saw start, until its Starting Up has played; null when it opened onto one already going. */
    val startID: Int? = null,
    val finale: Finale? = null,
    /** The orb's cache key: the session's ID, or the draft's run until its session exists, so the hand-off keeps the orb mid-gesture. */
    val key: String? = null,
    /** This screen saw the run start, with the turn and last server item then: a run that added none (a failed or cancelled send) has no turn to flash for. */
    val sawStart: Boolean = false,
    val startTurn: String? = null,
    val baseline: String? = null,
    /** The session is running again only because of the finished run's own trailing events: not a new run. */
    val trailing: Boolean = false,
) {
    enum class Outcome { done, failed, interrupted }

    data class Finale(
        val id: Int,
        val kind: Kind,
        /** The turn it ends: the last user item when the run ended. */
        val turn: String?,
        val finaleDuration: Double = WorkingRun.finale,
        val handoffDuration: Double = WorkingRun.handoff,
    ) {
        enum class Kind {
            /** The run ended with nothing new yet; the settlement read decides. */
            pending,
            done,
            failed,
        }

        val duration: Double get() = if (kind == Kind.pending) SETTLE else finaleDuration + handoffDuration
    }

    /** What the run's transitions are read from. */
    data class Signal(
        val sessionID: String?,
        val isRunning: Boolean,
        val isInterrupting: Boolean,
        /** The last user item, and the last item the server wrote. */
        val turn: String?,
        val answer: String?,
    ) {
        constructor(sessionID: String?, isRunning: Boolean, isInterrupting: Boolean, items: List<OpenCodeTranscriptItem>) : this(
            sessionID = sessionID,
            isRunning = isRunning,
            isInterrupting = isInterrupting,
            turn = lastUserItem(items),
            answer = lastServerItem(items),
        )
    }

    /** Before a content batch marks the session running: is it the end of the run that just finished, or the start of another? */
    fun batchArriving(
        events: List<OpenCodeLiveContentEvent>,
        sessionID: String?,
        isRunning: Boolean,
        items: List<OpenCodeTranscriptItem>,
    ): WorkingRun {
        if (isRunning && !trailing) return this
        val known = events.all { event ->
            if (event is OpenCodeLiveContentEvent.StepStarted) false
            else items.asReversed().any { it.id.startsWith(event.messageID) }
        }
        if (!isRunning) {
            val trails = finale != null && known
            return if (trailing != trails) copy(trailing = trails) else this
        }
        if (trailing && !known) {
            // A new step: it was a new run after all.
            return start(Signal(sessionID, isRunning = true, isInterrupting = false, items = items))
        }
        return this
    }

    fun update(old: Signal, new: Signal, items: List<OpenCodeTranscriptItem>, failedSends: Set<String>): WorkingRun {
        if (old.sessionID != null && old.sessionID != new.sessionID) {
            // Another session: this screen saw none of its run start or end.
            return WorkingRun(counter = counter)
        }
        // A new message while the last run's flash plays on: its run started before the message arrived.
        if (new.isRunning && old.isRunning && finale != null && new.turn != finale.turn) return start(new)
        // The settlement read brought the run's answer or its error.
        if (!new.isRunning && finale?.kind == Finale.Kind.pending && new.answer != baseline) return resolve(items, new.turn)
        if (old.isRunning == new.isRunning) return this
        if (new.isRunning) return if (!trailing) start(new) else this
        // The trailing events are over; the flash plays on.
        if (trailing) return copy(trailing = false)
        // Stop was tapped: the line leaves at once. The run's end clears the flag in the same action, so it's read from before.
        if (old.isInterrupting) return this
        if (sawStart && new.answer == baseline) {
            // Nothing new yet. A failed send, or one cancelled before the server took it, ran no
            // turn; anything else waits for its settlement read.
            val startTurn = startTurn
            if (startTurn != null && (startTurn in failedSends || items.none { it.id == startTurn })) return this
            val next = counter + 1
            return copy(counter = next, finale = Finale(id = next, kind = Finale.Kind.pending, turn = new.turn))
        }
        return resolve(items, new.turn)
    }

    private fun start(signal: Signal): WorkingRun {
        val next = counter + 1
        return copy(
            counter = next,
            startID = next,
            finale = null,
            trailing = false,
            sawStart = true,
            startTurn = signal.turn,
            baseline = signal.answer,
            key = key ?: (signal.sessionID ?: "draft-$next"),
        )
    }

    private fun resolve(items: List<OpenCodeTranscriptItem>, turn: String?): WorkingRun = when (outcome(items)) {
        Outcome.done -> (counter + 1).let { copy(counter = it, finale = Finale(id = it, kind = Finale.Kind.done, turn = turn)) }
        Outcome.failed -> (counter + 1).let { copy(counter = it, finale = Finale(id = it, kind = Finale.Kind.failed, turn = turn)) }
        Outcome.interrupted, null -> copy(finale = null)
    }

    /** Starting Up or the finale has played out: a remount doesn't replay it. */
    fun elapsed(id: Int): WorkingRun = copy(
        startID = if (startID == id) null else startID,
        finale = if (finale?.id == id) null else finale,
    )

    companion object {
        /** How long the Done or Failed flash holds the line. */
        const val finale: Double = 0.55

        /** Then the line hands off to the reply's copy button in the same row. */
        const val handoff: Double = 0.34

        /** How long a run that ended with nothing new waits for its settlement read: a provider error before any text only arrives with it. */
        const val SETTLE: Double = 1.5

        private const val STOPPED_RUN_TEXT = "Interrupted"

        fun token(navigationID: Int, id: Int): String = "$navigationID|$id"

        /**
         * How the last turn ended: Failed when it carries a run error, unless the run was stopped
         * (a neutral end); Done when the server answered; null when it didn't (a send that failed
         * or never reached it). An answer after an error (a later run in the same turn) is Done:
         * the error was an earlier run's.
         */
        fun outcome(items: List<OpenCodeTranscriptItem>): Outcome? {
            var answered = false
            for (item in items.asReversed()) {
                when (item) {
                    is OpenCodeTranscriptItem.UserText, is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles ->
                        return if (answered) Outcome.done else null
                    is OpenCodeTranscriptItem.RunError -> {
                        if (answered) return Outcome.done
                        return if (item.error.isStop) Outcome.interrupted else Outcome.failed
                    }
                    is OpenCodeTranscriptItem.Info -> if (item.id.endsWith("-error")) {
                        if (answered) return Outcome.done
                        // A transcript cached before run errors had their own row.
                        return if (item.text == STOPPED_RUN_TEXT || item.text.startsWith("Run failed: aborted")) Outcome.interrupted else Outcome.failed
                    }
                    is OpenCodeTranscriptItem.AssistantMarkdown, is OpenCodeTranscriptItem.Tool, is OpenCodeTranscriptItem.Compaction ->
                        answered = true
                    else -> continue
                }
            }
            return if (answered) Outcome.done else null
        }

        fun lastUserItem(items: List<OpenCodeTranscriptItem>): String? = items.lastOrNull { item ->
            item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages || item is OpenCodeTranscriptItem.UserFiles
        }?.id

        fun lastServerItem(items: List<OpenCodeTranscriptItem>): String? = items.lastOrNull { item ->
            when (item) {
                is OpenCodeTranscriptItem.AssistantMarkdown, is OpenCodeTranscriptItem.Tool,
                is OpenCodeTranscriptItem.Compaction, is OpenCodeTranscriptItem.RunError -> true
                is OpenCodeTranscriptItem.Info -> item.id.endsWith("-error")
                else -> false
            }
        }?.id
    }
}

/** Where a glyph's state comes from. */
sealed interface AgentActivitySource {
    /** Work with no finer signal: Thinking. */
    data object Running : AgentActivitySource

    /** The main session's run, beside its Working line. */
    data class Session(val working: WorkingActivity) : AgentActivitySource

    data class Subagent(val subagent: SubagentActivity) : AgentActivitySource

    /** The background-work capsule: its most telling subagent, else its shells. */
    data class Summary(val subagents: List<SubagentActivity>, val shells: Int) : AgentActivitySource

    /** The lab's large preview. */
    data object Lab : AgentActivitySource

    fun activity(now: Double, lab: AgentActivityOverride?): AgentActivity {
        if (lab != null && followsLab) return lab.activity(now)
        return when (this) {
            Running, Lab -> AgentActivity.thinking
            is Session -> working.activity(now)
            is Subagent -> subagent.activity(now)
            is Summary -> leading(subagents, now)?.second ?: (if (shells > 0) AgentActivity.streaming else AgentActivity.idle)
        }
    }

    fun counts(now: Double, lab: AgentActivityOverride?): AgentActivityCounts {
        if (lab != null && followsLab) return lab.counts(now)
        return when (this) {
            Running, is Session, Lab -> AgentActivityCounts()
            is Subagent -> subagent.counts
            is Summary -> leading(subagents, now)?.first?.counts ?: AgentActivityCounts()
        }
    }

    private val followsLab: Boolean
        get() = when (this) {
            is Subagent, is Summary, Lab -> true
            Running, is Session -> false
        }

    companion object {
        /** The background-work capsule's: its subagents, running, and its shells. */
        fun summary(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>, waiting: Set<String>): AgentActivitySource =
            Summary(
                subagents = agents.map { SubagentActivity(sessionID = it.id, phase = SubagentActivity.Phase.running, needsInput = it.id in waiting) },
                shells = shells.size,
            )

        /** Most telling first, for a summary of several. */
        private val priority = listOf(
            AgentActivity.waiting, AgentActivity.failed, AgentActivity.handoff, AgentActivity.startup, AgentActivity.tool,
            AgentActivity.streaming, AgentActivity.longWork, AgentActivity.thinking, AgentActivity.done, AgentActivity.idle,
        )

        private fun rank(activity: AgentActivity): Int = priority.indexOf(activity).let { if (it < 0) 99 else it }

        private fun leading(subagents: List<SubagentActivity>, now: Double): Pair<SubagentActivity, AgentActivity>? =
            subagents.map { it to it.activity(now) }.minByOrNull { rank(it.second) }
    }
}

/**
 * A small projection of every session's live events, outside the reducers: when each run started,
 * and when it last streamed text, called a tool, or began spawning a subagent. The event stream
 * records into it as events arrive; glyphs read it while they draw.
 *
 * Process-wide: it is a visual projection (as `ShellOutputMeter`), never read by a reducer.
 * `:server` sits below this module, so the session's event consumer must call [record] for every
 * [OpenCodeServerEvent] it receives.
 */
class AgentActivityLedger {
    data class Record(
        val isActive: Boolean = false,
        val startedAt: Double? = null,
        val activityAt: Double,
        val textAt: Double? = null,
        val toolAt: Double? = null,
        val spawnedAt: Double? = null,
        val counts: AgentActivityCounts = AgentActivityCounts(),
    )

    private val lock = Any()
    private val records = HashMap<String, Record>()

    fun record(sessionID: String): Record? = synchronized(lock) { records[sessionID] }

    fun record(event: OpenCodeServerEvent, now: Double = AgentActivityClock.now()) {
        when (event) {
            is OpenCodeServerEvent.ExecutionStarted -> update(event.sessionID, now) { it.copy(isActive = true, startedAt = now) }
            is OpenCodeServerEvent.ExecutionEnded -> update(event.sessionID, now) { it.copy(isActive = false) }
            is OpenCodeServerEvent.ContentBatch -> update(event.sessionID, now) { start ->
                // Its own events mean it's running, even when its start came before the stream connected.
                var record = start.copy(isActive = true)
                var output = false
                for (content in event.events) {
                    when {
                        content is OpenCodeLiveContentEvent.TextDelta -> output = true
                        content is OpenCodeLiveContentEvent.ToolCalled ->
                            record = record.copy(toolAt = now, counts = record.counts.copy(tool = record.counts.tool + 1))
                        content is OpenCodeLiveContentEvent.ToolInputStarted && (content.name == "subagent" || content.name == "task") ->
                            record = record.copy(spawnedAt = now)
                    }
                }
                if (output) record = record.copy(textAt = now, counts = record.counts.copy(output = record.counts.output + 1))
                record
            }
            else -> Unit
        }
    }

    private inline fun update(sessionID: String, now: Double, change: (Record) -> Record) {
        synchronized(lock) {
            val base = (records[sessionID] ?: Record(activityAt = now)).copy(activityAt = now)
            records[sessionID] = change(base)
            // Sessions come and go; keep the projection small.
            if (records.size > 256) {
                records.minByOrNull { it.value.activityAt }?.key?.let { records.remove(it) }
            }
        }
    }

    companion object {
        val shared = AgentActivityLedger()
    }
}

/** The lab's hold on subagent glyphs: one state, or the prototype's showcase of three turns. */
data class AgentActivityOverride(val mode: Mode, val start: Double) {
    sealed interface Mode {
        data class State(val activity: AgentActivity) : Mode
        data object Showcase : Mode
    }

    fun activity(now: Double): AgentActivity = when (mode) {
        is Mode.State -> mode.activity
        Mode.Showcase -> AgentActivityShowcase.segment(max(0.0, now - start)).activity
    }

    /** Tool calls and output in the prototype's steady rhythms (three calls every 2.2 s, five chunks every 2.1 s), or the showcase's own events. */
    fun counts(now: Double): AgentActivityCounts {
        val t = max(0.0, now - start)
        return when {
            mode is Mode.State && mode.activity == AgentActivity.tool -> AgentActivityCounts(tool = rhythm(t, 2.2, listOf(0.15, 0.4, 0.65)))
            mode is Mode.State && mode.activity == AgentActivity.streaming ->
                AgentActivityCounts(output = rhythm(t, 2.1, listOf(0.1, 0.4, 0.7, 1.0, 1.3)))
            mode is Mode.State -> AgentActivityCounts()
            else -> AgentActivityShowcase.counts(t)
        }
    }

    private companion object {
        fun rhythm(t: Double, period: Double, offsets: List<Double>): Int {
            val cycles = floor(t / period)
            val phase = t - cycles * period
            return cycles.toInt() * offsets.size + offsets.count { it <= phase }
        }
    }
}

/**
 * The prototype's showcase (`activity-glyph/index.html`, TURNS): a turn that finishes, one that
 * fails, one that hands off, with tool calls in bursts and output in chunks fixed per segment,
 * looping.
 */
object AgentActivityShowcase {
    data class Segment(val activity: AgentActivity, val at: Double, val duration: Double)
    data class Timeline(val segments: List<Segment>, val tools: List<Double>, val outputs: List<Double>, val length: Double)

    val turns: List<List<Pair<AgentActivity, Double>>> = listOf(
        listOf(
            AgentActivity.idle to 1.2, AgentActivity.startup to 1.4, AgentActivity.thinking to 2.2, AgentActivity.tool to 2.0,
            AgentActivity.streaming to 2.6, AgentActivity.thinking to 1.2, AgentActivity.waiting to 3.0, AgentActivity.thinking to 1.4,
            AgentActivity.longWork to 4.2, AgentActivity.streaming to 2.2, AgentActivity.done to 2.4, AgentActivity.idle to 1.2,
        ),
        listOf(
            AgentActivity.startup to 1.2, AgentActivity.thinking to 1.6, AgentActivity.tool to 1.6, AgentActivity.streaming to 2.2,
            AgentActivity.failed to 2.6, AgentActivity.idle to 1.2,
        ),
        listOf(
            AgentActivity.startup to 1.2, AgentActivity.thinking to 1.8, AgentActivity.tool to 1.4, AgentActivity.handoff to 2.8,
            AgentActivity.idle to 1.4,
        ),
    )

    val timeline: Timeline by lazy {
        var seed = 3L
        fun rnd(): Double { seed = (seed * 16807) % 2147483647; return seed.toDouble() / 2147483647 }
        val segments = mutableListOf<Segment>()
        val tools = mutableListOf<Double>()
        val outputs = mutableListOf<Double>()
        var length = 0.0
        for (turn in turns) {
            for ((activity, duration) in turn) {
                val at = length
                if (activity == AgentActivity.tool) {
                    var t = 0.08
                    while (t < duration - 0.1) {
                        val n = 2 + floor(rnd() * 3).toInt()
                        var i = 0
                        while (i < n && t < duration - 0.1) { tools.add(at + t); t += 0.14 + rnd() * 0.22; i += 1 }
                        t += 0.5 + rnd() * 0.5
                    }
                }
                if (activity == AgentActivity.streaming) {
                    var t = 0.06
                    while (t < duration - 0.05) { outputs.add(at + t); t += if (rnd() < 0.8) 0.1 + rnd() * 0.2 else 0.4 + rnd() * 0.4 }
                }
                segments.add(Segment(activity, at, duration))
                length += duration
            }
        }
        Timeline(segments, tools, outputs, length)
    }

    fun segment(t: Double): Segment {
        val local = t % timeline.length
        return timeline.segments.lastOrNull { local >= it.at } ?: timeline.segments[0]
    }

    fun counts(t: Double): AgentActivityCounts {
        val loops = floor(t / timeline.length).toInt()
        val local = t - loops * timeline.length
        return AgentActivityCounts(
            tool = loops * timeline.tools.size + timeline.tools.count { it <= local },
            output = loops * timeline.outputs.size + timeline.outputs.count { it <= local },
        )
    }
}

/**
 * Debug builds let the session lab hold subagent glyphs in one state, play the showcase, or show a
 * 120 dp preview over the session. Android has no bridge yet, so [handle] is the entry point a
 * debug-only receiver can call with the same command names. Observable for Compose.
 */
object AgentActivityLab {
    var override: AgentActivityOverride? by mutableStateOf<AgentActivityOverride?>(null)
    var showsPreview: Boolean by mutableStateOf(false)

    /** Commands: a state's raw value holds it, `showcase` plays the showcase, `live` lets go, `preview` toggles the preview. */
    fun handle(command: String, now: Double = AgentActivityClock.now()) {
        when (command) {
            "live" -> override = null
            "preview" -> showsPreview = !showsPreview
            "showcase" -> override = AgentActivityOverride(AgentActivityOverride.Mode.Showcase, now)
            else -> {
                val activity = AgentActivity.of(command) ?: return
                override = AgentActivityOverride(AgentActivityOverride.Mode.State(activity), now)
            }
        }
    }
}
