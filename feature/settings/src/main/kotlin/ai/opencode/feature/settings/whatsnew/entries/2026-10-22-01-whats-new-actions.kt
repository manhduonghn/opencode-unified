package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.whatsNewActions: WhatsNewEntry
    get() = smaller(
        id = "whats-new-actions",
        day = "2026-10-22",
        title = localized(R.string.settings_whatsnew_entry_whats_new_actions_title),
        symbol = "gift",
        summary = localized(R.string.settings_whatsnew_entry_whats_new_actions_summary),
    )
