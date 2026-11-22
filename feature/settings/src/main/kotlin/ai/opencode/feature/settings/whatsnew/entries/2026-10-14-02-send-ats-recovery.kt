package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sendAtsRecovery: WhatsNewEntry
    get() = smaller(
        id = "send-ats-recovery",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_send_ats_recovery_title),
        symbol = "arrow.clockwise",
        summary = localized(R.string.settings_whatsnew_entry_send_ats_recovery_summary),
    )
