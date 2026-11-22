package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.catchUpAllCaughtUpRow: WhatsNewEntry
    get() = smaller(
        id = "catch-up-all-caught-up-row",
        day = "2026-10-22",
        title = localized(R.string.settings_whatsnew_entry_catch_up_all_caught_up_row_title),
        symbol = "checkmark.circle",
        summary = localized(R.string.settings_whatsnew_entry_catch_up_all_caught_up_row_summary),
    )
