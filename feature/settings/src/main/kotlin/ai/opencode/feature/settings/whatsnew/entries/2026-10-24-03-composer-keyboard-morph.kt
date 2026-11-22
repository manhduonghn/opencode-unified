package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerKeyboardMorph: WhatsNewEntry
    get() = smaller(
        id = "composer-keyboard-morph",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_composer_keyboard_morph_title),
        symbol = "keyboard",
        summary = localized(R.string.settings_whatsnew_entry_composer_keyboard_morph_summary),
    )
