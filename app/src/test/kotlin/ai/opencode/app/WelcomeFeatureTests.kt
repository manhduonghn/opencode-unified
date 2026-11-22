package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Here the presenting view's `onClose` sends the dismissal. */
class WelcomeFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()

    private val server = OpenCodeServer(url = "https://paired.example", username = "opencode", password = "token")

    private fun pairedState(welcome: WelcomeFeature.State? = null): AppFeature.State = shared.run {
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
        AppFeature.State.initial().copy(home = home.copy(welcome = welcome))
    }

    private fun overHome(action: WelcomeFeature.Action): AppFeature.Action =
        AppFeature.Action.Home(HomeFeature.Action.Welcome(PresentationAction.Presented(action)))

    private fun AppFeature.State.updatingWelcome(update: (WelcomeFeature.State) -> WelcomeFeature.State): AppFeature.State =
        copy(home = home?.let { it.copy(welcome = it.welcome?.let(update)) })

    @Test
    fun showWelcomeThenCloseLeavesThePairedServerAlone() = runTest {
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(pairedState(), effects)
        store.send(AppFeature.Action.Home(HomeFeature.Action.Settings(HomeSettingsFeature.Action.WelcomeOpened))) {
            it.copy(home = it.home?.copy(welcome = WelcomeFeature.State(isPreview = true)))
        }
        store.send(overHome(WelcomeFeature.Action.GetStartedTapped)) {
            it.updatingWelcome { welcome -> welcome.copy(step = WelcomeFeature.Step.pair) }
        }
        store.send(overHome(WelcomeFeature.Action.CloseTapped))
        store.send(AppFeature.Action.Home(HomeFeature.Action.Welcome(PresentationAction.Dismiss))) {
            it.copy(home = it.home?.copy(welcome = null))
        }
        assertEquals(server, store.state.home?.server)
        assertEquals(AppFeature.State.Connection.Idle, store.state.connection)
        store.finish()
        effects.skipInFlightEffects()
    }

    @Test
    fun previewScanOpensTheScannerOverHome() = runTest {
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(pairedState(WelcomeFeature.State(step = WelcomeFeature.Step.pair, isPreview = true)), effects)
        store.send(overHome(WelcomeFeature.Action.ScanTapped))
        store.receive(overHome(WelcomeFeature.Action.Delegate(WelcomeFeature.Request.Scan))) {
            it.copy(isScanningPairingCode = true)
        }
        assertEquals(server, store.state.home?.server)
        effects.skipInFlightEffects()
    }

    @Test
    fun previewPasteOfAnEmptyClipboardSaysSoInTheSheet() = runTest {
        val effects = detachedScope(coroutineContext)
        val haptics = Locked<List<Haptic>>(emptyList())
        val state = pairedState(WelcomeFeature.State(step = WelcomeFeature.Step.pair, isShowingOtherWays = true, isPreview = true))
        val store = shared.appStore(state, effects) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, string = { null })
            this[HapticsClient] = HapticsClient(play = { haptic -> haptics.update { it + haptic } })
        }
        store.send(overHome(WelcomeFeature.Action.PasteTapped))
        store.receive(overHome(WelcomeFeature.Action.Delegate(WelcomeFeature.Request.Paste))) {
            it.updatingWelcome { welcome -> welcome.copy(pasteError = AppFeature.EMPTY_CLIPBOARD_MESSAGE) }
        }
        assertEquals(true, store.state.home?.welcome?.isShowingOtherWays)
        assertEquals(listOf(Haptic.error), haptics.value)
        effects.skipInFlightEffects()
    }

    @Test
    fun firstRunGetStartedThenScanRunsTheClassicPairing() = runTest {
        val store = shared.appStore(shared.appState(), detachedScope(coroutineContext))
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.GetStartedTapped)) {
            it.copy(welcome = it.welcome.copy(step = WelcomeFeature.Step.pair))
        }
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.ScanTapped))
        store.receive(AppFeature.Action.Welcome(WelcomeFeature.Action.Delegate(WelcomeFeature.Request.Scan))) {
            it.copy(isScanningPairingCode = true, connection = AppFeature.State.Connection.Picking)
        }
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.BackTapped)) {
            it.copy(welcome = it.welcome.copy(step = WelcomeFeature.Step.intro))
        }
    }

    @Test
    fun enterAddressWaitsForOtherWaysToClose() = runTest {
        val state = shared.appState().let { it.copy(welcome = it.welcome.copy(step = WelcomeFeature.Step.pair, isShowingOtherWays = true)) }
        val store = shared.appStore(state, detachedScope(coroutineContext))
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.EnterAddressTapped)) {
            it.copy(welcome = it.welcome.copy(isShowingOtherWays = false, pendingRequest = WelcomeFeature.Request.EnterAddress))
        }
        store.send(AppFeature.Action.Welcome(WelcomeFeature.Action.OtherWaysDismissed)) {
            it.copy(welcome = it.welcome.copy(pendingRequest = null))
        }
        store.receive(AppFeature.Action.Welcome(WelcomeFeature.Action.Delegate(WelcomeFeature.Request.EnterAddress))) {
            it.copy(isEditingServer = true, password = "", connection = AppFeature.State.Connection.Picking)
        }
    }
}
