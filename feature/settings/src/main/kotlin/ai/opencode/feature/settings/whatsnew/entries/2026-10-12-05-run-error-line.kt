package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.runErrorLine: WhatsNewEntry
    get() = feature(
        id = "run-error-line",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_run_error_line_title),
        symbol = "exclamationmark.bubble",
        tint = Tint.red,
        summary = localized(R.string.settings_whatsnew_entry_run_error_line_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_run_error_line_detail_1),
            localized(R.string.settings_whatsnew_entry_run_error_line_detail_2),
            localized(R.string.settings_whatsnew_entry_run_error_line_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_run_error_line_example),
    )
