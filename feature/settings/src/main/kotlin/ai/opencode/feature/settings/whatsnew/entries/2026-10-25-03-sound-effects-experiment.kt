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

internal val WhatsNewShipped.soundEffectsExperiment: WhatsNewEntry
    get() = feature(
        id = "sound-effects-experiment",
        day = "2026-10-25",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_sound_effects_experiment_title),
        symbol = "speaker.wave.2.fill",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_sound_effects_experiment_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_sound_effects_experiment_detail_1),
            localized(R.string.settings_whatsnew_entry_sound_effects_experiment_detail_2),
            localized(R.string.settings_whatsnew_entry_sound_effects_experiment_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_sound_effects_experiment_example),
        tryIt = Destination.Open(SettingsDestination.Experiment("sound-effects")),
    )
