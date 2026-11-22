package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.loadingTouchRecovery: WhatsNewEntry
    get() = smaller(
        id = "loading-touch-recovery",
        day = "2026-11-04",
        title = localized(R.string.settings_whatsnew_entry_loading_touch_recovery_title),
        symbol = "hand.draw",
        summary = localized(R.string.settings_whatsnew_entry_loading_touch_recovery_summary),
    )
