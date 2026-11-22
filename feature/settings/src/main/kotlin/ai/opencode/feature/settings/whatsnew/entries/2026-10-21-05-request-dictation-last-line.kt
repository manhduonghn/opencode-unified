package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.requestDictationLastLine: WhatsNewEntry
    get() = smaller(
        id = "request-dictation-last-line",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_request_dictation_last_line_title),
        symbol = "mic",
        summary = localized(R.string.settings_whatsnew_entry_request_dictation_last_line_summary),
    )
