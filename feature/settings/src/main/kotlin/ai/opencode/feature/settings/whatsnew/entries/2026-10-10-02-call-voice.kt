package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callVoice: WhatsNewEntry
    get() = feature(
        id = "call-voice",
        day = "2026-10-10",
        title = localized(R.string.settings_whatsnew_entry_call_voice_title),
        symbol = "phone.and.waveform.fill",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_call_voice_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_call_voice_detail_1),
            localized(R.string.settings_whatsnew_entry_call_voice_detail_2),
            localized(R.string.settings_whatsnew_entry_call_voice_detail_3),
            localized(R.string.settings_whatsnew_entry_call_voice_detail_4),
            localized(R.string.settings_whatsnew_entry_call_voice_detail_5),
            localized(R.string.settings_whatsnew_entry_call_voice_detail_6),
        ),
        example = localized(R.string.settings_whatsnew_entry_call_voice_example),
        tryIt = Destination.Call,
    )
