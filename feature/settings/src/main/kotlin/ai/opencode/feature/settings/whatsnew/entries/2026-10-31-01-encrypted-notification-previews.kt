package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.encryptedNotificationPreviews: WhatsNewEntry
    get() = smaller(
        id = "encrypted-notification-previews",
        day = "2026-10-31",
        title = localized(R.string.settings_whatsnew_entry_encrypted_notification_previews_title),
        symbol = "lock.fill",
        summary = localized(R.string.settings_whatsnew_entry_encrypted_notification_previews_summary),
        tryIt = Destination.notifications,
    )
