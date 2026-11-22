package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.returnNewlineDefault: WhatsNewEntry
    get() = smaller(
        id = "return-newline-default",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_return_newline_default_title),
        symbol = "return",
        summary = localized(R.string.settings_whatsnew_entry_return_newline_default_summary),
        tryIt = Destination.Open(SettingsDestination.Sessions),
    )
