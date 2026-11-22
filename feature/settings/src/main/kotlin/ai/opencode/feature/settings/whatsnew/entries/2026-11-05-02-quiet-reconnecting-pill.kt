package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.quietReconnectingPill: WhatsNewEntry
    get() = smaller(
        id = "quiet-reconnecting-pill",
        day = "2026-11-05",
        title = localized(R.string.settings_whatsnew_entry_quiet_reconnecting_pill_title),
        symbol = "arrow.triangle.2.circlepath",
        summary = localized(R.string.settings_whatsnew_entry_quiet_reconnecting_pill_summary),
    )
