package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smoothLinkPreviewArrival: WhatsNewEntry
    get() = smaller(
        id = "smooth-link-preview-arrival",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_smooth_link_preview_arrival_title),
        symbol = "link",
        summary = localized(R.string.settings_whatsnew_entry_smooth_link_preview_arrival_summary),
    )
