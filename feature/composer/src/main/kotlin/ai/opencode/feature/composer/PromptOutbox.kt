package ai.opencode.feature.composer

import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerID
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.retry.NetworkRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** How one delivery ended. Lives here because [PromptOutbox] reports it. */
sealed interface DeliveryOutcome {
    /** [appliedModel] / [appliedAgent]: the model and agent this delivery applied before its prompt. */
    data class Sent(
        val sessionID: String,
        val created: OpenCodeSession?,
        val appliedModel: OpenCodeModelRef? = null,
        val appliedAgent: String? = null,
    ) : DeliveryOutcome

    data class Failed(val sessionID: String?, val created: OpenCodeSession?, val error: OpenCodeClientError) : DeliveryOutcome

    /**
     * Stopped for what the reader asked: deleted (never posted, or taken back once admitted)
     * or held by Stop before it was posted.
     */
    data class Withdrawn(val sessionID: String?, val created: OpenCodeSession?) : DeliveryOutcome

    val createdSession: OpenCodeSession?
        get() = when (this) {
            is Sent -> created
            is Failed -> created
            is Withdrawn -> created
        }
}

/**
 * Sends from this phone waiting for the server, in the order they were made. Each session (or
 * draft) is one lane that delivers one message at a time, so the server admits them in order. It
 * lives for the app's lifetime in its own scope, so leaving a session or the app doesn't stop a
 * lane. A send that fails for good, or that Stop holds back, holds the sends behind it until it's
 * retried or deleted. The retry loop itself is the session's delivery job; this class only orders,
 * holds and reports.
 */
class PromptOutbox(
    /**
     * Keeps the app running briefly when it leaves the foreground with sends still on their
     * way; returns the call that ends it.
     */
    private val backgroundWork: suspend () -> () -> Unit = { {} },
    private val now: () -> Instant = { Instant.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    sealed interface Lane {
        data class Session(val id: String) : Lane

        /** A new session's draft, named by its first message. Once a send creates the session, later sends in the lane go to it. */
        data class Draft(val id: String) : Lane
    }

    sealed interface Status {
        /** Delivered or failed, with the send as it last stood. */
        data class Settled(val outcome: DeliveryOutcome, val pending: PendingPrompt) : Status

        /** Deleted: never posted, or taken back from the server once admitted. */
        data object Withdrawn : Status

        /** Stop held it back before it was posted; the screen that stopped it shows it as a failed send. */
        data object Stopped : Status

        /** A change asked for while it was being posted didn't reach the server. */
        data class UpdateFailed(val sessionID: String, val restoring: OpenCodeDelivery?, val error: OpenCodeClientError) : Status

        /** Blocking foreground work was moved (or failed to move) ahead of a steer. */
        data class BackgroundMoved(val sessionID: String, val key: String, val error: OpenCodeClientError?) : Status

        /** The send ended (or was held) without moving the work it claimed. */
        data class BackgroundUnused(val key: String) : Status
    }

    /** What a delivery job does next, from what the reader has asked. */
    sealed interface Attempt {
        data class Post(val pending: PendingPrompt) : Attempt
        data object Cancelled : Attempt
        data object Stopped : Attempt

        /** A retry replaced it; the new job takes over and this one just ends. */
        data object Superseded : Attempt
    }

    /** What the reader asked of a send while it was being posted. */
    data class Intent(
        val cancelled: Boolean = false,
        /** Stop came while it was being posted: it finishes this attempt and, if that fails, isn't tried again. */
        val stopped: Boolean = false,
        /** Set when the delivery wanted now isn't the one it was posted with. */
        val changedDelivery: OpenCodeDelivery? = null,
        val postedDelivery: OpenCodeDelivery? = null,
    )

    /** A delivery job's view of its send. */
    class Handle(
        /** The session an earlier send in a draft's lane created, if any. */
        val createdSessionID: String?,
        /** The ID a draft's lane creates its session with, the same for every attempt, so a retry after a lost response can't make a second one. */
        val newSessionID: String,
        /** The send as it stands now. */
        val current: () -> PendingPrompt,
        /** Called before each attempt: posts it as it stands now, or stops. */
        val begin: () -> Attempt,
        /**
         * Waits out `sleep` before trying again: true to try again, false when `sleep` gave up
         * (the retry budget ran out offline) or Delete or Stop ended it early.
         */
        val wait: suspend (sleep: suspend () -> Boolean) -> Boolean,
        val intent: () -> Intent,
        /** A failed deferred change: the screen hears it, and the send is kept as the server has it. */
        val report: (Status) -> Unit,
        /** The draft's session exists: Stop from here on is a session's Stop. */
        val created: (OpenCodeSession) -> Unit,
        /** Claims the blocking foreground work key to background ahead of a steer, once. */
        val takeBackgroundKey: () -> String? = { null },
        val networkOperation: NetworkRetry.ManualOperation = NetworkRetry.ManualOperation(),
    )

    /**
     * Delivers one message and reports how it ended: `Sent`, `Failed` (a stopped send
     * included), or `Withdrawn` when it was deleted.
     */
    fun interface Job {
        suspend fun deliver(handle: Handle): DeliveryOutcome
    }

    private enum class Phase { Waiting, Posting, Retrying, Held }

    private data class Item(
        val pending: PendingPrompt,
        val job: Job,
        val phase: Phase,
        val cancelled: Boolean = false,
        val stopped: Boolean = false,
        val postedDelivery: OpenCodeDelivery? = null,
        val backgroundKey: String? = null,
        val sleeper: Deferred<Boolean>? = null,
        /** A retry replaces the item; a job still running for the one it replaced stops at its next step and its outcome is ignored. */
        val generation: Int,
        val networkOperation: NetworkRetry.ManualOperation = NetworkRetry.ManualOperation(),
    )

    private data class Admitted(val pending: PendingPrompt, val sessionID: String, val at: Instant)

    private data class LaneState(
        val serverKey: String,
        val items: MutableList<Item> = mutableListOf(),
        var admitted: List<Admitted> = emptyList(),
        var isDraft: Boolean = false,
        var draftID: String? = null,
        var directory: String? = null,
        val newSessionID: String,
        var createdSession: OpenCodeSession? = null,
        var createdSessionID: String? = null,
        /** The created session has been reported to a screen. */
        var announced: Boolean = false,
        var isWorking: Boolean = false,
    ) {
        fun appendAdmitted(pending: PendingPrompt, sessionID: String, at: Instant) {
            admitted = (admitted + Admitted(pending, sessionID, at)).takeLast(8)
        }
    }

    private class Storage {
        val lanes = HashMap<String, LaneState>()
        /** A session created by a draft's lane keeps using that lane. */
        val aliases = HashMap<String, String>()
        val observers = HashMap<String, HashMap<UUID, Channel<Status>>>()
        var generation = 0
        /** Messages the server announced as delivered, newest last, so a send recorded after its delivery event already reads as delivered. */
        val delivered = ArrayList<String>()
        var persistence: ComposerPersistence? = null
    }

    private class PersistedSnapshot(
        val persistence: ComposerPersistence,
        val serverID: String,
        val sessionID: String?,
        val draftID: String?,
        val directory: String?,
        val newSessionID: String,
        val createdSession: OpenCodeSession?,
        val pending: PendingPrompt,
        val isHeld: Boolean,
        val backgroundKey: String?,
    ) {
        fun record() {
            persistence.recordOutbox(serverID, sessionID, draftID, directory, newSessionID, createdSession, pending, isHeld, backgroundKey)
        }
    }

    private val lock = Any()
    private val storage = Storage()

    fun bind(persistence: ComposerPersistence) {
        synchronized(lock) { storage.persistence = persistence }
    }

    fun contains(messageID: String): Boolean = synchronized(lock) { locate(messageID) != null }

    fun directory(messageID: String): String? = synchronized(lock) {
        val (key, _) = locate(messageID) ?: return null
        storage.lanes[key]?.directory
    }

    fun seedPersisted(entry: RestoredOutboxEntry, job: Job) {
        synchronized(lock) {
            val id = entry.pending.messageID
            if (locate(id) != null) return
            storage.generation += 1
            val resolvedSessionID = entry.sessionID ?: entry.createdSession?.id
            val lane: Lane = when {
                resolvedSessionID != null -> Lane.Session(resolvedSessionID)
                entry.draftID != null -> Lane.Draft(entry.draftID)
                else -> Lane.Draft(id)
            }
            val key = resolve(lane, entry.serverID)
            val state = storage.lanes[key] ?: LaneState(serverKey = entry.serverID, newSessionID = entry.newSessionID)
            if (lane is Lane.Draft && resolvedSessionID == null) {
                state.isDraft = true
                state.draftID = lane.id
            } else if (entry.draftID != null) {
                storage.aliases[key(Lane.Draft(entry.draftID), entry.serverID)] = key
            }
            entry.directory?.let { state.directory = it }
            entry.createdSession?.let { created ->
                state.createdSession = created
                state.createdSessionID = created.id
                state.draftID = null
                storage.aliases[key(Lane.Session(created.id), entry.serverID)] = key
            }
            if (resolvedSessionID != null) {
                if (state.createdSessionID == null) state.createdSessionID = resolvedSessionID
                state.draftID = null
                storage.aliases[key(Lane.Session(resolvedSessionID), entry.serverID)] = key
            }
            state.items += Item(
                pending = entry.pending,
                job = job,
                phase = if (entry.isHeld) Phase.Held else Phase.Waiting,
                backgroundKey = entry.backgroundKey,
                generation = storage.generation,
            )
            storage.lanes[key] = state
        }
    }

    fun startIfNeeded(lane: Lane, serverKey: String) {
        val start = synchronized(lock) { claimWorker(resolve(lane, serverKey)) }
        if (start != null) scope.launch(DependencyValues.current.asContextElement()) { run(start) }
    }

    /**
     * Adds a send to the end of its lane, or, for a held send being retried, puts it back in
     * its own place. Call it in the order the sends were made. The flow reports the send
     * until it settles.
     */
    fun enqueue(
        pending: PendingPrompt,
        lane: Lane,
        serverKey: String,
        backgroundKey: String? = null,
        newSessionID: String? = null,
        directory: String? = null,
        job: Job,
    ): Flow<Status> {
        val (stream, channel) = makeStream(pending.messageID)
        val (start, persisted) = synchronized(lock) {
            val id = pending.messageID
            observe(id, channel)
            storage.generation += 1
            val explicitSessionID = (lane as? Lane.Session)?.id
            val explicitDraftID = (lane as? Lane.Draft)?.id
            val key: String
            val located = locate(id)
            if (located != null) {
                key = located.first
                val index = located.second
                val laneState = storage.lanes.getValue(key)
                if (laneState.items[index].phase == Phase.Held) {
                    val operation = laneState.items[index].networkOperation
                    operation.retried()
                    laneState.items[index] = Item(
                        pending = pending, job = job, phase = Phase.Waiting,
                        backgroundKey = backgroundKey, generation = storage.generation, networkOperation = operation,
                    )
                }
            } else {
                key = resolve(lane, serverKey)
                if (storage.lanes[key] == null) {
                    // A draft lane creates its session under this ID; one the share sheet may
                    // already have made (`newSessionID`) is found, not made twice.
                    val state = LaneState(
                        serverKey = serverKey,
                        draftID = if (explicitSessionID == null) explicitDraftID else null,
                        directory = directory,
                        newSessionID = newSessionID ?: ServerID.newSessionID(now(), id),
                        createdSessionID = explicitSessionID,
                    )
                    if (lane is Lane.Draft) state.isDraft = true
                    storage.lanes[key] = state
                }
                storage.lanes[key]?.items?.add(
                    Item(pending = pending, job = job, phase = Phase.Waiting, backgroundKey = backgroundKey, generation = storage.generation),
                )
            }
            val state = storage.lanes[key]
            if (state != null) {
                if (explicitSessionID != null) {
                    state.createdSessionID = explicitSessionID
                    state.draftID = null
                } else if (state.createdSessionID != null || state.announced) {
                    state.draftID = null
                } else if (explicitDraftID != null && state.draftID == null) {
                    state.draftID = explicitDraftID
                }
                if (directory != null && state.directory == null) state.directory = directory
            }
            val snapshot = persistedSnapshot(
                state = state,
                sessionID = explicitSessionID,
                draftID = explicitDraftID,
                directory = directory,
                pending = pending,
                isHeld = false,
                backgroundKey = backgroundKey,
            )
            claimWorker(key) to snapshot
        }
        persisted?.record()
        if (start != null) scope.launch(DependencyValues.current.asContextElement()) { run(start) }
        return stream
    }

    /** Reports a send already on its way, for a screen opened after it was made. Null once it has settled or been deleted. */
    fun statuses(messageID: String): Flow<Status>? {
        val (stream, channel) = makeStream(messageID)
        val found = synchronized(lock) {
            val (key, index) = locate(messageID) ?: return@synchronized false
            val item = storage.lanes[key]?.items?.get(index) ?: return@synchronized false
            if (item.phase == Phase.Held || item.cancelled) return@synchronized false
            observe(messageID, channel)
            true
        }
        return if (found) stream else null
    }

    /** Waiting to try a send again. Nothing on screen shows it (a retry reads as sent); tests wait on it. */
    fun isRetrying(messageID: String): Boolean = synchronized(lock) {
        val (key, index) = locate(messageID) ?: return false
        storage.lanes[key]?.items?.get(index)?.phase == Phase.Retrying
    }

    data class Entry(
        val pending: PendingPrompt,
        /** Waits behind a send that failed or was stopped. */
        val isBlocked: Boolean,
    )

    data class Contents(
        /** Still on their way, in order, without any held ([UndeliveredPrompts] keeps those as failed sends) or deleted. */
        val unsent: List<Entry> = emptyList(),
        /** Admitted in the last couple of minutes, oldest first: a screen that opens before the transcript has them still shows them. */
        val admitted: List<PendingPrompt> = emptyList(),
    )

    val retainedAttachmentBytes: Int
        get() = synchronized(lock) {
            var total = 0
            for (lane in storage.lanes.values) {
                for (item in lane.items) total += item.pending.attachments.sumOf { it.data.size }
                for (admitted in lane.admitted) total += admitted.pending.attachments.sumOf { it.data.size }
            }
            total
        }

    fun contents(serverKey: String, sessionID: String): Contents = contents(serverKey, Lane.Session(sessionID))

    fun contents(serverKey: String, lane: Lane): Contents = synchronized(lock) {
        expireAdmitted()
        val laneState = storage.lanes[resolve(lane, serverKey)] ?: return Contents()
        val unsent = mutableListOf<Entry>()
        var blocked = false
        for (item in laneState.items) {
            if (item.phase == Phase.Held) {
                blocked = true
                continue
            }
            if (item.cancelled) continue
            unsent += Entry(item.pending, blocked)
        }
        val admitted = if (lane is Lane.Session) {
            laneState.admitted.filter { it.sessionID == lane.id }.map { it.pending }
        } else {
            emptyList()
        }
        Contents(unsent, admitted)
    }

    /**
     * A send this phone made outside the outbox (the voice agent's), once the server admitted
     * it, so a screen that opens on its session shows it like any admitted send. It reads as
     * pending (`wasRunning`) until the server announces its delivery.
     */
    fun recordAdmitted(pending: PendingPrompt, sessionID: String, serverKey: String) {
        synchronized(lock) {
            val key = resolve(Lane.Session(sessionID), serverKey)
            val lane = storage.lanes[key] ?: LaneState(serverKey = serverKey, newSessionID = ServerID.newSessionID(now(), pending.messageID))
            val admitted = pending.copy(ownsRun = false, wasRunning = pending.messageID !in storage.delivered)
            lane.admitted = lane.admitted.filterNot { it.pending.messageID == admitted.messageID }
            lane.appendAdmitted(admitted, sessionID, now())
            storage.lanes[key] = lane
            expireAdmitted()
        }
    }

    /** The server delivered a message into its session's history: an admitted send no longer waits there. */
    fun markDelivered(messageID: String) {
        synchronized(lock) {
            storage.delivered.remove(messageID)
            storage.delivered += messageID
            if (storage.delivered.size > 64) repeat(storage.delivered.size - 64) { storage.delivered.removeAt(0) }
            for (lane in storage.lanes.values) {
                // Updates the lane's first match only.
                val index = lane.admitted.indexOfFirst { it.pending.messageID == messageID }
                if (index < 0) continue
                lane.admitted = lane.admitted.toMutableList().also {
                    it[index] = it[index].copy(pending = it[index].pending.copy(wasRunning = false))
                }
            }
        }
    }

    /** The server dropped an admitted message from its inbox before delivering it: a screen opening on its session no longer shows it. */
    fun forgetAdmitted(messageID: String) {
        synchronized(lock) {
            for (lane in storage.lanes.values) lane.admitted = lane.admitted.filterNot { it.pending.messageID == messageID }
        }
    }

    /** A send failed or was stopped in this lane, so later sends wait. */
    fun isHeld(lane: Lane, serverKey: String): Boolean = synchronized(lock) {
        storage.lanes[resolve(lane, serverKey)]?.items?.any { it.phase == Phase.Held } == true
    }

    /**
     * Deletes a send: true if the outbox has it. Waiting or held, it goes now; being retried,
     * its wait ends and it goes; being posted, the server is told to drop it once it's
     * admitted. Sends behind it go on.
     */
    fun withdraw(messageID: String): Boolean {
        var found = false
        var observers: List<Channel<Status>> = emptyList()
        var start: String? = null
        var unused: String? = null
        var persistence: ComposerPersistence? = null
        synchronized(lock) {
            val (key, index) = locate(messageID) ?: return@synchronized
            val lane = storage.lanes[key] ?: return@synchronized
            val item = lane.items[index]
            found = true
            persistence = storage.persistence
            when (item.phase) {
                Phase.Waiting, Phase.Held -> {
                    lane.items.removeAt(index)
                    observers = storage.observers.remove(messageID)?.values?.toList().orEmpty()
                    start = claimWorker(key)
                    if (start == null) prune(key)
                    unused = item.backgroundKey
                }
                Phase.Retrying, Phase.Posting -> {
                    item.sleeper?.cancel()
                    lane.items[index] = item.copy(cancelled = true)
                }
            }
        }
        if (found) persistence?.settlePrompts(setOf(messageID))
        for (observer in observers) {
            unused?.let { observer.trySend(Status.BackgroundUnused(it)) }
            observer.trySend(Status.Withdrawn)
            observer.close()
        }
        start?.let { key -> scope.launch(DependencyValues.current.asContextElement()) { run(key) } }
        return found
    }

    /**
     * Changes how a send is delivered: true if the outbox has it. Not yet posted, it goes that
     * way; being posted, the server hears once it's admitted.
     */
    fun setDelivery(messageID: String, delivery: OpenCodeDelivery, backgroundKey: String? = null): Boolean {
        var effectiveBackgroundKey: String? = null
        var persistence: ComposerPersistence? = null
        val updated = synchronized(lock) {
            val (key, index) = locate(messageID) ?: return@synchronized false
            val lane = storage.lanes[key] ?: return@synchronized false
            val item = lane.items[index]
            if (item.cancelled) return@synchronized false
            val claimed = item.backgroundKey
            val nextBackgroundKey = if (delivery == OpenCodeDelivery.Steer) claimed ?: backgroundKey else null
            lane.items[index] = item.copy(pending = item.pending.copy(delivery = delivery), backgroundKey = nextBackgroundKey)
            effectiveBackgroundKey = nextBackgroundKey
            persistence = storage.persistence
            true
        }
        if (updated) persistence?.updateOutboxDelivery(messageID, delivery, effectiveBackgroundKey)
        return updated
    }

    /**
     * Stop: nothing in the lane is posted until the reader retries it. Sends waiting or being
     * retried are held at once and returned, as they now stand, for the screen to show as
     * failed sends (so Retry works at once). One already being posted finishes this attempt:
     * admitted, it's delivered; failed, it's held as a failed send without another try.
     */
    fun stop(lane: Lane, serverKey: String): List<PendingPrompt> {
        val stopped = mutableListOf<PendingPrompt>()
        val observers = mutableListOf<Pair<Channel<Status>, String?>>()
        synchronized(lock) {
            val state = storage.lanes[resolve(lane, serverKey)] ?: return@synchronized
            for (index in state.items.indices) {
                var item = state.items[index]
                if (item.cancelled) continue
                item = item.copy(stopped = true)
                when (item.phase) {
                    Phase.Waiting, Phase.Retrying -> {
                        // A job waiting to retry wakes, sees it held, and stops.
                        item.sleeper?.cancel()
                        val unused = item.backgroundKey
                        item = item.copy(sleeper = null, phase = Phase.Held, backgroundKey = null)
                        stopped += item.pending
                        storage.observers.remove(item.pending.messageID)?.values?.forEach { observers += it to unused }
                    }
                    Phase.Posting, Phase.Held -> Unit
                }
                state.items[index] = item
            }
        }
        for ((observer, unused) in observers) {
            unused?.let { observer.trySend(Status.BackgroundUnused(it)) }
            observer.trySend(Status.Stopped)
            observer.close()
        }
        return stopped
    }

    sealed interface DraftStop {
        /** Its session exists (it may not have reached the screen yet): Stop is that session's Stop. */
        data class Created(val session: OpenCodeSession) : DraftStop

        /** Nothing was created: every send not yet admitted is deleted, and returned for the composer. The lane is abandoned. */
        data class Cancelled(val pendings: List<PendingPrompt>) : DraftStop
    }

    /** A draft's Stop, decided at once against the session's creation. */
    fun stopDraft(lane: Lane, serverKey: String): DraftStop {
        val decision = synchronized(lock) {
            val state = storage.lanes[resolve(lane, serverKey)] ?: return@synchronized DraftStop.Cancelled(emptyList())
            val session = state.createdSession
            if (session != null) {
                state.announced = true
                state.draftID = null
                DraftStop.Created(session)
            } else {
                DraftStop.Cancelled(state.items.filter { it.phase != Phase.Held && !it.cancelled }.map { it.pending })
            }
        }
        if (decision is DraftStop.Cancelled) decision.pendings.forEach { withdraw(it.messageID) }
        return decision
    }

    /** Failed sends the server turns out to have after all no longer hold their lanes. */
    fun settle(messageIDs: Set<String>) {
        if (messageIDs.isEmpty()) return
        val (held, persistence) = synchronized(lock) {
            expireAdmitted()
            storage.lanes.values.flatMap { lane ->
                lane.items.filter { it.phase == Phase.Held && it.pending.messageID in messageIDs }.map { it.pending.messageID }
            } to storage.persistence
        }
        persistence?.settlePrompts(messageIDs)
        held.forEach(::withdraw)
    }

    // Lanes

    private suspend fun run(key: String) {
        val endBackgroundWork = backgroundWork()
        try {
            while (true) {
                val (item, lane) = next(key) ?: break
                val id = item.pending.messageID
                val generation = item.generation
                val handle = Handle(
                    createdSessionID = lane.first,
                    newSessionID = lane.second,
                    current = { pending(id) ?: item.pending },
                    begin = { begin(id, generation) },
                    wait = { sleep -> wait(id, generation, sleep) },
                    intent = { intent(id) },
                    report = { status -> report(status, id) },
                    created = { session -> created(session, key) },
                    takeBackgroundKey = { takeBackgroundKey(id, generation) },
                    networkOperation = item.networkOperation,
                )
                val outcome = item.job.deliver(handle)
                finish(id, generation, key, outcome)
            }
        } finally {
            endBackgroundWork()
        }
    }

    private fun finish(id: String, generation: Int, key: String, outcome: DeliveryOutcome) {
        var observers: List<Channel<Status>> = emptyList()
        var status: Status? = null
        var unused: String? = null
        var settledPersistence: ComposerPersistence? = null
        var heldSnapshot: PersistedSnapshot? = null
        synchronized(lock) {
            val lane = storage.lanes[key] ?: return@synchronized
            val index = lane.items.indexOfFirst { it.pending.messageID == id }
            if (index < 0 || lane.items[index].generation != generation) return@synchronized
            val item = lane.items[index]
            // A claim the job never took: a held send claims afresh on Retry.
            lane.items[index] = item.copy(backgroundKey = null)
            val created = outcome.createdSession
            if (lane.isDraft && created != null && lane.createdSession == null) {
                lane.createdSession = created
                lane.createdSessionID = created.id
                storage.aliases[key(Lane.Session(created.id), lane.serverKey)] = key
            }
            // The first screen to hear of a draft's session is told it was created, once.
            var announce: OpenCodeSession? = null
            val session = lane.createdSession
            if (lane.isDraft && !lane.announced && session != null) {
                lane.announced = true
                announce = session
            }
            if (lane.createdSessionID != null || lane.announced) lane.draftID = null
            when (outcome) {
                is DeliveryOutcome.Sent -> {
                    lane.items.removeAt(index)
                    // Its delivery can arrive over the event stream before the post's answer.
                    val pending = if (item.pending.messageID in storage.delivered) item.pending.copy(wasRunning = false) else item.pending
                    lane.appendAdmitted(pending, outcome.sessionID, now())
                    expireAdmitted()
                    status = Status.Settled(
                        DeliveryOutcome.Sent(outcome.sessionID, announce, outcome.appliedModel, outcome.appliedAgent),
                        item.pending,
                    )
                    settledPersistence = storage.persistence
                }
                is DeliveryOutcome.Failed -> if (item.cancelled) {
                    lane.items.removeAt(index)
                    status = Status.Withdrawn
                    settledPersistence = storage.persistence
                } else {
                    lane.items[index] = lane.items[index].copy(phase = Phase.Held, sleeper = null)
                    status = Status.Settled(DeliveryOutcome.Failed(outcome.sessionID, announce, outcome.error), item.pending)
                    heldSnapshot = persistedSnapshot(
                        state = lane,
                        sessionID = outcome.sessionID,
                        pending = item.pending,
                        isHeld = true,
                        backgroundKey = null,
                    )
                }
                is DeliveryOutcome.Withdrawn -> {
                    lane.items.removeAt(index)
                    status = Status.Withdrawn
                    settledPersistence = storage.persistence
                }
            }
            observers = storage.observers.remove(id)?.values?.toList().orEmpty()
            unused = item.backgroundKey
        }
        settledPersistence?.settlePrompts(setOf(id))
        heldSnapshot?.record()
        val settled = status ?: return
        for (observer in observers) {
            unused?.let { observer.trySend(Status.BackgroundUnused(it)) }
            observer.trySend(settled)
            observer.close()
        }
    }

    /** A draft's session exists as soon as it's created, before its first send is admitted. */
    private fun created(session: OpenCodeSession, key: String) {
        var info: Triple<ComposerPersistence, String, String?>? = null
        synchronized(lock) {
            val lane = storage.lanes[key] ?: return@synchronized
            if (!lane.isDraft || lane.createdSession != null) return@synchronized
            val draftID = lane.draftID
            lane.createdSession = session
            lane.createdSessionID = session.id
            lane.draftID = null
            storage.aliases[key(Lane.Session(session.id), lane.serverKey)] = key
            val persistence = storage.persistence ?: return@synchronized
            info = Triple(persistence, lane.serverKey, draftID)
        }
        info?.let { (persistence, serverKey, draftID) -> persistence.noteDraftSessionCreated(serverKey, draftID, session) }
    }

    /** The lane's first send, if it's waiting (with the lane's created session ID and new session ID); otherwise the worker stops. */
    private fun next(key: String): Pair<Item, Pair<String?, String>>? = synchronized(lock) {
        val lane = storage.lanes[key]
        val head = lane?.items?.firstOrNull()
        if (lane == null || head == null || head.phase != Phase.Waiting) {
            lane?.isWorking = false
            prune(key)
            return null
        }
        lane.items[0] = head.copy(phase = Phase.Posting)
        head to (lane.createdSessionID to lane.newSessionID)
    }

    private fun pending(messageID: String): PendingPrompt? = synchronized(lock) {
        val (key, index) = locate(messageID) ?: return null
        storage.lanes[key]?.items?.get(index)?.pending
    }

    private fun begin(messageID: String, generation: Int): Attempt = synchronized(lock) {
        val (key, index) = locate(messageID) ?: return Attempt.Cancelled
        val lane = storage.lanes[key] ?: return Attempt.Cancelled
        val item = lane.items[index]
        if (item.generation != generation) return Attempt.Superseded
        if (item.cancelled) return Attempt.Cancelled
        if (item.stopped || item.phase == Phase.Held) return Attempt.Stopped
        lane.items[index] = item.copy(phase = Phase.Posting, postedDelivery = item.pending.delivery, sleeper = null)
        Attempt.Post(item.pending)
    }

    private fun intent(messageID: String): Intent = synchronized(lock) {
        val (key, index) = locate(messageID) ?: return Intent(cancelled = true)
        val item = storage.lanes[key]?.items?.get(index) ?: return Intent(cancelled = true)
        val changed = item.postedDelivery?.let { if (it == item.pending.delivery) null else item.pending.delivery }
        Intent(cancelled = item.cancelled, stopped = item.stopped, changedDelivery = changed, postedDelivery = item.postedDelivery)
    }

    private fun takeBackgroundKey(messageID: String, generation: Int): String? = synchronized(lock) {
        val (key, index) = locate(messageID) ?: return null
        val lane = storage.lanes[key] ?: return null
        val item = lane.items[index]
        if (item.generation != generation || item.cancelled || item.stopped || item.pending.delivery != OpenCodeDelivery.Steer) return null
        val backgroundKey = item.backgroundKey ?: return null
        lane.items[index] = item.copy(backgroundKey = null)
        backgroundKey
    }

    /** Marks the send as being retried and waits, unless Delete or Stop came first or come meanwhile. */
    private suspend fun wait(messageID: String, generation: Int, sleep: suspend () -> Boolean): Boolean = coroutineScope {
        val sleeper = async(start = CoroutineStart.LAZY) { sleep() }
        val registered = synchronized(lock) {
            val (key, index) = locate(messageID) ?: return@synchronized false
            val lane = storage.lanes[key] ?: return@synchronized false
            val item = lane.items[index]
            if (item.generation != generation || item.cancelled || item.stopped || item.phase == Phase.Held) return@synchronized false
            lane.items[index] = item.copy(phase = Phase.Retrying, sleeper = sleeper)
            true
        }
        if (!registered) {
            sleeper.cancel()
            return@coroutineScope false
        }
        try {
            sleeper.await() && !sleeper.isCancelled
        } catch (cancelled: CancellationException) {
            // Delete or Stop cancelled the wait; the job's own cancellation still propagates.
            currentCoroutineContext().ensureActive()
            false
        }
    }

    private fun report(status: Status, messageID: String) {
        val observers = synchronized(lock) {
            // A failed PATCH: the send is kept (and remembered once admitted) as the server has it, with the delivery it was posted with.
            if (status is Status.UpdateFailed && status.restoring != null) {
                locate(messageID)?.let { (key, index) ->
                    val lane = storage.lanes.getValue(key)
                    val item = lane.items[index]
                    lane.items[index] = item.copy(pending = item.pending.copy(delivery = status.restoring))
                }
            }
            storage.observers[messageID]?.values?.toList().orEmpty()
        }
        observers.forEach { it.trySend(status) }
    }

    /** A stream for [messageID]'s statuses; collecting it ends when the send settles. Cancelling the collection unregisters it. */
    private fun makeStream(messageID: String): Pair<Flow<Status>, Channel<Status>> {
        val channel = Channel<Status>(Channel.UNLIMITED)
        val stream = flow {
            try {
                for (status in channel) emit(status)
            } finally {
                synchronized(lock) {
                    val observers = storage.observers[messageID]
                    observers?.entries?.removeAll { it.value === channel }
                    if (observers?.isEmpty() == true) storage.observers.remove(messageID)
                }
            }
        }
        return stream to channel
    }

    private fun observe(messageID: String, channel: Channel<Status>) {
        storage.observers.getOrPut(messageID) { HashMap() }[UUID.randomUUID()] = channel
    }

    private fun persistedSnapshot(
        state: LaneState?,
        sessionID: String?,
        draftID: String? = null,
        directory: String? = null,
        pending: PendingPrompt,
        isHeld: Boolean,
        backgroundKey: String?,
    ): PersistedSnapshot? {
        val persistence = storage.persistence ?: return null
        state ?: return null
        val resolvedSessionID = sessionID ?: state.createdSessionID
        return PersistedSnapshot(
            persistence = persistence,
            serverID = state.serverKey,
            sessionID = resolvedSessionID,
            draftID = if (resolvedSessionID == null && !state.announced) draftID ?: state.draftID else null,
            directory = directory ?: state.directory,
            newSessionID = state.newSessionID,
            createdSession = state.createdSession,
            pending = pending,
            isHeld = isHeld,
            backgroundKey = backgroundKey,
        )
    }

    /** Marks the lane as worked on if its first send is ready and no worker runs; the caller then starts one. */
    private fun claimWorker(key: String): String? {
        val lane = storage.lanes[key] ?: return null
        if (lane.isWorking || lane.items.firstOrNull()?.phase != Phase.Waiting) return null
        lane.isWorking = true
        return key
    }

    /**
     * An empty session lane goes once it has nothing recent to remember. A draft's lane stays,
     * so a send the screen makes before it hears of its session still goes there rather than
     * creating another.
     */
    private fun prune(key: String) {
        val lane = storage.lanes[key] ?: return
        if (lane.items.isEmpty() && !lane.isWorking && !lane.isDraft && lane.admitted.isEmpty()) storage.lanes.remove(key)
    }

    private fun expireAdmitted() {
        val cutoff = now().minusMillis((ADMITTED_MEMORY_SECONDS * 1000).toLong())
        for (lane in storage.lanes.values) {
            if (lane.admitted.any { it.at <= cutoff }) lane.admitted = lane.admitted.filter { it.at > cutoff }
        }
    }

    private fun locate(messageID: String): Pair<String, Int>? {
        for ((key, lane) in storage.lanes) {
            val index = lane.items.indexOfFirst { it.pending.messageID == messageID }
            if (index >= 0) return key to index
        }
        return null
    }

    private fun resolve(lane: Lane, serverKey: String): String {
        val key = key(lane, serverKey)
        return storage.aliases[key] ?: key
    }

    companion object Key : DependencyKey<PromptOutbox> {
        /** How long a delivered send is remembered for a screen opened just after (seconds). */
        const val ADMITTED_MEMORY_SECONDS = 120.0

        override val liveValue: PromptOutbox by lazy {
            PromptOutbox(backgroundWork = { dependency(BackgroundTaskClient).begin("Send messages") })
        }

        override val testValue: PromptOutbox get() = PromptOutbox()

        private fun key(lane: Lane, serverKey: String): String = when (lane) {
            is Lane.Session -> "$serverKey|session|${lane.id}"
            is Lane.Draft -> "$serverKey|draft|${lane.id}"
        }

    }
}
