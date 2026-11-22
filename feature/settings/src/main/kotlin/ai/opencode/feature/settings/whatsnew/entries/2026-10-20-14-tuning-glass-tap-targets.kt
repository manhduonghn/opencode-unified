package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.tuningGlassTapTargets: WhatsNewEntry
    get() = feature(
        id = "tuning-glass-tap-targets",
        day = "2026-10-20",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_tuning_glass_tap_targets_title),
        symbol = "slider.horizontal.3",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_tuning_glass_tap_targets_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_tuning_glass_tap_targets_detail_1),
        ),
        example = localized(R.string.settings_whatsnew_entry_tuning_glass_tap_targets_example),
    )
