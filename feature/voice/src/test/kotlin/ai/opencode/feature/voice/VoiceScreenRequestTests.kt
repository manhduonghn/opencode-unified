package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.TestStore
import ai.opencode.core.store.ifLet
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.VoiceFeatureTestSupport.EventStream
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.increment
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receiveEnded
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.settle
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceScreenRequestFeature.Action
import ai.opencode.feature.voice.VoiceScreenRequestFeature.Refusal
import ai.opencode.feature.voice.VoiceScreenRequestFeature.State
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.JsonRpcID
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class VoiceScreenRequestTests {
    @get:Rule val isolation = TestIsolationRule()

    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val call = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(1), tool = ClientTool.voiceRequestScreen,
        sessionID = "ses_voice", reason = "To check the layout.",
    )
    private val capture = VoiceScreenshotClient.Capture(
        jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
        screen = "Session",
        appearance = VoiceAppState.Appearance(style = "dark", dynamicType = "L"),
    )

    private fun makeStore(
        scope: CoroutineScope,
        clock: TestClock,
        replies: LockIsolated<List<ClientToolResult>>,
        haptics: LockIsolated<List<Haptic>>,
        delivers: Boolean = true,
        capture: suspend () -> VoiceScreenshotClient.Result = { VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow) },
    ): TestStore<State, Action> = TestStore(State(), VoiceScreenRequestFeature(), scope) {
        this[Clock] = clock
        this[HapticsClient] = HapticsClient(play = { haptics.append(it) })
        this[ClientToolsClient] = ClientToolsClient(
            connect = { emptyFlow() },
            reply = { _, result ->
                replies.append(result)
                delivers
            },
        )
        this[VoiceScreenshotClient] = VoiceScreenshotClient(captureShared = capture) {
            VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
        }
    }

    /** Effects run here, so a test's leftover timer is cancelled rather than waited on. */
    private fun effects(scope: CoroutineScope): CoroutineScope = CoroutineScope(scope.coroutineContext + Job())

    @Test fun shareCapturesAfterTheCardLeavesAndReturnsTheImage() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val haptics = LockIsolated(listOf<Haptic>())
        val captures = LockIsolated(0)
        val shot = capture
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, haptics) {
            captures.increment()
            VoiceScreenshotClient.Result.Taken(shot)
        }

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.ShareTapped) { it.copy(pending = null, capturing = call) }
        assertEquals(listOf(Haptic.confirm), haptics.value)
        // The card is gone before the picture is taken.
        clock.advance(349.milliseconds)
        assertEquals(0, captures.value)
        clock.advance(1.milliseconds)
        store.receive(Action.Captured(call, VoiceScreenshotClient.Result.Taken(shot))) { it.copy(capturing = null) }
        // The minute's timer went with the card.
        clock.advance(60.seconds)
        store.finish()

        assertEquals(1, captures.value)
        assertEquals(
            listOf(
                ClientToolResult(
                    listOf(
                        ClientToolResult.Content.Text("The user shared the OpenCode Android app's screen; screen: Session; dark mode."),
                        ClientToolResult.Content.Jpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())),
                    ),
                ),
            ),
            replies.value,
        )
        scope.cancel()
    }

    @Test fun notNowRefusesWithoutCapturing() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val haptics = LockIsolated(listOf<Haptic>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, haptics) {
            fail("Not Now must never capture")
            VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
        }

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.NotNowTapped) { it.copy(pending = null) }
        clock.advance(60.seconds)
        store.finish()

        assertEquals(listOf(Haptic.cancel), haptics.value)
        assertEquals(listOf(ClientToolResult.failure(Refusal.declined)), replies.value)
        scope.cancel()
    }

    @Test fun noAnswerWithinAMinuteTimesOut() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val haptics = LockIsolated(listOf<Haptic>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, haptics)

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        clock.advance(59.seconds)
        assertTrue(replies.value.isEmpty())
        clock.advance(1.seconds)
        store.receive(Action.TimedOut) { it.copy(pending = null) }
        // A late tap on a card that's gone does nothing.
        store.send(Action.ShareTapped)
        store.finish()

        assertTrue(haptics.value.isEmpty())
        assertEquals(listOf(ClientToolResult.failure(Refusal.timedOut)), replies.value)
        scope.cancel()
    }

    @Test fun theCallEndingCancelsTheRequest() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()))

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.CallEnded) { it.copy(pending = null) }
        clock.advance(60.seconds)
        store.finish()

        assertEquals(listOf(ClientToolResult.failure(Refusal.callEnded)), replies.value)
        scope.cancel()
    }

    @Test fun aSecondRequestWhileOneIsUpIsRefused() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()))
        val second = call.copy(id = JsonRpcID.Number(2))

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.Requested(second))
        settle()
        assertEquals(listOf(ClientToolResult.failure(Refusal.busy)), replies.value)
        store.send(Action.CallEnded) { it.copy(pending = null) }
        store.finish()
        scope.cancel()
    }

    @Test fun aRefusedCaptureSaysWhyWithoutAnImage() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val failed = VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.credentials)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf())) { failed }

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.ShareTapped) { it.copy(pending = null, capturing = call) }
        clock.advance(VoiceScreenRequestFeature.SETTLE)
        store.receive(Action.Captured(call, failed)) { it.copy(capturing = null) }
        store.finish()

        assertEquals(listOf(ClientToolResult.failure(Refusal.failed(VoiceScreenshotClient.Failure.credentials))), replies.value)
        scope.cancel()
    }

    @Test fun theSocketClosingTakesTheCardWithoutAReply() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()))

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        // Another socket's going leaves it.
        store.send(Action.ConnectionClosed(UUID.randomUUID()))
        store.send(Action.ConnectionClosed(connection)) { it.copy(pending = null) }
        clock.advance(60.seconds)
        store.finish()

        // The client refused it as the socket closed.
        assertTrue(replies.value.isEmpty())
        scope.cancel()
    }

    @Test fun theSocketClosingDuringTheSettleNeverCaptures() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf())) {
            fail("A dropped request must never capture")
            VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
        }

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.ShareTapped) { it.copy(pending = null, capturing = call) }
        store.send(Action.ConnectionClosed(connection)) { it.copy(capturing = null) }
        clock.advance(1.seconds)
        store.finish()
        settle()

        // Its one answer, which the closed socket's client finds already refused.
        assertEquals(listOf(ClientToolResult.failure(Refusal.callEnded)), replies.value)
        scope.cancel()
    }

    @Test fun aCaptureWhoseReplyFoundNoSocketIsNotNoted() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val shot = capture
        val scope = effects(this)
        val store = makeStore(scope, clock, replies, LockIsolated(listOf()), delivers = false) {
            VoiceScreenshotClient.Result.Taken(shot)
        }

        store.send(Action.Requested(call)) { it.copy(pending = call) }
        store.send(Action.ShareTapped) { it.copy(pending = null, capturing = call) }
        clock.advance(VoiceScreenRequestFeature.SETTLE)
        store.receive(Action.Captured(call, VoiceScreenshotClient.Result.Taken(shot))) { it.copy(capturing = null) }
        store.finish()
        assertEquals(1, replies.value.size)
        scope.cancel()
    }

    @Test fun tearingTheCallDownDuringTheSettleStillAnswers() = runTest {
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        val store = TestStore(HostState(request = State()), screenRequestHost(), scope) {
            this[Clock] = clock
            this[HapticsClient] = HapticsClient()
            this[ClientToolsClient] = ClientToolsClient(
                connect = { emptyFlow() },
                reply = { _, result ->
                    replies.append(result)
                    true
                },
            )
            this[VoiceScreenshotClient] = VoiceScreenshotClient(
                captureShared = {
                    fail("A torn-down call must never capture")
                    VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
                },
            ) { VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow) }
        }

        store.send(HostAction.Request(PresentationAction.Presented(Action.Requested(call)))) {
            it.copy(request = it.request?.copy(pending = call))
        }
        store.send(HostAction.Request(PresentationAction.Presented(Action.ShareTapped))) {
            it.copy(request = it.request?.copy(pending = null, capturing = call))
        }
        store.send(HostAction.Request(PresentationAction.Dismiss)) { it.copy(request = null) }
        clock.advance(1.seconds)
        store.finish()
        settle()
        assertEquals(listOf(ClientToolResult.failure(Refusal.callEnded)), replies.value)
        scope.cancel()
    }

    /** The call's place in the session: presented, so dismissing it cancels its effects. */
    data class HostState(val request: State?)

    sealed interface HostAction {
        data class Request(val action: PresentationAction<Action>) : HostAction
    }

    private fun screenRequestHost(): Reducer<HostState, HostAction> = ifLet(
        id = "request",
        parent = Reducer { state, _ -> Reduced(state) },
        state = { it.request },
        setState = { state, child -> state.copy(request = child) },
        action = { (it as? HostAction.Request)?.action },
        embed = { HostAction.Request(it) },
        child = VoiceScreenRequestFeature(),
    )

    // region VoiceFeatureTests extension

    private lateinit var h: Harness

    private val screenCall = ClientToolCall(
        connection = UUID.fromString("00000000-0000-0000-0000-000000000003"), id = JsonRpcID.Number(1),
        tool = ClientTool.voiceRequestScreen, sessionID = "ses_voice",
    )

    /** A live call (Android asserts what each step changes by hand). */
    private suspend fun startLiveCall(
        configure: DependencyValues.Builder.() -> Unit,
    ): TestStore<VoiceFeature.State, VoiceFeature.Action> {
        val events = EventStream()
        val store = h.store(h.state { VoiceFeature.State(server, session) }) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[Clock] = ImmediateClock()
            voiceCall { copy(start = { _, _ -> events.events }, completeFunction = { _, _ -> }) }
            configure()
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")
        store.send(VoiceFeature.Action.Start)
        events.yield(VoiceCallEvent.Connected)
        store.receive(VoiceFeature.Action.CallEvent::class) { it.live() }
        return store
    }

    private fun recordReplies(replies: LockIsolated<List<ClientToolResult>>) = ClientToolsClient(
        connect = { emptyFlow() },
        reply = { _, result ->
            replies.append(result)
            true
        },
    )

    @Test fun endingTheCallRefusesItsScreenRequest() = voiceTest { harness ->
        h = harness
        val replies = LockIsolated(listOf<ClientToolResult>())
        val store = startLiveCall {
            this[Clock] = TestClock()
            this[ClientToolsClient] = recordReplies(replies)
        }
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.Requested(FloatingCallStage.card)))
        assertEquals(FloatingCallStage.card, store.state.floatingStage.stage)

        store.send(VoiceFeature.Action.ScreenRequest(Action.Requested(screenCall)))
        assertEquals(screenCall, store.state.screenRequest.pending)
        // The floating call's card would cover the request, and stays an orb.
        assertEquals(FloatingCallStage.orb, store.state.floatingStage.stage)
        assertTrue(store.state.floatingStage.isHeld)
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.OrbTapped))
        store.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.Requested(FloatingCallStage.full)))
        assertEquals(FloatingCallStage.orb, store.state.floatingStage.stage)

        // Hung up from the background or the notification: settled in the same pass, so
        // the reply leaves before the app's socket can close.
        store.send(VoiceFeature.Action.EndTapped)
        assertNull(store.state.screenRequest.pending)
        assertFalse(store.state.floatingStage.isHeld)
        store.receiveEnded()
        settle()
        assertEquals(listOf(ClientToolResult.failure(Refusal.callEnded)), replies.value)
    }

    @Test fun theCallsSocketClosingClearsTheCard() = voiceTest { harness ->
        h = harness
        val store = startLiveCall {
            this[Clock] = TestClock()
            this[ClientToolsClient] = ClientToolsClient(
                connect = { emptyFlow() },
                reply = { _, _ ->
                    fail("The client refuses a closed socket's calls")
                    false
                },
            )
        }
        store.send(VoiceFeature.Action.ScreenRequest(Action.Requested(screenCall)))
        assertEquals(screenCall, store.state.screenRequest.pending)
        assertTrue(store.state.floatingStage.isHeld)
        store.send(VoiceFeature.Action.ScreenRequest(Action.ConnectionClosed(screenCall.connection)))
        assertNull(store.state.screenRequest.pending)
        assertFalse(store.state.floatingStage.isHeld)
        store.send(VoiceFeature.Action.EndTapped)
        store.receiveEnded()
    }

    @Test fun sharingSendsThePictureOnlyToTheAgent() = voiceTest { harness ->
        h = harness
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val shot = VoiceScreenshotClient.Capture(jpeg = byteArrayOf(0xFF.toByte()), screen = null, appearance = null)
        val store = startLiveCall {
            this[Clock] = clock
            this[ClientToolsClient] = recordReplies(replies)
            this[VoiceScreenshotClient] = VoiceScreenshotClient(captureShared = { VoiceScreenshotClient.Result.Taken(shot) }) {
                VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow)
            }
        }

        store.send(VoiceFeature.Action.ScreenRequest(Action.Requested(screenCall)))
        assertTrue(store.state.floatingStage.isHeld)
        store.send(VoiceFeature.Action.ScreenRequest(Action.ShareTapped))
        assertNull(store.state.screenRequest.pending)
        assertEquals(screenCall, store.state.screenRequest.capturing)
        assertFalse(store.state.floatingStage.isHeld)
        clock.advance(VoiceScreenRequestFeature.SETTLE)
        store.receive({ it is VoiceFeature.Action.ScreenRequest && it.action is Action.Captured }, "captured")
        assertNull(store.state.screenRequest.capturing)
        assertEquals(1, replies.value.size)
        assertTrue(replies.value.first().content.contains(ClientToolResult.Content.Jpeg(byteArrayOf(0xFF.toByte()))))
        store.send(VoiceFeature.Action.EndTapped)
        store.receiveEnded()
    }

    // endregion
}
