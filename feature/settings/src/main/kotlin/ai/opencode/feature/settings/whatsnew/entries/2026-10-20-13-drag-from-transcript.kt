package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.dragFromTranscript: WhatsNewEntry
    get() = feature(
        id = "drag-from-transcript",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_drag_from_transcript_title),
        symbol = "hand.draw",
        tint = Tint.orange,
        summary = localized(R.string.settings_whatsnew_entry_drag_from_transcript_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_drag_from_transcript_detail_1),
            localized(R.string.settings_whatsnew_entry_drag_from_transcript_detail_2),
            localized(R.string.settings_whatsnew_entry_drag_from_transcript_detail_3),
            localized(R.string.settings_whatsnew_entry_drag_from_transcript_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_drag_from_transcript_example),
        tryIt = Destination.Prompt(localized(R.string.settings_whatsnew_entry_drag_from_transcript_prompt)),
    )
