package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.openingScreens
import ai.opencode.feature.drawer.VoiceHomeTestSupport.receiveVoice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voice
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.voice.LiveCallCoordinator
import ai.opencode.feature.voice.LiveClient
import ai.opencode.feature.voice.LiveCommand
import ai.opencode.feature.voice.LiveConnection
import ai.opencode.feature.voice.LiveDelegation
import ai.opencode.feature.voice.LiveInputMessage
import ai.opencode.feature.voice.LiveServerEvent
import ai.opencode.feature.voice.LiveSessionConfig
import ai.opencode.feature.voice.RealtimePreflightClient
import ai.opencode.feature.voice.VoiceAudioClient
import ai.opencode.feature.voice.VoiceAudioEvent
import ai.opencode.feature.voice.VoiceAudioSession
import ai.opencode.feature.voice.VoiceCallClient
import ai.opencode.feature.voice.VoiceCallDiagnostics
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceCallTiming
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.VoiceWorkAdmission
import ai.opencode.feature.voice.VoiceWorkClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A GPT-Live delegation keeps running across a Home session switch and a reconnect. The call runs
 * through a real [LiveCallCoordinator] over fake sockets on the test's virtual time.
 */
class LiveDelegationFeatureTestsSession {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)

    private class NetworkConnectionLost : IOException("The network connection was lost.")

    private class FakeLiveSocket(val config: LiveSessionConfig) {
        private val serverEvents = Channel<LiveServerEvent>(Channel.UNLIMITED)
        val events: Flow<LiveServerEvent> = serverEvents.consumeAsFlow()
        private val sent = mutableListOf<LiveCommand>()
        val commands: List<LiveCommand> get() = synchronized(sent) { sent.toList() }

        fun yieldEvent(event: LiveServerEvent) {
            serverEvents.trySend(event)
        }

        fun finish(error: Throwable) {
            serverEvents.close(error)
        }

        fun record(command: LiveCommand) = synchronized(sent) { sent += command; Unit }
    }

    /** On the test's virtual time. */
    private class LiveHarness(test: TestScope) {
        private val openedChannel = Channel<FakeLiveSocket>(Channel.UNLIMITED)
        private val values: DependencyValues = DependencyValues.testing().update {
            this[Clock] = object : Clock {
                override fun now(): Duration = test.testScheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            }
            this[DateClient] = DateClient { Instant.ofEpochMilli(test.testScheduler.currentTime) }
        }
        val coordinator: LiveCallCoordinator = DependencyValues.withValues(values) {
            LiveCallCoordinator(
                timing = VoiceCallTiming(
                    rotateAfter = 3_000.0,
                    forceRotateAfter = 3_500.0,
                    retryDelays = listOf(0.01, 0.01, 0.01),
                    liveDelegationSettle = Duration.ZERO,
                    liveTranscriptSettle = 40.milliseconds,
                    liveAcknowledgementWait = Duration.ZERO,
                ),
                diagnostics = VoiceCallDiagnostics(),
                dispatcher = StandardTestDispatcher(test.testScheduler),
            )
        }

        suspend fun nextOpened(): FakeLiveSocket = openedChannel.receive()

        suspend fun start(instructions: String): Flow<VoiceCallEvent> {
            val live = LiveClient { _, config ->
                val socket = FakeLiveSocket(config)
                openedChannel.trySend(socket)
                LiveConnection(
                    events = socket.events,
                    send = { command ->
                        yield()
                        socket.record(command)
                    },
                    close = {},
                )
            }
            val audio = VoiceAudioClient(start = {
                VoiceAudioSession(
                    capture = Channel<ByteArray>().consumeAsFlow(),
                    events = Channel<VoiceAudioEvent>().consumeAsFlow(),
                    enqueue = { _, _ -> },
                    flush = { 0 },
                    setMuted = { },
                    stop = { },
                )
            })
            return coordinator.start(apiKey = "test", config = LiveSessionConfig(instructions = instructions, delegation = LiveDelegation.client), live = live, audio = audio)
        }
    }

    private fun FakeLiveSocket.thinking(): List<LiveCommand.AppendThinking> = commands.filterIsInstance<LiveCommand.AppendThinking>()

    @Test
    fun aDelegationKeepsRunningAcrossASessionSwitchAndAReconnect() = homeTest { h ->
        val other = OpenCodeSession(id = "ses_other", serverTitle = "Other session", projectID = "p", directory = "/Users/kit/project", updatedAt = HomeFeatureTestSupport.distantPast)
        val project = OpenCodeProject(id = "p", name = "project", directory = "/Users/kit/project")
        h.setShared(SharedKeys.openAIAPIKey, "sk-test")
        h.setShared(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session, other), activeSessionIDs = emptySet()))
            home = home.openingDetail(session)
            home.withCall(VoiceFeature.State(server, session))
        }

        val harness = LiveHarness(this)
        val messageLookups = mutableListOf<Pair<String, String>>()
        val store = h.store(home) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[Clock] = TestClock()
            // Android: every start mints the call's notification identity.
            this[UuidClient] = UuidClient.incrementing()
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
            this[VoiceCallClient] = VoiceCallClient(
                start = { _, config -> harness.start(instructions = config.instructions) },
                completeFunction = { callID, output -> harness.coordinator.completeFunction(callID, output) },
                closeDelegation = { callID, reason -> harness.coordinator.closeDelegation(callID, reason) },
                setMuted = { muted -> harness.coordinator.setMuted(muted) },
                updateInstructions = { full, delta -> harness.coordinator.updateInstructions(full, delta) },
                end = { harness.coordinator.end() },
            )
            this[VoiceWorkClient] = VoiceWorkClient.testValue.let { inert ->
                VoiceWorkClient(
                    submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_abandoned") },
                    awaitReply = { _, _, _ -> awaitCancellation() },
                    fetchReply = inert.fetchReply,
                    fetchRunReply = inert.fetchRunReply,
                    checkSessionActive = inert.checkSessionActive,
                    activeSessionIDs = inert.activeSessionIDs,
                    noteSessionSettled = inert.noteSessionSettled,
                    noteActiveSessions = inert.noteActiveSessions,
                    recentMessages = inert.recentMessages,
                )
            }
            // Admission now shows the prompt before its transcript echo. An empty
            // inbox triggers a bounded lookup by ID to reconcile that visible row.
            this[OpenCodeClient] = OpenCodeClient.testValue.openingScreens { id -> if (id == other.id) other else session }.copy(
                message = { _, sessionID, messageID ->
                    synchronized(messageLookups) { messageLookups += sessionID to messageID }
                    emptyList<OpenCodeTranscriptItem>()
                },
            )
        }
        store.exhaustive = false

        store.send(voice(VoiceFeature.Action.Start))

        val first = harness.nextOpened()
        first.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
        store.receiveVoice(VoiceFeature.Action.CallEvent::class)

        first.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Run the full migration.", startMilliseconds = 100, endMilliseconds = 500))
        store.receiveVoice(VoiceFeature.Action.CallEvent::class)
        first.yieldEvent(LiveServerEvent.DelegationCreated(id = "item_del_abandoned", target = "client", offsetMilliseconds = 500))
        store.receiveVoice(VoiceFeature.Action.CallEvent::class)
        store.receiveVoice(VoiceFeature.Action.WorkAdmitted::class)

        // Switch sessions in the drawer while `item_del_abandoned` is still running.
        store.send(HomeFeature.Action.SessionTapped(other.id))
        store.receiveVoice(VoiceFeature.Action.TargetChanged::class)
        delay(40)
        store.skipReceivedActions()
        assertFalse(first.thinking().any { it.content.contains("The call moved to another session") })
        assertEquals(listOf("item_del_abandoned"), store.state.sessionDetail?.voice?.pendingWork?.map { it.callID })

        // Now drop the socket so the coordinator opens a seeded replacement.
        first.finish(NetworkConnectionLost())
        val second = harness.nextOpened()
        val developerSeed = second.config.input.firstOrNull { it.role == LiveInputMessage.Role.developer }?.text ?: ""
        assertTrue("The request is still running after the switch: $developerSeed", developerSeed.contains("still running"))
        assertTrue(messageLookups.all { it == (session.id to "msg_abandoned") })

        harness.coordinator.end()
        h.skipInFlightEffects()
    }
}
