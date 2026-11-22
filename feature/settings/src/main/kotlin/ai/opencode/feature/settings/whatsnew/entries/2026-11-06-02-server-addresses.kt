package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.serverAddresses: WhatsNewEntry
    get() = feature(
        id = "server-addresses",
        day = "2026-11-06",
        title = localized(R.string.settings_whatsnew_entry_server_addresses_title),
        symbol = "point.3.connected.trianglepath.dotted",
        tint = Tint.green,
        summary = localized(R.string.settings_whatsnew_entry_server_addresses_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_server_addresses_detail_1),
            localized(R.string.settings_whatsnew_entry_server_addresses_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_server_addresses_example),
    )
