package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerKeyboardWidth: WhatsNewEntry
    get() = smaller(
        id = "composer-keyboard-width",
        day = "2026-11-05",
        title = localized(R.string.settings_whatsnew_entry_composer_keyboard_width_title),
        symbol = "arrow.left.and.right",
        summary = localized(R.string.settings_whatsnew_entry_composer_keyboard_width_summary),
    )
