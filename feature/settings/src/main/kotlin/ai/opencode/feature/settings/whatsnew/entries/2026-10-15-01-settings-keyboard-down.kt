package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.settingsKeyboardDown: WhatsNewEntry
    get() = smaller(
        id = "settings-keyboard-down",
        day = "2026-10-15",
        title = localized(R.string.settings_whatsnew_entry_settings_keyboard_down_title),
        symbol = "keyboard.chevron.compact.down",
        summary = localized(R.string.settings_whatsnew_entry_settings_keyboard_down_summary),
    )
