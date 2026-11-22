package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.subagentCapsulePress: WhatsNewEntry
    get() = smaller(
        id = "subagent-capsule-press",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_subagent_capsule_press_title),
        symbol = "capsule",
        summary = localized(R.string.settings_whatsnew_entry_subagent_capsule_press_summary),
    )
