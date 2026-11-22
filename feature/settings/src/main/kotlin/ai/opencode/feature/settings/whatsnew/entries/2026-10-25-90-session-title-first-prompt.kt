package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionTitleFirstPrompt: WhatsNewEntry
    get() = smaller(
        id = "session-title-first-prompt",
        day = "2026-10-25",
        title = localized(R.string.settings_whatsnew_entry_session_title_first_prompt_title),
        symbol = "text.quote",
        summary = localized(R.string.settings_whatsnew_entry_session_title_first_prompt_summary),
    )
