package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.stopRunsYourSteer: WhatsNewEntry
    get() = feature(
        id = "stop-runs-your-steer",
        day = "2026-10-11",
        title = localized(R.string.settings_whatsnew_entry_stop_runs_your_steer_title),
        symbol = "arrow.turn.up.right",
        tint = Tint.indigo,
        summary = localized(R.string.settings_whatsnew_entry_stop_runs_your_steer_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_stop_runs_your_steer_detail_1),
            localized(R.string.settings_whatsnew_entry_stop_runs_your_steer_detail_2),
            localized(R.string.settings_whatsnew_entry_stop_runs_your_steer_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_stop_runs_your_steer_example),
    )
