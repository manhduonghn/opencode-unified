package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbFloats: WhatsNewEntry
    get() = smaller(
        id = "living-orb-floats",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_living_orb_floats_title),
        symbol = "circle.hexagongrid",
        summary = localized(R.string.settings_whatsnew_entry_living_orb_floats_summary),
    )
