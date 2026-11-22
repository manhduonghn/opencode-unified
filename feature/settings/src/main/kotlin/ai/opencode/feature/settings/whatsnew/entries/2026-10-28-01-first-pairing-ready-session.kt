package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.firstPairingReadySession: WhatsNewEntry
    get() = smaller(
        id = "first-pairing-ready-session",
        day = "2026-10-28",
        title = localized(R.string.settings_whatsnew_entry_first_pairing_ready_session_title),
        symbol = "checkmark.seal",
        summary = localized(R.string.settings_whatsnew_entry_first_pairing_ready_session_summary),
    )
