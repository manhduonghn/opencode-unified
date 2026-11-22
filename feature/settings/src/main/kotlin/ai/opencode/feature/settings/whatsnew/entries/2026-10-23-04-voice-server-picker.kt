package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceServerPicker: WhatsNewEntry
    get() = smaller(
        id = "voice-server-picker",
        day = "2026-10-23",
        title = localized(R.string.settings_whatsnew_entry_voice_server_picker_title),
        symbol = "server.rack",
        summary = localized(R.string.settings_whatsnew_entry_voice_server_picker_summary),
    )
