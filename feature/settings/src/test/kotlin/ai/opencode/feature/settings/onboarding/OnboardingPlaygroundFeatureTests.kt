package ai.opencode.feature.settings.onboarding

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.HapticsRecorder
import ai.opencode.feature.settings.Locked
import ai.opencode.feature.settings.SettingsTestShared
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Action
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.CameraFeed
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Control
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Failure
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Latency
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Phase
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.State
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Variant
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every phase, interruptions, the simulated server's failures and Replay, and that nothing in it
 * reaches a server, the saved servers or the keychain. The Close test checks that nothing keeps
 * running (`finish`), since the view's `onClose` dismisses.
 */
class OnboardingPlaygroundFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    /** Every way out of the sandbox, recorded so a test can prove none was taken. */
    private class Escapes {
        val calls = Locked(listOf<String>())
        val haptics = HapticsRecorder()
        val shared = SettingsTestShared()

        fun record(call: String) = calls.update { it + call }
    }

    private fun TestScope.makeStore(
        state: State = State(),
        clock: TestClock,
        escapes: Escapes,
        camera: Boolean = false,
    ) = TestStore(state, OnboardingPlaygroundFeature(), this) {
        this[Clock] = clock
        this[HapticsClient] = escapes.haptics.client
        this[OnboardingCameraClient] = OnboardingCameraClient { camera }
        this[SharedStore] = escapes.shared.store
        this[OpenCodeClient] = OpenCodeClient.testValue.copy(
            redeemPairing = {
                escapes.record("redeemPairing")
                throw OpenCodeClientError("sandbox")
            },
            health = { escapes.record("health") },
            homeSnapshot = {
                escapes.record("homeSnapshot")
                throw OpenCodeClientError("sandbox")
            },
            createSession = { _, _, _ ->
                escapes.record("createSession")
                throw OpenCodeClientError("sandbox")
            },
        )
    }

    @Test fun theFlowEarnsOneHeartbeatOnTheServersReply() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(clock = clock, escapes = escapes, camera = true)

        store.send(Action.Task) { it.copy(cameraFeed = CameraFeed.live) }
        store.send(Action.GetStartedTapped) { it.copy(phase = Phase.story) }
        store.send(Action.ConnectTapped) { it.copy(phase = Phase.camera) }
        store.send(Action.SampleCodeTapped) { it.copy(phase = Phase.waiting) }
        // Waiting stays quiet: no heartbeat until the server answers.
        clock.advance(1400.milliseconds)
        clock.advance(100.milliseconds)
        store.receive(Action.ServerReplied) { it.copy(phase = Phase.connected, heartbeats = 1) }
        store.send(Action.StartSessionTapped) { it.copy(phase = Phase.session) }
        store.send(Action.BackTapped) { it.copy(phase = Phase.connected) }
        store.finish()

        assertEquals(listOf("tap", "tap", "tap", "success", "tap"), escapes.haptics.value)
        assertTrue(escapes.calls.value.isEmpty())
    }

    @Test fun aRealPairingCodeStaysInTheSandbox() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(State(phase = Phase.camera), clock = clock, escapes = escapes)

        store.send(Action.CodeScanned("https://box.example/auth/connect/Ab3_x-9Q")) { it.copy(phase = Phase.waiting) }
        clock.advance(1.5.seconds)
        store.receive(Action.ServerReplied) { it.copy(phase = Phase.connected, heartbeats = 1) }
        store.send(Action.StartSessionTapped) { it.copy(phase = Phase.session) }

        assertNull(escapes.shared.get(ServerSharedKeys.savedServer))
        assertTrue(escapes.shared.get(ServerSharedKeys.savedServers).isEmpty())
        assertTrue("The playground never redeems, connects or creates a session", escapes.calls.value.isEmpty())
        // A code the camera reads again while waiting is ignored.
        store.send(Action.CodeScanned("https://box.example/auth/connect/Ab3_x-9Q"))
        store.finish()
    }

    @Test fun aCodeThatIsntAPairingCodeFailsAsAWrongCode() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(State(phase = Phase.camera), clock = clock, escapes = escapes)

        store.send(Action.CodeScanned("https://example.com/menu")) { it.copy(phase = Phase.waiting) }
        clock.advance(1.5.seconds)
        store.receive(Action.PairingFailed(Failure.wrongCode)) { it.copy(phase = Phase.failed, failedWith = Failure.wrongCode) }
        testScheduler.runCurrent()
        assertEquals(listOf("error"), escapes.haptics.value)

        store.send(Action.ScanAgainTapped) { it.copy(phase = Phase.camera) }
        store.finish()
        assertEquals(listOf("error", "tap"), escapes.haptics.value)
    }

    @Test fun theFailureSwitchTurnsTheReplyDownAfterTheChosenLatency() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(clock = clock, escapes = escapes)

        store.send(Action.FailureChanged(Failure.unreachable)) { it.copy(failure = Failure.unreachable) }
        store.send(Action.LatencyChanged(Latency.slow)) { it.copy(latency = Latency.slow) }
        store.send(Action.LatencyChanged(Latency.slow))
        store.send(Action.ControlTapped(Control.scan)) { it.copy(phase = Phase.waiting) }
        clock.advance(4900.milliseconds)
        clock.advance(100.milliseconds)
        store.receive(Action.PairingFailed(Failure.unreachable)) { it.copy(phase = Phase.failed, failedWith = Failure.unreachable) }

        // Try Again asks the same simulated server, which still turns it down.
        store.send(Action.TryAgainTapped) { it.copy(phase = Phase.waiting) }
        store.send(Action.FailureChanged(null)) { it.copy(failure = null) }
        clock.advance(5.seconds)
        store.receive(Action.PairingFailed(Failure.unreachable)) { it.copy(phase = Phase.failed) }
        store.finish()

        assertEquals(listOf("selection", "selection", "tap", "error", "tap", "selection", "error"), escapes.haptics.value)
    }

    @Test fun everyPhaseButtonInterruptsAPendingReply() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(clock = clock, escapes = escapes)

        store.send(Action.ControlTapped(Control.scan)) { it.copy(phase = Phase.waiting) }
        clock.advance(1.seconds)
        // Back to the welcome mid-wait: the reply never arrives.
        store.send(Action.ControlTapped(Control.welcome)) { it.copy(phase = Phase.welcome) }
        clock.advance(5.seconds)

        store.send(Action.ControlTapped(Control.scan)) { it.copy(phase = Phase.waiting) }
        // Reply now: the heartbeat comes at once and the timer's reply is dropped.
        store.send(Action.ControlTapped(Control.reply)) { it.copy(phase = Phase.connected, heartbeats = 1) }
        clock.advance(5.seconds)

        store.send(Action.ControlTapped(Control.scan)) { it.copy(phase = Phase.waiting) }
        store.send(Action.CancelTapped) { it.copy(phase = Phase.camera) }
        clock.advance(5.seconds)

        store.send(Action.ControlTapped(Control.firstSession)) { it.copy(phase = Phase.session) }
        store.send(Action.ControlTapped(Control.fail)) { it.copy(phase = Phase.failed, failedWith = Failure.unreachable) }
        store.send(Action.ControlTapped(Control.story)) { it.copy(phase = Phase.story) }
        store.send(Action.ControlTapped(Control.camera)) { it.copy(phase = Phase.camera) }
        store.finish()

        assertEquals(listOf("tap", "tap", "tap", "success", "tap", "cancel", "tap", "error", "tap", "tap"), escapes.haptics.value)
    }

    @Test fun buttonsForAnotherPageDoNothing() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(clock = clock, escapes = escapes)

        store.send(Action.ConnectTapped)
        store.send(Action.SampleCodeTapped)
        store.send(Action.CancelTapped)
        store.send(Action.TryAgainTapped)
        store.send(Action.StartSessionTapped)
        store.send(Action.BackTapped)
        store.send(Action.ServerReplied)
        store.send(Action.PairingFailed(Failure.wrongCode))
        store.send(Action.ReplayStepped)
        store.finish()
        assertTrue(escapes.haptics.value.isEmpty())
    }

    @Test fun replayWalksTheWholeFlowFromTheWelcome() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(State(variant = Variant.echoHandshake, phase = Phase.failed), clock = clock, escapes = escapes)

        store.send(Action.ReplayTapped) { it.copy(phase = Phase.welcome, isReplaying = true) }
        clock.advance(1.6.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.story) }
        clock.advance(1.8.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.camera) }
        clock.advance(2.4.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.waiting) }
        clock.advance(1.5.seconds)
        store.receive(Action.ServerReplied) { it.copy(phase = Phase.connected, heartbeats = 1) }
        clock.advance(1.6.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.session, isReplaying = false) }
        store.finish()
        assertEquals(listOf("tap", "success"), escapes.haptics.value)
    }

    @Test fun anyButtonStopsReplay() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(clock = clock, escapes = escapes)

        store.send(Action.ReplayTapped) { it.copy(isReplaying = true) }
        clock.advance(1.6.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.story) }
        store.send(Action.ControlTapped(Control.fail)) { it.copy(phase = Phase.failed, isReplaying = false) }
        clock.advance(10.seconds)

        // A failure set on the server ends a replay at the failure page.
        store.send(Action.FailureChanged(Failure.wrongCode)) { it.copy(failure = Failure.wrongCode) }
        store.send(Action.ReplayTapped) { it.copy(phase = Phase.welcome, isReplaying = true) }
        clock.advance(1.6.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.story) }
        clock.advance(1.8.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.camera) }
        clock.advance(2.4.seconds)
        store.receive(Action.ReplayStepped) { it.copy(phase = Phase.waiting) }
        clock.advance(1.5.seconds)
        store.receive(Action.PairingFailed(Failure.wrongCode)) {
            it.copy(phase = Phase.failed, failedWith = Failure.wrongCode, isReplaying = false)
        }
        store.finish()
    }

    @Test fun theVariantChangesInPlace() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(State(phase = Phase.connected, heartbeats = 2), clock = clock, escapes = escapes)

        store.send(Action.VariantChanged(Variant.quietRelay)) { it.copy(variant = Variant.quietRelay) }
        store.send(Action.VariantChanged(Variant.quietRelay))
        store.send(Action.VariantChanged(Variant.echoHandshake)) { it.copy(variant = Variant.echoHandshake) }
        store.finish()
        assertEquals(listOf("selection", "selection"), escapes.haptics.value)
    }

    @Test fun hidingTheControlsIsQuietAndClosingDismissesAndStopsEverything() = runTest {
        val clock = TestClock()
        val escapes = Escapes()
        val store = makeStore(clock = clock, escapes = escapes)

        store.send(Action.ControlsHiddenChanged(true)) { it.copy(controlsHidden = true) }
        store.send(Action.ControlsHiddenChanged(false)) { it.copy(controlsHidden = false) }
        store.send(Action.ControlTapped(Control.scan)) { it.copy(phase = Phase.waiting) }
        store.send(Action.CloseTapped)
        clock.advance(5.seconds)
        // Nothing keeps running once closed.
        store.finish()
        assertEquals(listOf("tap", "tap"), escapes.haptics.value)
    }
}
