package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.voiceAppScreenshot: WhatsNewEntry
    get() = feature(
        id = "voice-app-screenshot",
        day = "2026-10-25",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_voice_app_screenshot_title),
        symbol = "camera.viewfinder",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_voice_app_screenshot_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_voice_app_screenshot_detail_1),
            localized(R.string.settings_whatsnew_entry_voice_app_screenshot_detail_2),
            localized(R.string.settings_whatsnew_entry_voice_app_screenshot_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_voice_app_screenshot_example),
    )
