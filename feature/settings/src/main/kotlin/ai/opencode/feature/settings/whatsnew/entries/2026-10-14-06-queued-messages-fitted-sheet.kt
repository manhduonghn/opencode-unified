package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.queuedMessagesFittedSheet: WhatsNewEntry
    get() = smaller(
        id = "queued-messages-fitted-sheet",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_queued_messages_fitted_sheet_title),
        symbol = "text.bubble",
        summary = localized(R.string.settings_whatsnew_entry_queued_messages_fitted_sheet_summary),
    )
