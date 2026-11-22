package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.keyboardMenusAndHeader: WhatsNewEntry
    get() = smaller(
        id = "keyboard-menus-and-header",
        day = "2026-10-14",
        title = localized(R.string.settings_whatsnew_entry_keyboard_menus_and_header_title),
        symbol = "keyboard",
        summary = localized(R.string.settings_whatsnew_entry_keyboard_menus_and_header_summary),
    )
