package ai.opencode.feature.voice

/**
 * Semantic view of one call: what was said, what the model asked for, and
 * how the audio path is doing. Bytes, base64, and timing stay below this.
 */
sealed interface VoiceCallEvent {
    data object Connected : VoiceCallEvent
    data object UserSpeechStarted : VoiceCallEvent
    data class UserTranscript(val itemID: String, val text: String, val final: Boolean) : VoiceCallEvent
    data class AssistantDelta(val itemID: String?, val delta: String) : VoiceCallEvent
    data class AssistantDone(val itemID: String?, val text: String) : VoiceCallEvent
    data object AssistantPlaybackFinished : VoiceCallEvent
    data class FunctionCall(val callID: String, val name: String, val arguments: RealtimeJSON) : VoiceCallEvent

    /**
     * GPT-Live delegated to its Responses backend (Server Voice): the
     * delegator runs there, and its tool calls come back through the call.
     */
    data class ServerDelegation(val callID: String) : VoiceCallEvent
    data object AudioInterrupted : VoiceCallEvent
    data object AudioResumed : VoiceCallEvent
    data object RouteChanged : VoiceCallEvent

    /** The session dropped and a replacement is being opened. */
    data object Reconnecting : VoiceCallEvent
    data object Reconnected : VoiceCallEvent

    /** Microphone audio is backing up on the way out (true), or flowing again. */
    data class ConnectionPoor(val poor: Boolean) : VoiceCallEvent
    data class ServiceError(val message: String) : VoiceCallEvent
}
