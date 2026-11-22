package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceOpenSessionFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.ClientToolsTarget
import ai.opencode.server.clienttools.JsonRpcID
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Non-exhaustive: these assert navigation and transport outcomes only. */
class VoiceOpenSessionRaceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private val root = session("ses_root", directory = "/root")
    private val target = session("ses_target", directory = "/target")
    private val other = session("ses_other", directory = "/other")

    private fun session(id: String, directory: String) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = directory, updatedAt = Instant.parse("0001-01-01T00:00:00Z"),
    )

    private fun initial(attached: Boolean = false, sessions: List<OpenCodeSession> = listOf(root, target, other)): AppFeature.State = shared.run {
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet()))
        val voice = VoiceFeature.State(server, root).withPhase(VoiceFeature.State.Phase.Live).copy(usesSessionDelegator = true)
        val detail = SessionFeature.State.session(server, root).copy(voice = voice)
        val app = AppFeature.State.initial().copy(home = home.copy(sessionDetail = detail))
        app.copy(
            clientTools = app.clientTools.copy(
                target = app.clientToolsTarget,
                status = if (attached) ClientToolsFeature.State.Status.Attached else ClientToolsFeature.State.Status.Connecting,
                connection = if (attached) UUID.randomUUID() else null,
            ),
        )
    }

    private fun voiceAction(action: VoiceFeature.Action): AppFeature.Action =
        AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(action))))

    private fun sse(id: String = "tool_open"): AppFeature.Action = voiceAction(
        VoiceFeature.Action.SessionDelegatorReconciledTools(
            listOf(
                OpenCodeRunMessage.ToolCall(
                    id = id, name = "ios_voice_open_session",
                    input = OpenCodeJSONValue.Object(mapOf("session_id" to OpenCodeJSONValue.Str(target.id))),
                ),
            ),
        ),
    )

    private fun call(id: String = "tool_open"): ClientToolCall = ClientToolCall(
        connection = UUID.randomUUID(), id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenSession,
        sessionID = root.id, targetSessionID = target.id, toolCallID = id,
    )

    private fun toolCalled(call: ClientToolCall): AppFeature.Action =
        AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(call)))

    private fun store(
        state: AppFeature.State,
        effects: kotlinx.coroutines.CoroutineScope,
        clock: TestClock = TestClock(),
        getSession: suspend (OpenCodeServer, String) -> OpenCodeSession,
        reply: suspend (ClientToolCall, ClientToolResult) -> Boolean = { _, _ -> true },
    ): TestStore<AppFeature.State, AppFeature.Action> {
        val store = shared.appStore(state, effects) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(now)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = getSession,
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, familyRoot -> OpenCodeSessionFamily(sessions = listOf(familyRoot), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
            this[ClientToolsClient] = ClientToolsClient(connect = { emptyFlow() }, reply = reply)
        }
        store.exhaustive = false
        return store
    }

    /**
     * Reduces what effects sent until [condition] holds (TCA's exhaustivity-off store reduces them as
     * they arrive; this `TestStore` reduces them only when asked).
     */
    private suspend fun TestStore<AppFeature.State, AppFeature.Action>.settleUntil(condition: () -> Boolean) {
        for (attempt in 0 until 500) {
            skipReceivedActions()
            if (condition()) return
            delay(10)
        }
        throw AssertionError("Timed out waiting for an effect")
    }

    @Test fun navigationToolNeedsNoExperimentAndPinsBothReplySockets() {
        val current = session("ses_current", directory = "/current")
        var app = shared.run {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(current), activeSessionIDs = emptySet()))
            val base = VoiceFeature.State(server, current).withPhase(VoiceFeature.State.Phase.Live).copy(usesSessionDelegator = true)
            val voice = base.copy(sessionDelegator = base.sessionDelegator.copy(activeSessionID = "ses_voice"))
            val detail = SessionFeature.State.session(server, current).copy(voice = voice)
            AppFeature.State.initial().copy(
                home = home.copy(sessionDetail = detail),
                voiceDelegatorLocation = ClientToolsLocation(sessionID = "ses_voice", directory = "/voice"),
            )
        }
        val primary = ClientToolsTarget(server = server, directory = "/current", tools = setOf(ClientTool.voiceOpenSession))
        val delegator = ClientToolsTarget(server = server, directory = "/voice", tools = setOf(ClientTool.voiceOpenSession))
        assertTrue(app.clientTools.tools.isEmpty())
        assertEquals(primary, app.clientToolsTarget)
        assertEquals(delegator, app.delegatorClientToolsTarget)
        val operation = VoiceOpenSessionFeature.Operation(
            id = "tool_open", targetID = target.id, generation = 0, stage = VoiceOpenSessionFeature.Stage.loading,
        )
        app = app.copy(
            clientTools = app.clientTools.copy(target = primary),
            delegatorClientTools = app.delegatorClientTools.copy(target = delegator),
            voiceOpenSession = app.voiceOpenSession.copy(active = operation),
        )
        app = app.copy(
            home = app.home?.let { home ->
                val detail = home.sessionDetail
                val voice = detail?.voice
                home.copy(sessionDetail = detail?.copy(voice = voice?.copy(target = voice.target.copy(directory = "/voice"))))
            },
        )
        assertEquals(primary, app.clientToolsTarget)
        assertEquals(delegator, app.delegatorClientToolsTarget)
        app = app.copy(voiceOpenSession = app.voiceOpenSession.copy(active = null, replies = listOf(call())))
        assertEquals(delegator, app.delegatorClientToolsTarget)
        app = app.copy(voiceOpenSession = app.voiceOpenSession.copy(replies = emptyList()))
        assertEquals("/voice", app.clientToolsTarget?.directory)
        assertEquals(null, app.delegatorClientToolsTarget)
    }

    @Test fun timedOutMCPThenManualMoveNeverOpensALateSSELookup() = runBlocking {
        val clock = TestClock()
        val gates = Locked<List<CompletableDeferred<Unit>>>(emptyList())
        val started = Channel<Unit>(Channel.UNLIMITED)
        val call = call()
        // A pushed child keeps the root's voice effect scope alive.
        val state = initial(attached = true, sessions = listOf(root, target, other.copy(parentID = root.id)))
        val effects = detachedScope(coroutineContext)
        val store = store(state, effects, clock, getSession = { _, _ ->
            val gate = CompletableDeferred<Unit>()
            gates.update { it + gate }
            started.send(Unit)
            gate.await()
            target
        })
        store.send(sse())
        store.send(toolCalled(call))
        store.settleUntil { started.tryReceive().isSuccess }
        clock.advance(6.seconds)
        store.receive(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Replied(call)))
        store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(other.id)))
        store.receive(voiceAction(VoiceFeature.Action.TargetChanged))
        for (gate in gates.value) gate.complete(Unit)
        store.finish()
        store.skipReceivedActions()
        assertEquals("A terminal open must not steal the screen later", other.id, store.state.home?.topDetail?.session?.id)
        assertEquals("Attached MCP owns the ID; SSE must not start a lookup", 1, gates.value.size)
        effects.skipInFlightEffects()
    }

    @Test fun backgroundRefusalAlsoRefusesTheSSEPath() = runBlocking {
        val state = initial().copy(isInForeground = false)
        val lookups = Locked(0)
        val effects = detachedScope(coroutineContext)
        val store = store(state, effects, getSession = { _, _ ->
            lookups.update { it + 1 }
            target
        })
        store.send(toolCalled(call()))
        store.send(sse())
        store.finish()
        assertEquals(0, lookups.value)
        assertEquals(root.id, store.state.home?.topDetail?.session?.id)
        effects.skipInFlightEffects()
    }

    @Test fun SSEOpenPinsSocketsUntilTheDrawerArrives() = runBlocking {
        val state = initial().let { it.copy(home = it.home?.copy(isDrawerOpen = true)) }
        val original = state.clientToolsTarget
        val effects = detachedScope(coroutineContext)
        val store = store(state, effects, getSession = { _, _ -> target })
        store.send(sse())
        store.receive(voiceAction(VoiceFeature.Action.TargetChanged))
        assertEquals(target.id, store.state.home?.topDetail?.session?.id)
        assertEquals("SSE must retain the reply socket across directories", original, store.state.clientToolsTarget)
        store.send(AppFeature.Action.Home(HomeFeature.Action.SessionDrawerArrived(isOpen = false)))
        store.finish()
        assertEquals("opened", store.state.voiceOpenSession.records["tool_open"]?.outcome)
        effects.skipInFlightEffects()
    }

    @Test fun aManualMoveInvalidatesAnUnattachedSSELookup() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val state = initial(sessions = listOf(root, target, other.copy(parentID = root.id)))
        val effects = detachedScope(coroutineContext)
        val store = store(state, effects, getSession = { _, _ ->
            started.send(Unit)
            gate.await()
            target
        })
        store.send(sse())
        store.settleUntil { started.tryReceive().isSuccess }
        store.send(AppFeature.Action.Home(HomeFeature.Action.SessionTapped(other.id)))
        store.receive(voiceAction(VoiceFeature.Action.TargetChanged))
        gate.complete(Unit)
        store.finish()
        store.skipReceivedActions()
        // Even a transport delivering a result after cancellation cannot reopen it.
        store.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Loaded("tool_open", Result.success(target))))
        assertEquals(other.id, store.state.home?.topDetail?.session?.id)
        assertEquals("cancelled", store.state.voiceOpenSession.records["tool_open"]?.outcome)
        effects.skipInFlightEffects()
    }

    @Test fun busyIsTerminalForBothTransports() = runBlocking {
        for (sseFirst in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val started = Channel<Unit>(Channel.UNLIMITED)
            val lookups = Locked(0)
            val replies = Locked<List<ClientToolResult>>(emptyList())
            val second = call("tool_busy")
            val effects = detachedScope(coroutineContext)
            val store = store(
                initial(), effects,
                getSession = { _, _ ->
                    lookups.update { it + 1 }
                    started.send(Unit)
                    gate.await()
                    target
                },
                reply = { _, reply ->
                    replies.update { it + reply }
                    true
                },
            )
            store.send(sse())
            store.settleUntil { started.tryReceive().isSuccess }
            if (sseFirst) store.send(sse("tool_busy"))
            store.send(toolCalled(second))
            store.receive(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Replied(second)))
            if (!sseFirst) store.send(sse("tool_busy"))
            gate.complete(Unit)
            store.finish()
            store.skipReceivedActions()
            assertEquals(1, lookups.value)
            assertEquals("busy", store.state.voiceOpenSession.records["tool_busy"]?.outcome)
            assertEquals(listOf(ClientToolResult.failure("{\"ok\":false,\"status\":\"busy\"}")), replies.value)
            assertEquals(1, store.state.home?.drawerCloseRequests)
            effects.skipInFlightEffects()
        }
    }

    @Test fun MCPJoiningSSEKeepsBothSocketsUntilItsReplyIsSent() = runBlocking {
        val lookup = CompletableDeferred<Unit>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val replying = Channel<Unit>(Channel.UNLIMITED)
        val replyGate = CompletableDeferred<Unit>()
        val lookups = Locked(0)
        val target = session("ses_target", directory = "/voice")
        val call = call()
        var state = initial(sessions = listOf(root, target, other))
        state = state.copy(
            home = state.home?.let { home ->
                val detail = home.sessionDetail
                val voice = detail?.voice
                home.copy(
                    sessionDetail = detail?.copy(
                        voice = voice?.copy(sessionDelegator = voice.sessionDelegator.copy(activeSessionID = "ses_voice")),
                    ),
                )
            },
            voiceDelegatorLocation = ClientToolsLocation(sessionID = "ses_voice", directory = "/voice"),
        )
        state = state.copy(delegatorClientTools = state.delegatorClientTools.copy(target = state.delegatorClientToolsTarget))
        val primary = state.clientToolsTarget
        val delegator = state.delegatorClientToolsTarget
        val effects = detachedScope(coroutineContext)
        val store = store(
            state, effects,
            getSession = { _, _ ->
                lookups.update { it + 1 }
                started.send(Unit)
                lookup.await()
                target
            },
            reply = { _, _ ->
                replying.send(Unit)
                replyGate.await()
                true
            },
        )
        store.send(sse())
        store.settleUntil { started.tryReceive().isSuccess }
        store.send(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(call))))
        lookup.complete(Unit)
        // Acknowledgement waits for the drawer only when it was open; here the reply goes out.
        store.settleUntil { replying.tryReceive().isSuccess }
        store.skipReceivedActions()
        assertEquals(target.id, store.state.home?.topDetail?.session?.id)
        assertEquals(VoiceOpenSessionFeature.Owner.sse, store.state.voiceOpenSession.records["tool_open"]?.owner)
        assertEquals("opened", store.state.voiceOpenSession.records["tool_open"]?.outcome)
        assertEquals(primary, store.state.clientToolsTarget)
        assertEquals(delegator, store.state.delegatorClientToolsTarget)
        assertEquals(1, lookups.value)
        replyGate.complete(Unit)
        store.receive(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Replied(call)))
        store.finish()
        store.skipReceivedActions()
        assertFalse(store.state.voiceOpenSession.inFlight)
        assertEquals("/voice", store.state.clientToolsTarget?.directory)
        assertEquals(null, store.state.delegatorClientToolsTarget)
        effects.skipInFlightEffects()
    }
}
