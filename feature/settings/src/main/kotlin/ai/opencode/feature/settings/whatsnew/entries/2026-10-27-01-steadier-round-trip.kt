package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.steadierRoundTrip: WhatsNewEntry
    get() = smaller(
        id = "steadier-round-trip",
        day = "2026-10-27",
        title = localized(R.string.settings_whatsnew_entry_steadier_round_trip_title),
        symbol = "waveform.path.ecg",
        summary = localized(R.string.settings_whatsnew_entry_steadier_round_trip_summary),
    )
