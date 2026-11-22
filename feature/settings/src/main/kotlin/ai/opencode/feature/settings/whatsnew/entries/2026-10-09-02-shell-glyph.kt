package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.shellGlyph: WhatsNewEntry
    get() = smaller(
        id = "shell-glyph",
        day = "2026-10-09",
        title = localized(R.string.settings_whatsnew_entry_shell_glyph_title),
        symbol = "apple.terminal",
        summary = localized(R.string.settings_whatsnew_entry_shell_glyph_summary),
    )
