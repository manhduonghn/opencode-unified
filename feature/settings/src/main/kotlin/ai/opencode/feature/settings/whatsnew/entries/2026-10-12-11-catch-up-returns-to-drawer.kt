package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.catchUpReturnsToDrawer: WhatsNewEntry
    get() = smaller(
        id = "catch-up-returns-to-drawer",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_catch_up_returns_to_drawer_title),
        symbol = "rectangle.stack",
        summary = localized(R.string.settings_whatsnew_entry_catch_up_returns_to_drawer_summary),
    )
