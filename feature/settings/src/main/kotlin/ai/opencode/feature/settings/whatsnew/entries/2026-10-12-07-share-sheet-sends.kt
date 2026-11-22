package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shareSheetSends: WhatsNewEntry
    get() = feature(
        id = "share-sheet-sends",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_share_sheet_sends_title),
        symbol = "square.and.arrow.up",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_share_sheet_sends_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_share_sheet_sends_detail_1),
            localized(R.string.settings_whatsnew_entry_share_sheet_sends_detail_2),
            localized(R.string.settings_whatsnew_entry_share_sheet_sends_detail_3),
            localized(R.string.settings_whatsnew_entry_share_sheet_sends_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_share_sheet_sends_example),
    )
