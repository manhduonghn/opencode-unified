package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.defaultModel: WhatsNewEntry
    get() = smaller(
        id = "default-model",
        day = "2026-11-08",
        title = localized(R.string.settings_whatsnew_entry_default_model_title),
        symbol = "cpu",
        summary = localized(R.string.settings_whatsnew_entry_default_model_summary),
    )
