package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.browserPushIn: WhatsNewEntry
    get() = smaller(
        id = "browser-push-in",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_browser_push_in_title),
        symbol = "safari",
        summary = localized(R.string.settings_whatsnew_entry_browser_push_in_summary),
        tryIt = Destination.Open(SettingsDestination.Experiment("in-app-browser")),
    )
