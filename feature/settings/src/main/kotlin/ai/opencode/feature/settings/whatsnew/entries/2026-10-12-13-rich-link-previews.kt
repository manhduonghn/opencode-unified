package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.richLinkPreviews: WhatsNewEntry
    get() = feature(
        id = "rich-link-previews",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_rich_link_previews_title),
        symbol = "link",
        tint = Tint.cyan,
        summary = localized(R.string.settings_whatsnew_entry_rich_link_previews_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_rich_link_previews_detail_1),
            localized(R.string.settings_whatsnew_entry_rich_link_previews_detail_2),
            localized(R.string.settings_whatsnew_entry_rich_link_previews_detail_3),
            localized(R.string.settings_whatsnew_entry_rich_link_previews_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_rich_link_previews_example),
        tryIt = Destination.Prompt(localized(R.string.settings_whatsnew_entry_rich_link_previews_prompt)),
    )
