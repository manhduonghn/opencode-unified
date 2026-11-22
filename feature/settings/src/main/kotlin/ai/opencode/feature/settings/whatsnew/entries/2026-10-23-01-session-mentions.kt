package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionMentions: WhatsNewEntry
    get() = smaller(
        id = "session-mentions",
        day = "2026-10-23",
        title = localized(R.string.settings_whatsnew_entry_session_mentions_title),
        symbol = "bubble.left.and.bubble.right",
        summary = localized(R.string.settings_whatsnew_entry_session_mentions_summary),
    )
