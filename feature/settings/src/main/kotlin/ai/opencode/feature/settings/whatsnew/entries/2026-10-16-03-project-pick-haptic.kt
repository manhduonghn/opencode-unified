package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.projectPickHaptic: WhatsNewEntry
    get() = smaller(
        id = "project-pick-haptic",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_project_pick_haptic_title),
        symbol = "folder",
        summary = localized(R.string.settings_whatsnew_entry_project_pick_haptic_summary),
    )
