package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.themes: WhatsNewEntry
    get() = feature(
        id = "themes",
        day = "2026-10-18",
        title = localized(R.string.settings_whatsnew_entry_themes_title),
        symbol = "paintpalette",
        tint = Tint.purple,
        summary = localized(R.string.settings_whatsnew_entry_themes_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_themes_detail_1),
            localized(R.string.settings_whatsnew_entry_themes_detail_2),
            localized(R.string.settings_whatsnew_entry_themes_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_themes_example),
        tryIt = Destination.Open(SettingsDestination.Themes),
    )
