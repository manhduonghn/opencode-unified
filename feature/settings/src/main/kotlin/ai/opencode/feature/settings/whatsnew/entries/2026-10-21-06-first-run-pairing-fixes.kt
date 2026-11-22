package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.firstRunPairingFixes: WhatsNewEntry
    get() = smaller(
        id = "first-run-pairing-fixes",
        day = "2026-10-21",
        title = localized(R.string.settings_whatsnew_entry_first_run_pairing_fixes_title),
        symbol = "qrcode.viewfinder",
        summary = localized(R.string.settings_whatsnew_entry_first_run_pairing_fixes_summary),
    )
