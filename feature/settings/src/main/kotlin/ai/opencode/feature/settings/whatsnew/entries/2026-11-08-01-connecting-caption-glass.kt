package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.connectingCaptionGlass: WhatsNewEntry
    get() = smaller(
        id = "connecting-caption-glass",
        day = "2026-11-08",
        title = localized(R.string.settings_whatsnew_entry_connecting_caption_glass_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_connecting_caption_glass_summary),
    )
