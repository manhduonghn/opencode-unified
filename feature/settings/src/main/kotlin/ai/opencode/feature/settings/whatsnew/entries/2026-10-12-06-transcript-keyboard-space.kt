package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.transcriptKeyboardSpace: WhatsNewEntry
    get() = smaller(
        id = "transcript-keyboard-space",
        day = "2026-10-12",
        title = localized(R.string.settings_whatsnew_entry_transcript_keyboard_space_title),
        symbol = "keyboard.chevron.compact.down",
        summary = localized(R.string.settings_whatsnew_entry_transcript_keyboard_space_summary),
    )
