package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerPhotoViewer: WhatsNewEntry
    get() = smaller(
        id = "composer-photo-viewer",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_composer_photo_viewer_title),
        symbol = "photo",
        summary = localized(R.string.settings_whatsnew_entry_composer_photo_viewer_summary),
    )
