package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.foldQuestions: WhatsNewEntry
    get() = smaller(
        id = "fold-questions",
        day = "2026-11-02",
        title = localized(R.string.settings_whatsnew_entry_fold_questions_title),
        symbol = "chevron.down",
        summary = localized(R.string.settings_whatsnew_entry_fold_questions_summary),
    )
