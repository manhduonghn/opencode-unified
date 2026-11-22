package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.dragToPin: WhatsNewEntry
    get() = smaller(
        id = "drag-to-pin",
        day = "2026-10-18",
        title = localized(R.string.settings_whatsnew_entry_drag_to_pin_title),
        symbol = "pin",
        summary = localized(R.string.settings_whatsnew_entry_drag_to_pin_summary),
    )
