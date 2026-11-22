package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.plusMenuPhotoPicker: WhatsNewEntry
    get() = smaller(
        id = "plus-menu-photo-picker",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_plus_menu_photo_picker_title),
        symbol = "photo.on.rectangle",
        summary = localized(R.string.settings_whatsnew_entry_plus_menu_photo_picker_summary),
    )
