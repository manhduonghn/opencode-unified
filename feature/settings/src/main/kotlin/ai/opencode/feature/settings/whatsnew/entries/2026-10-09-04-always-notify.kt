package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.alwaysNotify: WhatsNewEntry
    get() = feature(
        id = "always-notify",
        day = "2026-10-09",
        title = localized(R.string.settings_whatsnew_entry_always_notify_title),
        symbol = "bell.badge",
        tint = Tint.red,
        summary = localized(R.string.settings_whatsnew_entry_always_notify_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_always_notify_detail_1),
            localized(R.string.settings_whatsnew_entry_always_notify_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_always_notify_example),
        tryIt = Destination.notifications,
    )
