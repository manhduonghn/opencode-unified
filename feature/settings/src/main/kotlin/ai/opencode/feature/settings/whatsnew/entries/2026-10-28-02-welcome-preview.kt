package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.welcomePreview: WhatsNewEntry
    get() = smaller(
        id = "welcome-preview",
        day = "2026-10-28",
        title = localized(R.string.settings_whatsnew_entry_welcome_preview_title),
        symbol = "hand.wave",
        summary = localized(R.string.settings_whatsnew_entry_welcome_preview_summary),
    )
