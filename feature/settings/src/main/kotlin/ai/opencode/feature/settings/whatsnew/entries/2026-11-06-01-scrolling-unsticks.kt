package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.scrollingUnsticks: WhatsNewEntry
    get() = smaller(
        id = "scrolling-unsticks",
        day = "2026-11-06",
        title = localized(R.string.settings_whatsnew_entry_scrolling_unsticks_title),
        symbol = "hand.draw",
        summary = localized(R.string.settings_whatsnew_entry_scrolling_unsticks_summary),
    )
