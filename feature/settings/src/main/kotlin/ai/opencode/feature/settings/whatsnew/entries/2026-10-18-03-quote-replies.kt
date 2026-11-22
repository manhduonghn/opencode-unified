package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.quoteReplies: WhatsNewEntry
    get() = smaller(
        id = "quote-replies",
        day = "2026-10-18",
        title = localized(R.string.settings_whatsnew_entry_quote_replies_title),
        symbol = "text.quote",
        summary = localized(R.string.settings_whatsnew_entry_quote_replies_summary),
    )
