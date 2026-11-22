package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.opticalInsets: WhatsNewEntry
    get() = smaller(
        id = "optical-insets",
        day = "2026-10-19",
        title = localized(R.string.settings_whatsnew_entry_optical_insets_title),
        symbol = "plus.circle",
        summary = localized(R.string.settings_whatsnew_entry_optical_insets_summary),
    )
