package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.inlineImageLinks: WhatsNewEntry
    get() = smaller(
        id = "inline-image-links",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_inline_image_links_title),
        symbol = "photo",
        summary = localized(R.string.settings_whatsnew_entry_inline_image_links_summary),
    )
