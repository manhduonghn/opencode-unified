package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelListAndLevelMeter: WhatsNewEntry
    get() = feature(
        id = "model-list-and-level-meter",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_model_list_and_level_meter_title),
        symbol = "cpu",
        tint = Tint.purple,
        summary = localized(R.string.settings_whatsnew_entry_model_list_and_level_meter_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_model_list_and_level_meter_detail_1),
            localized(R.string.settings_whatsnew_entry_model_list_and_level_meter_detail_2),
            localized(R.string.settings_whatsnew_entry_model_list_and_level_meter_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_model_list_and_level_meter_example),
        tryIt = Destination.ModelList,
    )
