package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newSessionRecentProjects: WhatsNewEntry
    get() = feature(
        id = "new-session-recent-projects",
        day = "2026-10-11",
        title = localized(R.string.settings_whatsnew_entry_new_session_recent_projects_title),
        symbol = "folder",
        tint = Tint.teal,
        summary = localized(R.string.settings_whatsnew_entry_new_session_recent_projects_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_new_session_recent_projects_detail_1),
            localized(R.string.settings_whatsnew_entry_new_session_recent_projects_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_new_session_recent_projects_example),
        tryIt = Destination.NewSession,
    )
