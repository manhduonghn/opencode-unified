package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.imagePreviews: WhatsNewEntry
    get() = feature(
        id = "image-previews",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_image_previews_title),
        symbol = "photo.on.rectangle",
        tint = Tint.purple,
        summary = localized(R.string.settings_whatsnew_entry_image_previews_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_image_previews_detail_1),
            localized(R.string.settings_whatsnew_entry_image_previews_detail_2),
            localized(R.string.settings_whatsnew_entry_image_previews_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_image_previews_example),
        tryIt = Destination.Prompt(localized(R.string.settings_whatsnew_entry_image_previews_prompt)),
    )
