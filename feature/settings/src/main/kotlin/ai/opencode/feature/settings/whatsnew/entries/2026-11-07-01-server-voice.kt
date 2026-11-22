package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverVoice: WhatsNewEntry
    get() = feature(
        id = "server-voice",
        day = "2026-11-07",
        title = localized(R.string.settings_whatsnew_entry_server_voice_title),
        symbol = "waveform",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_server_voice_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_server_voice_detail_1),
            localized(R.string.settings_whatsnew_entry_server_voice_detail_2),
            localized(R.string.settings_whatsnew_entry_server_voice_detail_3),
            localized(R.string.settings_whatsnew_entry_server_voice_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_server_voice_example),
        tryIt = Destination.Open(SettingsDestination.Voice),
    )
