package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionListQuietHierarchy: WhatsNewEntry
    get() = smaller(
        id = "session-list-quiet-hierarchy",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_session_list_quiet_hierarchy_title),
        symbol = "textformat.size",
        summary = localized(R.string.settings_whatsnew_entry_session_list_quiet_hierarchy_summary),
    )
