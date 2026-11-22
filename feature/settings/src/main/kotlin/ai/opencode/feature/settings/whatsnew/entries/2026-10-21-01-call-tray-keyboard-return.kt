package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayKeyboardReturn: WhatsNewEntry
    get() = smaller(
        id = "call-tray-keyboard-return",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_call_tray_keyboard_return_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_call_tray_keyboard_return_summary),
    )
