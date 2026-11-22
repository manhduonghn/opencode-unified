package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.composerLargeTextTargets: WhatsNewEntry
    get() = smaller(
        id = "composer-large-text-targets",
        day = "2026-10-16",
        title = localized(R.string.settings_whatsnew_entry_composer_large_text_targets_title),
        symbol = "hand.tap",
        summary = localized(R.string.settings_whatsnew_entry_composer_large_text_targets_summary),
    )
