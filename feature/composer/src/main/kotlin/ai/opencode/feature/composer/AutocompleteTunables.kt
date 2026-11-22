package ai.opencode.feature.composer

/** Whether choosing a `/` command fills it in or runs it. */
enum class AutocompleteCommandAction(val rawValue: String) { Fill("fill"), Run("run") }

/** Lengths in dp, times in seconds. */
object ComposerAutocompleteTunables {
    /** `composer.autocomplete.commandAction` "A / command". */
    val commandAction = AutocompleteCommandAction.Fill
    /** `composer.autocomplete.runDelay` "Runs after landing (s)" (0…1). */
    const val runDelay = 0.35

    /** `composer.autocomplete.rowHeight` "Row height (pt)" (36…56). */
    const val rowHeight = 40.0
    /** `composer.autocomplete.iconSize` "Icon (pt)" (16…32). */
    const val iconSize = 22.0
    /** `composer.autocomplete.maxRows` "Rows before scrolling" (2…8). */
    const val maxRows = 5.5
    /** `composer.autocomplete.padding` "Highlight inset (pt)" (2…20). */
    const val padding = 12.0
    /** `composer.autocomplete.rowInset` "Icon inset in highlight (pt)" (2…24). */
    const val rowInset = 7.0
    /** `composer.autocomplete.edgeFade` "Scroll edge fade (pt)" (0…60). */
    const val edgeFade = 24.0
}
