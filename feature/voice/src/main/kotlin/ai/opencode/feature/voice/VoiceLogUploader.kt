package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import java.util.UUID

/**
 * Developer Mode uploads a call's log to the voice-logs server under this phone's owner key, and
 * the prompt carries its page. Diagnostics sit outside voice, so the live value is inert (off); the
 * app or devtools installs the real uploader through `DependencyValues.installRoot` (keys
 * `SharedKeys.developerMode` / `developerModeOwner`).
 */
class VoiceLogUploader(
    val isEnabled: () -> Boolean,
    /** True once the log server took it. */
    val upload: suspend (log: VoiceCallLog) -> Boolean,
    /** The uploaded log's page. Carries the owner key: never log it. */
    val url: suspend (logID: UUID) -> String,
) {
    companion object Key : DependencyKey<VoiceLogUploader> {
        private val inert = VoiceLogUploader(isEnabled = { false }, upload = { false }, url = { "" })
        override val liveValue: VoiceLogUploader = inert
        override val testValue: VoiceLogUploader get() = inert
    }
}
