package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.replyTextSelection: WhatsNewEntry
    get() = feature(
        id = "reply-text-selection",
        day = "2026-11-05",
        title = localized(R.string.settings_whatsnew_entry_reply_text_selection_title),
        symbol = "selection.pin.in.out",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_reply_text_selection_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_reply_text_selection_detail_1),
            localized(R.string.settings_whatsnew_entry_reply_text_selection_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_reply_text_selection_example),
    )
