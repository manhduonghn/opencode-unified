package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callSoundsFromTheTap: WhatsNewEntry
    get() = smaller(
        id = "call-sounds-from-the-tap",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_call_sounds_from_the_tap_title),
        symbol = "phone",
        summary = localized(R.string.settings_whatsnew_entry_call_sounds_from_the_tap_summary),
    )
