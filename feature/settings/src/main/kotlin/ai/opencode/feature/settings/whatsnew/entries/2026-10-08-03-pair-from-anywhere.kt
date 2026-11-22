package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pairFromAnywhere: WhatsNewEntry
    get() = feature(
        id = "pair-from-anywhere",
        day = "2026-10-08",
        title = localized(R.string.settings_whatsnew_entry_pair_from_anywhere_title),
        symbol = "qrcode",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_pair_from_anywhere_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_pair_from_anywhere_detail_1),
        ),
        example = localized(R.string.settings_whatsnew_entry_pair_from_anywhere_example),
        tryIt = Destination.servers,
    )
