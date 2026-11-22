package ai.opencode.feature.voice

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
import java.net.URI
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * An agent plays music or a clip on the live call (`android_voice_play_audio`) and pauses, resumes
 * or stops it (`android_voice_audio_control`); the person does the same from the player row above
 * the composer. The audio itself, and its ducking, live in [CallAudioPlayer]; this holds what the
 * row shows and answers the tools.
 */
class VoiceCallAudioFeature : Reducer<VoiceCallAudioFeature.State, VoiceCallAudioFeature.Action> {
    data class State(
        val track: CallAudioTrack? = null,
        val isPaused: Boolean = false,
        /** Turned down while someone speaks. */
        val isDucked: Boolean = false,
        /** The play call whose audio is downloading. */
        val loading: ClientToolCall? = null,
    )

    sealed interface Action {
        data class PlayRequested(val call: ClientToolCall) : Action
        data class ControlRequested(val call: ClientToolCall) : Action

        /**
         * The play call's one reply went out ([replied]), or found its call already answered
         * (its socket closed), in which case the track stopped.
         */
        data class Started(val call: ClientToolCall, val result: CallAudioResult, val replied: Boolean) : Action
        data object PauseTapped : Action
        data object ResumeTapped : Action
        data object StopTapped : Action
        data class Player(val event: CallAudioEvent) : Action

        /** The call ended or failed: the audio stops with it. */
        data object CallEnded : Action
    }

    object Reply {
        const val badURL = "Give an https URL of an audio file."
        const val replaced = "A newer play request replaced this one."
        const val callEnded = "The call ended before the audio started."
        const val cancelled = "The play request was cancelled before the audio started."
        const val nothingPlaying = "Nothing is playing on the call."
        const val badAction = "Use pause, resume or stop."

        fun playing(track: CallAudioTrack): String {
            var line = "Playing “${track.title}” on the call"
            if (track.duration != null) line += " (${duration(track.duration)})"
            return "$line. It ducks while anyone speaks; use android_voice_audio_control to pause, resume or stop it."
        }

        fun duration(seconds: Double): String {
            val total = seconds.roundToInt()
            return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
        }
    }

    enum class CancelID { load, events }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        return when (action) {
            is Action.PlayRequested -> {
                val url = httpsURL(action.call.url) ?: return Reduced(state, reply(action.call, ClientToolResult.failure(Reply.badURL)))
                // Refused in this pass, before the newer load cancels its download.
                val effects = mutableListOf<Effect<Action>>()
                val loading = state.loading
                if (loading != null) effects.add(reply(loading, ClientToolResult.failure(Reply.replaced)))
                AppLog.voice.info { "Call audio requested" }
                // Listening from the request, so a short clip's end isn't missed.
                effects.add(listen())
                effects.add(load(action.call, url))
                Reduced(state.copy(loading = action.call), Effect.merge(effects))
            }

            is Action.Started -> {
                if (state.loading != action.call) return Reduced(state)
                val next = state.copy(loading = null)
                val result = action.result
                if (result is CallAudioResult.Started && action.replied) {
                    val playing = next.copy(track = result.track, isPaused = false, isDucked = false)
                    return Reduced(playing)
                }
                if (result is CallAudioResult.Started) {
                    // Its socket went first: the track stopped, and it had replaced what played.
                    AppLog.voice.info { "Call audio dropped with its socket" }
                    return Reduced(clear(next), Effect.cancel(CancelID.events))
                }
                val failure = (result as CallAudioResult.Failed).failure
                AppLog.voice.notice { "Call audio failed: ${failure::class.simpleName}" }
                // What was already playing carries on.
                if (next.track == null) Reduced(next, Effect.cancel(CancelID.events)) else Reduced(next)
            }

            is Action.ControlRequested -> {
                val call = action.call
                if (state.track == null) return Reduced(state, reply(call, ClientToolResult.failure(Reply.nothingPlaying)))
                when (call.action?.lowercase()) {
                    "pause" -> pause(state).also(reply(call, "Paused."))
                    "resume" -> resume(state).also(reply(call, "Resumed."))
                    "stop" -> stop(state).also(reply(call, "Stopped."))
                    else -> Reduced(state, reply(call, ClientToolResult.failure(Reply.badAction)))
                }
            }

            Action.PauseTapped -> {
                if (state.track == null || state.isPaused) return Reduced(state)
                haptics.play(Haptic.tap)
                pause(state)
            }

            Action.ResumeTapped -> {
                if (state.track == null || !state.isPaused) return Reduced(state)
                haptics.play(Haptic.tap)
                resume(state)
            }

            Action.StopTapped -> {
                if (state.track == null) return Reduced(state)
                haptics.play(Haptic.cancel)
                stop(state)
            }

            is Action.Player -> when (val event = action.event) {
                is CallAudioEvent.Ducked -> {
                    if (state.track == null) Reduced(state) else Reduced(state.copy(isDucked = event.isDucked))
                }
                is CallAudioEvent.Finished -> {
                    val track = state.track
                    if (track == null || track.id != event.id) return Reduced(state)
                    AppLog.voice.info { "Call audio finished" }
                    Reduced(clear(state), Effect.cancel(CancelID.events))
                }
            }

            Action.CallEnded -> endCall(state)
        }
    }

    /**
     * The call is over: a play still downloading is refused now, in the same pass, so the reply
     * leaves before the socket does; the audio stops.
     */
    fun endCall(state: State): Reduced<State, Action> {
        if (state.track == null && state.loading == null) return Reduced(state)
        val callAudio = dependency(CallAudioClient)
        val effects = mutableListOf<Effect<Action>>(Effect.cancel(CancelID.load), Effect.cancel(CancelID.events))
        val loading = state.loading
        if (loading != null) effects.add(reply(loading, ClientToolResult.failure(Reply.callEnded)))
        effects.add(Effect.run { callAudio.stop() })
        return Reduced(clear(state.copy(loading = null)), Effect.merge(effects))
    }

    /**
     * The download, the start and the reply as one piece, so the call gets exactly one reply
     * whatever tears it down. Cancelled (replaced, the call ended), whoever cancelled it already
     * replied and this one finds the call answered; it's the backstop if nobody did. A reply that
     * finds its call gone (the socket closed and refused it) stops the track, so nothing plays
     * that the agent was told didn't.
     */
    private fun load(call: ClientToolCall, url: URI): Effect<Action> {
        val callAudio = dependency(CallAudioClient)
        val clientTools = dependency(ClientToolsClient)
        return Effect.run<Action> { send ->
            val result = try {
                callAudio.play(url, call.title)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { clientTools.reply(call, ClientToolResult.failure(Reply.cancelled)) }
                throw cancelled
            }
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) {
                    if (result is CallAudioResult.Started) callAudio.stopTrack(result.track.id)
                    clientTools.reply(call, ClientToolResult.failure(Reply.cancelled))
                }
                return@run
            }
            val answer = when (result) {
                is CallAudioResult.Started -> ClientToolResult(listOf(ClientToolResult.Content.Text(Reply.playing(result.track))))
                is CallAudioResult.Failed -> ClientToolResult.failure(result.failure.message)
            }
            val replied = clientTools.reply(call, answer)
            if (!replied && result is CallAudioResult.Started) callAudio.stopTrack(result.track.id)
            send(Action.Started(call, result, replied))
        }.cancellable(CancelID.load, cancelInFlight = true)
    }

    private fun pause(state: State): Reduced<State, Action> {
        if (state.isPaused) return Reduced(state)
        val callAudio = dependency(CallAudioClient)
        return Reduced(state.copy(isPaused = true), Effect.run { callAudio.pause() })
    }

    private fun resume(state: State): Reduced<State, Action> {
        if (!state.isPaused) return Reduced(state)
        val callAudio = dependency(CallAudioClient)
        return Reduced(state.copy(isPaused = false), Effect.run { callAudio.resume() })
    }

    private fun stop(state: State): Reduced<State, Action> {
        if (state.track == null) return Reduced(state)
        AppLog.voice.info { "Call audio stopped" }
        val callAudio = dependency(CallAudioClient)
        return Reduced(
            clear(state),
            Effect.merge(
                Effect.cancel(CancelID.events),
                Effect.run { callAudio.stop() },
            ),
        )
    }

    private fun clear(state: State): State = state.copy(track = null, isPaused = false, isDucked = false)

    private fun listen(): Effect<Action> {
        val callAudio = dependency(CallAudioClient)
        return Effect.run<Action> { send ->
            callAudio.events().collect { event -> send(Action.Player(event)) }
        }.cancellable(CancelID.events, cancelInFlight = true)
    }

    private fun reply(call: ClientToolCall, result: ClientToolResult): Effect<Action> {
        val clientTools = dependency(ClientToolsClient)
        return Effect.run { clientTools.reply(call, result) }
    }

    private fun reply(call: ClientToolCall, text: String): Effect<Action> =
        reply(call, ClientToolResult(listOf(ClientToolResult.Content.Text(text))))

    /** [reduced] with [effect] merged after its own. */
    private fun Reduced<State, Action>.also(effect: Effect<Action>): Reduced<State, Action> =
        Reduced(state, Effect.merge(this.effect, effect))

    companion object {
        /** A URL with an https scheme and a host, else null. */
        fun httpsURL(raw: String?): URI? {
            if (raw == null) return null
            val url = try {
                URI(raw)
            } catch (_: Exception) {
                return null
            }
            if (url.scheme?.lowercase() != "https" || url.host.isNullOrEmpty()) return null
            return url
        }
    }
}
