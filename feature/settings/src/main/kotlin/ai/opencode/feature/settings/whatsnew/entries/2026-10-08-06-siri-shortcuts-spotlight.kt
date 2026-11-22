package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.siriShortcutsSpotlight: WhatsNewEntry
    get() = feature(
        id = "siri-shortcuts-spotlight",
        day = "2026-10-08",
        title = localized(R.string.settings_whatsnew_entry_siri_shortcuts_spotlight_title),
        symbol = "square.grid.2x2",
        tint = Tint.purple,
        summary = localized(R.string.settings_whatsnew_entry_siri_shortcuts_spotlight_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_siri_shortcuts_spotlight_detail_1),
        ),
        example = localized(R.string.settings_whatsnew_entry_siri_shortcuts_spotlight_example),
    )
