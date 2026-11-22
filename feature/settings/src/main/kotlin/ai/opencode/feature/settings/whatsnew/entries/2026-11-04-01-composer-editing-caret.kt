package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerEditingCaret: WhatsNewEntry
    get() = smaller(
        id = "composer-editing-caret",
        day = "2026-11-04",
        title = localized(R.string.settings_whatsnew_entry_composer_editing_caret_title),
        symbol = "text.cursor",
        summary = localized(R.string.settings_whatsnew_entry_composer_editing_caret_summary),
    )
