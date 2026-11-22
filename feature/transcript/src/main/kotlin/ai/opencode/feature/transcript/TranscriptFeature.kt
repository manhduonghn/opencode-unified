package ai.opencode.feature.transcript

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * The transcript's store seam. The session builds the list's `Configuration` from its own state and
 * rows call `send(SessionFeature.Action)`; row UI state (`TranscriptDisclosureState`) lives with
 * the list. Here the session sets [State.configuration] (or calls [TranscriptList] directly) and
 * maps [Action.Delegate] to its own actions.
 */
class TranscriptFeature : Reducer<TranscriptFeature.State, TranscriptFeature.Action> {
    data class State(
        val configuration: TranscriptConfiguration = TranscriptConfiguration(),
        /** Incremented when the reader taps Jump to latest. */
        val jumpRequest: Int = 0,
        /** Whether the list's scroll behavior shows Jump to latest. */
        val showsJumpButton: Boolean = false,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
        /** The session replaced what the list draws. */
        data class ConfigurationChanged(val configuration: TranscriptConfiguration) : Action
        data object JumpToLatestTapped : Action
        data class ShowsJumpButtonChanged(val shows: Boolean) : Action
        /** Something a row or the list asks the session to do. */
        data class Delegate(val action: TranscriptAction) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(state)
        is Action.ConfigurationChanged -> Reduced(state.copy(configuration = action.configuration))
        Action.JumpToLatestTapped -> Reduced(state.copy(jumpRequest = state.jumpRequest + 1))
        is Action.ShowsJumpButtonChanged ->
            Reduced(if (state.showsJumpButton == action.shows) state else state.copy(showsJumpButton = action.shows))
        is Action.Delegate -> Reduced(state)
    }
}
