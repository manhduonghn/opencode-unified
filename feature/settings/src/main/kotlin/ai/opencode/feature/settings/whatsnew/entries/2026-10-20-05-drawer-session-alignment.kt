package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerSessionAlignment: WhatsNewEntry
    get() = smaller(
        id = "drawer-session-alignment",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_drawer_session_alignment_title),
        symbol = "sidebar.left",
        summary = localized(R.string.settings_whatsnew_entry_drawer_session_alignment_summary),
    )
