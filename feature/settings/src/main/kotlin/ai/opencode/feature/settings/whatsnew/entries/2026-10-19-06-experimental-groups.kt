package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.experimentalGroups: WhatsNewEntry
    get() = smaller(
        id = "experimental-groups",
        day = "2026-10-19",
        title = localized(R.string.settings_whatsnew_entry_experimental_groups_title),
        symbol = "flask",
        summary = localized(R.string.settings_whatsnew_entry_experimental_groups_summary),
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
