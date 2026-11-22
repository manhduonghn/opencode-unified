package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.connectionErrorDetails: WhatsNewEntry
    get() = smaller(
        id = "connection-error-details",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_connection_error_details_title),
        symbol = "network",
        summary = localized(R.string.settings_whatsnew_entry_connection_error_details_summary),
    )
