package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * An agent asking, during a call, to see the app's screen (`android_voice_request_screen`, Share
 * Screen with Agents). The person answers on a card above the composer: Share captures the app's
 * own window and returns it as the tool's image; Not Now, a minute's silence or the call ending
 * returns a refusal. Nothing is captured without a tap.
 */
class VoiceScreenRequestFeature : Reducer<VoiceScreenRequestFeature.State, VoiceScreenRequestFeature.Action> {
    data class State(
        /** The request on the card. */
        val pending: ClientToolCall? = null,
        /** Shared: the capture under way, card gone. */
        val capturing: ClientToolCall? = null,
    )

    sealed interface Action {
        data class Requested(val call: ClientToolCall) : Action
        data object ShareTapped : Action
        data object NotNowTapped : Action
        data object TimedOut : Action

        /** The call ended or failed with the card up. */
        data object CallEnded : Action

        /**
         * The socket the request came in on closed or was torn down: the card goes without a
         * reply, which the client already sent.
         */
        data class ConnectionClosed(val connection: UUID) : Action

        /** The capture's reply went out, or found its call gone. */
        data class Captured(val call: ClientToolCall, val result: VoiceScreenshotClient.Result) : Action
    }

    object Refusal {
        const val declined = "The user chose not to share their screen."
        const val timedOut = "The user didn’t answer the screen request within a minute."
        const val callEnded = "The call ended before the user answered the screen request."
        const val busy = "The user is already being asked to share their screen."
        const val otherRequest = "The user is already answering another request on the phone."
        const val noCall = "Screen sharing works only during a voice call in the OpenCode Android app."
        const val background = "The OpenCode Android app is in the background, so the user can’t see the screen request."
        const val covered = "The user has another screen open in the OpenCode Android app, so they can’t see the screen request."

        fun failed(failure: VoiceScreenshotClient.Failure): String = when (failure) {
            VoiceScreenshotClient.Failure.credentials ->
                "The user agreed, but a private screen (a password, pairing or Settings) is showing, so nothing was captured."
            VoiceScreenshotClient.Failure.background -> "The user agreed, but the app went to the background before the capture."
            VoiceScreenshotClient.Failure.noWindow,
            VoiceScreenshotClient.Failure.severalWindows,
            VoiceScreenshotClient.Failure.tooLarge,
            -> "The user agreed, but the capture failed. ${failure.message}"
        }
    }

    enum class CancelID { timeout, capture }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val clientTools = dependency(ClientToolsClient)
        val haptics = dependency(HapticsClient)
        val clock = dependency(Clock)
        return when (action) {
            is Action.Requested -> {
                if (state.pending != null || state.capturing != null) {
                    return Reduced(state, reply(clientTools, action.call, ClientToolResult.failure(Refusal.busy)))
                }
                AppLog.voice.info { "Screen request shown" }
                val timeout = Effect.run<Action> { send ->
                    clock.sleep(TIMEOUT)
                    send(Action.TimedOut)
                }.cancellable(CancelID.timeout, cancelInFlight = true)
                Reduced(state.copy(pending = action.call), timeout)
            }

            Action.ShareTapped -> {
                val call = state.pending ?: return Reduced(state)
                haptics.play(Haptic.confirm)
                AppLog.voice.info { "Screen request shared" }
                Reduced(
                    state.copy(pending = null, capturing = call),
                    Effect.merge(Effect.cancel(CancelID.timeout), capture(call)),
                )
            }

            Action.NotNowTapped -> {
                val call = state.pending ?: return Reduced(state)
                haptics.play(Haptic.cancel)
                AppLog.voice.info { "Screen request declined" }
                Reduced(
                    state.copy(pending = null),
                    Effect.merge(Effect.cancel(CancelID.timeout), reply(clientTools, call, ClientToolResult.failure(Refusal.declined))),
                )
            }

            Action.TimedOut -> {
                val call = state.pending ?: return Reduced(state)
                AppLog.voice.info { "Screen request timed out" }
                Reduced(state.copy(pending = null), reply(clientTools, call, ClientToolResult.failure(Refusal.timedOut)))
            }

            Action.CallEnded -> endCall(state)

            is Action.ConnectionClosed -> {
                var next = state
                val effects = mutableListOf<Effect<Action>>()
                if (state.pending?.connection == action.connection) {
                    next = next.copy(pending = null)
                    effects.add(Effect.cancel(CancelID.timeout))
                }
                if (state.capturing?.connection == action.connection) {
                    next = next.copy(capturing = null)
                    effects.add(Effect.cancel(CancelID.capture))
                }
                if (effects.isNotEmpty()) AppLog.voice.info { "Screen request dropped with its socket" }
                Reduced(next, Effect.merge(effects))
            }

            is Action.Captured -> {
                if (state.capturing != action.call) return Reduced(state)
                val next = state.copy(capturing = null)
                val result = action.result
                if (result is VoiceScreenshotClient.Result.Failed) {
                    AppLog.voice.notice { "Screen request capture refused: ${result.failure}" }
                }
                Reduced(next)
            }
        }
    }

    /**
     * The call is over: a request still on the card is refused now, in the same pass, so the
     * reply leaves before the socket does. A capture already shared finishes: the person said yes.
     */
    fun endCall(state: State): Reduced<State, Action> {
        val call = state.pending ?: return Reduced(state)
        val clientTools = dependency(ClientToolsClient)
        return Reduced(
            state.copy(pending = null),
            Effect.merge(Effect.cancel(CancelID.timeout), reply(clientTools, call, ClientToolResult.failure(Refusal.callEnded))),
        )
    }

    /**
     * The settle, the capture and its reply as one piece, so tearing the call down partway still
     * answers: before the capture with a refusal, after it with the picture (the client's reply
     * outlives a cancelled caller).
     */
    private fun capture(call: ClientToolCall): Effect<Action> {
        val clientTools = dependency(ClientToolsClient)
        val screenshot = dependency(VoiceScreenshotClient)
        val clock = dependency(Clock)
        return Effect.run<Action> { send ->
            try {
                clock.sleep(SETTLE)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { clientTools.reply(call, ClientToolResult.failure(Refusal.callEnded)) }
                throw cancelled
            }
            val result = screenshot.captureShared()
            val answer = when (result) {
                is VoiceScreenshotClient.Result.Taken -> ClientToolResult(
                    listOf(ClientToolResult.Content.Text(caption(result.capture)), ClientToolResult.Content.Jpeg(result.capture.jpeg)),
                )
                is VoiceScreenshotClient.Result.Failed -> ClientToolResult.failure(Refusal.failed(result.failure))
            }
            clientTools.reply(call, answer)
            send(Action.Captured(call, result))
        }.cancellable(CancelID.capture, cancelInFlight = true)
    }

    private fun reply(clientTools: ClientToolsClient, call: ClientToolCall, result: ClientToolResult): Effect<Action> =
        Effect.run { clientTools.reply(call, result) }

    companion object {
        /** How long the card waits for an answer. */
        val TIMEOUT: Duration = 60.seconds

        /** After Share, how long the card has to leave before the capture, so it isn't in the picture. */
        val SETTLE: Duration = 350.milliseconds

        /** The line beside the image: what it is, never what's in it. */
        fun caption(capture: VoiceScreenshotClient.Capture): String {
            val parts = mutableListOf("The user shared the OpenCode Android app's screen")
            if (capture.screen != null) parts.add("screen: ${capture.screen}")
            val style = capture.appearance?.style
            if (style != null) parts.add("$style mode")
            return parts.joinToString("; ") + "."
        }
    }
}
