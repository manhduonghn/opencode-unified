package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerOneEdge: WhatsNewEntry
    get() = smaller(
        id = "drawer-one-edge",
        day = "2026-10-10",
        title = localized(R.string.settings_whatsnew_entry_drawer_one_edge_title),
        symbol = "sidebar.left",
        summary = localized(R.string.settings_whatsnew_entry_drawer_one_edge_summary),
    )
