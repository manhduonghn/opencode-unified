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

internal val WhatsNewShipped.teamScrawlFeedback: WhatsNewEntry
    get() = feature(
        id = "team-scrawl-feedback",
        day = "2026-10-19",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_title),
        symbol = "hand.draw.fill",
        tint = Tint.teal,
        summary = localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_detail_1),
            localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_detail_2),
            localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_detail_3),
            localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_detail_4),
        ),
        example = localized(R.string.settings_whatsnew_entry_team_scrawl_feedback_example),
        tryIt = Destination.Open(SettingsDestination.Developer),
    )
