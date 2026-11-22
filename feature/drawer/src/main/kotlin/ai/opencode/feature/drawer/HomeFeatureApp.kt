package ai.opencode.feature.drawer

import ai.opencode.feature.drawer.HomeFeature.State

/* What `AppFeature` does to a Home it builds or replaces. */

/**
 * A switch made from Settings stays in Settings: the new server's Home opens Settings where
 * [previous] had it, told about this Home's connection, so the connection card shows the new server
 * arrive.
 */
fun State.keepingSettings(previous: State?): State {
    val settings = previous?.settings ?: return this
    return presentingSettings(settings)
}
