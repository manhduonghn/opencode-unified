package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callLinksAndAudio: WhatsNewEntry
    get() = feature(
        id = "call-links-and-audio",
        day = "2026-11-04",
        title = localized(R.string.settings_whatsnew_entry_call_links_and_audio_title),
        symbol = "checkmark.seal.fill",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_call_links_and_audio_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_call_links_and_audio_detail_1),
            localized(R.string.settings_whatsnew_entry_call_links_and_audio_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_call_links_and_audio_example),
    )
