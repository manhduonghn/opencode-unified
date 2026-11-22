package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.transcriptRowTargets: WhatsNewEntry
    get() = smaller(
        id = "transcript-row-targets",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_transcript_row_targets_title),
        symbol = "hand.point.up.left",
        summary = localized(R.string.settings_whatsnew_entry_transcript_row_targets_summary),
    )
