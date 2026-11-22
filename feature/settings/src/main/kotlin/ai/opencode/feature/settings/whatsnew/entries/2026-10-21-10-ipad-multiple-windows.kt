package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.ipadMultipleWindows: WhatsNewEntry
    get() = feature(
        id = "ipad-multiple-windows",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_title),
        symbol = "rectangle.split.2x1",
        tint = Tint.indigo,
        summary = localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_detail_1),
            localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_detail_2),
            localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_detail_3),
            localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_ipad_multiple_windows_example),
    )
