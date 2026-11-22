package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.signInFailedEditServer: WhatsNewEntry
    get() = smaller(
        id = "sign-in-failed-edit-server",
        day = "2026-10-24",
        title = localized(R.string.settings_whatsnew_entry_sign_in_failed_edit_server_title),
        symbol = "key",
        summary = localized(R.string.settings_whatsnew_entry_sign_in_failed_edit_server_summary),
    )
