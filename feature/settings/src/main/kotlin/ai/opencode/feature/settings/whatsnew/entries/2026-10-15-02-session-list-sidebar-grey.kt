package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionListSidebarGrey: WhatsNewEntry
    get() = smaller(
        id = "session-list-sidebar-grey",
        day = "2026-10-15",
        title = localized(R.string.settings_whatsnew_entry_session_list_sidebar_grey_title),
        symbol = "sidebar.left",
        summary = localized(R.string.settings_whatsnew_entry_session_list_sidebar_grey_summary),
    )
