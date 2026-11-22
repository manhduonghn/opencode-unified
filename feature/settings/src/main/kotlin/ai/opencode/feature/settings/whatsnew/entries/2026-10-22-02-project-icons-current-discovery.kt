package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectIconsCurrentDiscovery: WhatsNewEntry
    get() = smaller(
        id = "project-icons-current-discovery",
        day = "2026-10-22",
        title = localized(R.string.settings_whatsnew_entry_project_icons_current_discovery_title),
        symbol = "app",
        summary = localized(R.string.settings_whatsnew_entry_project_icons_current_discovery_summary),
    )
