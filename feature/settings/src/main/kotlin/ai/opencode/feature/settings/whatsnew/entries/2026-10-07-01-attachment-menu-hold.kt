package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.attachmentMenuHold: WhatsNewEntry
    get() = feature(
        id = "attachment-menu-hold",
        day = "2026-10-07",
        title = localized(R.string.settings_whatsnew_entry_attachment_menu_hold_title),
        symbol = "plus",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_attachment_menu_hold_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_attachment_menu_hold_detail_1),
            localized(R.string.settings_whatsnew_entry_attachment_menu_hold_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_attachment_menu_hold_example),
    )
