package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectIconsLit: WhatsNewEntry
    get() = smaller(
        id = "project-icons-lit",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_project_icons_lit_title),
        symbol = "app",
        summary = localized(R.string.settings_whatsnew_entry_project_icons_lit_summary),
    )
