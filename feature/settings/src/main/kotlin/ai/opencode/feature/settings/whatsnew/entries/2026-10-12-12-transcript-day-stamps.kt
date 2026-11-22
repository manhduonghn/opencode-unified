package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.transcriptDayStamps: WhatsNewEntry
    get() = feature(
        id = "transcript-day-stamps",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_transcript_day_stamps_title),
        symbol = "calendar",
        tint = Tint.red,
        summary = localized(R.string.settings_whatsnew_entry_transcript_day_stamps_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_transcript_day_stamps_detail_1),
            localized(R.string.settings_whatsnew_entry_transcript_day_stamps_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_transcript_day_stamps_example),
    )
