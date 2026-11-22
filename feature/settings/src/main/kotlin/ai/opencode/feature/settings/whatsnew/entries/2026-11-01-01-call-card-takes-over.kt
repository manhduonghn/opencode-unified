package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callCardTakesOver: WhatsNewEntry
    get() = smaller(
        id = "call-card-takes-over",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_call_card_takes_over_title),
        symbol = "rectangle.bottomhalf.inset.filled",
        summary = localized(R.string.settings_whatsnew_entry_call_card_takes_over_summary),
    )
