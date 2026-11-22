package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.answerFromTheBar: WhatsNewEntry
    get() = feature(
        id = "answer-from-the-bar",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_answer_from_the_bar_title),
        symbol = "checklist",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_answer_from_the_bar_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_answer_from_the_bar_detail_1),
            localized(R.string.settings_whatsnew_entry_answer_from_the_bar_detail_2),
            localized(R.string.settings_whatsnew_entry_answer_from_the_bar_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_answer_from_the_bar_example),
    )
