package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.bottomFollowOnLoad: WhatsNewEntry
    get() = smaller(
        id = "bottom-follow-on-load",
        day = "2026-10-17",
        title = localized(R.string.settings_whatsnew_entry_bottom_follow_on_load_title),
        symbol = "arrow.down",
        summary = localized(R.string.settings_whatsnew_entry_bottom_follow_on_load_summary),
    )
