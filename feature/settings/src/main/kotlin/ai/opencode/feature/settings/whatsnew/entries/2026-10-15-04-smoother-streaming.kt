package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.smootherStreaming: WhatsNewEntry
    get() = smaller(
        id = "smoother-streaming",
        day = "2026-10-15",
        title = localized(R.string.settings_whatsnew_entry_smoother_streaming_title),
        symbol = "text.line.last.and.arrowtriangle.forward",
        summary = localized(R.string.settings_whatsnew_entry_smoother_streaming_summary),
    )
