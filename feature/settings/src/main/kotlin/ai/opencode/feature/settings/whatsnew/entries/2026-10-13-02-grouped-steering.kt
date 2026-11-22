package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.groupedSteering: WhatsNewEntry
    get() = feature(
        id = "grouped-steering",
        day = "2026-10-13",
        title = localized(R.string.settings_whatsnew_entry_grouped_steering_title),
        symbol = "text.bubble",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_grouped_steering_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_grouped_steering_detail_1),
            localized(R.string.settings_whatsnew_entry_grouped_steering_detail_2),
            localized(R.string.settings_whatsnew_entry_grouped_steering_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_grouped_steering_example),
    )
