package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.autocompleteFastScroll: WhatsNewEntry
    get() = smaller(
        id = "autocomplete-fast-scroll",
        day = "2026-10-19",
        title = localized(R.string.settings_whatsnew_entry_autocomplete_fast_scroll_title),
        symbol = "at",
        summary = localized(R.string.settings_whatsnew_entry_autocomplete_fast_scroll_summary),
    )
