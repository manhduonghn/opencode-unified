package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceWaitsForYou: WhatsNewEntry
    get() = smaller(
        id = "voice-waits-for-you",
        day = "2026-10-30",
        title = localized(R.string.settings_whatsnew_entry_voice_waits_for_you_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_voice_waits_for_you_summary),
    )
