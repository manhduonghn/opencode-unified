package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.log.AppLog
import ai.opencode.server.model.utf8Length
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What [EventBatcher] calls as events pass through, installed by the features that own them
 * (`DependencyValues.installRoot { this[EventBatcherHooks] = … }`):
 *
 * - [prepareMarkdown] projects a text part's Markdown off the reducer's path. For a streaming part,
 *   [text] is the accumulated text so far and the transcript applies `holdingPendingMarker` itself;
 *   for an ended part it's the final text.
 * - [record] sees every event once per upstream connection.
 */
class EventBatcherHooks(
    val prepareMarkdown: (partID: String, text: String, isStreaming: Boolean) -> Unit = { _, _, _ -> },
    val record: (OpenCodeServerEvent) -> Unit = {},
) {
    companion object Key : DependencyKey<EventBatcherHooks> {
        override val liveValue: EventBatcherHooks = EventBatcherHooks()
        override val testValue: EventBatcherHooks = EventBatcherHooks()
    }
}

/** What happened to one event [EventBatcher] handed downstream. */
sealed interface EventEmission {
    /** Queued, with [remaining] free slots left in the buffer. */
    data class Enqueued(val remaining: Int) : EventEmission

    /** The buffer was full. The downstream has already ended with [EventStreamOverflow]. */
    data object Dropped : EventEmission

    /** The downstream is closed. */
    data object Terminated : EventEmission
}

/**
 * Keeps high-frequency events out of reducers: adjacent deltas for one part coalesce and flush
 * [flushInterval] after the first, at most 256 mutations per batch. A non-content event first
 * flushes the deltas it must follow. Under pressure, sessions off screen lose their streaming
 * deltas. Anything [emit] reports but [EventEmission.Enqueued] ends the batcher.
 */
class EventBatcher(
    private val scope: CoroutineScope,
    private val emit: (OpenCodeServerEvent) -> EventEmission,
    private val hooks: EventBatcherHooks = EventBatcherHooks(),
    private val clock: Clock,
    private val flushInterval: Duration = 75.milliseconds,
    private val capacity: Int = BUFFER_CAPACITY,
    private val isFocused: (String) -> Boolean = { true },
    private val onOverflow: (EventStreamOverflow) -> Unit = { throw it },
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, MutableList<OpenCodeLiveContentEvent>>()
    private var flushJob: Job? = null
    private var isTerminated = false

    /** This connection's accumulated text per part, as the reducer accumulates it. */
    private class PartText(val sessionID: String, val fromStart: Boolean) {
        val text = StringBuilder()
        var bytes = 0L
    }
    private val liveText = LinkedHashMap<String, PartText>()
    /** A gap in delivery without a recoverable prefix: wait for the authoritative TextEnded. */
    private val discarded = mutableSetOf<String>()

    /** The buffer's free slots after the last emission, and when that was measured. */
    private var remaining = capacity
    private var remainingMeasuredAt = clock.now()
    private var shedDeltas = 0
    private var shedBatches = 0

    /**
     * Half the buffer is waiting on a slow main thread. A reading older than a second is stale:
     * nothing was emitted since, so the reducer has caught up.
     */
    private val isUnderPressure: Boolean
        get() = remaining <= capacity / 2 && clock.now() - remainingMeasuredAt < 1.seconds

    /**
     * Attach a complete prefix whenever this connection has one: a screen can open between flush
     * and delivery. Under pressure, sessions off screen drop streaming deltas but keep their ends
     * and every other event.
     */
    private fun prepare(events: List<OpenCodeLiveContentEvent>, sessionID: String): List<OpenCodeLiveContentEvent> {
        val focused = isFocused(sessionID)
        val shed = !focused && isUnderPressure
        val kept = ArrayList<OpenCodeLiveContentEvent>(events.size)
        for (event in events) {
            val prepared = when (event) {
                is OpenCodeLiveContentEvent.TextStarted -> {
                    val id = "${event.messageID}-text-${event.ordinal}"
                    if (id !in discarded) liveText.getOrPut(id) { PartText(sessionID, fromStart = true) }
                    event
                }
                is OpenCodeLiveContentEvent.TextDelta -> {
                    val id = "${event.messageID}-text-${event.ordinal}"
                    if (id in discarded) continue
                    val part = liveText.getOrPut(id) { PartText(sessionID, fromStart = false) }
                    part.bytes += utf8Length(event.delta)
                    if ((shed && !part.fromStart) || (!focused && part.bytes > OFFSCREEN_PART_BYTE_LIMIT)) {
                        discard(id)
                        continue
                    }
                    part.text.append(event.delta)
                    if (shed) continue
                    if (!part.fromStart && !focused) event else {
                        val text = part.text.toString()
                        if (focused) hooks.prepareMarkdown(id, text, true)
                        if (part.fromStart) event.copy(accumulatedText = text) else event
                    }
                }
                is OpenCodeLiveContentEvent.TextEnded -> {
                    val id = "${event.messageID}-text-${event.ordinal}"
                    liveText.remove(id)
                    discarded.remove(id)
                    if (focused) hooks.prepareMarkdown(id, event.text, false)
                    event
                }
                is OpenCodeLiveContentEvent.ReasoningDelta, is OpenCodeLiveContentEvent.ToolInputDelta -> if (shed) continue else event
                else -> event
            }
            kept += prepared
        }
        if (liveText.size > OFFSCREEN_PART_LIMIT) {
            // LinkedHashMap preserves start order. Only offscreen accumulators share this budget.
            val offscreen = liveText.mapNotNull { (id, part) -> id.takeUnless { isFocused(part.sessionID) } }
            for (i in 0 until offscreen.size - OFFSCREEN_PART_LIMIT) discard(offscreen[i])
        }
        if (shed) {
            shedDeltas += events.size - kept.size
            if (kept.isEmpty()) shedBatches += 1
        }
        return kept
    }

    private fun discard(id: String) {
        liveText.remove(id)
        discarded.add(id)
    }

    fun receive(event: OpenCodeServerEvent) {
        synchronized(lock) {
            if (isTerminated) return
            hooks.record(event)
            if (event !is OpenCodeServerEvent.ContentBatch) {
                val target = event.targetSessionID
                if (target != null) flush(target) else flushLocked()
                emitLocked(event)
                return
            }
            pending.getOrPut(event.sessionID) { mutableListOf() }.appendCoalescingDeltas(event.events)
            if ((pending[event.sessionID]?.size ?: 0) >= BATCH_LIMIT) {
                flush(event.sessionID)
                return
            }
            if (pending.values.sumOf { it.size } >= BATCH_LIMIT) {
                flushLocked()
                return
            }
            if (flushJob != null) return
            flushJob = scope.launch {
                clock.sleep(flushInterval)
                if (!isActive) return@launch
                flush()
            }
        }
    }

    private fun flush(sessionID: String) {
        val events = pending.remove(sessionID)
        if (events.isNullOrEmpty()) return
        if (pending.isEmpty()) {
            flushJob?.cancel()
            flushJob = null
        }
        emitBatch(sessionID, events)
    }

    /** Delivers every pending batch now. */
    fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        flushJob?.cancel()
        flushJob = null
        if (pending.isEmpty()) return
        val batches = pending.toList()
        pending.clear()
        for ((sessionID, events) in batches) {
            if (events.isEmpty()) continue
            if (isTerminated) return
            emitBatch(sessionID, events)
        }
    }

    private fun emitBatch(sessionID: String, events: List<OpenCodeLiveContentEvent>) {
        val kept = prepare(events, sessionID)
        if (liveText.size + discarded.size > UNFINISHED_PART_LIMIT) {
            AppLog.stream.notice { "Event buffer overflow: too many unfinished text parts" }
            terminate()
            onOverflow(EventStreamOverflow())
            return
        }
        if (kept.isEmpty()) return
        emitLocked(OpenCodeServerEvent.ContentBatch(sessionID, kept))
    }

    private fun emitLocked(event: OpenCodeServerEvent) {
        if (isTerminated) return
        when (val result = emit(event)) {
            is EventEmission.Enqueued -> {
                remaining = result.remaining
                remainingMeasuredAt = clock.now()
                if (shedDeltas > 0 && !isUnderPressure) {
                    AppLog.stream.notice { "Event buffer pressure eased: shedDeltas=$shedDeltas shedBatches=$shedBatches" }
                    shedDeltas = 0
                    shedBatches = 0
                }
            }
            EventEmission.Dropped -> {
                AppLog.stream.notice { "Event buffer overflow: dropped=1 shedDeltas=$shedDeltas shedBatches=$shedBatches" }
                terminate()
            }
            EventEmission.Terminated -> terminate()
        }
    }

    private fun terminate() {
        isTerminated = true
        flushJob?.cancel()
        flushJob = null
        pending.clear()
        liveText.clear()
        discarded.clear()
    }

    companion object {
        /** The event stream's buffer size. */
        const val BUFFER_CAPACITY = 128
        private const val BATCH_LIMIT = 256
        private const val OFFSCREEN_PART_BYTE_LIMIT = 512 * 1024
        private const val OFFSCREEN_PART_LIMIT = 32
        private const val UNFINISHED_PART_LIMIT = 1024
    }
}
