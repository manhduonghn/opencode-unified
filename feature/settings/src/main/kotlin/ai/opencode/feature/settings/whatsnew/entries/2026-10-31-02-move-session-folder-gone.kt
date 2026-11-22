package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.moveSessionFolderGone: WhatsNewEntry
    get() = smaller(
        id = "move-session-folder-gone",
        day = "2026-10-31",
        title = localized(R.string.settings_whatsnew_entry_move_session_folder_gone_title),
        symbol = "folder",
        summary = localized(R.string.settings_whatsnew_entry_move_session_folder_gone_summary),
    )
