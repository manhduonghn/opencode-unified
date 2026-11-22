package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceMessages: WhatsNewEntry
    get() = smaller(
        id = "voice-messages",
        day = "2026-10-06",
        title = localized(R.string.settings_whatsnew_entry_voice_messages_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_voice_messages_summary),
    )
