package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.macMotionPlaygrounds: WhatsNewEntry
    get() = feature(
        id = "mac-motion-playgrounds",
        day = "2026-10-13",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_title),
        symbol = "slider.horizontal.3",
        tint = Tint.orange,
        summary = localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_detail_1),
            localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_detail_2),
            localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_detail_3),
            localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_detail_4),
            localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_detail_5),
            localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_detail_6),
        ),
        example = localized(R.string.settings_whatsnew_entry_mac_motion_playgrounds_example),
        tryIt = Destination.Open(SettingsDestination.Developer),
    )
