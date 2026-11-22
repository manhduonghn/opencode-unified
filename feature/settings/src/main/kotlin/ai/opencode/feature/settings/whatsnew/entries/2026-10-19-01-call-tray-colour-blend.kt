package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.callTrayColourBlend: WhatsNewEntry
    get() = smaller(
        id = "call-tray-colour-blend",
        day = "2026-10-19",
        title = localized(R.string.settings_whatsnew_entry_call_tray_colour_blend_title),
        symbol = "phone",
        summary = localized(R.string.settings_whatsnew_entry_call_tray_colour_blend_summary),
    )
