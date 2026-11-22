package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuTouchRelease: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-touch-release",
        day = "2026-11-05",
        title = localized(R.string.settings_whatsnew_entry_plus_menu_touch_release_title),
        symbol = "hand.draw",
        summary = localized(R.string.settings_whatsnew_entry_plus_menu_touch_release_summary),
    )
