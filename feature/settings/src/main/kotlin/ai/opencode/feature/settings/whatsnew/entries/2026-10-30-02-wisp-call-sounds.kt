package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.wispCallSounds: WhatsNewEntry
    get() = smaller(
        id = "wisp-call-sounds",
        day = "2026-10-30",
        title = localized(R.string.settings_whatsnew_entry_wisp_call_sounds_title),
        symbol = "speaker.wave.2",
        summary = localized(R.string.settings_whatsnew_entry_wisp_call_sounds_summary),
        tryIt = Destination.Open(SettingsDestination.Voice),
    )
