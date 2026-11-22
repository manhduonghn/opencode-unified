package ai.opencode.feature.system.clienttools

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.ClientToolsEvent
import ai.opencode.server.clienttools.ClientToolsTarget
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.retry.NetworkPathClient
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The phone's tools for a call (links, audio, and Share Screen with Agents while its experiment is
 * on): keeps one client MCP socket to a server and directory, so agents there get the tools offered
 * (`android_voice_request_screen`, `android_voice_open_url`, `android_voice_play_audio`,
 * `android_voice_audio_control`). Reconnects with backoff, except to a server without the route;
 * `AppFeature` runs one per [State.Slot], sets each target and hands each call to the call on screen.
 */
class ClientToolsFeature : Reducer<ClientToolsFeature.State, ClientToolsFeature.Action> {
    data class State(
        /** The tools the phone offers (the screen only while its experiment is on). */
        val tools: Set<ClientTool> = emptySet(),
        val target: ClientToolsTarget? = null,
        val status: Status = Status.Idle,
        /** Closes since the server last finished a handshake: the backoff step. */
        val failures: Int = 0,
        /**
         * A request reaching the server already woke one wait since the socket was last attached.
         * The server answering REST says nothing about the client MCP route, so it can't keep
         * cutting the backoff short.
         */
        val answerWakeUsed: Boolean = false,
        /** The socket calls are arriving on; its going clears what they showed. */
        val connection: UUID? = null,
        val slot: Slot = Slot.Session,
        /**
         * A server that answered the handshake with no such route: no socket to it again until the
         * target's server changes or the app restarts.
         */
        val unavailableServer: OpenCodeServer? = null,
    ) {
        val isEnabled: Boolean get() = tools.isNotEmpty()

        enum class Slot(val rawValue: String) {
            /** The call's directory (`AppFeature.clientToolsTarget`). */
            Session("session"),

            /** The directory of the session a call's delegator steers, when it's another (`AppFeature.delegatorClientToolsTarget`). */
            Delegator("delegator"),
        }

        enum class Status {
            Idle,
            Connecting,
            Attached,

            /** Closed; trying again after the backoff. */
            Waiting,

            /** The server has no client MCP route ([State.unavailableServer]). */
            Unavailable,
        }
    }

    sealed interface Action {
        data class ToolsChanged(val tools: Set<ClientTool>) : Action
        data class TargetChanged(val target: ClientToolsTarget?) : Action
        data class Socket(val event: ClientToolsEvent) : Action
        data object RetryElapsed : Action

        /** The network path changed: a dial in progress or a wait to redial goes again at once, with the backoff started over. */
        data object Rerouted : Action

        /** A request reached the server while waiting to redial: try once now ([State.answerWakeUsed]), keeping the backoff where it was. */
        data object ServerAnswered : Action

        /** Answers a call nobody on the phone can take. */
        data class Refuse(val call: ClientToolCall, val message: String) : Action
        data class Delegate(val delegate: DelegateAction) : Action
    }

    sealed interface DelegateAction {
        /** `AppFeature` hands it to the call, or sends [Action.Refuse]. */
        data class ToolCalled(val call: ClientToolCall) : DelegateAction

        /** The socket closed or was torn down: its unanswered calls are refused by the client, and anything showing them goes. */
        data class Disconnected(val connection: UUID) : DelegateAction
    }

    sealed interface CancelID {
        data class Socket(val slot: State.Slot) : CancelID
        data class Reroutes(val slot: State.Slot) : CancelID
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            is Action.ToolsChanged -> return Reduced(state.copy(tools = action.tools))

            is Action.TargetChanged -> {
                if (action.target == state.target) return Reduced(state)
                val changed = state.copy(target = action.target, failures = 0, answerWakeUsed = false)
                val dropped = dropConnection(changed)
                val target = action.target
                val stop: Effect<Action> = Effect.merge(
                    Effect.cancel(CancelID.Socket(state.slot)),
                    Effect.cancel(CancelID.Reroutes(state.slot)),
                )
                if (target == null) {
                    AppLog.network.info { "client tools (${state.slot.rawValue}): disconnected" }
                    return Reduced(
                        dropped.state.copy(status = State.Status.Idle),
                        Effect.merge(dropped.effect, stop),
                    )
                }
                val unavailable = state.unavailableServer
                if (unavailable != null && ClientToolsTarget.sameConnection(unavailable, target.server)) {
                    return Reduced(
                        dropped.state.copy(status = State.Status.Unavailable),
                        Effect.merge(dropped.effect, stop),
                    )
                }
                val connected = connect(dropped.state.copy(unavailableServer = null))
                val networkPath = dependency(NetworkPathClient)
                val reroutes = Effect.run<Action> { send ->
                    networkPath.reroutes().collect { send(Action.Rerouted) }
                }.cancellable(CancelID.Reroutes(state.slot), cancelInFlight = true)
                return Reduced(connected.state, Effect.merge(dropped.effect, connected.effect, reroutes))
            }

            is Action.Socket -> return socket(state, action.event)

            Action.RetryElapsed -> {
                if (state.target == null || state.status == State.Status.Unavailable) return Reduced(state)
                return connect(state)
            }

            Action.Rerouted -> {
                if (state.target == null) return Reduced(state)
                if (state.status != State.Status.Connecting && state.status != State.Status.Waiting) return Reduced(state)
                AppLog.network.info { "client tools (${state.slot.rawValue}): route changed, dialling again" }
                return connect(state.copy(failures = 0, answerWakeUsed = false))
            }

            Action.ServerAnswered -> {
                if (state.target == null || state.status != State.Status.Waiting) return Reduced(state)
                return connect(state.copy(answerWakeUsed = true))
            }

            is Action.Refuse -> {
                AppLog.network.info { "client tools: ${action.call.tool.rawValue} refused" }
                val clientTools = dependency(ClientToolsClient)
                val call = action.call
                val message = action.message
                return Reduced(state, Effect.run { clientTools.reply(call, ClientToolResult.failure(message)) })
            }

            is Action.Delegate -> return Reduced(state)
        }
    }

    private fun socket(state: State, event: ClientToolsEvent): Reduced<State, Action> {
        when (event) {
            is ClientToolsEvent.Attached -> {
                AppLog.network.info { "client tools (${state.slot.rawValue}): attached" }
                return Reduced(
                    state.copy(status = State.Status.Attached, failures = 0, answerWakeUsed = false, connection = event.connection),
                )
            }

            is ClientToolsEvent.Call -> {
                AppLog.network.info { "client tools: ${event.call.tool.rawValue} called" }
                return Reduced(
                    state.copy(connection = event.call.connection),
                    Effect.send(Action.Delegate(DelegateAction.ToolCalled(event.call))),
                )
            }

            is ClientToolsEvent.Closed -> {
                val connection = if (state.connection == event.connection) null else state.connection
                val disconnected: Effect<Action> = Effect.send(Action.Delegate(DelegateAction.Disconnected(event.connection)))
                if (state.target == null) return Reduced(state.copy(connection = connection), disconnected)
                val delay = backoff(state.failures)
                val listensForAnswers = !state.answerWakeUsed
                AppLog.network.notice {
                    val sooner = if (listensForAnswers) "sooner if the route changes or the server answers" else "sooner if the route changes"
                    "client tools (${state.slot.rawValue}): closed (${event.reason}), retry in ${delay.inWholeMilliseconds} ms, $sooner"
                }
                val clock = dependency(Clock)
                val networkPath = dependency(NetworkPathClient)
                val server = state.target.server.persistenceID
                val retry = Effect.run<Action> { send ->
                    // Whichever comes first: the backoff, or (once per stretch) a request reaching the server.
                    val answered = coroutineScope {
                        val waits = mutableListOf<Deferred<Boolean>>()
                        waits.add(async { clock.sleep(delay); false })
                        if (listensForAnswers) {
                            waits.add(async { networkPath.serverAnswered(server); true })
                        }
                        val first = select { for (wait in waits) wait.onAwait { it } }
                        for (wait in waits) wait.cancel()
                        first
                    }
                    send(if (answered) Action.ServerAnswered else Action.RetryElapsed)
                }.cancellable(CancelID.Socket(state.slot), cancelInFlight = true)
                return Reduced(
                    state.copy(connection = connection, failures = state.failures + 1, status = State.Status.Waiting),
                    Effect.merge(disconnected, retry),
                )
            }

            is ClientToolsEvent.Unavailable -> {
                val connection = if (state.connection == event.connection) null else state.connection
                val disconnected: Effect<Action> = Effect.send(Action.Delegate(DelegateAction.Disconnected(event.connection)))
                val target = state.target ?: return Reduced(state.copy(connection = connection), disconnected)
                AppLog.network.notice {
                    "client tools (${state.slot.rawValue}): the server has no client MCP route (HTTP ${event.status}), not retrying"
                }
                return Reduced(
                    state.copy(connection = connection, status = State.Status.Unavailable, unavailableServer = target.server),
                    disconnected,
                )
            }
        }
    }

    /** Tearing the socket down: whatever its calls showed goes now, before the client refuses them as the socket closes. */
    private fun dropConnection(state: State): Reduced<State, Action> {
        val connection = state.connection ?: return Reduced(state)
        return Reduced(state.copy(connection = null), Effect.send(Action.Delegate(DelegateAction.Disconnected(connection))))
    }

    private fun connect(state: State): Reduced<State, Action> {
        val target = state.target ?: return Reduced(state)
        val clientTools = dependency(ClientToolsClient)
        val effect = Effect.run<Action> { send ->
            clientTools.connect(target).collect { event -> send(Action.Socket(event)) }
        }.cancellable(CancelID.Socket(state.slot), cancelInFlight = true)
        return Reduced(state.copy(status = State.Status.Connecting), effect)
    }

    companion object {
        /**
         * 0.5 s doubling to a minute. A call can last a long time, in the background too, so a
         * server that stays away isn't redialled every few seconds; a reroute redials at once instead.
         */
        fun backoff(failures: Int): Duration {
            val step = minOf(failures, 7)
            val millis = minOf(500L * (1L shl step), 60_000L)
            return millis.milliseconds
        }
    }
}
