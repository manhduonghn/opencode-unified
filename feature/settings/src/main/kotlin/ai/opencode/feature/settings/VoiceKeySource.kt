package ai.opencode.feature.settings

import ai.opencode.core.strings.localized

/**
 * The status shown at a glance on the provider key row in Settings › Voice. `VoiceKeySource` itself
 * lives in `:feature:voice` (`VoiceRoute.keySource` returns it).
 */
sealed interface VoiceKeyStatus {
    data object Missing : VoiceKeyStatus
    data object Saved : VoiceKeyStatus
    data object Checking : VoiceKeyStatus
    data object Verified : VoiceKeyStatus
    data class Invalid(val message: String) : VoiceKeyStatus
    data class OnServer(val providerName: String) : VoiceKeyStatus

    val badgeTitle: String
        get() = when (this) {
            Missing -> localized(R.string.settings_voice_key_missing)
            Saved, is Invalid -> localized(R.string.settings_voice_key_saved)
            Checking -> localized(R.string.settings_voice_key_checking)
            Verified -> localized(R.string.settings_voice_key_verified)
            is OnServer -> providerName
        }
}
