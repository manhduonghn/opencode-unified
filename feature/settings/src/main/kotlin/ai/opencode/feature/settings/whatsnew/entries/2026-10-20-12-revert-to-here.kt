package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.revertToHere: WhatsNewEntry
    get() = feature(
        id = "revert-to-here",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_revert_to_here_title),
        symbol = "arrow.uturn.backward",
        tint = Tint.orange,
        summary = localized(R.string.settings_whatsnew_entry_revert_to_here_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_revert_to_here_detail_1),
            localized(R.string.settings_whatsnew_entry_revert_to_here_detail_2),
            localized(R.string.settings_whatsnew_entry_revert_to_here_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_revert_to_here_example),
    )
