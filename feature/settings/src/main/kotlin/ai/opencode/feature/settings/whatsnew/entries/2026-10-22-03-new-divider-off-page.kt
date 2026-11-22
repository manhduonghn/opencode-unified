package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newDividerOffPage: WhatsNewEntry
    get() = smaller(
        id = "new-divider-off-page",
        day = "2026-10-22",
        title = localized(R.string.settings_whatsnew_entry_new_divider_off_page_title),
        symbol = "line.horizontal.star.fill.line.horizontal",
        summary = localized(R.string.settings_whatsnew_entry_new_divider_off_page_summary),
    )
