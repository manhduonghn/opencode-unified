package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.renameClearButton: WhatsNewEntry
    get() = smaller(
        id = "rename-clear-button",
        day = "2026-11-07",
        title = localized(R.string.settings_whatsnew_entry_rename_clear_button_title),
        symbol = "xmark.circle",
        summary = localized(R.string.settings_whatsnew_entry_rename_clear_button_summary),
    )
