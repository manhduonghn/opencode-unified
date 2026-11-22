package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

class VoiceProviderTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")
    private val session = OpenCodeSession(
        id = "ses_voice",
        serverTitle = "Voice Provider",
        projectID = "proj_1",
        directory = "/Users/kit/code/open-source/opencode-ios",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )
    private val gptLive = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = true)
    private val keyless = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = false)

    /** The first ID the incrementing UUID client hands out: Android's call-presence ID. */
    private val presenceID = UUID(0, 0)

    /** Each test here gets a fresh in-memory store. */
    private fun reset(): SpeechTestShared = SpeechTestShared()

    /** `server` connected, `servers` saved, `choice` stored. */
    private fun SpeechTestShared.save(servers: List<OpenCodeServer>, choice: VoiceServerChoice = VoiceServerChoice.Automatic) = run {
        shared(ServerSharedKeys.savedServers).set(servers)
        shared(ServerSharedKeys.savedServer).set(server)
        shared(VoiceSharedKeys.voiceServerChoice).set(choice)
    }

    private fun SpeechTestShared.savePhoneKey() = run {
        shared(SharedKeys.openAIAPIKey).set("sk-phone")
        shared(SharedKeys.verifiedVoiceKeyFingerprint).set(VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
    }

    private fun SpeechTestShared.know(
        support: Map<String, ServerVoiceSupport> = emptyMap(),
        models: Map<String, ServerVoiceModelsAnswer> = emptyMap(),
    ) = run {
        shared(VoiceSharedKeys.serverVoiceSupportByServer).set(support)
        shared(VoiceSharedKeys.voiceModelsByServer).set(models)
    }

    private fun transcriptRecord(shared: SpeechTestShared, on: OpenCodeServer = server): VoiceTranscriptRecord? =
        shared.run { shared(VoiceSharedKeys.voiceTranscripts).value["${on.id}:${session.id}"] }

    private fun callDependencies(
        shared: SpeechTestShared,
        at: Long,
        checkSupport: suspend (OpenCodeServer) -> ServerVoiceSupport,
        start: suspend (String, RealtimeSessionConfig) -> Flow<VoiceCallEvent>,
        end: suspend () -> Unit,
        preflight: RealtimePreflightClient? = null,
    ): DependencyValues.Builder.() -> Unit = {
        this[SharedStore] = shared.store
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(at))
        // Android: the call's ongoing-notification ID and the start cue's clock.
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = TestClock()
        this[ServerVoiceClient] = ServerVoiceClient.testValue.let { ServerVoiceClient(checkSupport = checkSupport, models = it.models, createSession = it.createSession) }
        this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
            VoiceWorkClient(submit = work.submit, awaitReply = work.awaitReply, checkSessionActive = work.checkSessionActive, recentMessages = { _, _, _ -> emptyList() })
        }
        this[VoiceCallClient] = speechVoiceCall(start = start, end = end)
        preflight?.let { this[RealtimePreflightClient] = it }
    }

    private val gptLiveServerRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive))

    // MARK: States

    /**
     * Ready, No key, Update OpenCode and Offline come from the models list,
     * the voice route probe and whether the server answered. A server that
     * brokers calls but predates the list can't say: no state.
     */
    @Test fun eachStateComesFromWhatTheServerReports() {
        val call = VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)
        assertEquals(VoiceProviderState.unknown, VoiceProviderState.server(null, support = null, call = call))
        assertEquals(VoiceProviderState.ready, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = null, call = call))
        assertEquals(VoiceProviderState.ready, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = ServerVoiceSupport.supported, call = call))
        assertEquals(VoiceProviderState.noKey, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(keyless)), support = ServerVoiceSupport.supported, call = call))
        assertEquals(VoiceProviderState.needsUpdate, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = ServerVoiceSupport.unsupported, call = call))
        assertEquals("No models route (a 404)", VoiceProviderState.needsUpdate, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(emptyList()), support = null, call = call))
        assertEquals(VoiceProviderState.unknown, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(emptyList()), support = ServerVoiceSupport.supported, call = call))
        assertEquals(VoiceProviderState.offline, VoiceProviderState.server(ServerVoiceModelsAnswer.NoAnswer, support = null, call = call))
        assertEquals(VoiceProviderState.needsUpdate, VoiceProviderState.server(null, support = ServerVoiceSupport.unsupported, call = call))
        assertEquals(
            "GPT-Live's key isn't Realtime's",
            VoiceProviderState.noKey,
            VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = null, call = VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.realtime)),
        )
        assertEquals(VoiceProviderState.ready, VoiceProviderState.phone(hasKey = true))
        assertEquals(VoiceProviderState.noKey, VoiceProviderState.phone(hasKey = false))

        assertEquals(
            listOf("Ready", "No key", "Update OpenCode", "Offline"),
            listOf(VoiceProviderState.ready, VoiceProviderState.noKey, VoiceProviderState.needsUpdate, VoiceProviderState.offline).map { it.label },
        )
        assertTrue(VoiceProviderState.unknown.label == null && VoiceProviderState.unknown.symbol == null)
    }

    // MARK: Automatic

    /**
     * Automatic takes the connected server while it can (or can't be known
     * not to), then this phone's key, then another saved server that's
     * ready, else the connected server, whose failure says why.
     */
    @Test fun automaticTriesTheConnectedServerThenThePhoneThenAnotherReadyServer() {
        fun pick(readiness: VoiceReadiness): VoicePick =
            VoicePick.resolve(VoiceServerChoice.Automatic, ServerVoiceProvider.openAI, connected = server, saved = listOf(server, studio), readiness = readiness)
        val ready = mapOf(
            server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
            studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
        )
        assertEquals("Not heard from yet: its probe decides", server, pick(VoiceReadiness()).server)
        assertEquals(server, pick(VoiceReadiness(answers = ready, hasPhoneKey = true)).server)

        val tooOld = mapOf(server.persistenceID to ServerVoiceSupport.unsupported)
        assertEquals(VoiceProviderOption.Place.Phone, pick(VoiceReadiness(answers = ready, support = tooOld, hasPhoneKey = true)).place)
        assertEquals(studio, pick(VoiceReadiness(answers = ready, support = tooOld)).server)
        assertEquals(
            studio,
            pick(
                VoiceReadiness(
                    answers = mapOf(
                        server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(keyless)),
                        studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
                    ),
                ),
            ).server,
        )
        val silent = mapOf(server.persistenceID to ServerVoiceModelsAnswer.NoAnswer, studio.persistenceID to ServerVoiceModelsAnswer.NoAnswer)
        assertEquals(server, pick(VoiceReadiness(answers = silent)).server)
        assertTrue(pick(VoiceReadiness(answers = silent)).isAutomatic)

        val picked = VoicePick.resolve(
            VoiceServerChoice.of(studio), ServerVoiceProvider.openAI, connected = server, saved = listOf(server, studio),
            readiness = VoiceReadiness(answers = mapOf(studio.persistenceID to ServerVoiceModelsAnswer.NoAnswer), hasPhoneKey = true),
        )
        assertTrue("A pick stays, ready or not", picked.server == studio && !picked.isAutomatic)
        val gemini = VoicePick.resolve(
            VoiceServerChoice.Automatic, ServerVoiceProvider.geminiLive, connected = server, saved = emptyList(),
            readiness = VoiceReadiness(hasPhoneKey = true),
        )
        assertTrue("Gemini Live picked stays", gemini.server == server && gemini.provider == ServerVoiceProvider.geminiLive && !gemini.isAutomatic)
    }

    /**
     * A connected server known to be too old: Automatic starts the call on
     * this phone's key, asking no server.
     */
    @Test fun automaticCallsOnThePhoneKeyPastAConnectedServerThatNeedsAnUpdate() = runBlocking {
        val shared = reset()
        shared.save(listOf(server, studio))
        shared.savePhoneKey()
        shared.know(
            support = mapOf(server.persistenceID to ServerVoiceSupport.unsupported),
            models = mapOf(studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive))),
        )
        val keys = Locked(listOf<String>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 1_000,
                checkSupport = {
                    fail("The phone's key asks no server")
                    ServerVoiceSupport.supported
                },
                start = { key, config ->
                    assertNull(config.serverVoice)
                    keys.update { it + key }
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive),
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(1_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(1_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()
        assertEquals(listOf("sk-phone"), keys.value)
    }

    /**
     * No key on this phone: Automatic moves on to a saved server that's
     * ready, and the call bar names it.
     */
    @Test fun automaticCallsThroughAnotherReadyServerWithoutAPhoneKey() = runBlocking {
        val shared = reset()
        shared.save(listOf(server, studio))
        shared.know(
            support = mapOf(server.persistenceID to ServerVoiceSupport.unsupported, studio.persistenceID to ServerVoiceSupport.supported),
            models = mapOf(studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive))),
        )
        val brokers = Locked(listOf<OpenCodeServer?>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val connectGate = CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 2_000,
                checkSupport = {
                    fail("Studio's support is known")
                    ServerVoiceSupport.supported
                },
                start = { key, config ->
                    assertTrue(key.isEmpty())
                    brokers.update { it + config.serverVoice }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                serverVoiceBroker = studio,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Studio", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        assertEquals("Connecting via Studio…", store.state.statusText)
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(2_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(2_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()
        assertEquals(listOf<OpenCodeServer?>(studio), brokers.value)
    }

    /**
     * The connected server's probe finds it too old as the call starts:
     * Automatic moves the call to this phone's key.
     */
    @Test fun automaticMovesToThePhoneKeyWhenTheProbeFindsTheServerTooOld() = runBlocking {
        val shared = reset()
        shared.save(listOf(server))
        shared.savePhoneKey()
        val keys = Locked(listOf<String>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        // Holds the call until the probe's actions are received, so the order below is fixed.
        val connectGate = CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 3_000,
                checkSupport = { ServerVoiceSupport.unsupported },
                start = { key, config ->
                    assertNull(config.serverVoice)
                    keys.update { it + key }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.unsupported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.unsupported)
        }
        assertEquals(ServerVoiceSupport.unsupported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[server.persistenceID] })
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.unsupported)))
        store.receive(VoiceFeature.Action.CallPlaceChanged(null)) { it.copy(callPlace = null) }
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.PhoneKey(VoiceEngine.gptLive), usesDelegator = false)) {
            it.copy(activeRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive))
        }
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(3_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(3_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()
        assertEquals(listOf("sk-phone"), keys.value)
    }

    // MARK: A pick

    /**
     * The connected server picked, and too old: the call fails saying so,
     * with Update OpenCode, and never moves to the phone's key it has.
     */
    @Test fun aPickedServerThatFailsIsNeverSwappedForThePhoneKey() = runBlocking {
        val shared = reset()
        shared.save(listOf(server), choice = VoiceServerChoice.of(server))
        shared.savePhoneKey()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 4_000,
                checkSupport = { ServerVoiceSupport.unsupported },
                start = { _, _ ->
                    fail("A pick never moves to the phone's key")
                    throw CancellationException()
                },
                end = {},
                preflight = RealtimePreflightClient(verify = { _, _ -> fail("A pick never moves to the phone's key") }),
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.unsupported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.unsupported)
        }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.unsupported)))
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported), usesDelegator = false)) {
            it.copy(activeRoute = VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported))
        }
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = ServerVoiceError.unsupportedServerNotice)) {
            it.withPhase(VoiceFeature.State.Phase.Failed(ServerVoiceError.unsupportedServerNotice))
        }
        assertEquals(ServerVoiceSupport.unsupported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[server.persistenceID] })
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(4_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        assertEquals(
            VoiceCallDiagnosis(sentence = "My Mac needs a newer OpenCode for voice calls.", fix = VoiceCallFix.updateOpenCode, place = "My Mac"),
            store.state.failureDiagnosis,
        )
        store.skipReceivedActions()
        effects.skipInFlightEffects()
    }

    // MARK: Pins and reconnects

    /**
     * Choose Automatically off on My Mac pins My Mac itself: switched to
     * Studio, calls still go through My Mac.
     */
    @Test fun aPinnedConnectedServerStaysPinnedAfterASwitch() = runBlocking {
        val shared = reset()
        val readiness = VoiceReadiness(
            answers = mapOf(
                server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
                studio.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
            ),
            support = mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.supported),
        )
        val catalog = VoiceProviderCatalog(VoiceServerChoice.Automatic, ServerVoiceProvider.openAI, connected = server, others = listOf(studio), readiness = readiness)
        assertEquals(VoiceServerChoice.of(server), catalog.active.choice)

        shared.save(listOf(server, studio), choice = catalog.active.choice)
        shared.savePhoneKey()
        shared.know(support = readiness.support, models = readiness.answers)
        val brokers = Locked(listOf<OpenCodeServer?>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val connectGate = CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(studio, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 5_000,
                checkSupport = { ServerVoiceSupport.supported },
                start = { _, config ->
                    brokers.update { it + config.serverVoice }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                serverVoiceBroker = server,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = studio, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        assertEquals("Connecting via My Mac…", store.state.statusText)
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(5_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(5_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared, on = studio))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()
        assertEquals(listOf<OpenCodeServer?>(server), brokers.value)
    }

    /**
     * The pin Home writes for an earlier “connected server” choice keeps calls on that server after
     * a switch, and Automatic never becomes a pin.
     */
    @Test fun aLegacyConnectedPinResolvesToThatServer() {
        val pinned = VoiceServerChoice.Connected.pinningConnected(server)
        assertEquals(VoiceServerChoice.of(server), pinned)
        val pick = VoicePick.resolve(pinned, ServerVoiceProvider.openAI, connected = studio, saved = listOf(server, studio), readiness = VoiceReadiness())
        assertEquals(VoicePick(VoiceProviderOption.Place.Server(server), ServerVoiceProvider.openAI, isAutomatic = false), pick)
        assertEquals(VoiceServerChoice.Automatic, VoiceServerChoice.Automatic.pinningConnected(server))
    }

    // MARK: Failures

    /**
     * One sentence and the button that fixes it: a server's failure never
     * offers the phone's key.
     */
    @Test fun aFailedCallSaysWhyAndOffersTheOneFix() {
        val serverRoute = gptLiveServerRoute
        fun diagnose(message: String, route: VoiceRoute, place: String? = "My Mac") = VoiceCallDiagnosis.diagnose(message, route, place)
        val missingServerKey = "Couldn’t start voice: Voice needs an OpenAI API key on the server (a stored key or OPENAI_API_KEY). ChatGPT sign-in can’t start voice sessions."
        assertEquals(
            VoiceCallDiagnosis(sentence = "My Mac doesn’t have a key for voice calls.", fix = VoiceCallFix.chooseProvider, place = "My Mac"),
            diagnose(missingServerKey, serverRoute),
        )
        assertEquals("Couldn’t reach My Mac.", diagnose("No connection.", serverRoute).sentence)
        assertEquals(VoiceCallFix.chooseProvider, diagnose("No connection.", serverRoute).fix)
        assertEquals("My Mac couldn’t start the call.", diagnose("Couldn’t start voice: boom", serverRoute).sentence)
        assertEquals(VoiceCallFix.updateOpenCode, diagnose(ServerVoiceError.unsupportedServerNotice, VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported)).fix)

        val missingPhoneKey = VoiceRoute.Reason.MissingPhoneKey(VoiceEngine.gptLive)
        assertEquals(
            VoiceCallDiagnosis(sentence = "Add your OpenAI API key to call from this phone.", fix = VoiceCallFix.setUpOnIPhone, place = null),
            diagnose(missingPhoneKey.message, VoiceRoute.Unavailable(missingPhoneKey), null),
        )
        assertEquals(VoiceCallFix.setUpOnIPhone, diagnose(RealtimePreflightError.RejectedAPIKey.message, VoiceRoute.PhoneKey(VoiceEngine.gptLive), null).fix)
        assertEquals(VoiceCallFix.openSettings, diagnose(VoiceAudioError.microphoneDenied.message ?: "", serverRoute).fix)
        assertEquals(
            listOf("Set Up on This Phone", "Choose Provider", "Update OpenCode"),
            listOf(VoiceCallFix.setUpOnIPhone, VoiceCallFix.chooseProvider, VoiceCallFix.updateOpenCode).map { it.title },
        )
    }

    /**
     * The card's button opens the page that fixes it: Your OpenAI Account for
     * this phone's key and Voice Provider for a server's failure. Update
     * OpenCode opens the download page.
     */
    @Test fun theFixButtonOpensThePageThatFixesIt() = runBlocking {
        val shared = reset()
        val opened = Locked(listOf<String>())
        val state = shared.run { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Failed("No connection."))
            .copy(diagnosesFailure = true)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(state, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[VoiceCallFixClient] = VoiceCallFixClient(openURL = { url -> opened.update { it + url } }, openAppSettings = {})
        }
        store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.setUpOnIPhone))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.OpenVoiceSettings(VoiceSettingsPage.account)))
        store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.chooseProvider))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.OpenVoiceSettings(VoiceSettingsPage.provider)))
        store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.updateOpenCode))
        eventually { opened.value.isNotEmpty() }
        assertEquals(listOf(VoiceCallFix.downloadURL), opened.value)
        store.finish()
        effects.cancel()
    }

    /**
     * A Grok call's plain card: Check API Key opens Settings › Voice, where
     * its xAI key lives.
     */
    @Test fun aGrokFailureOpensVoiceSettings() = runBlocking {
        val shared = reset()
        val state = shared.run { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Failed("Add your xAI API key in Settings to use Voice."))
        val effects = detachedScope(coroutineContext)
        val store = TestStore(state, VoiceFeature(), effects) { this[SharedStore] = shared.store }
        assertNull(store.state.failureDiagnosis)
        store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.setUpOnIPhone))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.OpenVoiceSettings(VoiceSettingsPage.voice)))
        store.finish()
        effects.cancel()
        assertFalse(store.state.diagnosesFailure)
    }
}
