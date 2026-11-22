package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.rollingNumbers: WhatsNewEntry
    get() = smaller(
        id = "rolling-numbers",
        day = "2026-10-13",
        title = localized(R.string.settings_whatsnew_entry_rolling_numbers_title),
        symbol = "number",
        summary = localized(R.string.settings_whatsnew_entry_rolling_numbers_summary),
    )
