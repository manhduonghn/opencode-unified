package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shareOpenedFiles: WhatsNewEntry
    get() = feature(
        id = "share-opened-files",
        day = "2026-10-10",
        title = localized(R.string.settings_whatsnew_entry_share_opened_files_title),
        symbol = "photo.on.rectangle.angled",
        tint = Tint.pink,
        summary = localized(R.string.settings_whatsnew_entry_share_opened_files_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_share_opened_files_detail_1),
            localized(R.string.settings_whatsnew_entry_share_opened_files_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_share_opened_files_example),
    )
