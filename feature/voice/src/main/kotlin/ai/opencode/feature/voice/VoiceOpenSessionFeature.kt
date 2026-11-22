package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * One owner and one terminal outcome per server tool call (`android_voice_open_session`), across
 * MCP and SSE. App owns this reducer so moving or replacing the session cannot cancel it. It reads
 * only plain values ([Environment]) that App derives from Home, so it lives here, below Home and
 * Session.
 */
class VoiceOpenSessionFeature : Reducer<VoiceOpenSessionFeature.State, VoiceOpenSessionFeature.Action> {
    enum class Owner { mcp, sse }
    enum class Stage { waitingForMCP, loading, navigating }

    data class Record(
        val owner: Owner,
        val outcome: String? = null,
    )

    data class Operation(
        val id: String,
        val targetID: String,
        val generation: Int,
        val stage: Stage,
        val waiters: List<ClientToolCall> = emptyList(),
        val authorized: Boolean = false,
        val committed: Boolean = false,
        val arrived: Boolean = false,
    )

    data class Screen(
        val sessionID: String? = null,
        val visible: Boolean = false,
        val live: Boolean = false,
    )

    /**
     * Navigation identity changes even when a live call preserves its view ID, and on push/pop/back
     * to an earlier screen. Visibility is separate: closing the drawer or a sheet to reveal the
     * destination is not a new navigation.
     */
    data class Navigation(
        val rootGeneration: Int,
        val stack: List<Int>,
        val sessionID: String?,
        val browserID: UUID?,
        val catchUp: Boolean,
        val settings: Boolean = false,
        val whatsNew: Boolean = false,
        val deletion: String? = null,
        val presentations: List<Presentation> = emptyList(),
    )

    data class Presentation(
        val picker: String?,
        val folder: Boolean,
        val shell: String?,
    )

    data class Environment(
        val screen: Screen = Screen(),
        val foreground: Boolean = true,
        val attached: Boolean = false,
        val navigation: Navigation? = null,
        val callID: UUID? = null,
    )

    data class State(
        val active: Operation? = null,
        val records: Map<String, Record> = emptyMap(),
        /** Responses retain both sockets until the transport has sent them. */
        val replies: List<ClientToolCall> = emptyList(),
        val environment: Environment = Environment(),
        val generation: Int = 0,
    ) {
        val inFlight: Boolean get() = active != null || replies.isNotEmpty()

        data class Observation(
            val active: Operation?,
            val environment: Environment,
            val generation: Int,
        )

        val observation: Observation get() = Observation(active, environment, generation)

        /** The environment as App sees it now; a new navigation or call moves the generation on. */
        fun observing(next: Environment): State {
            var generation = generation
            if (environment.navigation != next.navigation || environment.callID != next.callID) generation += 1
            return copy(environment = next, generation = generation)
        }

        companion object {
            fun key(call: ClientToolCall): String = call.toolCallID ?: "mcp:${call.connection}:${call.id}"
        }
    }

    sealed interface Action {
        data class Requested(val call: ClientToolCall, val server: OpenCodeServer) : Action
        data class Fallback(val toolID: String, val targetID: String, val server: OpenCodeServer) : Action
        data class Refused(val call: ClientToolCall, val status: String) : Action
        data class Loaded(val id: String, val result: Result<OpenCodeSession>) : Action
        data object EnvironmentChanged : Action
        data class TimedOut(val id: String) : Action
        data class ConnectionClosed(val connection: UUID) : Action
        data class Replied(val call: ClientToolCall) : Action

        /** App checks the generation again before authorizing Home's navigation. */
        data class Delegate(val id: String, val session: OpenCodeSession) : Action
    }

    enum class CancelID { lookup, timeout }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            is Action.Requested -> return requested(state, action.call, action.server)

            is Action.Fallback -> {
                val id = action.toolID
                // MCP reservations, refusals and completed calls all suppress SSE.
                if (state.records[id] != null) return Reduced(state)
                val owner = if (state.environment.attached) Owner.mcp else Owner.sse
                val refusal = refusal(state)
                if (refusal != null) return Reduced(state.copy(records = state.records + (id to Record(owner, refusal))))
                if (state.active != null) return Reduced(state.copy(records = state.records + (id to Record(owner, "busy"))))
                if (!action.targetID.startsWith("ses_")) {
                    return Reduced(state.copy(records = state.records + (id to Record(owner, "not_found"))))
                }
                // An attached MCP path owns the call even when SSE arrives first. It gets the same
                // bounded deadline; SSE never starts a second lookup.
                val stage = if (owner == Owner.mcp) Stage.waitingForMCP else Stage.loading
                val next = state.copy(
                    records = state.records + (id to Record(owner)),
                    active = Operation(id = id, targetID = action.targetID, generation = state.generation, stage = stage),
                )
                val lookup = if (owner == Owner.sse) lookup(id, action.targetID, action.server) else Effect.none()
                return Reduced(next, Effect.merge(deadline(id), lookup))
            }

            is Action.Refused -> return refuse(state, action.call, action.status)

            is Action.Loaded -> {
                val active = state.active
                if (active == null || active.id != action.id || active.stage != Stage.loading) return Reduced(state)
                val refusal = refusal(state)
                if (refusal != null) return finish(state, refusal)
                if (active.generation != state.generation) return finish(state, "cancelled")
                val session = action.result.getOrNull()
                if (session != null) {
                    if (session.id != active.targetID) return finish(state, "failed")
                    val next = state.copy(active = active.copy(stage = Stage.navigating))
                    return Reduced(next, Effect.send(Action.Delegate(action.id, session)))
                }
                val error = action.result.exceptionOrNull() as? OpenCodeClientError
                AppLog.voice.info { "Open session lookup failed status=${error?.logSummary ?: "unknown"}" }
                return finish(state, if (error?.statusCode == 404) "not_found" else "failed")
            }

            Action.EnvironmentChanged -> return reconcile(state)

            is Action.TimedOut -> {
                if (state.active?.id != action.id) return Reduced(state)
                return finish(state, "timed_out")
            }

            is Action.ConnectionClosed -> {
                val waiters = state.active?.waiters ?: return Reduced(state)
                if (waiters.none { it.connection == action.connection }) return Reduced(state)
                return finish(state, "cancelled")
            }

            is Action.Replied -> return Reduced(state.copy(replies = state.replies.filter { it != action.call }))

            // App checks the generation again before authorizing Home's navigation.
            is Action.Delegate -> return Reduced(state)
        }
    }

    private fun requested(state: State, call: ClientToolCall, server: OpenCodeServer): Reduced<State, Action> {
        val id = State.key(call)
        val outcome = state.records[id]?.outcome
        if (outcome != null) return reply(state, call, outcome)
        val refusal = refusal(state)
        if (refusal != null) return refuse(state, call, refusal)
        val current = state.active
        if (current != null && current.id == id) {
            var active = current
            if (call !in active.waiters) active = active.copy(waiters = active.waiters + call)
            if (active.targetID != call.targetSessionID) return finish(state.copy(active = active), "failed")
            val needsLookup = active.stage == Stage.waitingForMCP
            if (needsLookup) active = active.copy(stage = Stage.loading)
            val next = state.copy(active = active)
            if (active.generation != next.generation) return finish(next, "cancelled")
            return Reduced(next, if (needsLookup) lookup(id, active.targetID, server) else Effect.none())
        }
        if (current != null) return refuse(state, call, "busy")
        val target = call.targetSessionID
        if (target == null || !target.startsWith("ses_")) return refuse(state, call, "not_found")
        val next = state.copy(
            records = state.records + (id to Record(Owner.mcp)),
            active = Operation(id = id, targetID = target, generation = state.generation, stage = Stage.loading, waiters = listOf(call)),
        )
        return Reduced(next, Effect.merge(lookup(id, target, server), deadline(id)))
    }

    private fun refusal(state: State): String? {
        if (!state.environment.screen.live) return "call_ended"
        if (!state.environment.foreground) return "background"
        return null
    }

    private fun reconcile(state: State): Reduced<State, Action> {
        var active = state.active ?: return Reduced(state)
        val refusal = refusal(state)
        if (refusal != null) return finish(state, refusal)
        val onTarget = state.environment.screen.sessionID == active.targetID
        if (active.generation != state.generation) {
            if (!active.committed || active.arrived || !onTarget) return finish(state, "cancelled")
            active = active.copy(generation = state.generation)
        }
        if (active.committed && onTarget) active = active.copy(arrived = true)
        val next = state.copy(active = active)
        if (!active.committed || !onTarget || !state.environment.screen.visible) return Reduced(next)
        return finish(next, "opened")
    }

    private fun refuse(state: State, call: ClientToolCall, status: String): Reduced<State, Action> {
        val id = State.key(call)
        val outcome = state.records[id]?.outcome
        if (outcome != null) return reply(state, call, outcome)
        val active = state.active
        if (active != null && active.id == id) {
            val joined = if (call in active.waiters) active else active.copy(waiters = active.waiters + call)
            return finish(state.copy(active = joined), status)
        }
        val owner = state.records[id]?.owner ?: Owner.mcp
        return reply(state.copy(records = state.records + (id to Record(owner, status))), call, status)
    }

    private fun finish(state: State, status: String): Reduced<State, Action> {
        val active = state.active ?: return Reduced(state)
        val records = state.records.toMutableMap()
        val record = records[active.id]
        if (record != null) records[active.id] = record.copy(outcome = status)
        var s = state.copy(records = records, active = null)
        AppLog.voice.info { "Open session result status=$status" }
        val effects = mutableListOf<Effect<Action>>(Effect.cancel(CancelID.lookup), Effect.cancel(CancelID.timeout))
        for (waiter in active.waiters) {
            val replied = reply(s, waiter, status)
            s = replied.state
            effects.add(replied.effect)
        }
        return Reduced(s, Effect.merge(effects))
    }

    private fun lookup(id: String, targetID: String, server: OpenCodeServer): Effect<Action> {
        val client = dependency(OpenCodeClient)
        return Effect.run<Action> { send ->
            val result = try {
                Result.success(client.getSession(server, targetID))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!currentCoroutineContext().isActive) return@run
            send(Action.Loaded(id, result))
        }.cancellable(CancelID.lookup)
    }

    private fun deadline(id: String): Effect<Action> {
        val clock = dependency(Clock)
        return Effect.run<Action> { send ->
            clock.sleep(TIMEOUT)
            send(Action.TimedOut(id))
        }.cancellable(CancelID.timeout)
    }

    private fun reply(state: State, call: ClientToolCall, status: String): Reduced<State, Action> {
        if (call in state.replies) return Reduced(state)
        val clientTools = dependency(ClientToolsClient)
        val opened = status == "opened"
        val result = ClientToolResult(listOf(ClientToolResult.Content.Text("{\"ok\":$opened,\"status\":\"$status\"}")), isError = !opened)
        val effect = Effect.run<Action> { send ->
            clientTools.reply(call, result)
            send(Action.Replied(call))
        }
        return Reduced(state.copy(replies = state.replies + call), effect)
    }

    companion object {
        /** The plugin waits eight seconds; the phone answers within six. */
        val TIMEOUT = 6.seconds
    }
}
