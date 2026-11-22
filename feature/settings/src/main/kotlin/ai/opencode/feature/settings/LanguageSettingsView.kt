package ai.opencode.feature.settings

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale

/** The app's own language, or null when it follows the system. */
@Composable
private fun rememberAppLanguage(): Locale? {
    val locales = LocalConfiguration.current.locales
    return remember(locales) { dependency(AppLanguageClient).current() }
}

/**
 * Settings › Language: a search field, System Default, the phone's languages the app speaks, then every language the
 * app is translated into, each named in itself.
 */
@Composable
fun LanguageSettingsView(store: SettingsStore) {
    val locales = LocalConfiguration.current.locales
    val current = rememberAppLanguage()
    val system = remember(locales) { dependency(AppLanguageClient).systemLocales() }
    val languages = remember(locales) {
        val collator = Collator.getInstance(locales.get(0))
        dependency(AppLanguageClient).supported().sortedWith { a, b -> collator.compare(a.nativeName, b.nativeName) }
    }
    val suggested = remember(system, languages) { AppLanguages.suggested(system, languages) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(languages, query, locales) { AppLanguages.matching(languages, query, locales.get(0)) }
    val choose = { locale: Locale? ->
        store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.AppLanguageChanged(locale)))
    }
    val suggestedHeader = stringResource(R.string.settings_language_suggested_header)
    val allHeader = stringResource(R.string.settings_language_all_header)

    SettingsPageScaffold(title = stringResource(R.string.settings_language_title), store = store) {
        item(key = "search") {
            OpenCodeSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.settings_language_search_placeholder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (query.isBlank()) {
            settingsSection(key = "system") {
                LanguageRow(
                    title = AnnotatedString(stringResource(R.string.settings_language_system_default)),
                    summary = system.first().spokenName,
                    isSelected = current == null,
                    onClick = { choose(null) },
                )
            }
            if (suggested.isNotEmpty()) {
                settingsSection(key = "suggested", header = suggestedHeader) {
                    LanguageRows(suggested, current, choose)
                }
            }
            settingsSection(key = "languages", header = allHeader) {
                LanguageRows(languages, current, choose)
            }
        } else if (matches.isEmpty()) {
            settingsSection(key = "languages") {
                SettingsRowFrame {
                    Text(
                        stringResource(R.string.settings_language_no_matches),
                        style = SettingsText.title,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                }
            }
        } else {
            settingsSection(key = "languages") {
                LanguageRows(matches, current, choose)
            }
        }
    }
}

@Composable
private fun LanguageRows(languages: List<Locale>, current: Locale?, choose: (Locale) -> Unit) {
    languages.forEachIndexed { index, language ->
        if (index > 0) SettingsDivider()
        LanguageRow(
            title = language.spokenName,
            summary = null,
            isSelected = current?.toLanguageTag() == language.toLanguageTag(),
            onClick = { choose(language) },
        )
    }
}

/** The name in its own locale, so TalkBack reads it in that language and CJK text gets its own glyphs. */
private val Locale.spokenName: AnnotatedString
    get() = AnnotatedString(nativeName, SpanStyle(localeList = LocaleList(toLanguageTag())))

@Composable
private fun LanguageRow(title: AnnotatedString, summary: AnnotatedString?, isSelected: Boolean, onClick: () -> Unit) {
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) { selected = isSelected },
        onClick = onClick,
        role = Role.RadioButton,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = SettingsText.title, color = OpenCodeTheme.colors.text)
            if (summary != null) Text(summary, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText)
        }
        OpenCodeIcon(
            SFSymbol.Checkmark,
            contentDescription = null,
            size = 20.dp,
            tint = if (isSelected) OpenCodeTheme.colors.selection else Color.Transparent,
        )
    }
}
