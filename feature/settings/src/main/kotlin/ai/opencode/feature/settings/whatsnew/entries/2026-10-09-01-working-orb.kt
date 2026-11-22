package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.workingOrb: WhatsNewEntry
    get() = smaller(
        id = "working-orb",
        day = "2026-10-09",
        title = localized(R.string.settings_whatsnew_entry_working_orb_title),
        symbol = "circle.dotted.circle",
        summary = localized(R.string.settings_whatsnew_entry_working_orb_summary),
    )
