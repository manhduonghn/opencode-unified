package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.queuedPillWholeTitle: WhatsNewEntry
    get() = smaller(
        id = "queued-pill-whole-title",
        day = "2026-10-15",
        title = localized(R.string.settings_whatsnew_entry_queued_pill_whole_title_title),
        symbol = "text.bubble",
        summary = localized(R.string.settings_whatsnew_entry_queued_pill_whole_title_summary),
    )
