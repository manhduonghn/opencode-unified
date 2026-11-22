package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.allExperiments: WhatsNewEntry
    get() = feature(
        id = "all-experiments",
        day = "2026-11-02",
        title = localized(R.string.settings_whatsnew_entry_all_experiments_title),
        symbol = "flask.fill",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_all_experiments_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_all_experiments_detail_1),
            localized(R.string.settings_whatsnew_entry_all_experiments_detail_2),
            localized(R.string.settings_whatsnew_entry_all_experiments_detail_3),
            localized(R.string.settings_whatsnew_entry_all_experiments_detail_4),
            localized(R.string.settings_whatsnew_entry_all_experiments_detail_5),
        ),
        example = localized(R.string.settings_whatsnew_entry_all_experiments_example),
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
