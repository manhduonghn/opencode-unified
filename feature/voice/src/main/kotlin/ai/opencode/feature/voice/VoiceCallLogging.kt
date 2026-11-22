package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import java.util.UUID

/** After-reducer logging seam. VoiceFeature's later wave maps its AFTER-reduction
 * snapshot/actions to these entries, and clears callLogID when apply returns true.
 * These are private call-log contents, never system logs. */
class VoiceCallLogging(private val client: VoiceCallLogClient = dependency(VoiceCallLogClient)) {
    data class State(val callLogID: UUID?, val isOnDraft: Boolean, val sessionTitle: String,
        val engine: String, val voice: String, val isAtEar: Boolean, val isMuted: Boolean,
        val usesDelegator: Boolean, val usesSessionDelegator: Boolean = false, val liveAssistantItemID: String? = null, val liveAssistantText: String = "",
        val moveReason: String? = null, val isTerminal: Boolean = false)
    sealed interface Event {
        data object start : Event
        data class callRouteResolved(val note: String? = null) : Event
        data class userTranscript(val itemID: String, val text: String, val final: Boolean) : Event
        data class assistantDelta(val itemID: String?) : Event
        data class assistantDone(val itemID: String?, val text: String) : Event
        data object assistantPlaybackFinished : Event
        data class functionCall(val callID: String, val name: String, val text: String?) : Event
        data object serverDelegation : Event
        data class serviceError(val message: String) : Event
        data class connectionPoor(val poor: Boolean) : Event
        data class workReplied(val callID: String, val reply: String?) : Event
        data class workFailed(val callID: String, val message: String) : Event
        data class delegatorFinished(val reply: String, val interrupted: String? = null, val failure: String? = null) : Event
        data class sessionStarted(val title: String) : Event
        data object targetChanged : Event
        data object muteTapped : Event
        data class atEarChanged(val atEar: Boolean) : Event
        data object other : Event
    }
    fun apply(state: State, event: Event): Boolean {
        val recorder = client.recorder ?: return false; val log = state.callLogID ?: return false
        fun note(text: String) = recorder.note(log, VoiceCallLog.Note.Kind.marker, text)
        when (event) {
            Event.start -> {
                recorder.begin(log, if (state.isOnDraft) VoiceFeature.State.newSessionTitle else state.sessionTitle, state.engine)
                note("Voice: ${state.voice}"); if (state.isAtEar) note("Raised to ear")
            }
            is Event.callRouteResolved -> {
                recorder.setEngine(log, state.engine)
                event.note?.let { note(it) }
            }
            is Event.userTranscript -> if (event.final) recorder.span(log, VoiceCallLog.Track.user, "u-${event.itemID}", event.text, end = true)
            is Event.assistantDelta -> recorder.span(log, VoiceCallLog.Track.voice, "a-${event.itemID ?: state.liveAssistantItemID ?: "live"}", state.liveAssistantText)
            is Event.assistantDone -> if (event.itemID != null) recorder.span(log, VoiceCallLog.Track.voice, "a-${event.itemID}", event.text.ifEmpty { null }, end = true) else recorder.endVoice(log, event.text.ifEmpty { null })
            Event.assistantPlaybackFinished -> recorder.endVoice(log, null)
            is Event.functionCall -> {
                note(if (event.name == "delegate") "Delegated${event.text?.let { ": $it" } ?: ""}" else "Called ${event.name}")
                if (event.name == "delegate" && !state.usesDelegator && !state.usesSessionDelegator) recorder.span(log, VoiceCallLog.Track.delegator, "work-${event.callID}", "Session “${state.sessionTitle}” working")
            }
            Event.serverDelegation -> note("Delegated to the delegator on the voice service")
            is Event.serviceError -> note("Error: ${event.message}")
            is Event.connectionPoor -> note(if (event.poor) "Weak connection: audio is being lost, delayed, or backing up (shown to the user)" else "Connection recovered")
            is Event.workReplied -> { recorder.span(log, VoiceCallLog.Track.delegator, "work-${event.callID}", detail = event.reply, end = true); note("Session replied: ${event.reply ?: "(no text)"}") }
            is Event.workFailed -> { recorder.span(log, VoiceCallLog.Track.delegator, "work-${event.callID}", detail = event.message, end = true, failed = true); note("Request failed: ${event.message}") }
            is Event.delegatorFinished -> note(when {
                event.failure != null -> "Delegator failed: ${event.failure}"
                event.interrupted != null -> "Delegator stopped after its message was sent, so its reply is still watched: ${event.interrupted}"
                else -> "Delegator answered: ${event.reply}"
            })
            is Event.sessionStarted -> note("Created “${event.title}”")
            Event.targetChanged -> {
                note((if (state.isOnDraft) "Moved to a new session draft" else "Moved to “${state.sessionTitle}”") + (state.moveReason?.let { " ($it)" } ?: ""))
                recorder.setTitle(log, if (state.isOnDraft) VoiceFeature.State.newSessionTitle else state.sessionTitle)
            }
            Event.muteTapped -> note(if (state.isMuted) "Muted" else "Unmuted")
            is Event.atEarChanged -> note(if (event.atEar) "Raised to ear" else "Lowered from ear")
            Event.other -> Unit
        }
        if (state.isTerminal) recorder.finish(log)
        return state.isTerminal
    }
}
