package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.launchBloom: WhatsNewEntry
    get() = smaller(
        id = "launch-bloom",
        day = "2026-10-18",
        title = localized(R.string.settings_whatsnew_entry_launch_bloom_title),
        symbol = "sparkles",
        summary = localized(R.string.settings_whatsnew_entry_launch_bloom_summary),
    )
