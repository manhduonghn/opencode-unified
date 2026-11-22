package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.modelDisplayNames: WhatsNewEntry
    get() = smaller(
        id = "model-display-names",
        day = "2026-11-02",
        title = localized(R.string.settings_whatsnew_entry_model_display_names_title),
        symbol = "textformat",
        summary = localized(R.string.settings_whatsnew_entry_model_display_names_summary),
    )
