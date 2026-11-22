package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbPresets: WhatsNewEntry
    get() = smaller(
        id = "living-orb-presets",
        day = "2026-10-26",
        title = localized(R.string.settings_whatsnew_entry_living_orb_presets_title),
        symbol = "circle.hexagongrid.fill",
        summary = localized(R.string.settings_whatsnew_entry_living_orb_presets_summary),
    )
