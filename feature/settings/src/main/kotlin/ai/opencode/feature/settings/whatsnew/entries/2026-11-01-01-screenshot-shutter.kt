package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Audience
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.screenshotShutter: WhatsNewEntry
    get() = feature(
        id = "screenshot-shutter",
        day = "2026-11-01",
        audience = Audience.developer,
        title = localized(R.string.settings_whatsnew_entry_screenshot_shutter_title),
        symbol = "camera.shutter.button",
        tint = Tint.gray,
        summary = localized(R.string.settings_whatsnew_entry_screenshot_shutter_summary),
        details = listOf(
            localized(R.string.settings_whatsnew_entry_screenshot_shutter_detail_1),
            localized(R.string.settings_whatsnew_entry_screenshot_shutter_detail_2),
            localized(R.string.settings_whatsnew_entry_screenshot_shutter_detail_3),
        ),
        example = localized(R.string.settings_whatsnew_entry_screenshot_shutter_example),
    )
