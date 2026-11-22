package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smartDictationAudio: WhatsNewEntry
    get() = smaller(
        id = "smart-dictation-audio",
        day = "2026-10-19",
        title = localized(R.string.settings_whatsnew_entry_smart_dictation_audio_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_smart_dictation_audio_summary),
    )
