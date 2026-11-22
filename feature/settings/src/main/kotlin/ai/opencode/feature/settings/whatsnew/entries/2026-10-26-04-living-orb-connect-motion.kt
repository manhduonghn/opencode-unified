package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.SettingsDestination
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.livingOrbConnectMotion: WhatsNewEntry
    get() = smaller(
        id = "living-orb-connect-motion",
        day = "2026-10-26",
        title = localized(R.string.settings_whatsnew_entry_living_orb_connect_motion_title),
        symbol = "phone.connection",
        summary = localized(R.string.settings_whatsnew_entry_living_orb_connect_motion_summary),
    )
