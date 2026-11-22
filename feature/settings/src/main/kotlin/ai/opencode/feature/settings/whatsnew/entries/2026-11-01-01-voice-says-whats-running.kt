package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceSaysWhatsRunning: WhatsNewEntry
    get() = smaller(
        id = "voice-says-whats-running",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_voice_says_whats_running_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_voice_says_whats_running_summary),
    )
