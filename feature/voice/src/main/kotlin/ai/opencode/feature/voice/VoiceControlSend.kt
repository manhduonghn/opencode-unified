package ai.opencode.feature.voice

import ai.opencode.core.log.AppLog

object VoiceControlSend {
    /** Never include the command payload or an error's localized description. */
    fun recordFailure(name: String, error: Throwable) {
        val diagnostic = RealtimeTransportDiagnostic.of(error)
        AppLog.voice.error { "voice_control_send_failed command=$name ${diagnostic.logSummary}" }
    }
}
