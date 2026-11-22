package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.quickerSend: WhatsNewEntry
    get() = smaller(
        id = "quicker-send",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_quicker_send_title),
        symbol = "paperplane",
        summary = localized(R.string.settings_whatsnew_entry_quicker_send_summary),
    )
