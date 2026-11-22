package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.muteSessions: WhatsNewEntry
    get() = feature(
        id = "mute-sessions",
        day = "2026-10-08",
        title = localized(R.string.settings_whatsnew_entry_mute_sessions_title),
        symbol = "bell.slash",
        tint = Tint.indigo,
        summary = localized(R.string.settings_whatsnew_entry_mute_sessions_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_mute_sessions_detail_1),
        ),
        example = localized(R.string.settings_whatsnew_entry_mute_sessions_example),
    )
