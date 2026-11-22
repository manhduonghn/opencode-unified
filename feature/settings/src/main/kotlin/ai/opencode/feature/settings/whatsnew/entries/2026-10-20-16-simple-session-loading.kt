package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.simpleSessionLoading: WhatsNewEntry
    get() = smaller(
        id = "simple-session-loading",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_simple_session_loading_title),
        symbol = "hourglass",
        summary = localized(R.string.settings_whatsnew_entry_simple_session_loading_summary),
    )
