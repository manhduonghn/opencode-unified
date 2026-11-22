package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.breathPingCallSounds: WhatsNewEntry
    get() = smaller(
        id = "breath-ping-call-sounds",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_breath_ping_call_sounds_title),
        symbol = "speaker.wave.1",
        summary = localized(R.string.settings_whatsnew_entry_breath_ping_call_sounds_summary),
    )
