package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerTextSelection: WhatsNewEntry
    get() = smaller(
        id = "composer-text-selection",
        day = "2026-10-29",
        title = localized(R.string.settings_whatsnew_entry_composer_text_selection_title),
        symbol = "character.cursor.ibeam",
        summary = localized(R.string.settings_whatsnew_entry_composer_text_selection_summary),
    )
