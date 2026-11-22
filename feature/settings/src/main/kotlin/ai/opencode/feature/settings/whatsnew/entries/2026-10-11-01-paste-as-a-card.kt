package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pasteAsACard: WhatsNewEntry
    get() = feature(
        id = "paste-as-a-card",
        day = "2026-10-11",
        title = localized(R.string.settings_whatsnew_entry_paste_as_a_card_title),
        symbol = "doc.on.clipboard",
        tint = Tint.brown,
        summary = localized(R.string.settings_whatsnew_entry_paste_as_a_card_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_paste_as_a_card_detail_1),
            localized(R.string.settings_whatsnew_entry_paste_as_a_card_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_paste_as_a_card_example),
    )
