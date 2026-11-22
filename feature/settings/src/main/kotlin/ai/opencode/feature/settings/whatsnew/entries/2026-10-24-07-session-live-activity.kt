package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionLiveActivity: WhatsNewEntry
    get() = smaller(
        id = "session-live-activity",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_session_live_activity_title),
        symbol = "platter.filled.top.iphone",
        summary = localized(R.string.settings_whatsnew_entry_session_live_activity_summary),
        tryIt = Destination.Open(SettingsDestination.Experiment("session-live-activity")),
    )
