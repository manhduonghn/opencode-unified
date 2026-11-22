package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.dictationKeepsGoing: WhatsNewEntry
    get() = feature(
        id = "dictation-keeps-going",
        day = "2026-10-08",
        title = localized(R.string.settings_whatsnew_entry_dictation_keeps_going_title),
        symbol = "mic.fill",
        tint = Tint.orange,
        summary = localized(R.string.settings_whatsnew_entry_dictation_keeps_going_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_dictation_keeps_going_detail_1),
            localized(R.string.settings_whatsnew_entry_dictation_keeps_going_detail_2),
            localized(R.string.settings_whatsnew_entry_dictation_keeps_going_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_dictation_keeps_going_example),
    )
