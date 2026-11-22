package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.diagnostics.DiagnosticsErrorCategory
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.EventStream
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.date
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.server.UrlErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The coordinator runs on real time here, with the same short timings and bounded waits. */
class VoiceResumeTests {
    /** OkHttp's reset socket. */
    private fun connectionLost() = SocketException("Connection reset")

    private fun notConnected() = ConnectException("Network is unreachable")

    /** One fake Realtime socket per `connect`. */
    internal class FakeSocket {
        private val serverChannel = Channel<RealtimeServerEvent>(Channel.UNLIMITED)
        val events: Flow<RealtimeServerEvent> = serverChannel.receiveAsFlow()
        val commands = LockIsolated(listOf<RealtimeCommand>())
        val closed = LockIsolated(false)

        /** Keeps `close()` suspended until `releaseClose()`, ignoring cancellation. */
        val holdsClose = LockIsolated(false)
        private val closeWaiter = LockIsolated<CompletableDeferred<Unit>?>(null)

        fun yield(event: RealtimeServerEvent) {
            serverChannel.trySend(event)
        }

        fun finish(throwing: Throwable? = null) {
            serverChannel.close(throwing)
        }

        val systemMessages: List<String> get() = commands.value.mapNotNull { (it as? RealtimeCommand.SystemMessage)?.text }
        val responseCreates: Int get() = commands.value.count { it == RealtimeCommand.ResponseCreate }
        val functionOutputs: Int get() = commands.value.count { it is RealtimeCommand.FunctionOutput }
        val isHeldClosing: Boolean get() = closeWaiter.value != null

        suspend fun close() {
            closed.setValue(true)
            if (!holdsClose.value) return
            val waiter = CompletableDeferred<Unit>()
            closeWaiter.setValue(waiter)
            withContext(NonCancellable) { waiter.await() }
        }

        fun releaseClose() {
            closeWaiter.value?.complete(Unit)
            closeWaiter.setValue(null)
        }

        fun connection() = RealtimeConnection(
            events = events,
            send = { command -> commands.append(command) },
            close = { close() },
        )
    }

    /** The call's events: `next()` waits, a failure throws. */
    internal class EventIterator(flow: Flow<VoiceCallEvent>, scope: CoroutineScope) {
        private val channel = Channel<Result<VoiceCallEvent?>>(Channel.UNLIMITED)

        init {
            scope.launch {
                try {
                    flow.collect { channel.send(Result.success(it)) }
                    channel.send(Result.success(null))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    channel.send(Result.failure(error))
                }
            }
        }

        suspend fun next(): VoiceCallEvent? = withTimeout(10.seconds) { channel.receive() }.getOrThrow()
    }

    internal class Harness(timing: VoiceCallTiming) {
        val sockets = LockIsolated(listOf<FakeSocket>())

        /** Connects after the first that should fail. */
        val failingConnects = LockIsolated(0)
        private val openedChannel = Channel<FakeSocket>(Channel.UNLIMITED)
        private val audioChannel = Channel<VoiceAudioEvent>(Channel.UNLIMITED)
        private val microphoneChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val connectedNotifications = LockIsolated(0)
        private val marks = LockIsolated(listOf<String>())
        val socketEvents = LockIsolated(listOf<VoiceCallDiagnostics.SocketEvent>())
        val diagnostics = VoiceCallDiagnostics(
            recordMark = { name -> marks.append(name) },
            recordSocketEvent = { event ->
                socketEvents.append(event)
                marks.append(event.name)
            },
        )
        val coordinator: VoiceCallCoordinator = DependencyValues.withValues(coordinatorDependencies()) {
            VoiceCallCoordinator(timing = timing, diagnostics = diagnostics)
        }

        suspend fun nextOpened(): FakeSocket = withTimeout(10.seconds) { openedChannel.receive() }

        fun audio(event: VoiceAudioEvent) {
            audioChannel.trySend(event)
        }

        fun microphone(data: ByteArray) {
            microphoneChannel.trySend(data)
        }

        val voiceEvents: List<String> get() = marks.value.filter { it.startsWith("voice_") }

        suspend fun start(): Flow<VoiceCallEvent> {
            val realtime = RealtimeClient { _, _ ->
                if (sockets.value.isNotEmpty() && failingConnects.value > 0) {
                    failingConnects.update { it - 1 }
                    throw notConnectedError()
                }
                val socket = FakeSocket()
                sockets.append(socket)
                openedChannel.trySend(socket)
                socket.connection()
            }
            val audio = VoiceAudioClient(start = {
                VoiceAudioSession(
                    capture = microphoneChannel.receiveAsFlow(),
                    events = audioChannel.receiveAsFlow(),
                    enqueue = { _, _ -> },
                    flush = { 0 },
                    setMuted = { },
                    stop = {},
                    noteConnected = { connectedNotifications.update { it + 1 } },
                )
            })
            return coordinator.start(
                apiKey = "test",
                config = RealtimeSessionConfig(instructions = "", tools = emptyList()),
                realtime = realtime,
                audio = audio,
            )
        }

        private fun notConnectedError() = ConnectException("Network is unreachable")
    }

    private companion object {
        /** The coordinator's world: real time. */
        fun coordinatorDependencies(): DependencyValues = DependencyValues.testing().update {
            this[Clock] = Clock.liveValue
            this[DateClient] = DateClient.liveValue
            this[VoiceCallSounds] = VoiceCallSounds(clock = Clock.liveValue, haptics = quietEngine)
        }

        /** Plays every pattern: keeps the Android fallback haptics out of these tests. */
        val quietEngine = object : VoiceHapticPlaying {
            override val isCallActive = true
            override fun prepare() {}
            override fun play(pattern: VoiceHapticPattern, at: Duration, kind: VoiceHapticEngine.Kind) = true
            override fun cancel(kind: VoiceHapticEngine.Kind) {}
            override fun endCall() {}
            override suspend fun finish() {}
        }

        fun delegate(callID: String, text: String? = null) = VoiceCallEvent.FunctionCall(
            callID = callID, name = "delegate",
            arguments = JsonObject(if (text == null) emptyMap() else mapOf("text" to JsonPrimitive(text))),
        )

        fun serverDelegate(callID: String, text: String? = null) = RealtimeServerEvent.FunctionCall(
            callID = callID, name = "delegate",
            arguments = JsonObject(if (text == null) emptyMap() else mapOf("text" to JsonPrimitive(text))),
        )

        fun timing(
            rotateAfter: Double = 3_000.0,
            forceRotateAfter: Double = 3_500.0,
            retryDelays: List<Double> = emptyList(),
            realtimeTranscriptWait: Duration = 1.seconds,
        ) = VoiceCallTiming(rotateAfter = rotateAfter, forceRotateAfter = forceRotateAfter, retryDelays = retryDelays, realtimeTranscriptWait = realtimeTranscriptWait)

        suspend fun eventually(timeout: Duration = 2.seconds, step: Duration = 5.milliseconds, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (!condition() && System.nanoTime() < deadline) delay(step)
        }

        fun timed(block: suspend CoroutineScope.() -> Unit) = runBlocking {
            withTimeout(1.minutes) { block() }
        }
    }

    /**
     * Realtime often delegates before it has transcribed the turn: the call
     * waits for the user's words so the request carries them.
     */
    @Test fun aDelegationWaitsForTheTurnsTranscript() = timed {
        val harness = Harness(timing(realtimeTranscriptWait = 5.seconds))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()
        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())

        val delegate = delegate("call_1", "Check the upload test")
        first.yield(RealtimeServerEvent.SpeechStarted)
        first.yield(RealtimeServerEvent.SpeechStopped)
        first.yield(serverDelegate("call_1", "Check the upload test"))
        first.yield(RealtimeServerEvent.InputTranscript(itemID = "u1", text = "can you check the upload test", final = true))
        assertEquals(VoiceCallEvent.UserSpeechStarted, iterator.next())
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "u1", text = "can you check the upload test", final = true), iterator.next())
        assertEquals(delegate, iterator.next())
        harness.coordinator.end()
    }

    /**
     * Moving the call while a delegation waits for its words keeps it: the
     * words arrive, then the delegation carries them, as if nothing moved.
     */
    @Test fun movingSessionsKeepsADelegationWaitingForItsTranscript() = timed {
        val harness = Harness(timing(realtimeTranscriptWait = 5.seconds))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()
        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())

        first.yield(RealtimeServerEvent.SpeechStarted)
        first.yield(RealtimeServerEvent.SpeechStopped)
        first.yield(serverDelegate("call_a", "Check the upload test"))
        assertEquals(VoiceCallEvent.UserSpeechStarted, iterator.next())
        delay(20.milliseconds)
        harness.coordinator.retarget()
        first.yield(RealtimeServerEvent.InputTranscript(itemID = "u1", text = "can you check the upload test", final = true))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "u1", text = "can you check the upload test", final = true), iterator.next())
        assertEquals(delegate("call_a", "Check the upload test"), iterator.next())
        assertFalse("A move never cancels a reply", first.commands.value.contains(RealtimeCommand.ResponseCancel))
        harness.coordinator.end()
    }

    @Test fun aDelegationStopsWaitingForATranscriptThatNeverComes() = timed {
        val harness = Harness(timing(realtimeTranscriptWait = 50.milliseconds))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()
        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())

        first.yield(RealtimeServerEvent.SpeechStarted)
        first.yield(RealtimeServerEvent.SpeechStopped)
        first.yield(serverDelegate("call_1"))
        assertEquals(VoiceCallEvent.UserSpeechStarted, iterator.next())
        assertEquals(delegate("call_1"), iterator.next())
        // Nothing is owed any more: the next delegation goes at once.
        first.yield(serverDelegate("call_2"))
        assertEquals(delegate("call_2"), iterator.next())
        harness.coordinator.end()
    }

    @Test fun rotationSeedsAReplacementAndSwapsAtATurnBoundary() = timed {
        val harness = Harness(timing(rotateAfter = 0.05, forceRotateAfter = 30.0))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.yield(RealtimeServerEvent.InputTranscript(itemID = "u1", text = "Why is the upload test flaky?", final = true))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "u1", text = "Why is the upload test flaky?", final = true), iterator.next())
        first.yield(RealtimeServerEvent.ResponseCreated(id = "r1"))

        // The replacement opens mid-response, seeded with the call so far.
        val second = harness.nextOpened()
        eventually { second.systemMessages.isNotEmpty() }
        assertEquals(true, second.systemMessages.firstOrNull()?.contains("User: Why is the upload test flaky?"))
        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        delay(30.milliseconds)
        assertFalse("No swap while the model is still answering", first.closed.value)

        first.yield(RealtimeServerEvent.OutputTranscriptDone(itemID = "a1", text = "It races the progress callback."))
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "a1", text = "It races the progress callback."), iterator.next())
        first.yield(RealtimeServerEvent.ResponseDone(id = "r1"))
        eventually { first.closed.value }
        assertTrue(first.closed.value)
        // The swap closes the old socket, then attaches the new one.
        eventually { second.systemMessages.size > 1 }
        // What was said after seeding reaches the replacement before anything else happens.
        assertEquals(true, second.systemMessages.lastOrNull()?.contains("You: It races the progress callback."))
        assertEquals(RealtimeCommand.SystemMessage(second.systemMessages.last()), second.commands.value.last())
        assertEquals(listOf("voice_rotation_swapped"), harness.voiceEvents)
        assertEquals("Rotation must not replay the connect chord", 1, harness.connectedNotifications.value)

        harness.coordinator.end()
    }

    @Test fun aDroppedSocketReconnectsAndDeliversTheLateResultAsContext() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01, 0.01, 0.01)))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        for (n in 0 until 10) harness.microphone(byteArrayOf(n.toByte(), 0))
        eventually { first.commands.value.count { it is RealtimeCommand.AppendAudio } == 10 }
        assertEquals(10, first.commands.value.count { it is RealtimeCommand.AppendAudio })
        first.yield(serverDelegate("call_1"))
        assertEquals(delegate("call_1"), iterator.next())

        first.finish(throwing = connectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, iterator.next())
        val second = harness.nextOpened()
        eventually { second.systemMessages.isNotEmpty() }
        assertEquals(true, second.systemMessages.firstOrNull()?.contains("One request you delegated is still running"))
        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, iterator.next())
        assertEquals("Mid-call reconnect must not replay the connect chord", 1, harness.connectedNotifications.value)

        // call_1 doesn't exist on the new session: the result goes in as context.
        harness.coordinator.completeFunction(callID = "call_1", output = "[COMPLETE] Fixed it.")
        assertEquals(true, second.systemMessages.lastOrNull()?.startsWith("Result of an earlier request"))
        assertEquals(true, second.systemMessages.lastOrNull()?.contains("[COMPLETE] Fixed it."))
        assertEquals(RealtimeCommand.ResponseCreate, second.commands.value.lastOrNull())
        assertFalse(second.commands.value.any { it is RealtimeCommand.FunctionOutput })

        assertEquals(listOf("voice_socket_dropped", "voice_socket_reconnected"), harness.voiceEvents)
        val dropped = harness.socketEvents.value.firstOrNull { it.name == "voice_socket_dropped" }
        assertNotNull(dropped)
        assertEquals(DiagnosticsErrorCategory.Offline.description, dropped!!.errorCategory)
        assertEquals("domain=NSURLErrorDomain code=-1005 closeCode=0", "domain=${dropped.domain} code=${dropped.code} closeCode=${dropped.closeCode}")

        harness.coordinator.end()
    }

    @Test fun theTimeLimitMovesTheCallInsteadOfEndingIt() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01)))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.yield(
            RealtimeServerEvent.Error(
                RealtimeServerError(
                    message = "Your session hit the maximum duration of 60 minutes.",
                    code = "session_expired",
                    type = "invalid_request_error",
                ),
            ),
        )
        assertEquals(VoiceCallEvent.Reconnecting, iterator.next())
        val second = harness.nextOpened()
        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, iterator.next())
        assertTrue(first.closed.value)
        assertEquals(listOf("voice_session_expired", "voice_socket_reconnected"), harness.voiceEvents)

        harness.coordinator.end()
    }

    @Test fun whenEveryRetryFailsTheCallEndsAsDropped() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01, 0.01, 0.01)))
        harness.failingConnects.setValue(3)
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.finish(throwing = connectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, iterator.next())
        val ended = runCatching { iterator.next() }.exceptionOrNull()
        assertEquals(RealtimeClientError.Dropped, ended)
        assertEquals(
            listOf("voice_socket_dropped", "voice_connect_failed", "voice_connect_failed", "voice_connect_failed", "voice_reconnect_exhausted"),
            harness.voiceEvents,
        )
    }

    @Test fun replacementsThatFailBeforeConfirmingUseUpTheRetries() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01, 0.01, 0.01)))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.finish(throwing = connectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, iterator.next())
        // Each replacement connects, then fails before `session.created`.
        repeat(3) {
            val socket = harness.nextOpened()
            eventually { socket.systemMessages.isNotEmpty() }
            socket.finish(throwing = connectionLost())
        }
        val ended = runCatching { iterator.next() }.exceptionOrNull()
        assertEquals(RealtimeClientError.Dropped, ended)
        assertEquals(4, harness.sockets.value.size)
        assertEquals(
            listOf("voice_socket_dropped", "voice_connect_failed", "voice_connect_failed", "voice_connect_failed", "voice_reconnect_exhausted"),
            harness.voiceEvents,
        )
    }

    @Test fun aCallWhoseFirstSessionNeverConfirmsFails() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01, 0.01, 0.01)))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.finish(throwing = connectionLost())
        val ended = runCatching { iterator.next() }.exceptionOrNull()
        assertEquals(UrlErrorCode.NETWORK_CONNECTION_LOST, ended?.let(UrlErrorCode::fromThrowable))
        delay(50.milliseconds)
        assertEquals("No reconnect for a call that never started", 1, harness.sockets.value.size)
        assertEquals(listOf("voice_connect_failed"), harness.voiceEvents)
    }

    @Test fun aTurnTranscribedAfterItsReplyStartedIsNotAnsweredAgainAfterASwap() = timed {
        val harness = Harness(timing(rotateAfter = 0.05, forceRotateAfter = 30.0))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        // Realtime's order: VAD commits the turn and the reply starts before the transcript lands.
        first.yield(RealtimeServerEvent.SpeechStarted)
        first.yield(RealtimeServerEvent.SpeechStopped)
        first.yield(RealtimeServerEvent.ResponseCreated(id = "r1"))
        first.yield(RealtimeServerEvent.InputTranscript(itemID = "u1", text = "Is the build green?", final = true))
        assertEquals(VoiceCallEvent.UserSpeechStarted, iterator.next())
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "u1", text = "Is the build green?", final = true), iterator.next())

        val second = harness.nextOpened()
        eventually { second.systemMessages.isNotEmpty() }
        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        first.yield(RealtimeServerEvent.OutputTranscriptDone(itemID = "a1", text = "It is."))
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "a1", text = "It is."), iterator.next())
        first.yield(RealtimeServerEvent.ResponseDone(id = "r1"))
        eventually { first.closed.value }
        delay(50.milliseconds)
        assertTrue(first.closed.value)
        assertEquals("The turn was already answered", 0, second.responseCreates)
        assertEquals(0, first.responseCreates)

        harness.coordinator.end()
    }

    @Test fun playbackThatDrainsWhileReconnectingLeavesTheReplyForTheNewSession() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01, 0.01, 0.01)))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.yield(RealtimeServerEvent.ResponseCreated(id = "r1"))
        first.yield(RealtimeServerEvent.OutputAudio(responseID = "r1", itemID = "a1", pcm = byteArrayOf(0, 0)))
        first.finish(throwing = connectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, iterator.next())

        val second = harness.nextOpened()
        harness.audio(VoiceAudioEvent.playbackDrained)
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        delay(30.milliseconds)
        assertEquals("Nothing goes to a session that hasn't confirmed", 0, second.responseCreates)

        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, iterator.next())
        eventually { second.responseCreates > 0 }
        delay(30.milliseconds)
        assertEquals("The cut-off reply is asked for once", 1, second.responseCreates)

        second.yield(RealtimeServerEvent.ResponseCreated(id = "r2"))
        second.yield(RealtimeServerEvent.ResponseDone(id = "r2"))
        second.yield(serverDelegate("call_2"))
        assertEquals(delegate("call_2"), iterator.next())
        // The floor is free again: a result is answered at once.
        harness.coordinator.completeFunction(callID = "call_2", output = "[COMPLETE] Done.")
        assertEquals(1, second.functionOutputs)
        assertEquals(2, second.responseCreates)

        harness.coordinator.end()
    }

    @Test fun aResultFinishingWhileTheOldSocketClosesGoesToTheNewSessionAsContext() = timed {
        val harness = Harness(timing(rotateAfter = 0.05, forceRotateAfter = 30.0))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.yield(serverDelegate("call_1"))
        assertEquals(delegate("call_1"), iterator.next())
        first.holdsClose.setValue(true)

        val second = harness.nextOpened()
        eventually { second.systemMessages.isNotEmpty() }
        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        // The swap is suspended closing the old socket when the result arrives.
        eventually { first.isHeldClosing }
        harness.coordinator.completeFunction(callID = "call_1", output = "[COMPLETE] Fixed it.")
        assertEquals(true, second.systemMessages.lastOrNull()?.startsWith("Result of an earlier request"))
        assertEquals(true, second.systemMessages.lastOrNull()?.contains("[COMPLETE] Fixed it."))
        assertTrue(first.functionOutputs == 0 && second.functionOutputs == 0)
        assertEquals("The reply waits for the swap to finish", 0, second.responseCreates)

        first.releaseClose()
        eventually { second.responseCreates > 0 }
        assertEquals(1, second.responseCreates)

        harness.coordinator.end()
    }

    @Test fun aResultFinishingWhileALostSocketClosesIsKeptForTheNextSession() = timed {
        val harness = Harness(timing(retryDelays = listOf(0.01, 0.01, 0.01)))
        val iterator = EventIterator(harness.start(), this)
        val first = harness.nextOpened()

        first.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        first.yield(serverDelegate("call_1"))
        assertEquals(delegate("call_1"), iterator.next())
        first.holdsClose.setValue(true)
        first.finish(throwing = connectionLost())
        assertEquals(VoiceCallEvent.Reconnecting, iterator.next())

        eventually { first.isHeldClosing }
        harness.coordinator.completeFunction(callID = "call_1", output = "[COMPLETE] Fixed it.")
        assertEquals(0, first.functionOutputs)

        val second = harness.nextOpened()
        eventually { second.systemMessages.isNotEmpty() }
        second.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Reconnected, iterator.next())
        eventually { second.responseCreates > 0 }
        assertTrue(second.systemMessages.any { it.startsWith("Result of an earlier request") && it.contains("[COMPLETE] Fixed it.") })
        assertEquals(0, second.functionOutputs)
        assertEquals(1, second.responseCreates)

        first.releaseClose()
        harness.coordinator.end()
    }

    @Test fun hangingUpDuringStalledHandshakeStopsRingsAndAudioPromptly() = timed {
        val connectWaiter = CompletableDeferred<Unit>()
        val connectReturn = CompletableDeferred<RealtimeConnection>()
        val socket = FakeSocket()
        val audioStopped = LockIsolated(false)
        val callID = UUID.randomUUID()
        val dependencies = coordinatorDependencies()
        val sounds = dependencies[VoiceCallSounds]
        val coordinator = DependencyValues.withValues(dependencies) { VoiceCallCoordinator(timing = VoiceCallTiming()) }

        val realtime = RealtimeClient { _, _ ->
            connectWaiter.complete(Unit)
            withContext(NonCancellable) { connectReturn.await() }
        }
        val audio = VoiceAudioClient(start = {
            sounds.startCallClock(id = callID) { }
            VoiceAudioSession(
                capture = emptyFlow(),
                events = emptyFlow(),
                enqueue = { _, _ -> },
                flush = { 0 },
                setMuted = { },
                noteConnected = { sounds.noteConnected(id = callID) },
                stop = {
                    audioStopped.setValue(true)
                    sounds.endCallClock(id = callID, playEndCue = false)
                },
            )
        })

        val startTask = async {
            coordinator.start(
                apiKey = "test",
                config = RealtimeSessionConfig(instructions = "", tools = emptyList()),
                realtime = realtime,
                audio = audio,
            )
        }

        // Wait until audio + ring clock have started and the handshake is stalled in connect.
        eventually { connectWaiter.isCompleted }
        // Past the beam's first turnaround, where the first ring plays.
        delay((DotScopeSimulation.SoundScheduler.pingTime(1) + 0.08).seconds)
        assertTrue("Ring clock started while connecting", sounds.snapshot().scheduledCues.isNotEmpty())
        assertFalse(audioStopped.value)

        // Hang up while the handshake is still stalled: audio and rings stop immediately,
        // without waiting for the stalled socket handshake to return.
        coordinator.end()
        assertTrue("End during a stalled handshake must stop audio immediately", audioStopped.value)
        assertNull("Ring clock must stop on pre-connect hang-up", sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        val cuesAtHangUp = sounds.snapshot().scheduledCues
        assertFalse("Pre-connect hang-up must not play .ended", cuesAtHangUp.map { it.cue }.contains(DotScopeSimulation.Cue.ended))
        delay(80.milliseconds)
        assertEquals("No rings fire after hang-up", cuesAtHangUp, sounds.snapshot().scheduledCues)

        // When the stalled handshake finally returns, the socket is discarded and start throws.
        connectReturn.complete(socket.connection())
        assertTrue(runCatching { startTask.await() }.isFailure)
        assertTrue("Late-opening socket from a cancelled handshake must be closed", socket.closed.value)
    }

    // The microphone path: nothing the user says is dropped or stranded.

    @Test fun everyCapturedFrameReachesTheServerAroundTheEndOfAReply() = timed {
        val harness = Harness(timing(forceRotateAfter = 3_300.0))
        val iterator = EventIterator(harness.start(), this)
        val socket = harness.nextOpened()
        socket.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        val appends = { socket.commands.value.count { it is RealtimeCommand.AppendAudio } }

        // The agent is replying; the user starts talking just before playback drains.
        socket.yield(RealtimeServerEvent.ResponseCreated(id = "r1"))
        socket.yield(RealtimeServerEvent.OutputAudio(responseID = "r1", itemID = "a1", pcm = ByteArray(4_800)))
        for (n in 0 until 3) harness.microphone(ByteArray(2_400) { n.toByte() })
        harness.audio(VoiceAudioEvent.playbackDrained)
        for (n in 3 until 15) harness.microphone(ByteArray(2_400) { n.toByte() })
        socket.yield(RealtimeServerEvent.SpeechStarted)
        for (n in 15 until 20) harness.microphone(ByteArray(2_400) { n.toByte() })
        eventually { appends() == 20 }
        assertEquals("No frame is dropped after playback drains, so the server's audio stays continuous", 20, appends())
        harness.coordinator.end()
    }

    @Test fun mutingMidSentenceEndsTheTurn() = timed {
        val harness = Harness(timing(forceRotateAfter = 3_300.0))
        val iterator = EventIterator(harness.start(), this)
        val socket = harness.nextOpened()
        socket.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())
        socket.yield(RealtimeServerEvent.SpeechStarted)
        iterator.next()
        harness.coordinator.setMuted(true)
        eventually { socket.commands.value.contains(RealtimeCommand.CommitAudio) }
        assertTrue("The server hears no more audio to end the turn itself", socket.commands.value.contains(RealtimeCommand.CommitAudio))
        // A commit creates no reply; the turn is still answered once the grace passes.
        for (i in 0 until 60) {
            if (socket.responseCreates != 0) break
            delay(50.milliseconds)
        }
        assertEquals(1, socket.responseCreates)
        harness.coordinator.end()
    }

    @Test fun audioArrivingWhileInterruptedNeverWedgesTheFloor() = timed {
        val harness = Harness(timing(forceRotateAfter = 3_300.0))
        val iterator = EventIterator(harness.start(), this)
        val socket = harness.nextOpened()
        socket.yield(RealtimeServerEvent.SessionCreated(expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, iterator.next())

        // Another app takes the audio mid-reply; the socket keeps streaming the reply.
        socket.yield(RealtimeServerEvent.ResponseCreated(id = "r1"))
        harness.audio(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AudioInterrupted, iterator.next())
        socket.yield(RealtimeServerEvent.OutputAudio(responseID = "r1", itemID = "a1", pcm = ByteArray(4_800)))
        socket.yield(RealtimeServerEvent.ResponseDone(id = "r1"))

        // A result arriving now can't be heard: it waits for the audio to come back.
        harness.coordinator.completeFunction(callID = "c-unknown", output = "Done.")
        delay(100.milliseconds)
        assertEquals(0, socket.responseCreates)

        // Back: it's spoken, and nothing waits on a drain that won't come.
        harness.audio(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, iterator.next())
        eventually { socket.responseCreates == 1 }
        assertEquals(1, socket.responseCreates)
        harness.coordinator.end()
    }
}

class VoiceResumeFeatureTests {
    @Test fun reconnectingShowsOnlyWhenTheSwapIsSlow() = voiceTest { h ->
        val clock = TestClock()
        val events = EventStream()
        val store = h.store(h.state { State(server, session) }) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[Clock] = clock
            voiceCall { copy(start = { _, _ -> events.events }) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.Start)
        events.yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live() }

        // A quick swap never shows.
        events.yield(VoiceCallEvent.Reconnecting)
        store.receive(Action.CallEvent::class)
        clock.advance(400.milliseconds)
        events.yield(VoiceCallEvent.Reconnected)
        store.receive(Action.CallEvent::class)
        clock.advance(2.seconds)
        assertFalse(store.state.isReconnecting)

        // A slow one does, until it's back.
        events.yield(VoiceCallEvent.Reconnecting)
        store.receive(Action.CallEvent::class)
        clock.advance(1.seconds)
        store.receive(Action.ReconnectingShown::class) { it.copy(isReconnecting = true) }
        assertEquals("Reconnecting…", store.state.statusText)
        events.yield(VoiceCallEvent.Reconnected)
        store.receive(Action.CallEvent::class) { it.copy(isReconnecting = false) }

        events.finish(throwing = RealtimeClientError.Dropped)
        store.receive(Action.CallDropped::class) { it.withPhase(State.Phase.Dropped) }

        // Rejoin starts a new call that remembers this one.
        store.send(Action.RejoinTapped)
        store.receive(Action.Start::class) { it.withPhase(State.Phase.Connecting) }
    }

    @Test fun aDropMidReplyClearsTheLiveReplyAndRejoinStartsClean() = voiceTest { h ->
        val clock = TestClock()
        val events = EventStream()
        val store = h.store(h.state { State(server, session) }) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[Clock] = clock
            voiceCall { copy(start = { _, _ -> events.events }) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.Start)
        events.yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live() }
        events.yield(VoiceCallEvent.AssistantDelta(itemID = "a1", delta = "The upload test races"))
        store.receive(Action.CallEvent::class) {
            it.copy(isAssistantSpeaking = true, liveAssistantItemID = "a1", liveAssistantText = "The upload test races")
        }
        assertEquals("Speaking", store.state.statusText)

        // The reply was cut off with the connection: nothing stays pinned in the captions.
        events.yield(VoiceCallEvent.Reconnecting)
        store.receive(Action.CallEvent::class) {
            it.copy(isAssistantSpeaking = false, liveAssistantItemID = null, liveAssistantText = "")
        }
        clock.advance(1.seconds)
        store.receive(Action.ReconnectingShown::class) { it.copy(isReconnecting = true) }
        assertEquals("Reconnecting…", store.state.statusText)

        events.finish(throwing = RealtimeClientError.Dropped)
        store.receive(Action.CallDropped::class) {
            it.withPhase(State.Phase.Dropped).copy(isReconnecting = false)
        }
        store.send(Action.RejoinTapped)
        store.receive(Action.Start::class) { it.withPhase(State.Phase.Connecting) }
        assertTrue(store.state.liveAssistantText.isEmpty() && store.state.liveAssistantItemID == null)
        assertTrue(!store.state.isAssistantSpeaking && !store.state.isReconnecting)
    }

    @Test fun theCallsDurationCountsFromConnectingThroughReconnectsUntilRejoin() = voiceTest { h ->
        val clock = TestClock()
        val now = LockIsolated(date(100))
        val calls = LockIsolated(listOf<EventStream>())
        val store = h.store(h.state { State(server, session) }) {
            this[DateClient] = DateClient { now.value }
            this[Clock] = clock
            voiceCall {
                copy(start = { _, _ ->
                    val stream = EventStream()
                    calls.append(stream)
                    stream.events
                })
            }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.Start)
        assertNull("Connecting isn't counted", store.state.connectedAt)
        VoiceFeatureTestSupport.eventually { calls.value.isNotEmpty() }
        now.setValue(date(103))
        calls.value[0].yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live().copy(connectedAt = date(103)) }

        // A slow swap to a new connection is the same call.
        now.setValue(date(160))
        calls.value[0].yield(VoiceCallEvent.Reconnecting)
        store.receive(Action.CallEvent::class)
        clock.advance(1.seconds)
        store.receive(Action.ReconnectingShown::class) { it.copy(isReconnecting = true) }
        calls.value[0].yield(VoiceCallEvent.Reconnected)
        store.receive(Action.CallEvent::class) { it.copy(isReconnecting = false) }
        assertEquals(date(103), store.state.connectedAt)

        calls.value[0].finish(throwing = RealtimeClientError.Dropped)
        store.receive(Action.CallDropped::class) { it.withPhase(State.Phase.Dropped) }

        // Rejoin is a new call: it counts from its own connection.
        store.send(Action.RejoinTapped)
        store.receive(Action.Start::class) { it.withPhase(State.Phase.Connecting).copy(connectedAt = null) }
        VoiceFeatureTestSupport.eventually { calls.value.size >= 2 }
        now.setValue(date(200))
        calls.value[1].yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live().copy(connectedAt = date(200)) }
        h.skipInFlightEffects()
    }

    @Test fun reconnectingAndPausedOutrankWhatTheCallWasDoing() = voiceTest { h ->
        var state = h.state { State(server, session) }.live()
        state = state.copy(isAssistantSpeaking = true, targetNeedsInput = true, isAudioInterrupted = true)
        assertEquals("Paused", state.statusText)
        state = state.copy(isReconnecting = true)
        assertEquals("Reconnecting…", state.statusText)
    }
}
