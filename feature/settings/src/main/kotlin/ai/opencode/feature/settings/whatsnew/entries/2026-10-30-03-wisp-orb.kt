package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.wispOrb: WhatsNewEntry
    get() = smaller(
        id = "wisp-orb",
        day = "2026-10-30",
        title = localized(R.string.settings_whatsnew_entry_wisp_orb_title),
        symbol = "circle.hexagongrid.fill",
        summary = localized(R.string.settings_whatsnew_entry_wisp_orb_summary),
    )
