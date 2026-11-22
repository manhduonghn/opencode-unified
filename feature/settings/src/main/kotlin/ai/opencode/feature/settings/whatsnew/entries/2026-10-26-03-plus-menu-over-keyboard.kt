package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuOverKeyboard: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-over-keyboard",
        day = "2026-10-26",
        title = localized(R.string.settings_whatsnew_entry_plus_menu_over_keyboard_title),
        symbol = "keyboard",
        summary = localized(R.string.settings_whatsnew_entry_plus_menu_over_keyboard_summary),
    )
