package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionIdLinks: WhatsNewEntry
    get() = feature(
        id = "session-id-links",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_session_id_links_title),
        symbol = "link",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_session_id_links_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_session_id_links_detail_1),
            localized(R.string.settings_whatsnew_entry_session_id_links_detail_2),
            localized(R.string.settings_whatsnew_entry_session_id_links_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_session_id_links_example),
        tryIt = Destination.Prompt(localized(R.string.settings_whatsnew_entry_session_id_links_prompt)),
    )
