package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.newMessagesDividerOn: WhatsNewEntry
    get() = feature(
        id = "new-messages-divider-on",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_new_messages_divider_on_title),
        symbol = "text.line.first.and.arrowtriangle.forward",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_new_messages_divider_on_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_new_messages_divider_on_detail_1),
            localized(R.string.settings_whatsnew_entry_new_messages_divider_on_detail_2),
            localized(R.string.settings_whatsnew_entry_new_messages_divider_on_detail_3),
            localized(R.string.settings_whatsnew_entry_new_messages_divider_on_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_new_messages_divider_on_example),
    )
