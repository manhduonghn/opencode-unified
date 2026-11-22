package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.moveToBackgroundBeforeSteer
import ai.opencode.server.retry.RetryBudget
import ai.opencode.server.sendPromptConfirming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The bridge from a voice delegation to the OpenCode server. The voice
 * feature never talks to `OpenCodeClient` directly, so this seam stays
 * independently testable and decoupled from the rest of the app.
 */
data class VoiceWorkTarget(
    val server: OpenCodeServer,
    /**
     * Null while the call sits on a new session's draft: the first request
     * creates the session in [directory].
     */
    val sessionID: String?,
    val directory: String,
    /**
     * Set only on a submission: the model applied before its prompt (a
     * draft's, or the screen's waiting pick). The call's own target never
     * carries one.
     */
    val model: OpenCodeModelRef? = null,
    /**
     * Set only on a submission from a draft whose session the screen is
     * already creating: creating it again finds that one.
     */
    val newSessionID: String? = null,
)

data class VoiceWorkAdmission(
    /**
     * Null when nothing was sent: the draft's session was created, but its
     * model couldn't be set ([modelError]).
     */
    val promptID: String?,
    /** The session this request created, when the target was a draft. */
    val createdSession: OpenCodeSession? = null,
    /** The submission's model was refused, so its prompt wasn't sent. */
    val modelError: OpenCodeClientError? = null,
)

/**
 * Work a session started in the background for a prompt the call watched
 * (a background subagent), still running when that prompt's run finished.
 */
data class VoiceBackgroundWork(
    /** The session doing it. */
    val sessionID: String,
    /** The session that started it. */
    val parentSessionID: String,
    val description: String,
)

/**
 * How long a deferred result waits at most from arrival before speaking
 * anyway, whether claimed or held behind another foreground turn.
 */
val ReplyWatch.Companion.claimedResultLimit: Duration get() = 20.seconds

fun ReplyWatch.Outcome.notice(title: String): String = when (this) {
    is ReplyWatch.Outcome.Replied -> {
        val bounded = spoken(reply)
        "The session “$title” finished the work you started there. Tell the user only what's new since you last spoke about it; don't repeat the request or anything you already said, and if nothing is new, say nothing. Its reply: $bounded"
    }
    ReplyWatch.Outcome.FinishedWithoutText ->
        "The session “$title” finished the work you started there without a text reply. Tell the user briefly that “$title” finished its steps."
    is ReplyWatch.Outcome.ReplyUnreadable ->
        "The session “$title” finished the work you started there, but its latest reply couldn’t be read ($reason). Tell the user “$title” is done and offer to read the session."
    is ReplyWatch.Outcome.Failed ->
        "The session “$title” stopped with an error on the work you started there: $message. Tell the user briefly what failed."
    is ReplyWatch.Outcome.Unreachable ->
        "Couldn’t reach the OpenCode server while waiting for “$title” ($message). Tell the user the server connection dropped while waiting on “$title”, and offer to check it again."
}

/** A reply as the voice agent gets it: code collapsed, bounded. */
private fun spoken(reply: String): String {
    val cleaned = LiveContentChunker.collapseCodeBlocks(reply.trim())
    return if (cleaned.length > VoiceHandoff.replyLimit) cleaned.take(VoiceHandoff.replyLimit) + "…" else cleaned
}

/**
 * Background work's result: the user heard only that it started, so it's
 * news to tell them unasked.
 */
private fun ReplyWatch.Outcome.backgroundNotice(title: String, work: String): String {
    val started = "The background work “$work” that the session “$title” started for the user"
    return when (this) {
        is ReplyWatch.Outcome.Replied ->
            "$started has finished. Unless you've just told the user what it found, tell them now, briefly, without waiting to be asked; if you have, say nothing. Its result: ${spoken(reply)}"
        ReplyWatch.Outcome.FinishedWithoutText ->
            "$started has finished without a text result. Tell the user briefly that it's done."
        is ReplyWatch.Outcome.ReplyUnreadable ->
            "$started has finished, but its result couldn’t be read ($reason). Tell the user it's done and offer to read “$title”."
        is ReplyWatch.Outcome.Failed ->
            "$started stopped with an error: $message. Tell the user briefly what failed."
        is ReplyWatch.Outcome.Unreachable ->
            "Couldn’t reach the OpenCode server while waiting for the background work “$work” in “$title” ($message). Tell the user the connection dropped while waiting on it, and offer to check it again."
    }
}

/**
 * One reply for several requests, or one the delegator already reported
 * in part: names what it answers and asks only for what's new.
 */
fun ReplyWatch.Outcome.notice(title: String, answering: List<String>, alreadyTold: String?, background: String? = null): String {
    val base = background?.let { backgroundNotice(title, it) } ?: notice(title)
    if (answering.size <= 1 && alreadyTold == null) return base
    var lead = ""
    if (answering.size > 1) {
        lead += "This one reply answers ${answering.size} earlier requests: " + answering.joinToString(", ") { "“$it”" } + ". "
    }
    if (alreadyTold != null) {
        lead += "You already told the user part of it: “$alreadyTold”. Say only what they haven't heard yet. "
    }
    return lead + base
}

/**
 * Wakes in-flight `/wait` calls when SSE or `/api/session/active` reports a session has settled, so
 * a wedged long-poll socket never holds back a reply. It also owns each session's `/wait`: every
 * reply watch and delegator wait on a session shares one long poll, cancelled once none of them is
 * left.
 */
class VoiceWorkCoordinator {
    /**
     * A session on one server: two servers can share a session ID, and one
     * server's settlement never ends a wait on the other.
     */
    private data class SessionKey(val server: String, val sessionID: String) {
        constructor(server: OpenCodeServer, sessionID: String) : this(server.persistenceID, sessionID)
    }

    private val mutex = Mutex()
    private val waiters = HashMap<SessionKey, HashMap<Long, CompletableDeferred<Unit>>>()
    private var nextID = 0L

    private class SharedWait(
        val generation: Long,
        val job: Job,
        val joined: HashMap<Long, CompletableDeferred<Result<Unit>>>,
    )

    private val sharedWaits = HashMap<SessionKey, SharedWait>()

    /** Runs the shared polls: each outlives any one waiter and stops when the last leaves. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * `client.waitForIdle`, made once per session however many wait on it.
     * A waiter that's cancelled returns at once; the poll stops when the last
     * one leaves.
     */
    suspend fun sharedWaitForIdle(server: OpenCodeServer, sessionID: String, client: OpenCodeClient) {
        // Already cancelled: it never opens a wait.
        if (!currentCoroutineContext().isActive) throw leftWait
        val key = SessionKey(server, sessionID)
        val result = CompletableDeferred<Result<Unit>>()
        val id = mutex.withLock {
            val id = nextID++
            val existing = sharedWaits[key]
            if (existing != null) {
                existing.joined[id] = result
            } else {
                val generation = nextID++
                // The shared wait keeps the caller's context (dependencies, retry policy,
                // dispatcher) but outlives it.
                val job = scope.launch(currentCoroutineContext().minusKey(Job), start = CoroutineStart.LAZY) {
                    val outcome = try {
                        client.waitForIdle(server, sessionID)
                        Result.success(Unit)
                    } catch (error: OpenCodeClientError) {
                        Result.failure(error)
                    }
                    finish(key, generation, outcome)
                }
                sharedWaits[key] = SharedWait(generation, job, hashMapOf(id to result))
                job.start()
            }
            id
        }
        try {
            result.await().getOrThrow()
        } finally {
            withContext(NonCancellable) { leave(key, id) }
        }
    }

    private suspend fun finish(key: SessionKey, generation: Long, result: Result<Unit>) {
        val wait = mutex.withLock {
            val wait = sharedWaits[key]
            if (wait == null || wait.generation != generation) return
            sharedWaits.remove(key)
            wait
        }
        for (continuation in wait.joined.values) continuation.complete(result)
    }

    private suspend fun leave(key: SessionKey, id: Long) {
        mutex.withLock {
            val wait = sharedWaits[key] ?: return
            val continuation = wait.joined.remove(id) ?: return
            continuation.complete(Result.failure(leftWait))
            if (wait.joined.isEmpty()) {
                wait.job.cancel()
                sharedWaits.remove(key)
            }
        }
    }

    /**
     * Who's waiting on the session's `/wait` (0 when none is open), and how
     * many session waits are open in all.
     */
    suspend fun waiterCount(server: OpenCodeServer, sessionID: String): Int =
        mutex.withLock { sharedWaits[SessionKey(server, sessionID)]?.joined?.size ?: 0 }

    suspend fun openWaitCount(): Int = mutex.withLock { sharedWaits.size }

    suspend fun noteSessionSettled(server: OpenCodeServer, sessionID: String) {
        settle(SessionKey(server, sessionID))
    }

    /** A snapshot of one server's running sessions settles only that server's waits. */
    suspend fun noteActiveSessions(server: OpenCodeServer, activeIDs: Set<String>) {
        val settled = mutex.withLock { waiters.keys.filter { it.server == server.persistenceID && it.sessionID !in activeIDs } }
        for (key in settled) settle(key)
    }

    private suspend fun settle(key: SessionKey) {
        val sessionWaiters = mutex.withLock { waiters.remove(key) } ?: return
        for (signal in sessionWaiters.values) signal.complete(Unit)
    }

    suspend fun waitForIdleOrSettlement(server: OpenCodeServer, sessionID: String, client: OpenCodeClient) {
        val signal = CompletableDeferred<Unit>()
        val key = SessionKey(server, sessionID)
        val id = mutex.withLock {
            val id = nextID++
            waiters.getOrPut(key) { HashMap() }[id] = signal
            id
        }
        try {
            val failure: OpenCodeClientError? = coroutineScope {
                val wait = async {
                    try {
                        sharedWaitForIdle(server, sessionID, client)
                        null
                    } catch (error: OpenCodeClientError) {
                        error
                    }
                }
                val first = select {
                    wait.onAwait { it }
                    signal.onAwait { null }
                }
                coroutineContext.cancelChildren()
                first
            }
            if (failure != null) throw failure
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    waiters[key]?.remove(id)
                    if (waiters[key]?.isEmpty() == true) waiters.remove(key)
                }
            }
        }
        // A cancelled wait ends its settlement stream too: that isn't a
        // settlement, and the caller mustn't read the reply after it.
        if (!currentCoroutineContext().isActive) throw leftWait
    }

    suspend fun fetchReply(
        server: OpenCodeServer,
        sessionID: String,
        promptID: String,
        client: OpenCodeClient,
        clock: Clock,
    ): String? {
        val delays = listOf(150.milliseconds, 400.milliseconds)
        for (attempt in 0..delays.size) {
            try {
                return client.assistantReply(server, sessionID, promptID)
            } catch (error: OpenCodeClientError) {
                // Only the projection lagging the settlement: a transport failure
                // already rode out the request's own retries.
                if (attempt >= delays.size || !error.isRetryableWaitError || error.transport != null ||
                    !currentCoroutineContext().isActive
                ) {
                    throw error
                }
                clock.sleep(delays[attempt])
            }
        }
        return null
    }

    private companion object {
        val leftWait = OpenCodeClientError(message = "Stopped waiting")
    }
}

class VoiceWorkClient(
    /**
     * Admits one delegation as steering, so a follow-up reaches a running
     * session at its next step and an idle one starts at once.
     */
    val submit: suspend (target: VoiceWorkTarget, text: String) -> VoiceWorkAdmission,
    /** Waits for the run to settle and returns the attributed assistant text. */
    val awaitReply: suspend (server: OpenCodeServer, sessionID: String, promptID: String) -> String?,
    /**
     * Waits for the session to settle on the same `/wait` as its reply
     * watches: the delegator's `wait_for_session`.
     */
    val awaitIdle: suspend (server: OpenCodeServer, sessionID: String) -> Unit = { server, sessionID ->
        dependency(OpenCodeClient).waitForIdle(server, sessionID)
    },
    /** Reads the assistant reply directly once SSE or a snapshot reported the session settled. */
    val fetchReply: suspend (server: OpenCodeServer, sessionID: String, promptID: String) -> String? = { server, sessionID, promptID ->
        dependency(OpenCodeClient).assistantReply(server, sessionID, promptID)
    },
    /**
     * The reply of the run a prompt was steered into, for a watched prompt
     * whose own read found none (a later delegation joined the run first).
     */
    val fetchRunReply: suspend (server: OpenCodeServer, sessionID: String, promptID: String) -> String? = { _, _, _ -> null },
    /**
     * Background work a prompt's run started that's still running (a
     * background subagent), so its result is spoken when it lands.
     * [reply] is the run's reply as the call read it: work whose completion
     * the session wrote that reply after was covered by it.
     */
    val backgroundWork: suspend (server: OpenCodeServer, sessionID: String, promptID: String, reply: String?) -> List<VoiceBackgroundWork> = { _, _, _, _ -> emptyList() },
    /** Background work's result: the final reply of the session doing it. */
    val backgroundReply: suspend (server: OpenCodeServer, sessionID: String) -> String? = { _, _ -> null },
    /**
     * Session-delegator background work's result: what `parentSessionID` said
     * when `<subagent>` reported `childSessionID`'s completion, or null when a
     * user delegation turn in that run covers it (or the parent stayed silent).
     */
    val relayedBackgroundReply: suspend (server: OpenCodeServer, parentSessionID: String, childSessionID: String) -> String? =
        { _, _, _ -> null },
    /** Tri-state active check: `true` if running, `false` if confirmed idle, `null` if unreachable. */
    val checkSessionActive: suspend (server: OpenCodeServer, sessionID: String) -> Boolean? = { server, sessionID ->
        try {
            dependency(OpenCodeClient).isSessionActive(server, sessionID)
        } catch (_: OpenCodeClientError) {
            null
        }
    },
    val activeSessionIDs: suspend (server: OpenCodeServer) -> Set<String> = { server ->
        dependency(OpenCodeClient).homeSnapshot(server).activeSessionIDs
    },
    val noteSessionSettled: suspend (server: OpenCodeServer, sessionID: String) -> Unit = { _, _ -> },
    val noteActiveSessions: suspend (server: OpenCodeServer, activeSessionIDs: Set<String>) -> Unit = { _, _ -> },
    /** Recent plain-text messages for the voice model's context. */
    val recentMessages: suspend (server: OpenCodeServer, sessionID: String, limit: Int) -> List<String>,
) {
    data class NewestReply(val reply: String?, val promptIDs: Set<String>)

    companion object Key : DependencyKey<VoiceWorkClient> {
        override val liveValue: VoiceWorkClient by lazy { live(VoiceWorkCoordinator()) }

        /** The live client on its own coordinator: tests get a fresh one. */
        fun live(coordinator: VoiceWorkCoordinator): VoiceWorkClient =
            VoiceWorkClient(
                submit = { target, text ->
                    val client = dependency(OpenCodeClient)
                    // One budget for the delegation, as for a delegator tool: creating,
                    // the model, and the prompt retry within what's left of it.
                    val budget = RetryBudget(VoiceDelegatorWorkspace.Latency.standard.total)
                    var created: OpenCodeSession? = null
                    val sessionID: String
                    if (target.sessionID != null) {
                        sessionID = target.sessionID
                    } else {
                        val session = budget.required {
                            client.createSession(
                                target.server,
                                target.directory,
                                target.newSessionID ?: dependency(ServerIDClient).sessionID(),
                            )
                        }
                        created = session
                        sessionID = session.id
                    }
                    // Like a typed send: the model (a draft's, or a pick since the last
                    // send) goes just before the prompt, and a refused one sends nothing.
                    val model = target.model
                    var refused: VoiceWorkAdmission? = null
                    if (model != null) {
                        try {
                            budget.required { client.switchModel(target.server, sessionID, model) }
                        } catch (error: OpenCodeClientError) {
                            // A session just created is handed back so it isn't left behind.
                            if (created == null) throw error.copy(message = "Couldn’t switch model: ${error.message}")
                            refused = VoiceWorkAdmission(promptID = null, createdSession = created, modelError = error)
                        }
                    }
                    refused ?: run {
                        if (created == null) client.moveToBackgroundBeforeSteer(target.server, sessionID)
                        val promptID = budget.required {
                            client.sendPromptConfirming(target.server, sessionID, text, OpenCodeDelivery.Steer)
                        }
                        VoiceWorkAdmission(promptID = promptID, createdSession = created)
                    }
                },
                awaitReply = { server, sessionID, promptID ->
                    val client = dependency(OpenCodeClient)
                    val clock = dependency(Clock)
                    coordinator.waitForIdleOrSettlement(server, sessionID, client)
                    coordinator.fetchReply(server, sessionID, promptID, client, clock)
                },
                awaitIdle = { server, sessionID ->
                    coordinator.waitForIdleOrSettlement(server, sessionID, dependency(OpenCodeClient))
                },
                fetchReply = { server, sessionID, promptID ->
                    coordinator.fetchReply(server, sessionID, promptID, dependency(OpenCodeClient), dependency(Clock))
                },
                fetchRunReply = { server, sessionID, promptID ->
                    dependency(OpenCodeClient).runReply(server, sessionID, promptID)
                },
                backgroundWork = { server, sessionID, promptID, reply ->
                    val client = dependency(OpenCodeClient)
                    // The run may be longer than a page: read back to its prompt.
                    var messages: List<OpenCodeRunMessage> = emptyList()
                    var cursor: String? = null
                    for (page in 0 until backgroundSearchPages) {
                        val read = client.runMessages(server, sessionID, cursor)
                        messages = read.messages + messages
                        if (messages.any { it.id == promptID }) break
                        cursor = read.next ?: break
                    }
                    pendingBackgroundWork(messages, sessionID, promptID, reply)
                },
                backgroundReply = { server, sessionID ->
                    replyAfterPrompt(dependency(OpenCodeClient).transcript(server, sessionID, 40), afterPromptID = null)
                },
                relayedBackgroundReply = relayed@{ server, parentSessionID, childSessionID ->
                    val client = dependency(OpenCodeClient)
                    val clock = dependency(Clock)
                    val delays = listOf(150.milliseconds, 400.milliseconds)
                    for (attempt in 0..delays.size) {
                        coordinator.waitForIdleOrSettlement(server, parentSessionID, client)
                        var messages: List<OpenCodeRunMessage> = emptyList()
                        var cursor: String? = null
                        for (page in 0 until backgroundSearchPages) {
                            val read = client.runMessages(server, parentSessionID, cursor)
                            messages = read.messages + messages
                            if (messages.any { it.completedChildID == childSessionID }) break
                            cursor = read.next ?: break
                        }
                        when (val outcome = subagentRelayOutcome(messages, childSessionID)) {
                            is SubagentRelayOutcome.Replied -> return@relayed outcome.reply
                            SubagentRelayOutcome.CoveredOrSilent -> return@relayed null
                            SubagentRelayOutcome.Pending -> {
                                if (attempt >= delays.size || !currentCoroutineContext().isActive) {
                                    throw OpenCodeClientError("Waiting for Voice session to relay background work.")
                                }
                                clock.sleep(delays[attempt])
                            }
                        }
                    }
                    throw OpenCodeClientError("Waiting for Voice session to relay background work.")
                },
                noteSessionSettled = { server, sessionID -> coordinator.noteSessionSettled(server, sessionID) },
                noteActiveSessions = { server, activeIDs -> coordinator.noteActiveSessions(server, activeIDs) },
                recentMessages = { server, sessionID, limit ->
                    val items = dependency(OpenCodeClient).transcript(server, sessionID, limit)
                    formatRecentMessages(items)
                },
            )

        override val testValue: VoiceWorkClient
            get() = VoiceWorkClient(
                submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_test") },
                awaitReply = { _, _, _ -> null },
                checkSessionActive = { _, _ -> false },
                recentMessages = { _, _, _ -> emptyList() },
            )

        /** Pages read back looking for a watched prompt's run (40 messages each). */
        const val backgroundSearchPages = 5

        /**
         * Background subagents a prompt's run started (from the prompt to the
         * next prompt after the run's text) whose results the read [reply] didn't
         * cover: still running, or finished without the session writing text of
         * that reply after its completion landed.
         */
        fun pendingBackgroundWork(
            messages: List<OpenCodeRunMessage>,
            parentSessionID: String,
            afterPromptID: String,
            reply: String?,
        ): List<VoiceBackgroundWork> {
            val anchor = messages.indexOfLast { it.id == afterPromptID }
            if (anchor < 0) return emptyList()
            val launched = mutableListOf<VoiceBackgroundWork>()
            val finished = mutableSetOf<String>()
            val covered = mutableSetOf<String>()
            var sawText = false
            for (message in messages.subList(anchor + 1, messages.size)) {
                if (message.type == "user") {
                    if (sawText) break
                    continue
                }
                message.completedChildID?.let { finished += it }
                for (launch in message.launches) {
                    if (launched.any { it.sessionID == launch.sessionID }) continue
                    val description = launch.description?.trim()?.takeIf { it.isNotEmpty() }
                    launched += VoiceBackgroundWork(launch.sessionID, parentSessionID, description ?: "background work")
                }
                if (message.type != "assistant" || message.text.isEmpty()) continue
                sawText = true
                if (isRead(message.text, reply)) covered += finished
            }
            return launched.filter { it.sessionID !in covered }
        }

        /** Text the read reply carries: its opening, since a long reply is cut. */
        fun isRead(text: String, reply: String?): Boolean {
            if (reply == null) return false
            return reply.contains(text.take(200))
        }

        sealed interface SubagentRelayOutcome {
            data object Pending : SubagentRelayOutcome
            data object CoveredOrSilent : SubagentRelayOutcome
            data class Replied(val reply: String) : SubagentRelayOutcome
        }

        /**
         * What a session-delegator parent session said about a completed background
         * subagent (`<subagent>` synthetic): [SubagentRelayOutcome.Pending] until `<subagent>` arrives
         * and the parent's run after it settles (or is covered), [SubagentRelayOutcome.CoveredOrSilent]
         * when a user turn or combined subagent reply covers it or it settled silent.
         */
        fun subagentRelayOutcome(messages: List<OpenCodeRunMessage>, childSessionID: String): SubagentRelayOutcome {
            val anchor = messages.indexOfLast { it.completedChildID == childSessionID }
            if (anchor < 0) return SubagentRelayOutcome.Pending
            val runStart = messages.subList(0, anchor).indexOfLast { it.type == "idle" }.let { if (it < 0) 0 else it + 1 }
            val before = messages.subList(runStart, anchor)
            val launchedHere = before.any { message -> message.launches.any { it.sessionID == childSessionID } }
            if (!launchedHere && before.any { it.type == "user" }) return SubagentRelayOutcome.CoveredOrSilent
            var reply: String? = null
            var settled = false
            for (message in messages.subList(anchor + 1, messages.size)) {
                if (message.type == "idle") {
                    settled = true
                    break
                }
                if (message.type == "user" || message.completedChildID != null) {
                    return reply?.let(SubagentRelayOutcome::Replied) ?: SubagentRelayOutcome.CoveredOrSilent
                }
                if (message.type != "assistant" || message.text.isEmpty()) continue
                reply = bounded(message.text, readMore = message.id)
            }
            if (!settled) return SubagentRelayOutcome.Pending
            return reply?.let(SubagentRelayOutcome::Replied) ?: SubagentRelayOutcome.CoveredOrSilent
        }

        fun relayedSubagentReply(messages: List<OpenCodeRunMessage>, childSessionID: String): String? =
            (subagentRelayOutcome(messages, childSessionID) as? SubagentRelayOutcome.Replied)?.reply

        /** How much of a session's final reply the delegator reads. */
        const val replyReadLimit = 6_000

        /** How much of one message a `read_session` by `message_id` returns. */
        const val messageReadLimit = 12_000
        const val excerptLimit = 400

        /**
         * Recent messages, each excerpted. With [fullLatestReply] (the delegator's
         * read), the newest reply comes whole: every text part of it, newest first,
         * sharing [replyReadLimit]; and anything cut says how much is left and the
         * `message_id` that reads it all.
         */
        fun formatRecentMessages(items: List<OpenCodeTranscriptItem>, fullLatestReply: Boolean = false): List<String> {
            val budgets = if (fullLatestReply) latestReplyBudgets(items) else emptyMap()
            return items.mapIndexedNotNull { index, item ->
                when (item) {
                    is OpenCodeTranscriptItem.UserText -> {
                        val content = VoiceHandoff.parse(item.text)?.instruction ?: item.text
                        "user: " + excerpt(content, readMore = if (fullLatestReply) item.id else null)
                    }
                    is OpenCodeTranscriptItem.AssistantMarkdown -> {
                        val text = item.text
                        if (text.isEmpty()) return@mapIndexedNotNull null
                        val budget = budgets[index]
                        if (budget != null) {
                            "assistant: " + bounded(text, limit = budget, readMore = item.id)
                        } else {
                            "assistant: " + excerpt(text, readMore = if (fullLatestReply) item.id else null)
                        }
                    }
                    else -> null
                }
            }
        }

        /**
         * The newest reply's text parts (back from the last one to the prompt
         * before it), each with its share of [replyReadLimit], newest first.
         */
        private fun latestReplyBudgets(items: List<OpenCodeTranscriptItem>): Map<Int, Int> {
            val last = items.indexOfLast { replyText(it) != null }
            if (last < 0) return emptyMap()
            val indices = mutableListOf<Int>()
            for (index in last downTo 0) {
                if (isPrompt(items[index])) return budgets(indices, items)
                if (replyText(items[index]) != null) indices += index
            }
            return budgets(indices, items)
        }

        /**
         * [replyReadLimit] shared by these text parts, newest first; none gets
         * less than an excerpt.
         */
        private fun budgets(indices: List<Int>, items: List<OpenCodeTranscriptItem>): Map<Int, Int> {
            val budgets = LinkedHashMap<Int, Int>()
            var left = replyReadLimit
            for (index in indices.sortedDescending()) {
                val budget = maxOf(left, excerptLimit)
                budgets[index] = budget
                left = maxOf(0, left - minOf(replyText(items[index])?.length ?: 0, budget))
            }
            return budgets
        }

        private fun promptItemID(item: OpenCodeTranscriptItem): String? = when (item) {
            is OpenCodeTranscriptItem.UserText -> item.id
            is OpenCodeTranscriptItem.UserImages -> item.id
            is OpenCodeTranscriptItem.UserFiles -> item.id
            else -> null
        }

        private fun isPrompt(item: OpenCodeTranscriptItem): Boolean = promptItemID(item) != null

        private fun matchesPrompt(item: OpenCodeTranscriptItem, id: String): Boolean = when (item) {
            is OpenCodeTranscriptItem.UserText -> item.id == id
            is OpenCodeTranscriptItem.UserImages -> item.id.startsWith("$id-")
            is OpenCodeTranscriptItem.UserFiles -> item.id.startsWith("$id-")
            else -> false
        }

        private fun replyText(item: OpenCodeTranscriptItem): String? {
            if (item !is OpenCodeTranscriptItem.AssistantMarkdown) return null
            val text = item.text.trim()
            return text.ifEmpty { null }
        }

        /**
         * A reply for the delegator, every text part of it joined (a tool call
         * can split the answer from a closing line): the run after
         * [afterPromptID] up to the next prompt once it has text, or with null the
         * newest reply. Parts share [replyReadLimit], and a cut one names its ID.
         */
        fun fullReply(items: List<OpenCodeTranscriptItem>, afterPromptID: String?): String? {
            val budgets: Map<Int, Int>
            if (afterPromptID != null) {
                val anchor = items.indexOfLast { matchesPrompt(it, afterPromptID) }
                if (anchor < 0) return null
                val indices = mutableListOf<Int>()
                for (index in items.indices) {
                    if (index <= anchor) continue
                    if (isPrompt(items[index]) && indices.isNotEmpty()) break
                    if (replyText(items[index]) != null) indices += index
                }
                budgets = budgets(indices, items)
            } else {
                // A prompt after the last text has no reply yet, not that one.
                val last = items.indexOfLast { replyText(it) != null }.coerceAtLeast(0)
                if (items.drop(last).any(::isPrompt)) return null
                budgets = latestReplyBudgets(items)
            }
            if (budgets.isEmpty()) return null
            return budgets.keys.sorted().mapNotNull { index ->
                replyText(items[index])?.let { bounded(it, limit = budgets.getValue(index), readMore = items[index].id) }
            }.joinToString("\n\n")
        }

        /**
         * One message by the ID a read gave: an assistant text part
         * (`msg_…-text-0`), a user message, or a whole assistant message (its
         * text parts joined). Null when the transcript doesn't have it.
         */
        fun messageText(items: List<OpenCodeTranscriptItem>, id: String): String? {
            val parts = mutableListOf<String>()
            for (item in items) {
                when {
                    item is OpenCodeTranscriptItem.UserText && item.id == id ->
                        return VoiceHandoff.parse(item.text)?.instruction ?: item.text
                    item is OpenCodeTranscriptItem.AssistantMarkdown && (item.id == id || item.id.startsWith("$id-text-")) -> {
                        val text = item.text
                        if (item.id == id) return text
                        if (text.isNotEmpty()) parts += text
                    }
                }
            }
            return if (parts.isEmpty()) null else parts.joinToString("\n\n")
        }

        /**
         * A page of a message from [offset], at most [messageReadLimit], saying
         * where to continue when there's more.
         */
        fun messagePage(text: String, id: String, offset: Int): String {
            if (offset >= text.length) return ""
            val rest = text.drop(maxOf(0, offset))
            val (kept, left) = cut(rest, messageReadLimit)
            if (left <= 0) return kept
            return kept + "… [truncated: $left more characters; call read_session with message_id \"$id\" and offset ${offset + kept.length} to read on]"
        }

        /**
         * Assistant text after [afterPromptID] (or after the newest user turn when
         * [afterPromptID] is null), never an earlier turn's assistant reply.
         */
        fun replyAfterPrompt(items: List<OpenCodeTranscriptItem>, afterPromptID: String?): String? {
            val startIndex: Int
            if (afterPromptID != null) {
                val anchor = items.indexOfLast { matchesPrompt(it, afterPromptID) }
                if (anchor < 0) return null
                startIndex = anchor + 1
            } else {
                val lastUser = items.indexOfLast(::isPrompt)
                startIndex = if (lastUser >= 0) lastUser + 1 else 0
            }
            if (startIndex >= items.size) return null
            var reply: String? = null
            for (item in items.subList(startIndex, items.size)) {
                when (item) {
                    // Prompts steered in before any text joined this run: its reply is
                    // the run's, after them. Once there is text, the next prompt ends it.
                    is OpenCodeTranscriptItem.UserText -> if (reply != null) return reply
                    is OpenCodeTranscriptItem.AssistantMarkdown -> replyText(item)?.let { reply = bounded(it, readMore = item.id) }
                    else -> Unit
                }
            }
            return reply
        }

        /**
         * For a read of an idle session with no prompt of its own: the reply to
         * its newest prompt, and which of the [tracked] prompts it answers. When
         * the newest prompts got no text, there is no reply and only they are
         * answered (they finished without one); otherwise the prompts since the
         * reply before it, steered in ahead of its text. Earlier prompts had
         * replies of their own and still speak.
         */
        fun newestReply(items: List<OpenCodeTranscriptItem>, tracked: List<String>): NewestReply {
            fun prompt(id: String): String? = tracked.firstOrNull { id == it || id.startsWith("$it-") }
            val last = items.indexOfLast { replyText(it) != null }
            val after = items.drop(if (last >= 0) last + 1 else 0)
            // Sent after the last reply, or the session never replied: they finished without text.
            if (last < 0 || after.any(::isPrompt)) {
                return NewestReply(null, after.mapNotNullTo(LinkedHashSet()) { item -> promptItemID(item)?.let(::prompt) })
            }
            val reply = replyText(items[last]) ?: return NewestReply(null, emptySet())
            val ids = LinkedHashSet<String>()
            // Back from the reply to the one before it, past that reply's own
            // earlier text parts (no prompt between them).
            var sawPrompt = false
            for (item in items.subList(0, last).asReversed()) {
                val id = promptItemID(item)
                if (id != null) {
                    sawPrompt = true
                    prompt(id)?.let { ids += it }
                } else if (sawPrompt && replyText(item) != null) {
                    break
                }
            }
            return NewestReply(bounded(reply, readMore = items[last].id), ids)
        }

        /**
         * The text whole when it fits; otherwise cut at a line or word break with
         * a marker saying how much was left out, never silently mid-item. With
         * [readMore], the marker names the message ID that reads the rest.
         */
        fun bounded(text: String, limit: Int = replyReadLimit, readMore: String? = null): String {
            val (kept, left) = cut(text, limit)
            if (left <= 0) return text
            return kept + truncationMarker(left, readMore)
        }

        /**
         * At most [limit] characters, ending at a line or word break near it,
         * and how many characters were left out.
         */
        private fun cut(text: String, limit: Int): Pair<String, Int> {
            if (text.length <= limit) return text to 0
            var cut = text.take(limit)
            // Snap back to a break only near the end, never to the very start.
            val window = minOf(400, limit / 4)
            val near = { index: Int -> index > 0 && cut.length - index <= window }
            val line = cut.indexOfLast(::isNewline)
            val word = cut.lastIndexOf(' ')
            if (line >= 0 && near(line)) {
                cut = cut.substring(0, line)
            } else if (word >= 0 && near(word)) {
                cut = cut.substring(0, word)
            }
            return cut to (text.length - cut.length)
        }

        private fun truncationMarker(left: Int, readMore: String?): String {
            if (readMore == null) return "… (truncated, $left more characters)"
            return "… [truncated: $left more characters; call read_session with message_id \"$readMore\" to read all]"
        }

        /**
         * One line of at most [limit] characters. With [readMore], a cut one says
         * how much is left and the message ID that reads it all, so a reader never
         * mistakes it for a reply that was itself cut off.
         */
        fun excerpt(text: String, limit: Int = excerptLimit, readMore: String? = null): String {
            // Empty pieces are dropped, so blank lines collapse too.
            val collapsed = text.split(*newlineSeparators).filter { it.isNotEmpty() }.joinToString(" ")
            if (collapsed.length <= limit) return collapsed
            if (readMore == null) return collapsed.take(limit) + "…"
            return collapsed.take(limit) + truncationMarker(collapsed.length - limit, readMore)
        }

        private fun isNewline(char: Char): Boolean =
            char == '\n' || char == '\r' || char == '\u000B' || char == '\u000C' ||
                char == '\u0085' || char == '\u2028' || char == '\u2029'

        private val newlineSeparators = arrayOf("\r\n", "\n", "\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")
    }
}
