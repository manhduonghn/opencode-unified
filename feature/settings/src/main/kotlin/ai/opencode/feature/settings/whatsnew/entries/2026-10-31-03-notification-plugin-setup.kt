package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.notificationPluginSetup: WhatsNewEntry
    get() = smaller(
        id = "notification-plugin-setup",
        day = "2026-10-31",
        title = localized(R.string.settings_whatsnew_entry_notification_plugin_setup_title),
        symbol = "bell.badge",
        summary = localized(R.string.settings_whatsnew_entry_notification_plugin_setup_summary),
        tryIt = Destination.notifications,
    )
