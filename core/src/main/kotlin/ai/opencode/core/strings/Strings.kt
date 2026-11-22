package ai.opencode.core.strings

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Reads the app's translated text where there is no composition: reducers, models, enums,
 * formatters and error messages. Composables use `stringResource` instead, which also
 * recomposes when the language changes.
 *
 * ```kotlin
 * val title: String get() = localized(R.string.session_rename_failed_title)
 * val summary = localizedPlural(R.plurals.drawer_session_count, count, count)
 * ```
 *
 * The text is read when the call runs, so call it from a function or a `get()`, never from
 * a value that is set once (a top-level `val`, a `const`, an enum constructor argument):
 * that would keep the language the app started in.
 */
fun localized(@StringRes id: Int, vararg args: Any): String = dependency(StringsClient).string(id, args)

/** The plural form of [id] for [count]. Pass [count] again in [args] when the text shows it. */
fun localizedPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    dependency(StringsClient).plural(id, count, args)

/** The string resources behind [localized] and [localizedPlural]. */
class StringsClient(
    val string: (id: Int, args: Array<out Any>) -> String,
    val plural: (id: Int, count: Int, args: Array<out Any>) -> String,
) {
    companion object Key : DependencyKey<StringsClient> {
        override val liveValue: StringsClient by lazy {
            // The application's resources follow the system and per-app language.
            val resources = dependency(AppContext).resources
            StringsClient(
                string = { id, args -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args) },
                plural = { id, count, args ->
                    if (args.isEmpty()) resources.getQuantityString(id, count) else resources.getQuantityString(id, count, *args)
                },
            )
        }

        /** The English text of `values/strings*.xml`, so unit tests assert the words a user reads. */
        override val testValue: StringsClient
            get() = StringsClient(string = SourceStrings::string, plural = SourceStrings::plural)
    }
}
