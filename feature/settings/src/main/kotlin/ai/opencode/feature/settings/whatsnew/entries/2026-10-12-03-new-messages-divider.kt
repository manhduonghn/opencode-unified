package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newMessagesDivider: WhatsNewEntry
    get() = feature(
        id = "new-messages-divider",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_new_messages_divider_title),
        symbol = "line.horizontal.star.fill.line.horizontal",
        tint = Tint.orange,
        summary = localized(R.string.settings_whatsnew_entry_new_messages_divider_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_new_messages_divider_detail_1),
            localized(R.string.settings_whatsnew_entry_new_messages_divider_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_new_messages_divider_example),
    )
