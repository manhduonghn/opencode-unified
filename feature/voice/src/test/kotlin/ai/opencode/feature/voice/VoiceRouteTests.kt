package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class VoiceRouteTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "route", serverTitle = "Routing", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

    /**
     * 192 combinations. Expected precedence is tabulated independently of the resolver:
     * Grok always phone; active Gemini always server; a broker whose support is
     * supported/undetermined owns remaining OpenAI; confirmed 404 falls back.
     */
    @Test fun completePrecedenceTable() {
        var count = 0
        for (engine in VoiceEngine.allCases) {
            for (provider in ServerVoiceProvider.allCases) {
                for (enabled in listOf(false, true)) {
                    for (support in listOf(ServerVoiceSupport.unknown, ServerVoiceSupport.checking, ServerVoiceSupport.supported, ServerVoiceSupport.unsupported)) {
                        for (openAI in listOf(false, true)) {
                            for (xai in listOf(false, true)) {
                                val expected: VoiceRoute = when {
                                    engine == VoiceEngine.grok ->
                                        if (xai) VoiceRoute.PhoneKey(VoiceEngine.grok) else VoiceRoute.Unavailable(VoiceRoute.Reason.MissingPhoneKey(VoiceEngine.grok))
                                    enabled && provider == ServerVoiceProvider.geminiLive && support == ServerVoiceSupport.unsupported ->
                                        VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported)
                                    enabled && provider == ServerVoiceProvider.geminiLive -> VoiceRoute.serverGeminiLive
                                    enabled && support in listOf(ServerVoiceSupport.unknown, ServerVoiceSupport.checking, ServerVoiceSupport.supported) ->
                                        VoiceRoute.serverOpenAI(if (engine == VoiceEngine.gptLive) VoiceRoute.OpenAI.gptLive else VoiceRoute.OpenAI.realtime)
                                    else -> if (openAI) VoiceRoute.PhoneKey(engine) else VoiceRoute.Unavailable(VoiceRoute.Reason.MissingPhoneKey(engine))
                                }
                                val route = VoiceRoute.resolve(
                                    selectedEngine = engine,
                                    serverVoiceEnabled = enabled, serverVoiceProvider = provider, serverVoiceSupport = support,
                                    hasOpenAIKey = openAI, hasXAIKey = xai,
                                )
                                assertEquals(expected, route)
                                assertEquals(expected == VoiceRoute.PhoneKey(VoiceEngine.gptLive), route.warmsPhoneConnection)
                                count += 1
                            }
                        }
                    }
                }
            }
        }
        assertEquals(192, count)
    }

    /** Each test's in-memory shared store starts from these values. */
    private fun preserveSettings(): VoiceTestShared {
        val shared = VoiceTestShared()
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        shared.set(SharedKeys.openAIAPIKey, null)
        shared.set(SharedKeys.xaiAPIKey, null)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, emptyMap())
        shared.set(VoiceSharedKeys.voiceTranscripts, emptyMap())
        return shared
    }

    @Test fun serverEnabledStartupUsesResolvedModelAndCredentials() {
        for (engine in listOf(VoiceEngine.grok, VoiceEngine.gptLive, VoiceEngine.realtime)) serverEnabledStartupUsesResolvedModelAndCredentials(engine)
    }

    private fun serverEnabledStartupUsesResolvedModelAndCredentials(engine: VoiceEngine) = runTest {
        val shared = preserveSettings()
        shared.set(VoiceSharedKeys.voiceEngine, engine)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.geminiLive)
        shared.set(SharedKeys.xaiAPIKey, "xai-test")
        val route: VoiceRoute = if (engine == VoiceEngine.grok) VoiceRoute.PhoneKey(VoiceEngine.grok) else VoiceRoute.serverGeminiLive
        val state = shared.read { VoiceFeature.State(server, session) }.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        val configs = mutableListOf<RealtimeSessionConfig>()
        val stream = CallEvents()
        val directory = Files.createTempDirectory("voice-route-").toFile()
        val logID = UUID.randomUUID()
        val recorder = DependencyValues.withValues(DependencyValues.testing().update { this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100)) }) {
            VoiceCallLogRecorder(directory = directory)
        }
        try {
            val store = voiceTestStore(shared, { state }) {
                this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
                this[UuidClient] = UuidClient { logID }
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[RealtimePreflightClient] = RealtimePreflightClient(verify = { key, model ->
                    if (!(engine == VoiceEngine.grok && key == "xai-test" && model == VoiceEngine.grok.modelID)) {
                        throw AssertionError("Unexpected preflight for $engine: $model")
                    }
                })
                this[VoiceWorkClient] = testVoiceWork()
                this[ServerVoiceClient] = ServerVoiceClient(
                    checkSupport = { throw AssertionError("No probe needed") },
                    models = { null },
                    createSession = { _, _ -> throw AssertionError("Unused") },
                )
                this[VoiceCallClient] = testVoiceCall(
                    start = { key, config ->
                        assertEquals(if (engine == VoiceEngine.grok) "xai-test" else "", key)
                        configs += config
                        stream.yieldEvent(VoiceCallEvent.Connected)
                        stream.flow
                    },
                    setAtEar = { },
                    end = { stream.finish() },
                )
            }
            val activeVoice = shared.read { state.callVoice.resolved(engine) }
            store.send(VoiceFeature.Action.Start) {
                it.copy(
                    callLogID = logID,
                    startedTarget = state.target,
                    activeRoute = route,
                    activeVoice = activeVoice,
                    diagnosesFailure = engine != VoiceEngine.grok,
                    callPlace = if (engine != VoiceEngine.grok) VoiceCallPlace("Localhost", VoiceProviderOption.Brand.gemini) else null,
                    // Android: the call's ongoing notification, started with the call.
                    callPresenceID = logID,
                )
            }
            store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
                it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(100))
            }
            stream.finish()
            store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
                it.withPhase(VoiceFeature.State.Phase.Ended).copy(callLogID = null)
            }
            assertEquals(
                mapOf("${server.id}:${session.id}" to VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(100), tail = emptyList(), log = emptyList())),
                shared.get(VoiceSharedKeys.voiceTranscripts),
            )
            store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
            store.finish()
            val config = configs.firstOrNull() ?: throw AssertionError("The call started ($engine)")
            assertEquals(route.engine, config.engine)
            assertEquals(if (engine == VoiceEngine.grok) null else server, config.serverVoice)
            assertEquals(route.logEngine, recorder.list().firstOrNull()?.engine)
            if (engine != VoiceEngine.grok) {
                assertTrue(config.instructions.contains("Gemini Live (gemini-3.8-live) from Google"))
                assertFalse(config.instructions.contains("voice layer is the Realtime model"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun retargetUsesTheStartedRouteEvenAfterSettingsChange() = runTest {
        val shared = preserveSettings()
        // A phone-key call must not start updating a server delegator just because
        // Server Voice is switched on mid-call.
        val state = shared.read { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesDelegator = true, activeRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive))
        var updates = 0
        val store = voiceTestStore(shared, { state }) {
            this[VoiceWorkClient] = testVoiceWork()
            this[VoiceCallClient] = testVoiceCall(
                retarget = { },
                updateInstructions = { _, _ -> },
                updateDelegatorInstructions = { updates += 1 },
            )
        }
        store.send(VoiceFeature.Action.TargetChanged)
        store.finish()
        assertEquals(0, updates)
    }

    @Test fun hangingUpDuringProbeOrPreflightNeverStartsTheCall() {
        for (probing in listOf(false, true)) hangingUpDuringProbeOrPreflightNeverStartsTheCall(probing)
    }

    private fun hangingUpDuringProbeOrPreflightNeverStartsTheCall(probing: Boolean) = runTest {
        val shared = preserveSettings()
        shared.set(SharedKeys.openAIAPIKey, "test-phone")
        if (!probing) shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        val clock = TestClock()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val state = shared.read { VoiceFeature.State(server, session) }
        val store = voiceTestStore(shared, { state }) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[Clock] = clock
            this[VoiceWorkClient] = testVoiceWork()
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = {
                    entered.trySend(Unit)
                    clock.sleep(60.seconds)
                    ServerVoiceSupport.supported
                },
                models = { null },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ ->
                entered.trySend(Unit)
                clock.sleep(60.seconds)
            })
            this[VoiceCallClient] = testVoiceCall(
                start = { _, _ -> throw AssertionError("A cancelled startup must never open a call") },
                end = { },
            )
        }
        store.send(VoiceFeature.Action.Start) {
            it.copy(
                startedTarget = state.target,
                activeRoute = if (probing) VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive) else VoiceRoute.PhoneKey(VoiceEngine.gptLive),
                activeVoice = CallVoice.marin,
                diagnosesFailure = true,
                callPlace = if (probing) VoiceCallPlace("Localhost", VoiceProviderOption.Brand.openAI) else null,
                // Android: the call's ongoing notification, started with the call.
                callPresenceID = UUID(0, 0),
            )
        }
        entered.receive()
        store.send(VoiceFeature.Action.EndTapped) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(
            "probing: $probing",
            mapOf("${server.id}:${session.id}" to VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(100), tail = emptyList(), log = emptyList())),
            shared.get(VoiceSharedKeys.voiceTranscripts),
        )
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
    }

    /** The server brokers the call's own session and hands back its SDP answer. */
    @Test fun theServerBrokerSendsTheCallsSessionAndReturnsItsAnswer() = runTest {
        val config = LiveSessionConfig(instructions = "context")
        val expectedRequest = ServerVoiceSessionRequest(
            model = config.model, session = config.sessionPayload(includeAudioFormat = false),
            transport = ServerVoiceTransportRequest.Webrtc(sdp = "offer", dcid = LiveRTC.dataChannelID),
        )
        val values = DependencyValues.testing().update {
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { ServerVoiceSupport.supported },
                models = { null },
                createSession = { requested, request ->
                    assertEquals(server, requested)
                    assertEquals(expectedRequest, request)
                    ServerVoiceTransportResponse.Webrtc(sdp = "server-answer")
                },
            )
        }
        val answer = withContext(values.asContextElement()) { VoiceBrokerTransport(server).exchange(config = config, sdp = "offer") }
        assertEquals("server-answer", answer)
    }

    /**
     * A refusal keeps its status: 0 when the server didn't answer, 404 without
     * the voice route, otherwise the server's own.
     */
    @Test fun serverBrokerRejectsWrongTransportAndPreservesRefusals() = runTest {
        val refusals: List<Pair<ServerVoiceError, LiveRTCRejected>> = listOf(
            ServerVoiceError.UnsupportedRoute to LiveRTCRejected(status = 404, message = ServerVoiceError.unsupportedServerNotice),
            ServerVoiceError.Rejected(status = 0, message = "Couldn’t start voice: The network connection was lost.") to
                LiveRTCRejected(status = 0, message = "Couldn’t start voice: The network connection was lost."),
            ServerVoiceError.Rejected(status = 503, message = "Couldn’t start voice: Voice needs an OpenAI API key on the server") to
                LiveRTCRejected(status = 503, message = "Couldn’t start voice: Voice needs an OpenAI API key on the server"),
        )
        suspend fun exchange(createSession: suspend (OpenCodeServer, ServerVoiceSessionRequest) -> ServerVoiceTransportResponse): Throwable? {
            val values = DependencyValues.testing().update {
                this[ServerVoiceClient] = ServerVoiceClient(checkSupport = { ServerVoiceSupport.supported }, models = { null }, createSession = createSession)
            }
            return withContext(values.asContextElement()) {
                runCatching { VoiceBrokerTransport(server).exchange(config = LiveSessionConfig(instructions = "test"), sdp = "offer") }.exceptionOrNull()
            }
        }
        for ((error, rejected) in refusals) {
            assertEquals(rejected, exchange { _, _ -> throw error })
        }
        assertEquals(
            LiveRTCRejected(status = 0, message = ServerVoiceError.InvalidResponse.errorDescription),
            exchange { _, _ -> ServerVoiceTransportResponse.Websocket(url = "unused", token = "unused", expiresAt = null) },
        )
    }
}
