package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverSwitcher: WhatsNewEntry
    get() = feature(
        id = "server-switcher",
        day = "2026-10-09",
        title = localized(R.string.settings_whatsnew_entry_server_switcher_title),
        symbol = "laptopcomputer",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_server_switcher_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_server_switcher_detail_1),
            localized(R.string.settings_whatsnew_entry_server_switcher_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_server_switcher_example),
        tryIt = Destination.servers,
    )
