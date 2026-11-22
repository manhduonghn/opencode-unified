package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.scrawlFasterStart: WhatsNewEntry
    get() = feature(
        id = "scrawl-faster-start",
        day = "2026-10-19",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_scrawl_faster_start_title),
        symbol = "hand.draw",
        tint = Tint.red,
        summary = localized(R.string.settings_whatsnew_entry_scrawl_faster_start_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_scrawl_faster_start_detail_1),
            localized(R.string.settings_whatsnew_entry_scrawl_faster_start_detail_2),
            localized(R.string.settings_whatsnew_entry_scrawl_faster_start_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_scrawl_faster_start_example),
    )
