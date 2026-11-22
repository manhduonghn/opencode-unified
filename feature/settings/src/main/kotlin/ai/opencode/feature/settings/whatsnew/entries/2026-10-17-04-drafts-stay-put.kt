package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.draftsStayPut: WhatsNewEntry
    get() = smaller(
        id = "drafts-stay-put",
        day = "2026-10-17",
        title = localized(R.string.settings_whatsnew_entry_drafts_stay_put_title),
        symbol = "square.and.pencil",
        summary = localized(R.string.settings_whatsnew_entry_drafts_stay_put_summary),
    )
