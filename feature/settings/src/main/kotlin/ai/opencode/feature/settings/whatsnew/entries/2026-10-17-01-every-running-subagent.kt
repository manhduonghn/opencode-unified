package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.everyRunningSubagent: WhatsNewEntry
    get() = smaller(
        id = "every-running-subagent",
        day = "2026-10-17",
        title = localized(R.string.settings_whatsnew_entry_every_running_subagent_title),
        symbol = "person.2",
        summary = localized(R.string.settings_whatsnew_entry_every_running_subagent_summary),
    )
