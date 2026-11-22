package ai.opencode.feature.settings.serverdetails

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeMCPServer
import ai.opencode.server.model.OpenCodeOAuthAttempt
import ai.opencode.server.model.OpenCodeOAuthStatus
import ai.opencode.server.model.OpenCodeServerCapabilities
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings › Developer › Server Details: the server's MCP servers, provider sign-ins and skills as
 * three tiles that open into trays, with problems as banners on top. One read of each, for the
 * server's own folder. Effects are keyed by the server and cancelled by [Action.Disappeared] and by
 * SettingsFeature's `ifLet`, which keeps server-side sign-in cleanup alive.
 */
class ServerDetailsFeature : Reducer<ServerDetailsFeature.State, ServerDetailsFeature.Action> {
    /** What a tray shows: a tile's full list, or one MCP server. */
    sealed interface Tray {
        val id: String

        data object Mcp : Tray {
            override val id: String get() = "mcp"
        }

        data object Accounts : Tray {
            override val id: String get() = "accounts"
        }

        data object Skills : Tray {
            override val id: String get() = "skills"
        }

        data class Server(val name: String) : Tray {
            override val id: String get() = "server:$name"
        }
    }

    /** An integration sign-in in flight for an MCP server that needs one. */
    data class SignIn(val mcp: String, val integrationID: String, val attempt: OpenCodeOAuthAttempt)

    data class State(
        val server: OpenCodeServer,
        val capabilities: OpenCodeServerCapabilities? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        /**
         * MCP servers waiting on Connect, Retry, Disconnect or a sign-in: shown as connecting
         * until the server answers.
         */
        val changingMCP: Set<String> = emptySet(),
        val mcpError: String? = null,
        val tray: Tray? = null,
        /** The MCP server a sign-in runs for, from the tap until it settles. */
        val signingIn: String? = null,
        val signIn: SignIn? = null,
        /** The sign-in page shows a code to paste back (`code` mode). */
        val awaitingCode: Boolean = false,
    ) {
        /** The server as the person sees it, connecting while an action is in flight. */
        fun displayed(mcp: OpenCodeMCPServer): OpenCodeMCPServer =
            if (mcp.name in changingMCP) mcp.copy(status = OpenCodeMCPServer.Status.Pending) else mcp

        val mcpServers: List<OpenCodeMCPServer>
            get() = (capabilities?.mcpServers ?: emptyList()).map(::displayed)

        /** Servers that need the person, as banners: sign-in first, then failures. */
        val problems: List<OpenCodeMCPServer>
            get() {
                val all = mcpServers
                return all.filter { it.status is OpenCodeMCPServer.Status.NeedsAuth } +
                    all.filter { it.status is OpenCodeMCPServer.Status.Failed }
            }
    }

    sealed interface Action {
        data object Task : Action
        /**
         * The page left the screen (or its server changed): stop loading and any sign-in so
         * nothing polls in the background.
         */
        data object Disappeared : Action
        data object RefreshPulled : Action
        data class Loaded(val capabilities: OpenCodeServerCapabilities) : Action
        data class LoadFailed(val message: String) : Action
        data class TrayChanged(val tray: Tray?) : Action
        /** Connect an Off server, or Retry a failed one. */
        data class ConnectTapped(val name: String) : Action
        data class DisconnectTapped(val name: String) : Action
        data class SignInTapped(val name: String) : Action
        data class MCPChanged(val name: String) : Action
        data class MCPChangeFailed(val name: String, val message: String) : Action
        data class SignInStarted(val signIn: SignIn) : Action
        data class SignInFinished(val name: String, val status: OpenCodeOAuthStatus) : Action
        data class CodeSubmitted(val code: String) : Action
        data object CodeCancelled : Action

        companion object {
            fun primaryAction(mcp: OpenCodeMCPServer): Action? = when (mcp.status) {
                is OpenCodeMCPServer.Status.NeedsAuth -> SignInTapped(mcp.name)
                OpenCodeMCPServer.Status.Connected -> DisconnectTapped(mcp.name)
                OpenCodeMCPServer.Status.Pending -> null
                else -> ConnectTapped(mcp.name)
            }
        }
    }

    private enum class CancelKind { load, signIn }

    /** Keyed by the server, so a fresh page for another server never cancels or receives the old one's work. */
    private data class CancelID(val kind: CancelKind, val server: String)

    private fun loadID(state: State) = CancelID(CancelKind.load, state.server.persistenceID)
    private fun signInID(state: State) = CancelID(CancelKind.signIn, state.server.persistenceID)

    companion object {
        /** How long a sign-in waits for the page to finish (`auto` mode). */
        val signInPollInterval: Duration = 2.seconds
        const val signInPollLimit = 150
    }

    internal fun cancelSignIn(state: State): Effect<Action> {
        val signIn = state.signIn ?: return Effect.none()
        val server = state.server
        return Effect.run {
            try {
                dependency(OpenCodeClient).cancelIntegrationOAuth(server, signIn.integrationID, signIn.attempt.attemptID)
            } catch (_: OpenCodeClientError) {
            }
        }
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task ->
            if (state.capabilities != null) Reduced(state) else load(state)

        Action.Disappeared -> {
            var next = state.copy(isLoading = false, signIn = null, awaitingCode = false)
            state.signingIn?.let { name ->
                next = next.copy(changingMCP = next.changingMCP - name, signingIn = null)
            }
            Reduced(
                next,
                Effect.merge(
                    Effect.cancel(loadID(state)),
                    Effect.cancel(signInID(state)),
                    cancelSignIn(state),
                ),
            )
        }

        Action.RefreshPulled -> load(state)

        is Action.Loaded -> Reduced(
            state.copy(
                isLoading = false,
                error = null,
                capabilities = if (state.capabilities != action.capabilities) action.capabilities else state.capabilities,
            ),
        )

        is Action.LoadFailed -> Reduced(state.copy(isLoading = false, error = action.message))

        is Action.TrayChanged ->
            Reduced(if (state.tray != action.tray) state.copy(tray = action.tray) else state)

        is Action.ConnectTapped -> setConnected(state, action.name, true)

        is Action.DisconnectTapped -> setConnected(state, action.name, false)

        is Action.SignInTapped -> signInTapped(state, action.name)

        is Action.SignInStarted -> {
            val signIn = action.signIn
            val next = state.copy(signIn = signIn)
            val open: Effect<Action> = Effect.run { _ -> dependency(OpenURLClient).open(signIn.attempt.url) }
            if (signIn.attempt.mode != OpenCodeOAuthAttempt.Mode.Auto) {
                Reduced(next.copy(awaitingCode = true), open)
            } else {
                val server = state.server
                Reduced(
                    next,
                    Effect.merge(
                        open,
                        Effect.run<Action> { send ->
                            val status = NetworkRetry.policy.withValue(RetryPolicy.once) { poll(server, signIn) }
                            send(Action.SignInFinished(signIn.mcp, status))
                        }.cancellable(signInID(state), cancelInFlight = true),
                    ),
                )
            }
        }

        is Action.CodeSubmitted -> {
            val signIn = state.signIn
            if (signIn == null || !state.awaitingCode) {
                Reduced(state)
            } else {
                val server = state.server
                val trimmed = action.code.trim()
                Reduced(
                    state.copy(awaitingCode = false),
                    Effect.run<Action> { send ->
                        try {
                            dependency(OpenCodeClient).completeIntegrationOAuth(server, signIn.integrationID, signIn.attempt.attemptID, trimmed)
                            send(Action.SignInFinished(signIn.mcp, OpenCodeOAuthStatus.Complete))
                        } catch (error: OpenCodeClientError) {
                            send(Action.SignInFinished(signIn.mcp, OpenCodeOAuthStatus.Failed(error.message)))
                        }
                    }.cancellable(signInID(state), cancelInFlight = true),
                )
            }
        }

        Action.CodeCancelled -> {
            val signIn = state.signIn
            if (signIn == null) {
                Reduced(state)
            } else {
                val server = state.server
                Reduced(
                    state.copy(
                        awaitingCode = false,
                        signIn = null,
                        signingIn = null,
                        changingMCP = state.changingMCP - signIn.mcp,
                    ),
                    Effect.merge(
                        Effect.cancel(signInID(state)),
                        Effect.run { _ ->
                            try {
                                dependency(OpenCodeClient).cancelIntegrationOAuth(server, signIn.integrationID, signIn.attempt.attemptID)
                            } catch (_: OpenCodeClientError) {
                            }
                        },
                    ),
                )
            }
        }

        is Action.SignInFinished -> {
            val next = state.copy(signIn = null, signingIn = null, awaitingCode = false)
            if (action.status != OpenCodeOAuthStatus.Complete) {
                AppLog.network.notice("MCP sign-in ended without completing")
                Reduced(
                    next.copy(
                        changingMCP = next.changingMCP - action.name,
                        mcpError = "Couldn’t sign in to ${action.name}",
                    ),
                )
            } else {
                Reduced(next, changeConnection(state.server, action.name, true))
            }
        }

        is Action.MCPChanged -> load(state.copy(changingMCP = state.changingMCP - action.name))

        is Action.MCPChangeFailed -> {
            AppLog.network.notice("MCP connection change failed: ${action.message.length} chars")
            load(
                state.copy(
                    changingMCP = state.changingMCP - action.name,
                    mcpError = "Couldn’t change ${action.name}",
                ),
            )
        }
    }

    private fun signInTapped(state: State, name: String): Reduced<State, Action> {
        if (name in state.changingMCP || state.signingIn != null) return Reduced(state)
        // No integration to sign in with: connecting runs the server's own auth.
        val integrationID = state.capabilities?.mcpServers?.firstOrNull { it.name == name }?.integrationID
            ?: return setConnected(state, name, true)
        val server = state.server
        return Reduced(
            state.copy(changingMCP = state.changingMCP + name, signingIn = name, mcpError = null),
            Effect.merge(
                Effect.run { _ -> dependency(HapticsClient).play(Haptic.tap) },
                Effect.run<Action> { send ->
                    try {
                        val attempt = dependency(OpenCodeClient).startIntegrationOAuth(server, integrationID)
                        send(Action.SignInStarted(SignIn(name, integrationID, attempt)))
                    } catch (error: OpenCodeClientError) {
                        send(Action.SignInFinished(name, OpenCodeOAuthStatus.Failed(error.message)))
                    }
                }.cancellable(signInID(state), cancelInFlight = true),
            ),
        )
    }

    private fun setConnected(state: State, name: String, connected: Boolean): Reduced<State, Action> {
        if (name in state.changingMCP) return Reduced(state)
        return Reduced(
            state.copy(changingMCP = state.changingMCP + name, mcpError = null),
            Effect.merge(
                Effect.run { _ -> dependency(HapticsClient).play(Haptic.tap) },
                changeConnection(state.server, name, connected),
            ),
        )
    }

    private fun changeConnection(server: OpenCodeServer, name: String, connected: Boolean): Effect<Action> =
        Effect.run { send ->
            try {
                dependency(OpenCodeClient).setMCPConnected(server, name, connected)
                send(Action.MCPChanged(name))
            } catch (error: OpenCodeClientError) {
                send(Action.MCPChangeFailed(name, error.message))
            }
        }

    private suspend fun poll(server: OpenCodeServer, signIn: SignIn): OpenCodeOAuthStatus {
        val client = dependency(OpenCodeClient)
        val clock = dependency(Clock)
        repeat(signInPollLimit) {
            try {
                clock.sleep(signInPollInterval)
                val status = client.integrationOAuthStatus(server, signIn.integrationID, signIn.attempt.attemptID)
                if (status != OpenCodeOAuthStatus.Pending) return status
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep polling: one failed check doesn't end the sign-in.
            }
        }
        return OpenCodeOAuthStatus.Expired
    }

    private fun load(state: State): Reduced<State, Action> {
        val server = state.server
        return Reduced(
            state.copy(isLoading = true),
            Effect.run<Action> { send ->
                try {
                    send(Action.Loaded(dependency(OpenCodeClient).serverCapabilities(server)))
                } catch (error: OpenCodeClientError) {
                    send(Action.LoadFailed(error.message))
                }
            }.cancellable(loadID(state), cancelInFlight = true),
        )
    }
}

/**
 * Port of TCA's `@Dependency(\.openURL)` as Server Details uses it: opens a sign-in page in the
 * system browser. No other module offers a URL opener yet.
 */
class OpenURLClient(val open: suspend (URI) -> Unit) {
    companion object Key : DependencyKey<OpenURLClient> {
        override val liveValue: OpenURLClient by lazy {
            val context = dependency(AppContext)
            OpenURLClient { url ->
                withContext(Dispatchers.Main) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        AppLog.app.notice("No app opens the sign-in page")
                    }
                }
            }
        }
        override val testValue = OpenURLClient { throw AssertionError("Unimplemented: OpenURLClient.open") }
    }
}
