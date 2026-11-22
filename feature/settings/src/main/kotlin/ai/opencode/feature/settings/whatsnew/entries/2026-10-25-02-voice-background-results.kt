package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceBackgroundResults: WhatsNewEntry
    get() = smaller(
        id = "voice-background-results",
        day = "2026-10-25",
        title = localized(R.string.settings_whatsnew_entry_voice_background_results_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_voice_background_results_summary),
    )
