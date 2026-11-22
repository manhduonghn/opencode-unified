package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.whatsNewPage: WhatsNewEntry
    get() = smaller(
        id = "whats-new-page",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_whats_new_page_title),
        symbol = "gift",
        summary = localized(R.string.settings_whatsnew_entry_whats_new_page_summary),
    )
