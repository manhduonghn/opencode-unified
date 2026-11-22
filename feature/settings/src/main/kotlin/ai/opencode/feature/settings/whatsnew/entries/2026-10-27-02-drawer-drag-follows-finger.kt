package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerDragFollowsFinger: WhatsNewEntry
    get() = smaller(
        id = "drawer-drag-follows-finger",
        day = "2026-10-27",
        title = localized(R.string.settings_whatsnew_entry_drawer_drag_follows_finger_title),
        symbol = "hand.point.up.left",
        summary = localized(R.string.settings_whatsnew_entry_drawer_drag_follows_finger_summary),
    )
