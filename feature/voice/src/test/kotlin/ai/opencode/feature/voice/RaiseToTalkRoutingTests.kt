package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.feature.voice.FakeVoiceAudioSession.Event
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The external (WebRTC) owner's controls, recording playback gain into the fake session. */
internal class RecordingAudioControl(private val session: FakeVoiceAudioSession) : VoiceExternalAudioControl {
    override fun setMuted(muted: Boolean) {}
    override fun setPlaybackVolume(volume: Float) = session.record(Event.VolumeChanged(volume))
    override fun flush() {}
    override fun interrupted(interrupted: Boolean) {
        if (!interrupted) session.record(Event.PlaybackStarted)
    }
    override fun stop() {}
}

/**
 * The engine runs its ramp on `Dispatchers.IO`, so [advance] moves one ramp step at a time and
 * waits for the woken step to land (sleep again or finish) before the next. The engine's teardown
 * waits (250/500 ms) settle at once and its backstop and quality window (≥ 3 s) never wake.
 */
internal class RampClock : Clock {
    private val lock = Any()
    private var time = Duration.ZERO
    private val sleepers = mutableListOf<Pair<Duration, CancellableContinuation<Unit>>>()
    private val pending get() = synchronized(lock) { sleepers.size }

    override fun now(): Duration = synchronized(lock) { time }

    override suspend fun sleep(duration: Duration) {
        if (duration > 1.seconds) awaitCancellation()
        if (duration >= 250.milliseconds || duration <= Duration.ZERO) return
        suspendCancellableCoroutine { continuation ->
            val sleeper = synchronized(lock) { (time + duration to continuation).also(sleepers::add) }
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(sleeper) } }
        }
    }

    suspend fun advance(by: Duration, engine: VoiceAudioEngine) {
        val step = VoiceAudioEngine.speakerRampDuration / VoiceAudioEngine.speakerRampSteps
        var left = by
        while (left > Duration.ZERO) {
            settle(engine)
            val move = minOf(left, step)
            val due = synchronized(lock) {
                time += move
                sleepers.filter { it.first <= time }.also { sleepers.removeAll(it) }
            }
            due.forEach { it.second.resume(Unit) }
            left -= move
        }
        settle(engine)
    }

    /** Waits until the ramp sleeps again or has finished (gain back at 1). */
    private suspend fun settle(engine: VoiceAudioEngine) {
        assertTrue("ramp step never landed", pollUntil { pending > 0 || engine.playbackVolume == 1f })
    }
}

/** A host coordinator whose finish check and end the test holds. */
internal class SuspendingTestCoordinator(
    private val onCheckIsFinished: suspend () -> Boolean,
    private val onEnd: suspend () -> Unit = {},
) : VoiceCallTestingCoordinator {
    private val isFinishedCalls = AtomicInteger()
    private val endCalls = AtomicInteger()

    override suspend fun checkIsFinished(): Boolean {
        isFinishedCalls.incrementAndGet()
        return onCheckIsFinished()
    }
    override suspend fun setAtEar(atEar: Boolean) {}
    override suspend fun end() {
        endCalls.incrementAndGet()
        onEnd()
    }
    suspend fun waitForIsFinishedCall(count: Int) = assertTrue(pollUntil { isFinishedCalls.get() >= count })
    suspend fun waitForEndCall() = assertTrue(pollUntil { endCalls.get() >= 1 })
}

/** Android's seam has no category or port override: activation is [Event.SetActive]. */
class RaiseToTalkRoutingTests {
    private val volumes: (FakeVoiceAudioSession) -> List<Float> =
        { fake -> fake.events.filterIsInstance<Event.VolumeChanged>().map { it.volume } }

    private fun liveConfig(isAtEar: Boolean) = RealtimeSessionConfig(
        engine = VoiceEngine.gptLive, model = LiveSessionConfig.defaultModel, instructions = "test", tools = emptyList(), isAtEar = isAtEar,
    )

    @Test fun setAtEarBeforeRunningIsNotLost() = routingTest {
        val fake = FakeVoiceAudioSession(builtInReceiver)
        val engine = routingEngine(fake, RampClock(), atEar = false)
        // Arrives before `engine.start()` sets `running = true`.
        engine.setAtEar(true)
        val audio = engine.start()
        audio.enqueue(byteArrayOf(1, 2), null)
        assertEquals(listOf(Event.SetActive(true), Event.Route(atEar = true)), fake.events)
        assertFalse(fake.events.contains(Event.Route(atEar = false)))
        audio.stop()
    }

    @Test fun loweringRampsAndOverridesToSpeakerWithoutResettingCategory() = routingTest {
        val fake = FakeVoiceAudioSession(builtInReceiver)
        val notes = mutableListOf<String>()
        val clock = RampClock()
        val engine = routingEngine(fake, clock, atEar = true, notes = notes)
        val audio = engine.start()
        fake.clearEvents()
        synchronized(notes) { notes.clear() }

        // Lowering from the ear drops gain before routing to the speaker, then ramps to 1.0.
        audio.setAtEar(false)
        assertEquals(listOf(Event.VolumeChanged(VoiceAudioEngine.speakerRampStartVolume), Event.Route(atEar = false)), fake.events)
        assertFalse(fake.events.contains(Event.SetActive(true)))
        synchronized(notes) {
            assertTrue(notes.contains("Ramping speaker gain (280 ms)"))
            assertTrue(notes.any { it.startsWith("Away from the ear: speaker · ") })
        }

        clock.advance(VoiceAudioEngine.speakerRampDuration, engine)
        val ramp = volumes(fake)
        assertEquals(VoiceAudioEngine.speakerRampStartVolume, ramp.first())
        assertEquals(1f, ramp.last())
        assertEquals(VoiceAudioEngine.speakerRampSteps + 1, ramp.size)
        ramp.zipWithNext().forEach { (a, b) -> assertTrue("$ramp", b > a) }

        // Raising back to the ear mid-ramp cancels the ramp and restores 1.0 immediately.
        audio.setAtEar(true)
        audio.setAtEar(false)
        clock.advance(80.milliseconds, engine)
        fake.clearEvents()
        synchronized(notes) { notes.clear() }
        audio.setAtEar(true)
        assertEquals(listOf(Event.VolumeChanged(1f), Event.Route(atEar = true)), fake.events)
        synchronized(notes) {
            assertEquals(1, notes.size)
            assertTrue(notes.first().startsWith("At the ear: earpiece · "))
        }
        audio.stop()
    }

    @Test fun externalRouteIgnoresTheEarAndLowering() = routingTest {
        val externalPorts = listOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_HDMI,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        assertTrue(externalPorts.all(VoiceAudioRoute::isExternalOutput))
        for (port in externalPorts) {
            val fake = FakeVoiceAudioSession(listOf(port))
            val notes = mutableListOf<String>()
            val clock = RampClock()
            val engine = routingEngine(fake, clock, atEar = false, notes = notes)
            val audio = engine.start()
            // External route on start never overrides to speaker (the route follows the device).
            assertEquals(VoiceAudioRoute.Override.none, VoiceAudioRoute.desiredOverride(atEar = false, hasExternalOutput = true))
            assertTrue(volumes(fake).isEmpty())

            fake.clearEvents()
            synchronized(notes) { notes.clear() }
            audio.setAtEar(true)
            audio.setAtEar(false)
            assertTrue("$port ${fake.events}", fake.events.isEmpty())
            synchronized(notes) { assertTrue("$port $notes", notes.isEmpty()) }
            audio.stop()
        }
    }

    @Test fun oldDeviceUnavailableFallsBackToReceiverAtEarOrSpeakerWithRamp() = routingTest {
        // 1. Unplugging headphones while at the ear falls back to the receiver without ramping.
        val earFake = FakeVoiceAudioSession(listOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        val earNotes = mutableListOf<String>()
        val earEngine = routingEngine(earFake, RampClock(), atEar = false, notes = earNotes)
        val earAudio = earEngine.start()
        // User raises phone to ear while wearing headphones (remembered, not routed yet).
        earAudio.setAtEar(true)
        earFake.clearEvents()
        synchronized(earNotes) { earNotes.clear() }

        // Headphones come out: route falls back to the built-in receiver.
        earFake.setOutputPorts(builtInReceiver)
        earEngine.handleConfigurationChange()
        assertEquals(listOf(Event.Route(atEar = true)), earFake.events)
        synchronized(earNotes) { assertTrue("$earNotes", earNotes.firstOrNull()?.startsWith("At the ear: earpiece · ") == true) }
        earAudio.stop()

        // 2. Taking earbuds out while away from the ear falls back to the speaker with the gain ramp.
        val speakerFake = FakeVoiceAudioSession(listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        val speakerNotes = mutableListOf<String>()
        val clock = RampClock()
        val speakerEngine = routingEngine(speakerFake, clock, atEar = false, notes = speakerNotes)
        val speakerAudio = speakerEngine.start()
        speakerFake.clearEvents()
        synchronized(speakerNotes) { speakerNotes.clear() }

        speakerFake.setOutputPorts(builtInReceiver)
        speakerEngine.handleConfigurationChange()
        assertEquals(listOf(Event.VolumeChanged(VoiceAudioEngine.speakerRampStartVolume), Event.Route(atEar = false)), speakerFake.events)
        synchronized(speakerNotes) {
            assertTrue(speakerNotes.contains("Ramping speaker gain (280 ms)"))
            assertTrue("$speakerNotes", speakerNotes.any { it.startsWith("Away from the ear: speaker · ") })
        }

        clock.advance(VoiceAudioEngine.speakerRampDuration, speakerEngine)
        assertEquals(1f, volumes(speakerFake).last())

        // 3. Connecting a new external device while on speaker clears the speaker route.
        speakerFake.clearEvents()
        speakerFake.setOutputPorts(listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        speakerEngine.handleConfigurationChange()
        assertEquals(listOf(Event.Route(atEar = false)), speakerFake.events)
        speakerAudio.stop()
    }

    @Test fun deadPreviousCoordinatorDoesNotReceiveSetAtEar() = routingTest {
        val host = VoiceCallHost()
        val firstAudioRoutes = mutableListOf<Boolean>()
        val firstSocket = Channel<LiveServerEvent>(Channel.UNLIMITED)
        val events = host.start(
            apiKey = "sk-test",
            config = liveConfig(isAtEar = true),
            realtime = RealtimeClient.testValue,
            live = LiveClient { _, _ -> LiveConnection(events = firstSocket.receiveAsFlow(), send = {}, close = {}) },
            audio = VoiceAudioClient(start = {
                VoiceAudioSession(
                    capture = emptyFlow(), events = emptyFlow(), enqueue = { _, _ -> }, flush = { 0 }, setMuted = {}, stop = {},
                    setAtEar = { atEar -> synchronized(firstAudioRoutes) { firstAudioRoutes += atEar } },
                )
            }),
        )
        // End the first coordinator naturally (without calling `host.end()`).
        firstSocket.close()
        runCatching { events.toList() }
        synchronized(firstAudioRoutes) { firstAudioRoutes.clear() }

        // `setAtEar` while `current` is already finished must not forward to the dead coordinator.
        host.setAtEar(false)
        host.setAtEar(true)
        synchronized(firstAudioRoutes) { assertTrue("$firstAudioRoutes", firstAudioRoutes.isEmpty()) }
        host.end()
    }

    @Test fun staleSetAtEarResumingAfterNewStartDoesNotWipeCurrentOrWritePending() = routingTest {
        val host = VoiceCallHost()
        val finishGate1 = CompletableDeferred<Boolean>()
        val c1 = SuspendingTestCoordinator(onCheckIsFinished = { finishGate1.await() })
        host.installForTesting(c1)

        // Interleaving 1a: `setAtEar(true)` enters while C1 is `current` (with no
        // `startingAttemptID`), and suspends at `c1.checkIsFinished()`.
        val staleTask = launch { host.setAtEar(true) }
        c1.waitForIsFinishedCall(1)

        // While `staleTask` is suspended, a new `start` installs C2 into `current`. The host is
        // one serial dispatcher, so the start queues behind the suspended `setAtEar` and runs
        // once it suspends (`finishGate1` is a suspension, not a block).
        val c2Routes = mutableListOf<Boolean>()
        val socket = Channel<LiveServerEvent>(Channel.UNLIMITED)
        val c2Events: Flow<VoiceCallEvent> = host.start(
            apiKey = "sk-test",
            config = liveConfig(isAtEar = false),
            realtime = RealtimeClient.testValue,
            live = LiveClient { _, _ -> LiveConnection(events = socket.receiveAsFlow(), send = {}, close = {}) },
            audio = VoiceAudioClient(start = {
                VoiceAudioSession(
                    capture = emptyFlow(), events = emptyFlow(), enqueue = { _, _ -> }, flush = { 0 }, setMuted = {}, stop = {},
                    setAtEar = { atEar -> synchronized(c2Routes) { c2Routes += atEar } },
                )
            }),
        )

        // Now release `staleTask` with `isFinished == true`: it must NOT wipe C2 from `current`.
        finishGate1.complete(true)
        staleTask.join()

        // C2 is still installed in `current` and receives subsequent `setAtEar` updates.
        host.setAtEar(true)
        synchronized(c2Routes) { assertEquals(listOf(true), c2Routes) }
        socket.close()
        host.end()
        c2Events.hashCode()

        // Interleaving 1b: `setAtEar(true)` enters before `start` (`entryAttemptID == null`),
        // and resumes while `start` is suspended inside `endCurrent()` (`startingAttemptID != null`).
        // It must NOT write `pendingAtEar` into the new start attempt.
        val finishGate2 = CompletableDeferred<Boolean>()
        val endGate = CompletableDeferred<Unit>()
        val c3 = SuspendingTestCoordinator(onCheckIsFinished = { finishGate2.await() }, onEnd = { endGate.await() })
        host.installForTesting(c3)

        val preStartTask = launch { host.setAtEar(true) }
        c3.waitForIsFinishedCall(1)

        val startedAtEar = mutableListOf<Boolean>()
        val socket2 = Channel<LiveServerEvent>(Channel.UNLIMITED)
        val startTask = async {
            host.start(
                apiKey = "sk-test",
                config = liveConfig(isAtEar = false),
                realtime = RealtimeClient.testValue,
                live = LiveClient { _, _ -> LiveConnection(events = socket2.receiveAsFlow(), send = {}, close = {}) },
                audio = VoiceAudioClient(
                    start = { VoiceAudioClient.testValue.start() },
                    startAtEar = { atEar ->
                        synchronized(startedAtEar) { startedAtEar += atEar }
                        VoiceAudioClient.testValue.start()
                    },
                ),
            )
        }
        c3.waitForEndCall()

        // `preStartTask` resumes while `startTask` is awaiting `endCurrent()`:
        // because `entryAttemptID` was null, it must not taint `startTask`'s `pendingAtEar`.
        finishGate2.complete(true)
        preStartTask.join()

        endGate.complete(Unit)
        startTask.await()
        assertTrue(pollUntil { synchronized(startedAtEar) { startedAtEar.isNotEmpty() } })
        synchronized(startedAtEar) { assertEquals(listOf(false), startedAtEar) }
        socket2.close()
        host.end()
    }

    @Test fun olderSetAtEarResumingAfterNewerSetAtEarDoesNotOverwritePending() = routingTest {
        val host = VoiceCallHost()
        val gate1 = CompletableDeferred<Boolean>()
        val gate2 = CompletableDeferred<Boolean>()
        val endGate = CompletableDeferred<Unit>()
        val callNumber = AtomicInteger()
        val c1 = SuspendingTestCoordinator(
            onCheckIsFinished = { if (callNumber.incrementAndGet() == 1) gate1.await() else gate2.await() },
            onEnd = { endGate.await() },
        )
        host.installForTesting(c1)

        val startedAtEar = mutableListOf<Boolean>()
        val socket = Channel<LiveServerEvent>(Channel.UNLIMITED)
        val startTask = async {
            host.start(
                apiKey = "sk-test",
                config = liveConfig(isAtEar = true),
                realtime = RealtimeClient.testValue,
                live = LiveClient { _, _ -> LiveConnection(events = socket.receiveAsFlow(), send = {}, close = {}) },
                audio = VoiceAudioClient(
                    start = { VoiceAudioClient.testValue.start() },
                    startAtEar = { atEar ->
                        synchronized(startedAtEar) { startedAtEar += atEar }
                        VoiceAudioClient.testValue.start()
                    },
                ),
            )
        }
        c1.waitForEndCall()

        // Both `setAtEar` calls enter during `startTask`'s `endCurrent()` window:
        // older `setAtEar(true)` enters first, newer `setAtEar(false)` enters second.
        val olderCall = launch { host.setAtEar(true) }
        c1.waitForIsFinishedCall(1)
        val newerCall = launch { host.setAtEar(false) }
        c1.waitForIsFinishedCall(2)

        // Newer `setAtEar(false)` resumes first; older `setAtEar(true)` resumes second.
        gate2.complete(true)
        newerCall.join()
        gate1.complete(true)
        olderCall.join()

        // Release `endCurrent()`: the newer `false` must win over the stale `true` resume.
        endGate.complete(Unit)
        startTask.await()
        assertTrue(pollUntil { synchronized(startedAtEar) { startedAtEar.isNotEmpty() } })
        synchronized(startedAtEar) { assertEquals(listOf(false), startedAtEar) }
        socket.close()
        host.end()
    }

    @Test fun cancellingRampOrStartingANewRampIgnoresStaleQueuedSteps() = routingTest {
        val fake = FakeVoiceAudioSession(builtInReceiver)
        val clock = RampClock()
        val engine = routingEngine(fake, clock, atEar = true)
        val audio = engine.start()

        // 1. Cancel during a ramp -> a queued step from the cancelled generation is a no-op.
        audio.setAtEar(false)
        val cancelledGeneration = engine.rampGeneration
        clock.advance(80.milliseconds, engine)
        audio.setAtEar(true)
        assertEquals(1f, engine.playbackVolume)
        fake.clearEvents()

        engine.stepSpeakerRamp(cancelledGeneration, 0.42f, finished = false)
        engine.stepSpeakerRamp(cancelledGeneration, 0.85f, finished = true)
        assertEquals(1f, engine.playbackVolume)
        assertTrue(fake.events.isEmpty())

        // 2. Starting a new ramp while an old final step is queued -> the old step is ignored.
        audio.setAtEar(false)
        val firstGeneration = engine.rampGeneration
        clock.advance(120.milliseconds, engine)
        audio.setAtEar(true)
        audio.setAtEar(false)
        val secondGeneration = engine.rampGeneration
        assertTrue(secondGeneration > firstGeneration)
        assertEquals(VoiceAudioEngine.speakerRampStartVolume, engine.playbackVolume)

        // Old final step arrives while the new ramp is at its start volume.
        engine.stepSpeakerRamp(firstGeneration, 1f, finished = true)
        assertEquals(VoiceAudioEngine.speakerRampStartVolume, engine.playbackVolume)

        // The new ramp still completes to 1.0.
        clock.advance(VoiceAudioEngine.speakerRampDuration, engine)
        assertEquals(1f, engine.playbackVolume)
        audio.stop()
    }

    /** The speaker route is re-applied there. */
    @Test fun manualStartSpeakerOverrideIsReappliedAcrossRecoveryPaths() = routingTest {
        val fake = FakeVoiceAudioSession(builtInReceiver)
        val clock = RampClock()
        val engine = routingEngine(fake, clock, atEar = false)
        val audio = engine.start()
        assertTrue(fake.events.contains(Event.Route(atEar = false)))

        // 1. Interruption began cancels any active ramp; interruption ended re-applies the speaker.
        engine.setAtEar(true)
        engine.setAtEar(false)
        val interruptedGeneration = engine.rampGeneration
        fake.clearEvents()
        engine.handleInterruption(began = true)
        engine.stepSpeakerRamp(interruptedGeneration, 0.5f, finished = false)
        assertEquals(1f, engine.playbackVolume)

        fake.clearEvents()
        engine.handleInterruption(began = false)
        assertTrue("${fake.events}", fake.events.contains(Event.Route(atEar = false)))

        // 2./3. A route or configuration change re-applies the speaker.
        fake.clearEvents()
        engine.handleConfigurationChange()
        assertTrue("${fake.events}", fake.events.contains(Event.Route(atEar = false)))
        clock.advance(VoiceAudioEngine.speakerRampDuration, engine)

        // 4. Media services reset stops the engine and yields `.mediaServicesReset`; a fresh manual start re-applies the speaker.
        engine.handleMediaReset()
        val events = audio.events.toList()
        assertTrue("$events", events.contains(VoiceAudioEvent.mediaServicesReset))
        assertFalse(engine.running)

        val restartedFake = FakeVoiceAudioSession(builtInReceiver)
        val restartedAudio = routingEngine(restartedFake, RampClock(), atEar = false).start()
        assertTrue(restartedFake.events.contains(Event.Route(atEar = false)))
        restartedAudio.stop()
    }
}
