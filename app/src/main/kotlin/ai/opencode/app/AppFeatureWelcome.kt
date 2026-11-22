package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.onboarding.WelcomeFeature

// The welcome's pairing requests, from the first run or from Show Welcome over Home.
internal val welcomeRequests = Reducer<AppFeature.State, AppFeature.Action> { state, action ->
    when (action) {
        is AppFeature.Action.Welcome ->
            (action.action as? WelcomeFeature.Action.Delegate)?.let { welcome(it.request, overHome = false, state) }
        is AppFeature.Action.Home -> {
            val presented = (action.action as? HomeFeature.Action.Welcome)?.action as? PresentationAction.Presented
            (presented?.action as? WelcomeFeature.Action.Delegate)?.let { welcome(it.request, overHome = true, state) }
        }
        else -> null
    } ?: Reduced(state)
}

private fun welcome(
    request: WelcomeFeature.Request,
    overHome: Boolean,
    state: AppFeature.State,
): Reduced<AppFeature.State, AppFeature.Action> =
    when (request) {
        WelcomeFeature.Request.Scan -> {
            val opening = beginScanningPairingCode(state.copy(pasteError = null))
            if (overHome) Reduced(opening) else cancelConnection(opening)
        }
        WelcomeFeature.Request.EnterAddress -> {
            val opening = beginNewServer(state)
            if (overHome) Reduced(opening) else cancelConnection(opening)
        }
        is WelcomeFeature.Request.Copy -> Reduced(state, Effect.send(AppFeature.Action.CopyTextTapped(request.text)))
        WelcomeFeature.Request.Paste -> {
            val text = dependency(PasteboardClient).string().orEmpty()
            val problem = pasteProblem(text)
            val next = updatingWelcome(state, overHome) { welcome ->
                welcome.copy(
                    pasteError = problem,
                    isShowingOtherWays = if (problem == null) false else welcome.isShowingOtherWays,
                )
            }
            if (problem != null) {
                dependency(HapticsClient).play(Haptic.error)
                Reduced(next)
            } else {
                Reduced(next, Effect.send(AppFeature.Action.PairingCodeScanned(text)))
            }
        }
    }

/** The welcome over Home, or the first run's. */
private fun updatingWelcome(
    state: AppFeature.State,
    overHome: Boolean,
    update: (WelcomeFeature.State) -> WelcomeFeature.State,
): AppFeature.State =
    if (overHome) {
        val home = state.home ?: return state
        val welcome = home.welcome ?: return state
        state.copy(home = home.copy(welcome = update(welcome)))
    } else {
        state.copy(welcome = update(state.welcome))
    }
