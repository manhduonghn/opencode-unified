package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.inlineToolRows: WhatsNewEntry
    get() = feature(
        id = "inline-tool-rows",
        day = "2026-10-13",
        title = localized(R.string.settings_whatsnew_entry_inline_tool_rows_title),
        symbol = "rectangle.split.1x2",
        tint = Tint.indigo,
        summary = localized(R.string.settings_whatsnew_entry_inline_tool_rows_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_inline_tool_rows_detail_1),
            localized(R.string.settings_whatsnew_entry_inline_tool_rows_detail_2),
            localized(R.string.settings_whatsnew_entry_inline_tool_rows_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_inline_tool_rows_example),
    )
