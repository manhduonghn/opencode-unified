package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.audioInCalls: WhatsNewEntry
    get() = smaller(
        id = "audio-in-calls",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_audio_in_calls_title),
        symbol = "music.note",
        summary = localized(R.string.settings_whatsnew_entry_audio_in_calls_summary),
    )
