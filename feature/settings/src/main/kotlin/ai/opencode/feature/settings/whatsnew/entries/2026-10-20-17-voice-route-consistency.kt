package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceRouteConsistency: WhatsNewEntry
    get() = smaller(
        id = "voice-route-consistency",
        day = "2026-10-20",
        title = localized(R.string.settings_whatsnew_entry_voice_route_consistency_title),
        symbol = "phone",
        summary = localized(R.string.settings_whatsnew_entry_voice_route_consistency_summary),
    )
