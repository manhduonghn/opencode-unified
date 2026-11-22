package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerTitleFade: WhatsNewEntry
    get() = smaller(
        id = "drawer-title-fade",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_drawer_title_fade_title),
        symbol = "text.alignleft",
        summary = localized(R.string.settings_whatsnew_entry_drawer_title_fade_summary),
    )
