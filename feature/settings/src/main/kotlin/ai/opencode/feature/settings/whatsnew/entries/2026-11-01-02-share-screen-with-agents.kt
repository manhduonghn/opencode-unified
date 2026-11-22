package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shareScreenWithAgents: WhatsNewEntry
    get() = smaller(
        id = "share-screen-with-agents",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_share_screen_with_agents_title),
        symbol = "rectangle.on.rectangle",
        summary = localized(R.string.settings_whatsnew_entry_share_screen_with_agents_summary),
        tryIt = Destination.Open(SettingsDestination.Experiment("share-screen-with-agents")),
    )
