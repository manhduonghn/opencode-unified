package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.openLinksFromCalls: WhatsNewEntry
    get() = smaller(
        id = "open-links-from-calls",
        day = "2026-11-01",
        title = localized(R.string.settings_whatsnew_entry_open_links_from_calls_title),
        symbol = "arrow.up.forward.app",
        summary = localized(R.string.settings_whatsnew_entry_open_links_from_calls_summary),
    )
