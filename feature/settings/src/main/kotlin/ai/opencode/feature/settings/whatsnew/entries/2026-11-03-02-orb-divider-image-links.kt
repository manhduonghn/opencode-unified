package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.orbDividerImageLinks: WhatsNewEntry
    get() = feature(
        id = "orb-divider-image-links",
        day = "2026-11-03",
        title = localized(R.string.settings_whatsnew_entry_orb_divider_image_links_title),
        symbol = "checkmark.seal.fill",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_orb_divider_image_links_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_orb_divider_image_links_detail_1),
            localized(R.string.settings_whatsnew_entry_orb_divider_image_links_detail_2),
            localized(R.string.settings_whatsnew_entry_orb_divider_image_links_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_orb_divider_image_links_example),
    )
