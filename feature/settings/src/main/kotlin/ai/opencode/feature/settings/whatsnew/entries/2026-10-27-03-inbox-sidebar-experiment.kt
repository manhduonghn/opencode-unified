package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.inboxSidebarExperiment: WhatsNewEntry
    get() = smaller(
        id = "inbox-sidebar-experiment",
        day = "2026-10-27",
        title = localized(R.string.settings_whatsnew_entry_inbox_sidebar_experiment_title),
        symbol = "tray",
        summary = localized(R.string.settings_whatsnew_entry_inbox_sidebar_experiment_summary),
        tryIt = Destination.Open(SettingsDestination.Experiment("inbox-sidebar")),
    )
