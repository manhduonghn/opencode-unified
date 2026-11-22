package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.searchAllSessions: WhatsNewEntry
    get() = smaller(
        id = "search-all-sessions",
        day = "2026-11-03",
        title = localized(R.string.settings_whatsnew_entry_search_all_sessions_title),
        symbol = "magnifyingglass",
        summary = localized(R.string.settings_whatsnew_entry_search_all_sessions_summary),
    )
