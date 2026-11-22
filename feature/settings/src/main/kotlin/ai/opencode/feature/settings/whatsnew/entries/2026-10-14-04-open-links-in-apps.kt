package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.openLinksInApps: WhatsNewEntry
    get() = smaller(
        id = "open-links-in-apps",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_open_links_in_apps_title),
        symbol = "arrow.up.forward.app",
        summary = localized(R.string.settings_whatsnew_entry_open_links_in_apps_summary),
        tryIt = Destination.Open(SettingsDestination.Experiment("in-app-browser")),
    )
