package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceVariedAcknowledgements: WhatsNewEntry
    get() = smaller(
        id = "voice-varied-acknowledgements",
        day = "2026-10-15",
        title = localized(R.string.settings_whatsnew_entry_voice_varied_acknowledgements_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_voice_varied_acknowledgements_summary),
    )
