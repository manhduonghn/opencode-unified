package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.settle
import ai.opencode.feature.voice.VoiceOpenSessionFeature.Action
import ai.opencode.feature.voice.VoiceOpenSessionFeature.Operation
import ai.opencode.feature.voice.VoiceOpenSessionFeature.Owner
import ai.opencode.feature.voice.VoiceOpenSessionFeature.Record
import ai.opencode.feature.voice.VoiceOpenSessionFeature.Stage
import ai.opencode.feature.voice.VoiceOpenSessionFeature.State
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.JsonRpcID
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.testing.WireFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class VoiceOpenSessionTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val call = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenSession,
        sessionID = "ses_voice", targetSessionID = "ses_target", toolCallID = "tool_open",
    )
    private val ready = State(environment = VoiceOpenSessionFeature.Environment(screen = VoiceOpenSessionFeature.Screen(live = true)))
    private val operation = Operation(id = "tool_open", targetID = "ses_target", generation = 0, stage = Stage.loading, waiters = listOf(call))
    private val target = OpenCodeSession(
        id = "ses_target", serverTitle = "Target", projectID = "other", directory = "/other",
        updatedAt = VoiceFeatureTestSupport.distantPast,
    )

    /** Effects run here, so a test's leftover timer is cancelled rather than waited on. */
    private fun effects(scope: CoroutineScope): CoroutineScope = CoroutineScope(scope.coroutineContext + Job())

    private fun makeStore(
        scope: CoroutineScope,
        clock: TestClock,
        replies: LockIsolated<List<ClientToolResult>>,
        initial: State = ready,
        client: OpenCodeClient,
    ): TestStore<State, Action> = TestStore(initial, VoiceOpenSessionFeature(), scope) {
        this[Clock] = clock
        this[OpenCodeClient] = client
        this[ClientToolsClient] = ClientToolsClient(
            connect = { emptyFlow() },
            reply = { _, result ->
                replies.append(result)
                true
            },
        )
    }

    private fun reply(status: String): ClientToolResult =
        ClientToolResult.failure("{\"ok\":false,\"status\":\"$status\"}")

    // region VoiceOpenSessionTests

    @Test fun openSessionLoadsAnotherProjectsBusySessionThroughTheLiveDecoder() = runBlocking {
        val fixture = WireFixture { WireFixture.Reply.Json(200, OPEN_SESSION_PAYLOAD) }
        val clock = TestClock()
        val replies = LockIsolated(listOf<ClientToolResult>())
        val scope = effects(this)
        try {
            val wireCall = call.copy(toolCallID = null)
            val key = State.key(wireCall)
            val store = makeStore(scope, clock, replies, client = OpenCodeClient.liveValue)
            store.send(Action.Requested(wireCall, fixture.server)) {
                it.copy(
                    records = mapOf(key to Record(Owner.mcp)),
                    active = Operation(id = key, targetID = "ses_target", generation = 0, stage = Stage.loading, waiters = listOf(wireCall)),
                )
            }
            var loaded: OpenCodeSession? = null
            store.receive(
                { action ->
                    val session = (action as? Action.Loaded)?.result?.getOrNull()
                    if (session != null) loaded = session
                    session != null && session.id == "ses_target" && session.projectID == "project_other" &&
                        session.directory == "/work/other-project" && session.parentID == null
                },
                "Loaded(ses_target)",
                timeout = 5.seconds,
            ) { it.copy(active = it.active?.copy(stage = Stage.navigating)) }
            store.receive(Action.Delegate(key, loaded!!))
            assertTrue(replies.value.isEmpty())
            clock.advance(6.seconds)
            store.receive(Action.TimedOut(key)) {
                it.copy(active = null, records = mapOf(key to Record(Owner.mcp, "timed_out")), replies = listOf(wireCall))
            }
            store.receive(Action.Replied(wireCall)) { it.copy(replies = emptyList()) }
            store.finish()
            assertEquals(listOf(reply("timed_out")), replies.value)
            assertEquals(1, fixture.requests.size)
            assertEquals("GET /api/session/ses_target", fixture.requests.first().route)
            assertNull(fixture.requests.first().url.query)
        } finally {
            fixture.close()
            scope.cancel()
        }
    }

    // endregion

    // region VoiceOpenSessionAcknowledgementTests

    @Test fun lookupFailuresAreReturned() = runTest {
        for (status in listOf(404, 503)) {
            val replies = LockIsolated(listOf<ClientToolResult>())
            val error = OpenCodeClientError("Lookup failed", statusCode = status)
            val result = if (status == 404) "not_found" else "failed"
            val scope = effects(this)
            val store = makeStore(scope, TestClock(), replies, client = OpenCodeClient.testValue.copy(getSession = { _, _ -> throw error }))
            store.send(Action.Requested(call, server)) {
                it.copy(active = operation, records = mapOf("tool_open" to Record(Owner.mcp)))
            }
            store.receive(Action.Loaded("tool_open", Result.failure(error))) {
                it.copy(active = null, records = mapOf("tool_open" to Record(Owner.mcp, result)), replies = listOf(call))
            }
            store.receive(Action.Replied(call)) { it.copy(replies = emptyList()) }
            // Terminal failures also suppress future SSE replays for the same ID.
            store.send(Action.Fallback(toolID = "tool_open", targetID = "ses_target", server = server))
            store.finish()
            settle()
            assertEquals(listOf(reply(result)), replies.value)
            scope.cancel()
        }
    }

    @Test fun missingNavigationTimesOutAndIgnoresLateLookup() = runTest {
        val clock = TestClock()
        val scope = effects(this)
        val store = makeStore(scope, clock, LockIsolated(listOf()), client = OpenCodeClient.testValue.copy(getSession = { _, _ -> target }))
        store.send(Action.Requested(call, server)) {
            it.copy(active = operation, records = mapOf("tool_open" to Record(Owner.mcp)))
        }
        store.receive(Action.Loaded("tool_open", Result.success(target))) { it.copy(active = it.active?.copy(stage = Stage.navigating)) }
        store.receive(Action.Delegate("tool_open", target))
        clock.advance(6.seconds)
        store.receive(Action.TimedOut("tool_open")) {
            it.copy(active = null, records = mapOf("tool_open" to Record(Owner.mcp, "timed_out")), replies = listOf(call))
        }
        store.receive(Action.Replied(call)) { it.copy(replies = emptyList()) }
        store.send(Action.Loaded("tool_open", Result.success(target)))
        store.send(Action.Fallback(toolID = "tool_open", targetID = target.id, server = server))
        store.finish()
        scope.cancel()
    }

    @Test fun closedSocketCancelsLookupAndFinishesTheID() = runTest {
        val cancelled = LockIsolated(false)
        val scope = effects(this)
        val client = OpenCodeClient.testValue.copy(
            getSession = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled.update { true }
                }
            },
        )
        val store = makeStore(scope, TestClock(), LockIsolated(listOf()), client = client)
        store.send(Action.Requested(call, server)) {
            it.copy(active = operation, records = mapOf("tool_open" to Record(Owner.mcp)))
        }
        store.send(Action.ConnectionClosed(connection)) {
            it.copy(active = null, records = mapOf("tool_open" to Record(Owner.mcp, "cancelled")), replies = listOf(call))
        }
        store.receive(Action.Replied(call)) { it.copy(replies = emptyList()) }
        store.send(Action.Fallback(toolID = "tool_open", targetID = "ses_target", server = server))
        store.finish()
        settle()
        assertTrue(cancelled.value)
        scope.cancel()
    }

    // endregion

    private companion object {
        /**
         * Same response shape as the reported top-level, busy session in another project.
         * Identifiers, title, model and directory are synthetic.
         */
        val OPEN_SESSION_PAYLOAD = """
            {"data":{"id":"ses_target","projectID":"project_other","agent":"build",
            "model":{"id":"model","providerID":"provider","variant":"high"},
            "cost":755.010638000001,"tokens":{"input":28073466,"output":2072070,
            "reasoning":782865,"cache":{"read":1127606976,"write":87903149}},
            "outcome":"succeeded","time":{"created":1790678006275,"updated":1793726943216,
            "busy":1793726904392,"idle":1793726879330,"viewed":1793554079330},
            "title":"Other Project","permissions":[{"action":"browser","resource":"*","effect":"allow"}],
            "location":{"directory":"/work/other-project"}}}
        """.trimIndent()
    }
}
