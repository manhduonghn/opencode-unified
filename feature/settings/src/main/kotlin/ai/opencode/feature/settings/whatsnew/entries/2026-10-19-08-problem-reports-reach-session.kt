package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.problemReportsReachSession: WhatsNewEntry
    get() = feature(
        id = "problem-reports-reach-session",
        day = "2026-10-19",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_title),
        symbol = "exclamationmark.bubble",
        tint = Tint.orange,
        summary = localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_detail_1),
            localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_detail_2),
            localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_detail_3),
            localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_detail_4),
            localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_detail_5),
        ),
        example = localized(R.string.settings_whatsnew_entry_problem_reports_reach_session_example),
    )
