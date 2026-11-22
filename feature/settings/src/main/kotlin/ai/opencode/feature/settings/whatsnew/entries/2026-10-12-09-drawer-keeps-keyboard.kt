package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerKeepsKeyboard: WhatsNewEntry
    get() = smaller(
        id = "drawer-keeps-keyboard",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_drawer_keeps_keyboard_title),
        symbol = "keyboard",
        summary = localized(R.string.settings_whatsnew_entry_drawer_keeps_keyboard_summary),
    )
