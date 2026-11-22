package ai.opencode.feature.settings.onboarding

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.settings.serverdetails.OpenURLClient
import java.net.URI

/**
 * The first-run welcome: the laptop and phone scene with Get Started, then one pairing step with
 * Scan Pairing Code and the rest behind Other Ways to Connect. It pairs nothing itself: its
 * requests go to `AppFeature`.
 *
 * Settings › Experimental › Show Welcome opens it over a paired app ([State.isPreview]).
 * [Action.CloseTapped] does nothing in the reducer; the presenting view's `onClose` dismisses it.
 */
class WelcomeFeature : Reducer<WelcomeFeature.State, WelcomeFeature.Action> {
    data class State(
        val step: Step = Step.intro,
        val isShowingOtherWays: Boolean = false,
        val isPreview: Boolean = false,
        /**
         * Asked for in Other Ways to Connect, sent once its sheet has gone so what it opens isn't
         * presented over the closing sheet.
         */
        val pendingRequest: Request? = null,
        /** Why a pasted link can't pair, shown under Paste (set by `AppFeature`). */
        val pasteError: String? = null,
    )

    enum class Step { intro, pair }

    sealed interface Request {
        data object Scan : Request
        data object Paste : Request
        data object EnterAddress : Request
        data class Copy(val text: String) : Request
    }

    sealed interface Action {
        /** The Other Ways sheet's presentation, set by the view. */
        data class OtherWaysShownChanged(val isShowing: Boolean) : Action
        data object GetStartedTapped : Action
        data object BackTapped : Action
        data object ScanTapped : Action
        data object OtherWaysTapped : Action
        data object PasteTapped : Action
        data object EnterAddressTapped : Action
        data class CopyTapped(val text: String) : Action
        data object OtherWaysDismissed : Action
        /** Through `OpenURLClient` so the view opens nothing itself. */
        data object OtherWaysToInstallTapped : Action
        data object CloseTapped : Action

        /** For `AppFeature`: what the person asked the pairing to do. */
        data class Delegate(val request: Request) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.OtherWaysShownChanged -> Reduced(state.copy(isShowingOtherWays = action.isShowing))

        Action.GetStartedTapped -> Reduced(state.copy(step = Step.pair))

        Action.BackTapped -> Reduced(state.copy(step = Step.intro, pasteError = null))

        Action.ScanTapped -> Reduced(state.copy(pasteError = null), Effect.send(Action.Delegate(Request.Scan)))

        Action.OtherWaysTapped -> Reduced(state.copy(isShowingOtherWays = true))

        // The sheet stays to say why a paste can't pair; a good link closes it.
        Action.PasteTapped -> Reduced(state, Effect.send(Action.Delegate(Request.Paste)))

        Action.EnterAddressTapped ->
            Reduced(state.copy(isShowingOtherWays = false, pendingRequest = Request.EnterAddress))

        is Action.CopyTapped -> Reduced(state, Effect.send(Action.Delegate(Request.Copy(action.text))))

        Action.OtherWaysDismissed -> {
            val request = state.pendingRequest
            if (request == null) Reduced(state)
            else Reduced(state.copy(pendingRequest = null), Effect.send(Action.Delegate(request)))
        }

        Action.OtherWaysToInstallTapped -> Reduced(
            state,
            Effect.run { dependency(OpenURLClient).open(URI(INSTALL_URL)) },
        )

        Action.CloseTapped -> Reduced(state)

        is Action.Delegate -> Reduced(state)
    }

    companion object {
        const val INSTALL_URL = "https://opencode.ai/download"
    }
}
