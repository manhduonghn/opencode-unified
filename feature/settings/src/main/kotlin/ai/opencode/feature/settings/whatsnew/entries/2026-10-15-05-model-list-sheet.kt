package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelListSheet: WhatsNewEntry
    get() = smaller(
        id = "model-list-sheet",
        day = "2026-10-15",
        title = localized(R.string.settings_whatsnew_entry_model_list_sheet_title),
        symbol = "rectangle.bottomhalf.inset.filled",
        summary = localized(R.string.settings_whatsnew_entry_model_list_sheet_summary),
        tryIt = Destination.Open(SettingsDestination.Experimental),
    )
