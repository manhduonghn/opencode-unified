package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTraySwitchContinuity: WhatsNewEntry
    get() = smaller(
        id = "call-tray-switch-continuity",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_call_tray_switch_continuity_title),
        symbol = "waveform",
        summary = localized(R.string.settings_whatsnew_entry_call_tray_switch_continuity_summary),
    )
