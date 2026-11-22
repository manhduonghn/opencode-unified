package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.catchUpFlicks: WhatsNewEntry
    get() = smaller(
        id = "catch-up-flicks",
        day = "2026-10-13",
        title = localized(R.string.settings_whatsnew_entry_catch_up_flicks_title),
        symbol = "rectangle.stack",
        summary = localized(R.string.settings_whatsnew_entry_catch_up_flicks_summary),
    )
