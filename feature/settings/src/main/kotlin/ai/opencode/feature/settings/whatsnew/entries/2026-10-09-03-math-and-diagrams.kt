package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Destination
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.mathAndDiagrams: WhatsNewEntry
    get() = feature(
        id = "math-and-diagrams",
        day = "2026-10-09",
        title = localized(R.string.settings_whatsnew_entry_math_and_diagrams_title),
        symbol = "function",
        tint = Tint.indigo,
        summary = localized(R.string.settings_whatsnew_entry_math_and_diagrams_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_math_and_diagrams_detail_1),
            localized(R.string.settings_whatsnew_entry_math_and_diagrams_detail_2),
        ),
        example = localized(R.string.settings_whatsnew_entry_math_and_diagrams_example),
        tryIt = Destination.Prompt(localized(R.string.settings_whatsnew_entry_math_and_diagrams_prompt)),
    )
