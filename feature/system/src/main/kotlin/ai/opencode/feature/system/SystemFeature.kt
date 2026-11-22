package ai.opencode.feature.system

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * The system area's public reducer name, kept for parents that wire it. The area itself is clients
 * and system entry points that `AppFeature` and `HomeFeature` drive.
 */
class SystemFeature : Reducer<SystemFeature.State, SystemFeature.Action> {
    data class State(
        val placeholder: Boolean = true,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(state)
    }
}
