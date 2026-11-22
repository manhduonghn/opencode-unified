package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey

/**
 * The voice call's marks on the diagnostics timeline. The live value is inert; `:devtools`/`:app`
 * may install a recording one through `DependencyValues.installRoot`.
 *
 * `voice` gets timing marks only, plus a short `excerpt` that the installed client keeps only while
 * Include Transcripts is on.
 */
class VoiceDiagnostics(
    val voice: (name: String, sessionID: String?, detail: String?, excerpt: String?) -> Unit = { _, _, _, _ -> },
    /** Debug lab runs count marks such as `voice_call_start`. */
    val labMark: (name: String) -> Unit = {},
) {
    companion object Key : DependencyKey<VoiceDiagnostics> {
        override val liveValue: VoiceDiagnostics = VoiceDiagnostics()
        override val testValue: VoiceDiagnostics get() = VoiceDiagnostics()
    }
}
