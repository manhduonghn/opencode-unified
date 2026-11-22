package ai.opencode.core.ui

import ai.opencode.core.sharing.SharedKey

/**
 * Settings → Appearance's persisted choices, same names and defaults. Mutate only from the Settings
 * reducer.
 */
object ThemeSharedKeys {
    /** The chosen theme; [AppTheme.OpenCode] (the desktop brand palette) by default on this branch. */
    val appTheme = SharedKey.appStorage("appearance-theme", AppTheme.OpenCode)

    /** System / Light / Dark override; [AppearanceColorScheme.System] by default. */
    val appearanceColorScheme = SharedKey.appStorage("appearance-color-scheme", AppearanceColorScheme.System)
}
