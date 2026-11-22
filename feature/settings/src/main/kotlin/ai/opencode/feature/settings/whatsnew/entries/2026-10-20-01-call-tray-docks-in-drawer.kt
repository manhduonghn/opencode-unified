package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayDocksInDrawer: WhatsNewEntry
    get() = smaller(
        id = "call-tray-docks-in-drawer",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_call_tray_docks_in_drawer_title),
        symbol = "phone",
        summary = localized(R.string.settings_whatsnew_entry_call_tray_docks_in_drawer_summary),
    )
