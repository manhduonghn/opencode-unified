package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceWebrtcWeakConnection: WhatsNewEntry
    get() = smaller(
        id = "voice-webrtc-weak-connection",
        day = "2026-10-08",
        title = localized(R.string.settings_whatsnew_entry_voice_webrtc_weak_connection_title),
        symbol = "antenna.radiowaves.left.and.right",
        summary = localized(R.string.settings_whatsnew_entry_voice_webrtc_weak_connection_summary),
    )
