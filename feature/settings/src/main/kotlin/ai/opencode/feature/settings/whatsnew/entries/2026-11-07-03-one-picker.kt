package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.onePicker: WhatsNewEntry
    get() = feature(
        id = "one-picker",
        day = "2026-11-07",
        title = localized(R.string.settings_whatsnew_entry_one_picker_title),
        symbol = "cpu",
        tint = Tint.purple,
        summary = localized(R.string.settings_whatsnew_entry_one_picker_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_one_picker_detail_1),
            localized(R.string.settings_whatsnew_entry_one_picker_detail_2),
            localized(R.string.settings_whatsnew_entry_one_picker_detail_3),
            localized(R.string.settings_whatsnew_entry_one_picker_detail_4),
            localized(R.string.settings_whatsnew_entry_one_picker_detail_5),
        ),
        example = localized(R.string.settings_whatsnew_entry_one_picker_example),
        tryIt = Destination.ModelList,
    )
