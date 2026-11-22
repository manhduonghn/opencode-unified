package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.rideOutDrops: WhatsNewEntry
    get() = feature(
        id = "ride-out-drops",
        day = "2026-10-11",
        title = localized(R.string.settings_whatsnew_entry_ride_out_drops_title),
        symbol = "wifi",
        tint = Tint.mint,
        summary = localized(R.string.settings_whatsnew_entry_ride_out_drops_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_ride_out_drops_detail_1),
            localized(R.string.settings_whatsnew_entry_ride_out_drops_detail_2),
            localized(R.string.settings_whatsnew_entry_ride_out_drops_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_ride_out_drops_example),
    )
