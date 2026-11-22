package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.date
import ai.opencode.feature.voice.VoiceFeatureTestSupport.increment
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceWork
import ai.opencode.feature.voice.VoiceFeatureTestSupport.withTestDependencies
import ai.opencode.feature.voice.VoiceLatencyBudgetTestSupport.ScriptedClock
import ai.opencode.feature.voice.VoiceLatencyBudgetTestSupport.WireFixture
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryEnvironment
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A voice call waits on the delegator's tools and its reply watch, so their network work has one
 * latency budget: optional steps are bounded, required ones share the request policy's window once,
 * and a reply wait's loop is its only retry layer.
 *
 * As, the bounded optional steps run on real time (short slices); the retry windows run on a
 * scripted clock.
 */
class VoiceLatencyBudgetTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val ear = OpenCodeSession(id = "ses_ear", serverTitle = "Fix the earpiece routing", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = date(400))
    private val docs = OpenCodeSession(id = "ses_docs", serverTitle = "Docs refresh", projectID = "web", directory = "/Users/kit/website", updatedAt = date(200))

    /** Short slices so a test that hits one finishes quickly. */
    private val quick = VoiceDelegatorWorkspace.Latency(total = 30.seconds, optional = 200.milliseconds, upload = 200.milliseconds)

    private suspend fun hang(): Nothing = awaitCancellation()

    private fun workspace(
        client: OpenCodeClient,
        viewing: OpenCodeSession? = null,
        draftModel: OpenCodeModelRef? = null,
        viewingModel: OpenCodeModelRef? = null,
        board: VoiceSessionBoard? = null,
        callLog: VoiceCallLog? = null,
        latency: VoiceDelegatorWorkspace.Latency? = null,
        server: OpenCodeServer? = null,
        effects: VoiceDelegatorWorkspace.Effects = VoiceDelegatorWorkspace.Effects(open = { }, explained = { }, note = { }),
    ) = VoiceDelegatorWorkspace(
        client = client,
        start = VoiceDelegatorWorkspace.Start(
            server = server ?: this.server,
            viewing = viewing,
            draftDirectory = "/Users/kit/opencode-ios",
            draftModel = draftModel,
            viewingModel = viewingModel,
            transcriptDelta = emptyList(),
            explained = emptySet(),
            board = { board },
            callLogSnapshot = { callLog },
            latency = latency ?: quick,
        ),
        effects = effects,
    )

    private fun parse(output: String): JsonObject =
        runCatching { Json.parseToJsonElement(output) as JsonObject }.getOrNull() ?: throw AssertionError(output)

    private suspend fun obj(workspace: VoiceDelegatorWorkspace, name: String, arguments: String): JsonObject =
        parse(workspace.run(VoiceDelegator.ToolCall(callID = "c", name = name, arguments = arguments)))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun elapsed(work: suspend () -> Unit): Duration {
        val start = TimeSource.Monotonic.markNow()
        work()
        return start.elapsedNow()
    }

    /** A test container on real time, with the client's transport. */
    private suspend fun <R> real(transport: OpenCodeTransport? = null, block: suspend () -> R): R = withTestDependencies({
        this[Clock] = Clock.liveValue
        this[DateClient] = DateClient.liveValue
        transport?.let { this[OpenCodeTransport] = it }
    }, block)

    /** The tool under the shared policy, on a scripted clock with fixed jitter. */
    private suspend fun <R> scripted(clock: ScriptedClock, transport: OpenCodeTransport, block: suspend () -> R): R = withTestDependencies({
        // The workspace's budget is measured on the dependency clock.
        this[Clock] = clock
        this[DateClient] = DateClient.liveValue
        this[OpenCodeTransport] = transport
    }) {
        NetworkRetry.policy.withValue(RetryPolicy.request) {
            NetworkRetry.clock.withValue(clock) {
                NetworkRetry.environment.withValue(RetryEnvironment.online(1.0)) { block() }
            }
        }
    }

    // MARK: search_sessions

    @Test fun drawerMatchesDontWaitForAHangingServerSearch() = voiceTest {
        val client = OpenCodeClient.testValue.copy(searchSessions = { _, _, _ -> hang() })
        val board = VoiceSessionBoard(
            serverID = server.persistenceID, sessions = listOf(ear, docs), busyIDs = emptySet(), attentionIDs = emptySet(),
            unreadIDs = emptySet(), pinnedIDs = emptyList(), attentionDirectories = emptySet(), isLive = true, attentionKnown = true,
        )
        val tools = workspace(client, board = board)
        var output = ""
        val took = real { elapsed { output = tools.run(VoiceDelegator.ToolCall("c", "search_sessions", """{"query":"earpiece"}""")) } }
        assertTrue(output.contains(ear.id))
        if (System.getenv("CI") != "true") {
            assertTrue("Took $took: the server's search is extra once the drawer matched", took < 2.seconds)
        }
    }

    @Test fun withoutDrawerMatchesBothQueriesShareOneBudget() = voiceTest {
        // The phrase finds nothing after 28 s of the 30 s budget; its fallback
        // term gets the 2 s left (two tries), not a fresh window (five).
        val clock = ScriptedClock()
        val fallbacks = LockIsolated(0)
        val fixture = WireFixture { request ->
            if (request.path != "/api/session") return@WireFixture WireFixture.status(404)
            if (request.queryValue("search") == "the earpiece routing") {
                clock.advance(28.seconds)
                return@WireFixture WireFixture.json(status = 200, body = """{"data":[]}""")
            }
            fallbacks.increment()
            WireFixture.networkConnectionLost
        }
        val board = VoiceSessionBoard(
            serverID = fixture.server.persistenceID, sessions = listOf(docs), busyIDs = emptySet(), attentionIDs = emptySet(),
            unreadIDs = emptySet(), pinnedIDs = emptyList(), attentionDirectories = emptySet(), isLive = true, attentionKnown = true,
        )
        val tools = workspace(OpenCodeClient.liveValue, board = board, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server)
        val output = scripted(clock, fixture.transport) {
            tools.run(VoiceDelegator.ToolCall("c", "search_sessions", """{"query":"the earpiece routing"}"""))
        }
        assertTrue(output, output.contains("Couldn’t search sessions"))
        assertEquals(2, fallbacks.value)
    }

    // MARK: create_session

    @Test fun aChosenModelThatCouldntBeSetIsReportedNotSkipped() = voiceTest {
        val client = OpenCodeClient.testValue.copy(
            createSession = { _, directory, _ -> OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = directory, updatedAt = VoiceFeatureTestSupport.distantPast) },
            switchModel = { _, _, _ -> throw OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500) },
            getSession = { _, _ -> throw OpenCodeClientError(message = "unused") },
        )
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val result = real { obj(workspace(client, draftModel = opus), "create_session", """{"directory":"/Users/kit/opencode-ios"}""") }
        assertEquals("ses_new", result.string("created"))
        assertEquals(true, result.string("model_error")?.contains("model couldn’t be set"))
    }

    @Test fun optionalStepsAroundACreateAreBounded() = voiceTest {
        // The server's default model needs a catalog read, and the fresh read
        // of the new session hangs too. Neither holds it up.
        val switched = LockIsolated(0)
        val client = OpenCodeClient.testValue.copy(
            modelCatalog = { _, _ -> hang() },
            createSession = { _, directory, _ -> OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = directory, updatedAt = VoiceFeatureTestSupport.distantPast) },
            switchModel = { _, _, _ -> switched.increment() },
            getSession = { _, _ -> hang() },
        )
        var result = JsonObject(emptyMap())
        val took = real { elapsed { result = obj(workspace(client, viewing = docs), "create_session", """{"directory":"/Users/kit/website"}""") } }
        assertEquals("ses_new", result.string("created"))
        assertNull(result["model_error"])
        assertEquals("No model was known in time; the server's default stands", 0, switched.value)
        if (System.getenv("CI") != "true") {
            assertTrue("Took $took", took < 2.seconds)
        }
    }

    @Test fun aCreateAndItsModelShareOneBudget() = voiceTest {
        // Creating takes 24.75 s of the 30 s budget (one 12 s timeout, a 0.75 s
        // wait, then 12 s to succeed): the chosen model gets what's left, 5.25 s,
        // so two tries, not the five a fresh window allows.
        val clock = ScriptedClock()
        val creates = LockIsolated(0)
        val models = LockIsolated(0)
        val fixture = WireFixture { request ->
            when (request.route) {
                "POST /api/session" -> {
                    clock.advance(12.seconds)
                    if (creates.increment() == 1) {
                        WireFixture.timedOut
                    } else {
                        WireFixture.json(status = 200, body = """{"data":{"id":"ses_new","title":"New","projectID":"p","location":{"directory":"/p"},"time":{"created":1,"updated":2}}}""")
                    }
                }
                "POST /api/session/ses_new/model" -> {
                    models.increment()
                    clock.advance(4.seconds)
                    WireFixture.timedOut
                }
                else -> WireFixture.status(404)
            }
        }
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val tools = workspace(OpenCodeClient.liveValue, draftModel = opus, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server)
        val output = withTestDependencies({
            this[Clock] = clock
            this[DateClient] = DateClient.liveValue
            this[OpenCodeTransport] = fixture.transport
        }) {
            NetworkRetry.policy.withValue(RetryPolicy.request) {
                NetworkRetry.clock.withValue(clock) {
                    NetworkRetry.environment.withValue(RetryEnvironment.online(0.5)) {
                        tools.run(VoiceDelegator.ToolCall("c", "create_session", """{"directory":"/p"}"""))
                    }
                }
            }
        }
        val result = parse(output)
        assertEquals("ses_new", result.string("created"))
        assertNotNull(result["model_error"])
        assertEquals(2, creates.value)
        assertEquals(2, models.value)
    }

    @Test fun aRefusedChosenModelSendsNothingAndKeepsThePick() = voiceTest {
        val calls = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<OpenCodeSession>())
        val client = OpenCodeClient.testValue.copy(
            createSession = { _, directory, _ -> OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = directory, updatedAt = VoiceFeatureTestSupport.distantPast) },
            switchModel = { _, _, _ -> throw OpenCodeClientError(message = "Unknown model", statusCode = 400) },
            getSession = { _, _ -> throw OpenCodeClientError(message = "unused") },
            sendDeliveredPrompt = { _, id, _, _ ->
                calls.append("prompt $id")
                "msg_1"
            },
        )
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val effects = VoiceDelegatorWorkspace.Effects(
            open = { session -> opened.append(session) },
            keptPick = { id, ref -> calls.append("kept $id ${ref.id}") },
            explained = { },
            note = { },
        )
        val tools = workspace(client, draftModel = opus, effects = effects)
        val result = real { obj(tools, "create_session", """{"directory":"/Users/kit/opencode-ios","text":"Fix the earpiece"}""") }
        assertEquals("ses_new", result.string("created"))
        assertEquals(true, result.string("model_error")?.contains("nothing was sent"))
        assertEquals(listOf("ses_new"), opened.value.map { it.id })
        assertEquals("No prompt on the server's default model", listOf("kept ses_new claude-opus-4-6"), calls.value)
    }

    @Test fun aSetModelShowsOnTheOpenedSessionWithoutAFreshRead() = voiceTest {
        val opened = LockIsolated(listOf<OpenCodeSession>())
        val client = OpenCodeClient.testValue.copy(
            createSession = { _, directory, _ -> OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = directory, updatedAt = VoiceFeatureTestSupport.distantPast) },
            switchModel = { _, _, _ -> },
            getSession = { _, _ -> throw OpenCodeClientError(message = "Offline") },
        )
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val effects = VoiceDelegatorWorkspace.Effects(open = { session -> opened.append(session) }, explained = { }, note = { })
        real { obj(workspace(client, draftModel = opus, effects = effects), "create_session", """{"directory":"/Users/kit/opencode-ios"}""") }
        assertEquals(opus, opened.value.firstOrNull()?.model)
    }

    @Test fun aPickAppliedBeforeASendSharesTheToolsBudget() = voiceTest {
        // The screen's pick takes 28 s of the 30 s budget: the prompt gets the
        // 2 s left (two tries), not a fresh window (five).
        val clock = ScriptedClock()
        val prompts = LockIsolated(0)
        val fixture = WireFixture { request ->
            when (request.route) {
                "POST /api/session/ses_ear/model" -> {
                    clock.advance(28.seconds)
                    WireFixture.status(204)
                }
                "POST /api/session/ses_ear/prompt" -> {
                    prompts.increment()
                    WireFixture.networkConnectionLost
                }
                else -> WireFixture.status(404)
            }
        }
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val applied = LockIsolated(0)
        val effects = VoiceDelegatorWorkspace.Effects(open = { }, appliedPick = { _, _ -> applied.increment() }, explained = { }, note = { })
        val tools = workspace(OpenCodeClient.liveValue, viewing = ear, viewingModel = opus, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server, effects = effects)
        val output = scripted(clock, fixture.transport) { tools.run(VoiceDelegator.ToolCall("c", "send_message", """{"text":"Fix it"}""")) }
        assertTrue(output, output.contains("error"))
        assertEquals(2, prompts.value)
        assertEquals("No prompt carried it, so the pick still waits", 0, applied.value)
    }

    @Test fun aRefusedPickFailsTheSendAndSaysWhy() = voiceTest {
        val prompts = LockIsolated(0)
        val client = OpenCodeClient.testValue.copy(
            switchModel = { _, _, _ -> throw OpenCodeClientError(message = "Unknown model", statusCode = 400) },
            sendDeliveredPrompt = { _, _, _, _ ->
                prompts.increment()
                "msg_1"
            },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        )
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val result = real { obj(workspace(client, viewing = ear, viewingModel = opus), "send_message", """{"text":"Fix it"}""") }
        assertEquals("Couldn’t switch model: Unknown model", result.string("error"))
        assertEquals(0, prompts.value)
    }

    @Test fun aDelegationsModelAndPromptShareOneBudget() = voiceTest {
        // Realtime: the same budget for the pick and the prompt it goes with.
        val clock = ScriptedClock()
        val prompts = LockIsolated(0)
        val fixture = WireFixture { request ->
            when (request.route) {
                "POST /api/session/ses_ear/model" -> {
                    clock.advance(28.seconds)
                    WireFixture.status(204)
                }
                "POST /api/session/ses_ear/prompt" -> {
                    prompts.increment()
                    WireFixture.networkConnectionLost
                }
                else -> WireFixture.status(404)
            }
        }
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        val target = VoiceWorkTarget(server = fixture.server, sessionID = "ses_ear", directory = "/p", model = opus)
        val error = runCatching {
            withTestDependencies({
                this[OpenCodeClient] = OpenCodeClient.liveValue
                this[Clock] = clock
                this[DateClient] = DateClient.liveValue
                this[OpenCodeTransport] = fixture.transport
            }) {
                NetworkRetry.policy.withValue(RetryPolicy.request) {
                    NetworkRetry.clock.withValue(clock) {
                        NetworkRetry.environment.withValue(RetryEnvironment.online(1.0)) {
                            VoiceWorkClient.liveValue.submit(target, "Fix it")
                        }
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue("$error", error is OpenCodeClientError)
        assertEquals(2, prompts.value)
    }

    // MARK: Riding out a drop

    /** The tool under the shared policy, on a scripted clock with fixed jitter. */
    private suspend fun riding(tools: VoiceDelegatorWorkspace, fixture: WireFixture, name: String, arguments: String, clock: ScriptedClock = ScriptedClock()): String =
        scripted(clock, fixture.transport) { tools.run(VoiceDelegator.ToolCall("c", name, arguments)) }

    private val earSession = """{"data":{"id":"ses_ear","title":"Fix the earpiece routing","projectID":"ios","location":{"directory":"/p"},"time":{"created":1,"updated":2}}}"""

    @Test fun aSendRidesOutADroppedConnectionUnderOneID() = voiceTest {
        // The link to the Mac dropped as the delegator sent. The prompt goes again under the same
        // ID once the connection is back.
        val prompts = LockIsolated(0)
        val fixture = WireFixture { request ->
            when (request.route) {
                "POST /api/session/ses_ear/background" -> WireFixture.status(204)
                "POST /api/session/ses_ear/prompt" ->
                    if (prompts.increment() == 1) WireFixture.networkConnectionLost else WireFixture.json(status = 200, body = """{"data":{}}""")
                else -> WireFixture.status(404)
            }
        }
        val output = riding(workspace(OpenCodeClient.liveValue, viewing = ear, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server), fixture, "send_message", """{"text":"Fix it"}""")
        val result = parse(output)
        val sentIDs = fixture.requests.filter { it.route.endsWith("/prompt") }.map { it.json().string("id") ?: throw AssertionError("No id") }
        assertEquals(2, sentIDs.size)
        assertEquals("One ID, which the server admits once", 1, sentIDs.toSet().size)
        assertEquals(sentIDs.firstOrNull(), result.string("message_id"))
    }

    @Test fun aSteersBackgroundMoveIsTriedOnceAndLeavesThePromptItsBudget() = voiceTest {
        // A move that never left isn't retried for 30 s ahead of the steer.
        val moves = LockIsolated(0)
        val fixture = WireFixture { request ->
            when (request.route) {
                "POST /api/session/ses_ear/background" -> {
                    moves.increment()
                    WireFixture.cannotConnectToHost
                }
                "POST /api/session/ses_ear/prompt" -> WireFixture.json(status = 200, body = """{"data":{}}""")
                else -> WireFixture.status(404)
            }
        }
        val clock = ScriptedClock()
        val output = riding(workspace(OpenCodeClient.liveValue, viewing = ear, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server), fixture, "send_message", """{"text":"Fix it"}""", clock = clock)
        assertTrue(output, output.contains("message_id"))
        assertEquals(1, moves.value)
        assertTrue("Nothing waited ahead of the steer", clock.sleeps.isEmpty())
    }

    @Test fun everyStepOfAToolSharesItsBudget() = voiceTest {
        // cancel_queued marks no step required: finding the session takes 28 s
        // of the 30 s budget, so the cancel gets the 2 s left (two tries), not
        // a fresh window (five).
        val clock = ScriptedClock()
        val cancels = LockIsolated(0)
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/session/ses_ear" -> {
                    clock.advance(28.seconds)
                    WireFixture.json(status = 200, body = earSession)
                }
                "DELETE /api/session/ses_ear/inbox/msg_q" -> {
                    cancels.increment()
                    WireFixture.networkConnectionLost
                }
                else -> WireFixture.status(404)
            }
        }
        val output = riding(workspace(OpenCodeClient.liveValue, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server), fixture, "cancel_queued", """{"session_id":"ses_ear","message_id":"msg_q"}""", clock = clock)
        assertTrue(output, output.contains("\"attempts\":2"))
        assertEquals(2, cancels.value)
    }

    @Test fun aSendWhoseAnswerWasLostIsReadBackBeforeItsCalledAFailure() = voiceTest {
        // Every attempt times out after the server may have taken it; the
        // inbox shows it did, so it's sent, not a failure to repeat.
        val posted = LockIsolated<String?>(null)
        val fixture = WireFixture { request ->
            when {
                request.route == "POST /api/session/ses_ear/background" -> WireFixture.status(204)
                request.route == "POST /api/session/ses_ear/prompt" -> {
                    posted.setValue(runCatching { request.json().string("id") }.getOrNull())
                    WireFixture.timedOut
                }
                request.route.startsWith("GET /api/session/ses_ear/message/") -> WireFixture.status(404)
                request.route == "GET /api/session/ses_ear/inbox" -> {
                    val id = posted.value ?: ""
                    WireFixture.json(status = 200, body = """{"data":[{"id":"$id","type":"user","delivery":"steer","payload":{"text":"Fix it"}}]}""")
                }
                else -> WireFixture.status(404)
            }
        }
        val output = riding(workspace(OpenCodeClient.liveValue, viewing = ear, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server), fixture, "send_message", """{"text":"Fix it"}""")
        val result = parse(output)
        assertNull(output, result["error"])
        assertEquals(posted.value, result.string("message_id"))
    }

    @Test fun aSendWhoseFirstAttemptMayHaveLandedIsReadBackThoughTheLastNeverLeft() = voiceTest {
        // The connection broke after the first POST went out, then the route
        // went down. The last error says "never sent", but the first may have
        // landed: the inbox decides.
        val posted = LockIsolated(listOf<String>())
        val fixture = WireFixture { request ->
            when {
                request.route == "POST /api/session/ses_ear/background" -> WireFixture.status(204)
                request.route == "POST /api/session/ses_ear/prompt" -> {
                    posted.append(runCatching { request.json().string("id") }.getOrNull() ?: "")
                    if (posted.value.size == 1) WireFixture.networkConnectionLost else WireFixture.cannotConnectToHost
                }
                request.route.startsWith("GET /api/session/ses_ear/message/") -> WireFixture.status(404)
                request.route == "GET /api/session/ses_ear/inbox" -> {
                    val id = posted.value.firstOrNull() ?: ""
                    WireFixture.json(status = 200, body = """{"data":[{"id":"$id","type":"user","delivery":"steer","payload":{"text":"Fix it"}}]}""")
                }
                else -> WireFixture.status(404)
            }
        }
        val output = riding(workspace(OpenCodeClient.liveValue, viewing = ear, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server), fixture, "send_message", """{"text":"Fix it"}""")
        val result = parse(output)
        assertEquals(5, posted.value.size)
        assertEquals(1, posted.value.toSet().size)
        assertNull(output, result["error"])
        assertEquals(posted.value.firstOrNull(), result.string("message_id"))
    }

    @Test fun aSessionCreatedBeforeItsMessageFailedIsStillReported() = voiceTest {
        val client = OpenCodeClient.testValue.copy(
            createSession = { _, directory, _ -> OpenCodeSession(id = "ses_new", serverTitle = "New", projectID = "p", directory = directory, updatedAt = VoiceFeatureTestSupport.distantPast) },
            modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            getSession = { _, _ -> throw OpenCodeClientError(message = "Offline") },
            sendDeliveredPrompt = { _, _, _, _ -> throw OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500) },
        )
        val result = real { obj(workspace(client, viewing = docs), "create_session", """{"directory":"/Users/kit/opencode-ios","text":"Fix the earpiece"}""") }
        assertEquals("The delegator must not create it again", "ses_new", result.string("created"))
        assertEquals("The server returned HTTP 500.", result.string("send_error"))
    }

    @Test fun aSendThatCantBeConfirmedSaysItMayHaveArrived() = voiceTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                "POST /api/session/ses_ear/background" -> WireFixture.status(204)
                else -> WireFixture.timedOut
            }
        }
        val output = riding(workspace(OpenCodeClient.liveValue, viewing = ear, latency = VoiceDelegatorWorkspace.Latency.standard, server = fixture.server), fixture, "send_message", """{"text":"Fix it"}""")
        val result = parse(output)
        assertEquals(true, (result["may_have_arrived"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(true, result.string("message_id")?.startsWith("msg_"))
        assertEquals(true, result.string("error")?.contains("may have arrived"))
    }

    // MARK: send_call_log

    @Test fun aHangingLogUploadFallsBackInlineInOnePrompt() = voiceTest {
        val prompts = LockIsolated(listOf<String>())
        val client = OpenCodeClient.testValue.copy(
            uploadFile = { _, _, _, _ -> flow<OpenCodeUploadEvent> { awaitCancellation() } },
            sendDeliveredPrompt = { _, _, text, _ ->
                prompts.append(text)
                "msg_log"
            },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        )
        val log = VoiceCallLog(id = UUID.randomUUID(), startedAt = date(1_000), title = "Voice lab", engine = "gptLive")
            .let { it.copy(notes = it.notes + VoiceCallLog.Note(id = 1, t = 1.0, kind = VoiceCallLog.Note.Kind.marker, text = "Marker one")) }
        var result = JsonObject(emptyMap())
        val took = real { elapsed { result = obj(workspace(client, viewing = ear, callLog = log), "send_call_log", """{"note":"Why did it stall?"}""") } }
        assertEquals(true, (result["call_log_attached"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("Sent once, with the log inline", 1, prompts.value.size)
        assertEquals(true, prompts.value.firstOrNull()?.contains("Marker one"))
        if (System.getenv("CI") != "true") {
            assertTrue("Took $took", took < 2.seconds)
        }
    }

    // MARK: Retargeting a live call

    @Test fun aMoveUpdatesTheInstructionsWithoutWaitingForContext() = voiceTest { h ->
        val updates = LockIsolated(listOf<String>())
        val state = h.state { State(server, ear) }.live()
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall { copy(retarget = {}, updateInstructions = { full, _ -> updates.append(full) }) }
            voiceWork { copy(recentMessages = { _, _, _ -> hang() }) }
        }
        store.exhaustive = false
        val took = elapsed {
            store.send(Action.TargetChanged)
            for (i in 0 until 200) {
                if (updates.value.isNotEmpty()) break
                delay(10.milliseconds)
            }
        }
        assertEquals(1, updates.value.size)
        if (System.getenv("CI") != "true") {
            assertTrue("Took $took: context waits at most $voiceContextDeadline", took < 1_500.milliseconds)
        }
        h.skipInFlightEffects()
    }

    @Test fun aNewerMoveCancelsTheOlderOnesUpdate() = voiceTest { h ->
        val updates = LockIsolated(0)
        val reads = LockIsolated(0)
        val state = h.state { State(server, ear) }.live()
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall { copy(retarget = {}, updateInstructions = { _, _ -> updates.increment() }) }
            voiceWork {
                copy(recentMessages = { _, _, _ ->
                    // The first read is slow; the second answers at once.
                    if (reads.increment() == 1) delay(300.milliseconds)
                    emptyList()
                })
            }
        }
        store.exhaustive = false
        store.send(Action.TargetChanged)
        delay(50.milliseconds)
        store.send(Action.TargetChanged)
        delay(900.milliseconds)
        assertEquals("Only the current target's instructions go out", 1, updates.value)
        h.skipInFlightEffects()
    }

    // MARK: Reply waits own their retries

    @Test fun aWorkWaitsReadsGoOnceEach() = voiceTest { h ->
        val policies = LockIsolated(listOf<RetryPolicy?>())
        val attempts = LockIsolated(0)
        val store = h.store(h.state { State(server, ear) }) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[Clock] = ImmediateClock()
            voiceCall { copy(completeFunction = { _, _ -> }) }
            voiceWork {
                copy(
                    submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_once") },
                    checkSessionActive = { _, _ ->
                        policies.append(NetworkRetry.policy.current())
                        true
                    },
                    awaitReply = { _, _, _ ->
                        policies.append(NetworkRetry.policy.current())
                        if (attempts.increment() == 1) throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                        "Done."
                    },
                )
            }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")
        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "run it", final = true)))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "call_once", name = "delegate", arguments = VoiceFeatureTestSupport.arguments("Run it."))))
        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkReplied::class)
        assertEquals(3, policies.value.size)
        assertTrue("The loop is the only retry layer", policies.value.all { it == RetryPolicy.once })
        store.finish()
    }

    @Test fun aFailingConnectionCostsAVoiceWaitOneWaitAndOneProbe() = voiceTest {
        // The live reply wait under the watch's `.once`: one `/wait`, one probe,
        // back to the watch, with no request policy inside.
        val routes = LockIsolated(listOf<String>())
        val fixture = WireFixture { request ->
            routes.append(request.route)
            WireFixture.networkConnectionLost
        }
        val error = runCatching {
            withTestDependencies({
                this[OpenCodeClient] = OpenCodeClient.liveValue
                this[Clock] = ImmediateClock()
                this[DateClient] = DateClient.liveValue
                this[OpenCodeTransport] = fixture.transport
            }) {
                NetworkRetry.policy.withValue(RetryPolicy.once) {
                    VoiceWorkClient.liveValue.awaitReply(fixture.server, "ses_1", "msg_1")
                }
            }
        }.exceptionOrNull()
        assertTrue("$error", error is OpenCodeClientError)
        assertEquals(listOf("POST /api/experimental/session/ses_1/wait", "GET /api/session/active"), routes.value)
    }
}
