package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.settingsSessionsPage: WhatsNewEntry
    get() = smaller(
        id = "settings-sessions-page",
        day = "2026-10-26",
        title = localized(R.string.settings_whatsnew_entry_settings_sessions_page_title),
        symbol = "bubble.left.fill",
        summary = localized(R.string.settings_whatsnew_entry_settings_sessions_page_summary),
        tryIt = Destination.Open(SettingsDestination.Sessions),
    )
