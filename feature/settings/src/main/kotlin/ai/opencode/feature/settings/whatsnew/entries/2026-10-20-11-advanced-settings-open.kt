package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.advancedSettingsOpen: WhatsNewEntry
    get() = smaller(
        id = "advanced-settings-open",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_advanced_settings_open_title),
        symbol = "flask",
        summary = localized(R.string.settings_whatsnew_entry_advanced_settings_open_summary),
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
