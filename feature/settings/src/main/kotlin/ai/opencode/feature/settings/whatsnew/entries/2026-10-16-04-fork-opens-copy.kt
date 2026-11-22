package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.forkOpensCopy: WhatsNewEntry
    get() = smaller(
        id = "fork-opens-copy",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_fork_opens_copy_title),
        symbol = "arrow.triangle.branch",
        summary = localized(R.string.settings_whatsnew_entry_fork_opens_copy_summary),
    )
