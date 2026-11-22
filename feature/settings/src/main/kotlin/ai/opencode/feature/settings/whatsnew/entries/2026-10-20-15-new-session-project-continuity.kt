package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newSessionProjectContinuity: WhatsNewEntry
    get() = smaller(
        id = "new-session-project-continuity",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_new_session_project_continuity_title),
        symbol = "folder",
        summary = localized(R.string.settings_whatsnew_entry_new_session_project_continuity_summary),
    )
