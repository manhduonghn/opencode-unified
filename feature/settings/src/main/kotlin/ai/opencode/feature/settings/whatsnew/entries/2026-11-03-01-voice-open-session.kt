package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceOpenSession: WhatsNewEntry
    get() = smaller(
        id = "voice-open-session",
        day = "2026-11-03",
        title = localized(R.string.settings_whatsnew_entry_voice_open_session_title),
        symbol = "arrow.triangle.swap",
        summary = localized(R.string.settings_whatsnew_entry_voice_open_session_summary),
    )
