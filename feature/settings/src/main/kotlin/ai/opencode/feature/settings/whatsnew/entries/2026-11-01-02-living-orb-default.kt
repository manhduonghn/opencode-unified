package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbDefault: WhatsNewEntry
    get() = feature(
        id = "living-orb-default",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_living_orb_default_title),
        symbol = "circle.hexagongrid.fill",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_living_orb_default_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_living_orb_default_detail_1),
            localized(R.string.settings_whatsnew_entry_living_orb_default_detail_2),
            localized(R.string.settings_whatsnew_entry_living_orb_default_detail_3),
            localized(R.string.settings_whatsnew_entry_living_orb_default_detail_4),
            localized(R.string.settings_whatsnew_entry_living_orb_default_detail_5),
        ),
        example = localized(R.string.settings_whatsnew_entry_living_orb_default_example),
        tryIt = Destination.Call,
    )
