package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.gptLiveOwnKey: WhatsNewEntry
    get() = smaller(
        id = "gpt-live-own-key",
        day = "2026-10-23",
        title = localized(R.string.settings_whatsnew_entry_gpt_live_own_key_title),
        symbol = "key",
        summary = localized(R.string.settings_whatsnew_entry_gpt_live_own_key_summary),
    )
