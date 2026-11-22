package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.markdownStreamsSmoothly: WhatsNewEntry
    get() = smaller(
        id = "markdown-streams-smoothly",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_markdown_streams_smoothly_title),
        symbol = "text.alignleft",
        summary = localized(R.string.settings_whatsnew_entry_markdown_streams_smoothly_summary),
    )
