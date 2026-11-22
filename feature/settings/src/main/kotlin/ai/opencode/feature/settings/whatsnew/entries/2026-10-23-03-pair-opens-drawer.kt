package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pairOpensDrawer: WhatsNewEntry
    get() = smaller(
        id = "pair-opens-drawer",
        day = "2026-10-23",
        title = localized(R.string.settings_whatsnew_entry_pair_opens_drawer_title),
        symbol = "desktopcomputer",
        summary = localized(R.string.settings_whatsnew_entry_pair_opens_drawer_summary),
    )
