package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.controlCenterSessions: WhatsNewEntry
    get() = feature(
        id = "control-center-sessions",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_control_center_sessions_title),
        symbol = "switch.2",
        tint = Tint.purple,
        summary = localized(R.string.settings_whatsnew_entry_control_center_sessions_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_control_center_sessions_detail_1),
            localized(R.string.settings_whatsnew_entry_control_center_sessions_detail_2),
            localized(R.string.settings_whatsnew_entry_control_center_sessions_detail_3),
            localized(R.string.settings_whatsnew_entry_control_center_sessions_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_control_center_sessions_example),
    )
