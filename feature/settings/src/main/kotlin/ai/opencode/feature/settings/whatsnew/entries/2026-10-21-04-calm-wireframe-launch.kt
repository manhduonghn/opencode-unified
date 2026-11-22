package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.calmWireframeLaunch: WhatsNewEntry
    get() = smaller(
        id = "calm-wireframe-launch",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_calm_wireframe_launch_title),
        symbol = "hourglass",
        summary = localized(R.string.settings_whatsnew_entry_calm_wireframe_launch_summary),
    )
