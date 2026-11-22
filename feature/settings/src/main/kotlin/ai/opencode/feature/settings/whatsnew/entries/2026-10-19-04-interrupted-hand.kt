package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.interruptedHand: WhatsNewEntry
    get() = smaller(
        id = "interrupted-hand",
        day = "2026-10-19",
        title = localized(R.string.settings_whatsnew_entry_interrupted_hand_title),
        symbol = "hand.raised.fill",
        summary = localized(R.string.settings_whatsnew_entry_interrupted_hand_summary),
    )
