package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.outageReconnectDelay: WhatsNewEntry
    get() = smaller(
        id = "outage-reconnect-delay",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_outage_reconnect_delay_title),
        symbol = "network",
        summary = localized(R.string.settings_whatsnew_entry_outage_reconnect_delay_summary),
    )
