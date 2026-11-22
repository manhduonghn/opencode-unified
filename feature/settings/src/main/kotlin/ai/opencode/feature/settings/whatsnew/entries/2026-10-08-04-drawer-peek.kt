package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerPeek: WhatsNewEntry
    get() = feature(
        id = "drawer-peek",
        day = "2026-10-08",
        title = localized(R.string.settings_whatsnew_entry_drawer_peek_title),
        symbol = "clock",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_drawer_peek_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_drawer_peek_detail_1),
        ),
        example = localized(R.string.settings_whatsnew_entry_drawer_peek_example),
    )
