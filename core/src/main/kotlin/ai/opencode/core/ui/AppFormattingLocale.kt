package ai.opencode.core.ui

import ai.opencode.core.R
import ai.opencode.core.strings.localized
import java.util.Locale

/**
 * The locale dates, times and numbers are written in: the language the app's text is in, with
 * the user's region and preferences (hour cycle, numbering). Where the app has no translation
 * for the system language its text is English, so its dates are too (“Gestern” never appears
 * beside English text, a German region still gets its 24-hour clock and day-month order).
 */
fun appFormattingLocale(system: Locale = Locale.getDefault(Locale.Category.FORMAT)): Locale {
    val app = Locale.forLanguageTag(localized(R.string.core_language_tag))
    return try {
        Locale.Builder().setLocale(system).setLanguage(app.language).setScript(app.script).setVariant("").build()
    } catch (_: java.util.IllformedLocaleException) {
        Locale.Builder().setLanguage(app.language).build()
    }
}
