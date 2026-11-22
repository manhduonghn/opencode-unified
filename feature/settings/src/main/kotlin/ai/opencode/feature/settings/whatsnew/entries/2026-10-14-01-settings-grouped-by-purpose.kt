package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.settingsGroupedByPurpose: WhatsNewEntry
    get() = smaller(
        id = "settings-grouped-by-purpose",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_settings_grouped_by_purpose_title),
        symbol = "gearshape",
        summary = localized(R.string.settings_whatsnew_entry_settings_grouped_by_purpose_summary),
    )
