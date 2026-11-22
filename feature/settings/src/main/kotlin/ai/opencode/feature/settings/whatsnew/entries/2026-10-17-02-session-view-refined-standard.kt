package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionViewRefinedStandard: WhatsNewEntry
    get() = smaller(
        id = "session-view-refined-standard",
        day = "2026-10-17",
        title = localized(R.string.settings_whatsnew_entry_session_view_refined_standard_title),
        symbol = "textformat.size",
        summary = localized(R.string.settings_whatsnew_entry_session_view_refined_standard_summary),
    )
