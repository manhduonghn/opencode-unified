package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.welcomePairingCopy: WhatsNewEntry
    get() = smaller(
        id = "welcome-pairing-copy",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_welcome_pairing_copy_title),
        symbol = "laptopcomputer.and.iphone",
        summary = localized(R.string.settings_whatsnew_entry_welcome_pairing_copy_summary),
    )
