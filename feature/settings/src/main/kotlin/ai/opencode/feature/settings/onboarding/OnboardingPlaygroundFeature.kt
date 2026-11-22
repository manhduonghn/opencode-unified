package ai.opencode.feature.settings.onboarding

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodePairingLink
import android.Manifest
import android.content.pm.PackageManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Settings → Advanced → Developer → Playgrounds → Onboarding: the onboarding's pages and their
 * motion in a full-screen sandbox. The camera is real when the app already has access, otherwise
 * simulated; the server is simulated by a timer. Nothing here reaches a server, the saved servers,
 * the keychain or a persisted setting.
 *
 * [Action.CloseTapped] cancels the playground's effects; the view then calls its `onClose`, which
 * sends the parent's `PresentationAction.Dismiss`.
 */
class OnboardingPlaygroundFeature : Reducer<OnboardingPlaygroundFeature.State, OnboardingPlaygroundFeature.Action> {
    /** The three treatments, in option order. */
    enum class Variant(val title: String, val shortTitle: String) {
        signalSweep("Signal Sweep", "Sweep"),
        quietRelay("Quiet Relay", "Relay"),
        echoHandshake("Echo Handshake", "Echo"),
        ;

        /**
         * The heartbeat wipes into the first session; Quiet Relay hands its signal to the
         * session header and cross-fades instead.
         */
        val wipes: Boolean get() = this != quietRelay
    }

    /** One page of the onboarding. */
    enum class Phase { welcome, story, camera, waiting, connected, failed, session }

    /** How the simulated server turns a code down. */
    enum class Failure(val title: String) {
        wrongCode("Wrong Code"),
        unreachable("Unreachable"),
    }

    /** How long the simulated server takes to answer. */
    enum class Latency(val seconds: Double, val title: String) {
        quick(0.3, "0.3 s"),
        normal(1.5, "1.5 s"),
        slow(5.0, "5 s"),
    }

    /** The control bar's phase buttons. Each interrupts whatever is running. */
    enum class Control(val title: String) {
        welcome("Welcome"),
        story("Story"),
        camera("Camera"),
        scan("Scan"),
        reply("Reply"),
        firstSession("First Session"),
        fail("Fail"),
    }

    enum class CameraFeed {
        /** The app already has camera access: the real preview. */
        live,
        /** No access, or no camera: a drawn stand-in. */
        simulated,
    }

    data class State(
        val variant: Variant = Variant.signalSweep,
        val phase: Phase = Phase.welcome,
        val latency: Latency = Latency.normal,
        /** What the simulated server does with the next code: null answers it. */
        val failure: Failure? = null,
        /** Why the last attempt failed, for the failure page. */
        val failedWith: Failure = Failure.unreachable,
        /** Every reply from the server so far; each one is a heartbeat. */
        val heartbeats: Int = 0,
        /** Replay is walking the flow on its own. */
        val isReplaying: Boolean = false,
        val controlsHidden: Boolean = false,
        val cameraFeed: CameraFeed = CameraFeed.simulated,
    )

    sealed interface Action {
        data object Task : Action
        data class ControlTapped(val control: Control) : Action
        data class VariantChanged(val variant: Variant) : Action
        data class LatencyChanged(val latency: Latency) : Action
        data class FailureChanged(val failure: Failure?) : Action
        data object ReplayTapped : Action
        data class ControlsHiddenChanged(val hidden: Boolean) : Action
        data object CloseTapped : Action

        // The onboarding's own buttons.
        data object GetStartedTapped : Action
        data object ConnectTapped : Action
        data object BackTapped : Action
        /** The live camera read a QR code. It's only decoded, never redeemed or kept. */
        data class CodeScanned(val text: String) : Action
        data object SampleCodeTapped : Action
        data object CancelTapped : Action
        data object TryAgainTapped : Action
        data object ScanAgainTapped : Action
        data object StartSessionTapped : Action

        // The simulated server and Replay.
        data object ServerReplied : Action
        data class PairingFailed(val failure: Failure) : Action
        data object ReplayStepped : Action
    }

    private enum class CancelID { pairing, replay }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(
            state.copy(cameraFeed = if (dependency(OnboardingCameraClient).isAuthorized()) CameraFeed.live else CameraFeed.simulated),
        )

        is Action.ControlTapped -> {
            val (interrupted, cancel) = interrupt(state)
            val next = when (action.control) {
                Control.welcome -> show(Phase.welcome, interrupted, Haptic.tap)
                Control.story -> show(Phase.story, interrupted, Haptic.tap)
                Control.camera -> show(Phase.camera, interrupted, Haptic.tap)
                Control.scan -> startPairing(interrupted, decodes = true).let { Reduced(it.state, Effect.merge(play(Haptic.tap), it.effect)) }
                Control.reply -> reply(interrupted)
                Control.firstSession -> show(Phase.session, interrupted, Haptic.tap)
                Control.fail -> fail(interrupted, interrupted.failure ?: Failure.unreachable)
            }
            Reduced(next.state, Effect.merge(cancel, next.effect))
        }

        is Action.VariantChanged ->
            if (state.variant == action.variant) Reduced(state) else Reduced(state.copy(variant = action.variant), play(Haptic.selection))

        is Action.LatencyChanged ->
            if (state.latency == action.latency) Reduced(state) else Reduced(state.copy(latency = action.latency), play(Haptic.selection))

        is Action.FailureChanged ->
            if (state.failure == action.failure) Reduced(state) else Reduced(state.copy(failure = action.failure), play(Haptic.selection))

        Action.ReplayTapped -> {
            val (interrupted, cancel) = interrupt(state)
            Reduced(
                interrupted.copy(phase = Phase.welcome, isReplaying = true),
                Effect.merge(cancel, play(Haptic.tap), scheduleReplayStep(after = Phase.welcome)),
            )
        }

        is Action.ControlsHiddenChanged ->
            if (state.controlsHidden == action.hidden) {
                Reduced(state)
            } else {
                // Showing the controls is a control acting; tucking them away stays quiet.
                Reduced(state.copy(controlsHidden = action.hidden), if (action.hidden) Effect.none() else play(Haptic.tap))
            }

        Action.CloseTapped -> Reduced(state, Effect.merge(Effect.cancel(CancelID.pairing), Effect.cancel(CancelID.replay)))

        Action.GetStartedTapped ->
            if (state.phase != Phase.welcome) Reduced(state) else interrupting(state) { show(Phase.story, it, Haptic.tap) }

        Action.ConnectTapped ->
            if (state.phase != Phase.story) Reduced(state) else interrupting(state) { show(Phase.camera, it, Haptic.tap) }

        Action.BackTapped -> {
            val back: Phase? = when (state.phase) {
                Phase.welcome -> null
                Phase.story -> Phase.welcome
                Phase.camera -> Phase.story
                Phase.waiting, Phase.connected, Phase.failed -> Phase.camera
                Phase.session -> Phase.connected
            }
            if (back == null) Reduced(state) else interrupting(state) { show(back, it, null) }
        }

        is Action.CodeScanned ->
            if (state.phase != Phase.camera) {
                Reduced(state)
            } else {
                val decodes = OpenCodePairingLink.decode(action.text) != null
                interrupting(state) { startPairing(it, decodes) }
            }

        Action.SampleCodeTapped ->
            if (state.phase != Phase.camera) {
                Reduced(state)
            } else {
                interrupting(state) { startPairing(it, decodes = true).let { r -> Reduced(r.state, Effect.merge(play(Haptic.tap), r.effect)) } }
            }

        Action.CancelTapped ->
            if (state.phase != Phase.waiting) Reduced(state) else interrupting(state) { show(Phase.camera, it, Haptic.cancel) }

        Action.TryAgainTapped ->
            if (state.phase != Phase.failed) {
                Reduced(state)
            } else {
                interrupting(state) { startPairing(it, decodes = true).let { r -> Reduced(r.state, Effect.merge(play(Haptic.tap), r.effect)) } }
            }

        Action.ScanAgainTapped ->
            if (state.phase != Phase.failed && state.phase != Phase.connected) {
                Reduced(state)
            } else {
                interrupting(state) { show(Phase.camera, it, Haptic.tap) }
            }

        Action.StartSessionTapped ->
            if (state.phase != Phase.connected) Reduced(state) else interrupting(state) { show(Phase.session, it, Haptic.tap) }

        Action.ServerReplied ->
            if (state.phase != Phase.waiting) {
                Reduced(state)
            } else {
                val beat = reply(state)
                if (!beat.state.isReplaying) beat else Reduced(beat.state, Effect.merge(beat.effect, scheduleReplayStep(after = Phase.connected)))
            }

        is Action.PairingFailed ->
            if (state.phase != Phase.waiting) Reduced(state) else fail(state.copy(isReplaying = false), action.failure)

        Action.ReplayStepped -> replayStepped(state)
    }

    private fun replayStepped(state: State): Reduced<State, Action> {
        if (!state.isReplaying) return Reduced(state)
        return when (state.phase) {
            Phase.welcome -> Reduced(state.copy(phase = Phase.story), scheduleReplayStep(after = Phase.story))
            Phase.story -> Reduced(state.copy(phase = Phase.camera), scheduleReplayStep(after = Phase.camera))
            // The reply comes from the simulated server; Replay picks up there.
            Phase.camera -> startPairing(state, decodes = true)
            Phase.connected -> Reduced(state.copy(phase = Phase.session, isReplaying = false))
            Phase.waiting, Phase.failed, Phase.session -> Reduced(state.copy(isReplaying = false))
        }
    }

    /** Stops Replay and any pending reply: every button interrupts. */
    private fun interrupt(state: State): Pair<State, Effect<Action>> =
        state.copy(isReplaying = false) to Effect.merge(Effect.cancel(CancelID.pairing), Effect.cancel(CancelID.replay))

    private inline fun interrupting(state: State, next: (State) -> Reduced<State, Action>): Reduced<State, Action> {
        val (interrupted, cancel) = interrupt(state)
        val reduced = next(interrupted)
        return Reduced(reduced.state, Effect.merge(cancel, reduced.effect))
    }

    private fun show(phase: Phase, state: State, haptic: Haptic?): Reduced<State, Action> =
        Reduced(state.copy(phase = phase), haptic?.let(::play) ?: Effect.none())

    /**
     * A code was found: wait for the simulated server, which answers after the chosen latency
     * unless a failure is set or the code isn't a pairing code.
     */
    private fun startPairing(state: State, decodes: Boolean): Reduced<State, Action> {
        val latency = state.latency.seconds.seconds
        val outcome: Action = when {
            state.failure != null -> Action.PairingFailed(state.failure)
            decodes -> Action.ServerReplied
            else -> Action.PairingFailed(Failure.wrongCode)
        }
        val effect = Effect.run<Action> { send ->
            dependency(Clock).sleep(latency)
            send(outcome)
        }.cancellable(CancelID.pairing, cancelInFlight = true)
        return Reduced(state.copy(phase = Phase.waiting), effect)
    }

    /** The server answered: one heartbeat. */
    private fun reply(state: State): Reduced<State, Action> =
        Reduced(state.copy(phase = Phase.connected, heartbeats = state.heartbeats + 1), play(Haptic.success))

    private fun fail(state: State, failure: Failure): Reduced<State, Action> =
        Reduced(state.copy(phase = Phase.failed, failedWith = failure), play(Haptic.error))

    private fun scheduleReplayStep(after: Phase): Effect<Action> {
        val pause = replayPause(after)
        return Effect.run<Action> { send ->
            dependency(Clock).sleep(pause)
            send(Action.ReplayStepped)
        }.cancellable(CancelID.replay, cancelInFlight = true)
    }

    private fun play(haptic: Haptic): Effect<Action> = Effect.run { dependency(HapticsClient).play(haptic) }

    companion object {
        /** How long Replay rests on each page before the next step. */
        fun replayPause(on: Phase): Duration = when (on) {
            Phase.welcome -> 1600.milliseconds
            Phase.story -> 1800.milliseconds
            Phase.camera -> 2400.milliseconds
            Phase.connected -> 1600.milliseconds
            Phase.waiting, Phase.failed, Phase.session -> Duration.ZERO
        }
    }
}

/**
 * Whether the app may already use the camera. The playground never asks: without access it shows a
 * simulated feed.
 */
class OnboardingCameraClient(val isAuthorized: () -> Boolean) {
    companion object Key : DependencyKey<OnboardingCameraClient> {
        override val liveValue: OnboardingCameraClient = OnboardingCameraClient {
            val context = dependency(AppContext)
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        }
        override val testValue: OnboardingCameraClient = OnboardingCameraClient { false }
    }
}
