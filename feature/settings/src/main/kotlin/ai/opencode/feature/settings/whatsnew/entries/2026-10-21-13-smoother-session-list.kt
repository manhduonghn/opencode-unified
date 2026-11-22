package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smootherSessionList: WhatsNewEntry
    get() = smaller(
        id = "smoother-session-list",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_smoother_session_list_title),
        symbol = "list.bullet",
        summary = localized(R.string.settings_whatsnew_entry_smoother_session_list_summary),
    )
