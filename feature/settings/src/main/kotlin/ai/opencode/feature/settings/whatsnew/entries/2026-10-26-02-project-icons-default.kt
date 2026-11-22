package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectIconsDefault: WhatsNewEntry
    get() = smaller(
        id = "project-icons-default",
        day = "2026-10-26",
        title = localized(R.string.settings_whatsnew_entry_project_icons_default_title),
        symbol = "app",
        summary = localized(R.string.settings_whatsnew_entry_project_icons_default_summary),
    )
