package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.stuckTouchRecovery: WhatsNewEntry
    get() = smaller(
        id = "stuck-touch-recovery",
        day = "2026-11-05",
        title = localized(R.string.settings_whatsnew_entry_stuck_touch_recovery_title),
        symbol = "hand.point.up.left",
        summary = localized(R.string.settings_whatsnew_entry_stuck_touch_recovery_summary),
    )
