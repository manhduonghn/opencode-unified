package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sendAfterAPause: WhatsNewEntry
    get() = smaller(
        id = "send-after-a-pause",
        day = "2026-10-26",
        title = localized(R.string.settings_whatsnew_entry_send_after_a_pause_title),
        symbol = "paperplane",
        summary = localized(R.string.settings_whatsnew_entry_send_after_a_pause_summary),
    )
