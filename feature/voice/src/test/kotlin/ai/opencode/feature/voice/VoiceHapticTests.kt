package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.feature.voice.DotScopeSimulation.Cue
import ai.opencode.feature.voice.DotScopeSimulation.Scan
import android.Manifest
import android.app.Notification
import android.os.VibratorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun <T> required(value: T?, message: String = "Expected a value"): T {
    assertNotNull(message, value)
    return value!!
}

/**
 * The call's haptics land with its sounds and its sweep. `patternsBecomeCorePatterns` is dropped:
 * Core Haptics splits a pattern into players, while Android's `VoiceHapticEngine` mixes every
 * pattern into one vibrator waveform.
 */
class VoiceHapticTests {
    private val widths = listOf(360f, 408f, 440f)
    private val origins = listOf(0f, 0.2f, 0.5f, 0.8f, 1f)

    /** Where the beam is as it launches out of the call button: its progress and overshoot. */
    private val launches: List<Pair<Float, Float>> = listOf(0f, 0.03f, 0.12f, 0.3f).map { elapsed ->
        val beam = Scan.connectingBeam(elapsed)
        beam.progress to beam.overshoot
    }

    /** Output latency the patterns are offset by; any value works, the patterns must carry it through. */
    private val audioDelay = 0.023

    /** When a sound first swings to half its loudest sample, read straight off the samples. */
    private fun halfPeakOnset(samples: FloatArray): Double {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        val index = samples.indexOfFirst { abs(it) >= 0.5f * peak }.coerceAtLeast(0)
        return index / PCM.sampleRate
    }

    // MARK: Sounds

    @Test fun envelopeFindsEachCuesAttack() {
        for (cue in listOf(VoiceCallCue.connect, VoiceCallCue.hangUp)) {
            val samples = VoiceCallSoundBank.samples(cue)
            val envelope = VoiceHapticDesign.envelope(cue)
            // Connect strikes at once; the hang-up's air swells in over 50 ms.
            assertTrue("$cue onset ${envelope.onset}", envelope.onset < 0.03)
            assertTrue("$cue onset disagrees with its samples", abs(envelope.onset - halfPeakOnset(samples)) < 0.005)
            assertTrue(envelope.peak >= envelope.onset)
            val length = samples.size / PCM.sampleRate
            assertTrue(envelope.end > envelope.peak && envelope.end <= length)
            assertTrue(envelope.level(envelope.end + 0.01) < VoiceCueEnvelope.floor)
        }
    }

    @Test fun connectAndHangUpRingAsLongAsTheirSounds() {
        val connect = VoiceHapticDesign.envelope(VoiceCallCue.connect)
        val hangUp = VoiceHapticDesign.envelope(VoiceCallCue.hangUp)
        // −30 dB of each: Wisp's 0.96 s connect bell and 0.38 s hang-up.
        assertTrue(connect.end > 0.6 && connect.end <= 0.96)
        assertTrue(hangUp.end > 0.3 && hangUp.end <= 0.38)
    }

    // MARK: Connecting

    @Test fun ticksLandAtEachTurnaroundAsItsHeard() {
        for (cue in listOf(Cue.sweepRight, Cue.sweepLeft)) {
            val pattern = VoiceHapticDesign.tick(cue, audioDelay = audioDelay)
            assertTrue(pattern.shaped.isEmpty())
            assertEquals(listOf(audioDelay), pattern.transients)
        }
        // The left turnaround ticks crisper than the right.
        val right = VoiceHapticDesign.tick(Cue.sweepRight, audioDelay = 0.0).events[0]
        val left = VoiceHapticDesign.tick(Cue.sweepLeft, audioDelay = 0.0).events[0]
        assertTrue(left.sharpness > right.sharpness)
        assertTrue(right.sharpness > 0.3f)
    }

    @Test fun ticksAreScheduledAPassAheadForEveryPingAtTheBeamsTurnaround() {
        val scheduler = DotScopeSimulation.SoundScheduler()
        scheduler.setStage(DotScopeSimulation.Stage.connecting)
        val predicted = mutableListOf<Pair<Cue, Double>>()
        val heard = mutableListOf<Pair<Cue, Double>>()
        var lastPass: Int? = null
        repeat(3_500) {
            val ring = scheduler.nextRing
            if (ring != null && ring.pass != lastPass) {
                lastPass = ring.pass
                predicted += ring.cue to ring.elapsed
            }
            scheduler.advance(by = 0.001)
            for (cue in scheduler.poll()) heard += cue to scheduler.elapsed
        }
        assertTrue(heard.size >= 6)
        for ((index, ping) in heard.withIndex()) {
            assertEquals(predicted[index].first, ping.first)
            assertTrue(abs(predicted[index].second - ping.second) <= 0.0011)
            // Each ping is at a turnaround, from the first one after the launch
            // out of the call button: the beam at one end of its span.
            val turnaround = (index + 1) * Scan.sweepPass.toDouble()
            assertTrue(abs(ping.second - turnaround - DotScopeSimulation.SoundScheduler.passOffset) <= 0.0011)
            val beam = Scan.connectingBeam(turnaround.toFloat())
            assertTrue(abs(beam.progress - (if (index % 2 == 0) 0f else 1f)) < 1e-3f)
        }
    }

    // MARK: Connect

    @Test fun bloomArrivalsMatchTheSweepPositions() {
        for (width in widths) {
            for ((origin, overshoot) in origins.map { it to 0f } + launches) {
                val arrivals = Scan.bloomArrivals(origin = origin, overshoot = overshoot, width = width)
                val x0 = Scan.beamX(progress = origin, width = width) + overshoot
                val edge = DotScopeSimulation.windowInset
                val span = width + 20
                // Each front unfolds with `passPosition`: it reaches its edge when that function
                // has covered the distance to it.
                val toMute = ((x0 - edge) / span).coerceIn(0f, 1f)
                val toEnd = ((width - edge - x0) / span).coerceIn(0f, 1f)
                assertTrue("Mute at $width/$origin/$overshoot", abs(Scan.passPosition(arrivals.mute / Scan.bloomDuration) - toMute) < 0.002f)
                assertTrue("End at $width/$origin/$overshoot", abs(Scan.passPosition(arrivals.end / Scan.bloomDuration) - toEnd) < 0.002f)
            }
        }
    }

    @Test fun connectStrikesWithItsCueAndTapsAsEachFrontArrives() {
        val envelope = VoiceHapticDesign.envelope(VoiceCallCue.connect)
        val strike = audioDelay + envelope.onset
        for (width in widths) {
            for (origin in origins) {
                val pattern = VoiceHapticDesign.connect(audioDelay = audioDelay, origin = origin, width = width)
                assertTrue(abs(pattern.transients[0] - strike) < 1e-9)
                assertTrue(abs(strike - (audioDelay + halfPeakOnset(VoiceCallSoundBank.samples(VoiceCallCue.connect)))) < 0.005)

                val arrivals = Scan.bloomArrivals(origin = origin, width = width)
                val expected = listOf(arrivals.mute, arrivals.end)
                    .map { VoiceHapticDesign.displayDelay + it.toDouble() }
                    .filter { it > strike + VoiceHapticDesign.minimumGap }
                    .sorted()
                val taps = pattern.transients.drop(1)
                if (expected.size == 2 && expected[1] - expected[0] < VoiceHapticDesign.minimumGap) {
                    assertEquals(1, taps.size)
                    assertTrue(abs(taps[0] - (expected[0] + expected[1]) / 2) < 1e-9)
                } else {
                    assertEquals("$width/$origin: $taps vs $expected", expected.size, taps.size)
                    for ((tap, arrival) in taps.zip(expected)) assertTrue(abs(tap - arrival) < 1e-9)
                }
            }
        }
        // From the left turnaround, Mute is already reached and End is a long way off.
        val fromLeft = VoiceHapticDesign.connect(audioDelay = audioDelay, origin = 0f, width = 408f)
        assertEquals(2, fromLeft.transients.size)
        assertTrue(fromLeft.transients[1] > 0.25)
        // From the middle both fronts arrive together.
        assertEquals(2, VoiceHapticDesign.connect(audioDelay = audioDelay, origin = 0.5f, width = 408f).transients.size)
    }

    @Test fun connectSwellFollowsItsCuesEnvelope() {
        val envelope = VoiceHapticDesign.envelope(VoiceCallCue.connect)
        val pattern = VoiceHapticDesign.connect(audioDelay = audioDelay, origin = 0.4f, width = 408f)
        val swell = pattern.shaped[0]
        assertTrue(abs(swell.time - (audioDelay + envelope.onset)) < 1e-9)
        assertTrue(abs(swell.end - (audioDelay + envelope.end)) < 1e-9)
        assertTrue(pattern.intensity.size <= VoiceHapticPattern.maxCurvePoints)
        assertTrue(pattern.sharpness.size <= VoiceHapticPattern.maxCurvePoints)
        for (point in pattern.intensity.dropLast(1)) {
            assertTrue(abs(point.value - sqrt(envelope.level(point.time - audioDelay))) < 1e-6f)
        }
        assertEquals(0f, pattern.intensity.last().value)
        // Loudest as the ninth joins the root, then decaying.
        val loudest = pattern.intensity.maxBy { it.value }
        assertTrue(envelope.level(loudest.time - audioDelay) > 0.75f)
        assertTrue(loudest.time - audioDelay < envelope.end / 2)
        // Brightens as the fronts spread, settling as the bloom completes.
        assertTrue(pattern.sharpness.zipWithNext().all { (a, b) -> a.value < b.value })
        assertTrue(abs(pattern.sharpness.last().time - VoiceHapticDesign.displayDelay - Scan.bloomDuration.toDouble()) < 1e-6)
    }

    // MARK: Hang-up

    @Test fun hangUpLandsAsTheSweepReachesEnd() {
        val cases = listOf(Triple(true, 0f, 0f)) + origins.map { Triple(false, it, 0f) } + launches.map { Triple(false, it.first, it.second) }
        for (width in widths) {
            for ((drawn, origin, overshoot) in cases) {
                val target = width - DotScopeSimulation.windowInset
                val arrival = Scan.endingArrival(drawn = drawn, origin = origin, overshoot = overshoot, width = width).toDouble()
                if (!drawn && Scan.beamX(progress = origin, width = width) + overshoot >= target) {
                    assertEquals(0.0, arrival, 0.0)
                    continue
                }
                val start = if (drawn) Scan.endingStart else Scan.beamX(progress = origin, width = width) + overshoot
                val covered = ((target - start) / (width - Scan.endingStop - start)).coerceIn(0f, 1f)
                assertTrue("$width/$drawn/$origin/$overshoot", abs(Scan.passPosition(arrival.toFloat() / Scan.endingPass) - covered) < 0.002f)
                // It lands while the pulse is still lit.
                assertTrue(arrival < 0.5)
                val pattern = VoiceHapticDesign.hangUp(drawn = drawn, origin = origin, overshoot = overshoot, width = width)
                assertTrue(abs(pattern.transients[0] - (VoiceHapticDesign.displayDelay + arrival)) < 1e-9)
                val pulse = required(pattern.events.firstOrNull { !it.isTransient })
                assertTrue(pulse.time == pattern.transients[0] && pulse.decay != null)
                assertTrue(pulse.intensity < 0.5f && pulse.sharpness < 0.3f)
            }
        }
    }

    @Test fun glideRumblesWithItsSoundAndDullsAsItFalls() {
        val envelope = VoiceHapticDesign.envelope(VoiceCallCue.hangUp)
        val pattern = VoiceHapticDesign.glide(audioDelay = audioDelay)
        assertTrue(pattern.events.isEmpty())
        assertTrue(abs(pattern.shaped[0].time - (audioDelay + halfPeakOnset(VoiceCallSoundBank.samples(VoiceCallCue.hangUp)))) < 0.003)
        assertTrue(abs(pattern.shaped[0].end - (audioDelay + envelope.end)) < 1e-9)
        assertTrue(pattern.intensity.size <= VoiceHapticPattern.maxCurvePoints)
        assertTrue(abs(pattern.sharpness.first().value) < 0.02f)
        assertTrue(pattern.sharpness.zipWithNext().all { (a, b) -> a.value >= b.value - 1e-6f })
        assertTrue(pattern.sharpness.last().value < -0.1f)
    }

    @Test fun controlsAreDistinct() {
        val muted = required(VoiceHapticDesign.control(VoiceCallHaptic.muted)).events[0]
        val unmuted = required(VoiceHapticDesign.control(VoiceCallHaptic.unmuted)).events[0]
        assertTrue(muted.time == 0.0 && unmuted.time == 0.0)
        assertTrue(muted.sharpness < unmuted.sharpness)
        assertNull(VoiceHapticDesign.control(VoiceCallHaptic.sweepPass))
    }

    // MARK: Playback

    @Test fun aLatePatternPlaysWhatIsLeft() {
        val pattern = VoiceHapticDesign.connect(audioDelay = audioDelay, origin = 0f, width = 408f)
        assertEquals(pattern, pattern.late(by = 0.0))
        val late = pattern.late(by = 0.1)
        // The strike was missed by more than a moment; the arrival tap still comes, 0.1 s sooner.
        assertEquals(pattern.transients.drop(1).map { it - 0.1 }, late.transients)
        assertEquals(0.0, late.shaped[0].time, 0.0)
        assertTrue(abs(late.shaped[0].end - (pattern.shaped[0].end - 0.1)) < 1e-9)
        assertEquals(0.0, late.intensity.first().time, 0.0)
        val slightly = VoiceHapticDesign.tick(Cue.sweepRight, audioDelay = 0.0).late(by = 0.02)
        assertEquals(listOf(0.0), slightly.transients)
        assertTrue(VoiceHapticDesign.tick(Cue.sweepRight, audioDelay = 0.0).late(by = 0.2).isEmpty)
    }
}

/**
 * A call's audio teardown never cuts off whatever claimed the session after it.
 * `endingACallReleasesItsHapticEngineAndANewCallKeepsIt` drives the real `VoiceHapticEngine`
 * (`Vibrator`, `ProcessLifecycleOwner`), so it runs under Robolectric in
 * [VoiceCallTeardownFrameworkTests].
 */
class VoiceCallTeardownTests {
    @Test fun dictationStartedAfterACallKeepsTheSession() {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        var deactivated = false
        val call = owner.holder { deactivated = true }
        val dictation = owner.holder { deactivated = true }
        runCatching { call.activate {} }
        runCatching { dictation.activate {} }
        call.release()
        assertFalse(deactivated)
        dictation.release()
        assertTrue(deactivated)
    }

    @Test fun anUnclaimedSessionIsDeactivatedOnceHapticsFinish() {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val order = mutableListOf<String>()
        val call = owner.holder { order += "deactivate" }
        runCatching { call.activate {} }
        order += "haptics"
        call.release()
        assertEquals(listOf("haptics", "deactivate"), order)
    }
}

/**
 * [VoiceCallTeardownTests]' engine test on Android's real `VoiceHapticEngine` (Robolectric's
 * shadow `Vibrator`). Releasing the engine is cancelling the vibrator and unpreparing it, so the
 * port also checks the new call's vibrator isn't cancelled and the last call's end is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCallTeardownFrameworkTests {
    @Test fun endingACallReleasesItsHapticEngineAndANewCallKeepsIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val vibrator = shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)
        val engine = VoiceHapticEngine(app, clock = TestClock())
        engine.prepare()
        assertTrue(engine.isCallActive)
        engine.endCall()
        assertFalse(engine.isCallActive)
        // The next call starts before the last one's teardown finishes.
        engine.prepare()
        engine.finish()
        assertTrue(engine.isCallActive)
        assertFalse(vibrator.isCancelled)
        // That call's own end does release it.
        engine.endCall()
        engine.finish()
        assertTrue(vibrator.isCancelled)
    }

    class RecordingVoiceCallService : VoiceCallService() {
        var promotions = 0
        var promotionFailure: RuntimeException? = null
        override fun startMicrophoneForeground(notification: Notification) {
            promotionFailure?.let { throw it }
            promotions += 1
            super.startMicrophoneForeground(notification)
        }
    }

    @Test fun rapidStartAndStopSatisfiesForegroundContractBeforeStopping() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val appShadow = shadowOf(app)
        appShadow.grantPermissions(Manifest.permission.RECORD_AUDIO)
        val commands = VoiceCallCommands()
        val client = VoiceCallServiceClient.makeLive(app, commands)
        val id = UUID.randomUUID()

        // Start and immediately stop before ActivityManager delivers onStartCommand:
        // stop() must NOT call stopService while startForegroundService is still queued,
        // or Android throws ForegroundServiceDidNotStartInTimeException.
        client.start(VoiceCallServiceState(id, VoiceCallServiceState.Phase.connecting))
        client.stop(id)
        assertNull("stopService while FGS start is pending crashes with ForegroundServiceDidNotStartInTimeException", appShadow.nextStoppedService)
        assertTrue(commands.hasPendingForegroundStart)

        val startedIntent = required(appShadow.nextStartedService)
        assertTrue(startedIntent.getBooleanExtra(VoiceCallService.FOREGROUND_START, false))

        withDependencies({ this[VoiceCallCommands] = commands }) {
            val controller = Robolectric.buildService(RecordingVoiceCallService::class.java, startedIntent).create()
            val service = controller.get()
            service.onStartCommand(startedIntent, 0, 1)
            assertEquals("Must call startForeground once to satisfy ActivityManager before stopping", 1, service.promotions)
            assertFalse(commands.hasPendingForegroundStart)
            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
        }
    }

    @Test fun postGrantPromotionAndRejectedForegroundPromotionEndCallCleanly() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val appShadow = shadowOf(app)
        val commands = VoiceCallCommands()
        val client = VoiceCallServiceClient.makeLive(app, commands)
        val id = UUID.randomUUID()
        val received = Channel<VoiceCallCommands.Command>(Channel.UNLIMITED)
        val collector = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            commands.events.collect { received.trySend(it) }
        }

        try {
            // 1. Without RECORD_AUDIO at start (first call after install), start() skips FGS admission.
            appShadow.denyPermissions(Manifest.permission.RECORD_AUDIO)
            client.start(VoiceCallServiceState(id, VoiceCallServiceState.Phase.connecting))
            assertNull(appShadow.nextStartedService)

            // 2. Once RECORD_AUDIO is granted during start(), update() promotes via foregroundStart
            // without gating on ProcessLifecycleOwner (so TileService / SystemActionActivity starts work).
            appShadow.grantPermissions(Manifest.permission.RECORD_AUDIO)
            client.update(VoiceCallServiceState(id, VoiceCallServiceState.Phase.live))
            val startedIntent = required(appShadow.nextStartedService)
            assertTrue(startedIntent.getBooleanExtra(VoiceCallService.FOREGROUND_START, false))

            // 3. If the OS rejects microphone FGS promotion inside onStartCommand, the service catches
            // it, emits an end command so the call never runs with a silenced mic, and stops cleanly.
            withDependencies({ this[VoiceCallCommands] = commands }) {
                val controller = Robolectric.buildService(RecordingVoiceCallService::class.java, startedIntent).create()
                val service = controller.get()
                service.promotionFailure = SecurityException("Simulated FGS microphone rejection")
                service.onStartCommand(startedIntent, 0, 1)
                assertFalse(commands.isActive(id))
                assertTrue(shadowOf(service).isStoppedBySelf)
                assertEquals(VoiceCallCommands.Command(id, VoiceCallCommands.Action.end), received.tryReceive().getOrNull())
            }
        } finally {
            collector.cancel()
        }
    }

    @Test fun liveRTCAudioDeviceSurvivesDisposedRemoteAudioTracks() {
        val app = RuntimeEnvironment.getApplication()
        val device = LiveRTCAudioDevice(app, realtime = true) {}
        try {
            class DisposableAudioTrack(var disposed: Boolean = false) : org.webrtc.AudioTrack(1L) {
                var volumes = 0
                var sinksAdded = 0
                var sinksRemoved = 0
                private fun checkAlive() {
                    if (disposed) throw IllegalStateException("MediaStreamTrack has been disposed.")
                }
                override fun setVolume(volume: Double) {
                    checkAlive()
                    volumes += 1
                }
                override fun addSink(sink: org.webrtc.AudioTrackSink) {
                    checkAlive()
                    sinksAdded += 1
                }
                override fun removeSink(sink: org.webrtc.AudioTrackSink) {
                    checkAlive()
                    sinksRemoved += 1
                }
            }

            // 1. Track already disposed before onAddTrack hopped onto the RTC dispatcher.
            val deadOnArrival = DisposableAudioTrack(disposed = true)
            device.attach(deadOnArrival)
            assertEquals(0, deadOnArrival.sinksAdded)

            // 2. Live track attached, then disposed by WebRTC before speaker gain ramp / teardown.
            val midCallDisposed = DisposableAudioTrack()
            val healthy = DisposableAudioTrack()
            device.attach(midCallDisposed)
            device.attach(healthy)
            assertEquals(1, midCallDisposed.sinksAdded)
            assertEquals(1, healthy.sinksAdded)

            midCallDisposed.disposed = true
            device.setPlaybackVolume(0.5f)
            assertEquals("Healthy track still receives speaker-ramp volume after peer track disposal", 2, healthy.volumes)

            // 3. Disposing the remaining track right before shutDown's detachTracks never aborts teardown.
            healthy.disposed = true
            device.detachTracks()
        } finally {
            device.release()
        }
    }
}

/** The haptics engine double. */
class FakeVoiceHapticEngine : VoiceHapticPlaying {
    override var isCallActive = false
    val plays = mutableListOf<VoiceHapticPattern>()
    override fun prepare() { isCallActive = true }
    override fun play(pattern: VoiceHapticPattern, at: Duration, kind: VoiceHapticEngine.Kind): Boolean {
        plays += pattern
        return true
    }
    override fun cancel(kind: VoiceHapticEngine.Kind) {}
    override fun endCall() { isCallActive = false }
    override suspend fun finish() {}
}

/**
 * Runs a `VoiceCallSounds` test on virtual time: the call clock is a [TestClock] and its loop and
 * fallbacks run on the test's scheduler.
 */
private class SoundsHarness(val test: TestScope) {
    val clock = TestClock()
    private val values = DependencyValues.testing()
    val scope = CoroutineScope(test.backgroundScope.coroutineContext + values.asContextElement())

    fun sounds(engine: VoiceHapticPlaying = VoiceHapticEngine.testValue) =
        DependencyValues.withValues(values) { VoiceCallSounds(clock = clock, haptics = engine, scope = scope) }

    fun <R> with(block: () -> R): R = DependencyValues.withValues(values, block)

    val now: Duration get() = clock.now()

    /** Moves the call clock to [deadline], letting the call's loop run. */
    suspend fun waitUntil(deadline: Duration) {
        test.runCurrent()
        while (clock.now() < deadline) {
            clock.advance(minOf(1.milliseconds, deadline - clock.now()))
            test.runCurrent()
        }
    }

    suspend fun sleep(duration: Duration) = waitUntil(clock.now() + duration)
}

/** A tick requested a pass ahead must not play once the call has moved on. */
class VoiceTickRaceTests {
    private fun SoundsHarness.connecting(): Pair<VoiceCallSounds, UUID> {
        val sounds = sounds()
        val id = UUID.randomUUID()
        with { sounds.startCallClock(id) {} }
        return sounds to id
    }

    @Test fun aTickRacingAHangUpNeverPlays() = runTest {
        val harness = SoundsHarness(this)
        val (sounds, id) = harness.connecting()
        harness.with {
            assertTrue(sounds.voiceTick(Cue.sweepRight, at = harness.now + 0.5.seconds, audioDelay = 0.0))
            // End tapped while the next pass's tick was on its way to the main actor.
            sounds.voice(VoiceCallHaptic.ended)
            assertFalse(sounds.ticksAreDue)
            assertFalse(sounds.voiceTick(Cue.sweepLeft, at = harness.now + 0.5.seconds, audioDelay = 0.0))
            sounds.endCallClock(id, playEndCue = false)
        }
    }

    @Test fun aTickRacingTheConnectOrAnEndedCallNeverPlays() = runTest {
        val harness = SoundsHarness(this)
        val (sounds, id) = harness.connecting()
        harness.with {
            sounds.noteConnected(id)
            assertFalse(sounds.voiceTick(Cue.sweepLeft, at = harness.now + 0.5.seconds, audioDelay = 0.0))
        }

        val (other, otherID) = harness.connecting()
        harness.with {
            other.endCallClock(otherID, playEndCue = false)
            assertFalse(other.voiceTick(Cue.sweepLeft, at = harness.now + 0.5.seconds, audioDelay = 0.0))
            sounds.endCallClock(id, playEndCue = false)
        }
    }
}

/**
 * The scanner runs from the tap; the call clock joins its timeline once the
 * audio engine is up, playing the call start then, and turnarounds passed
 * during startup don't tick.
 */
class VoiceCallStartupTests {
    @Test fun connectingRunsFromTheTapBeforeTheEngineIsUp() = runTest {
        val harness = SoundsHarness(this)
        val sounds = harness.sounds()
        val tapped = harness.now
        sounds.recordHaptic(VoiceCallHaptic.callStarted)
        val first = required(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        harness.waitUntil(tapped + 80.milliseconds)
        val later = required(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        assertTrue(first < 0.05)
        assertTrue("No hold while the engine starts: $first → $later", later >= 0.08 && later < 0.5)
        // A hang-up now sweeps the launching beam out from where it is.
        val ending = sounds.endingShape()
        assertTrue(!ending.drawn && ending.overshoot > 0)
    }

    @Test fun noTickForATurnaroundPassedBeforeTheEngineWasReady() = runTest {
        val harness = SoundsHarness(this)
        val sounds = harness.sounds()
        var played = 0
        val tapped = harness.now
        sounds.recordHaptic(VoiceCallHaptic.callStarted)
        // The engine takes past the first turnaround (Mute, 0.57 s) to start.
        val ready = tapped + (DotScopeSimulation.SoundScheduler.pingTime(1) + 0.08).seconds
        harness.waitUntil(ready)
        val id = UUID.randomUUID()
        harness.with { sounds.startCallClock(id) { played += 1 } }
        val adopted = required(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        assertTrue("The call clock adopts the scanner's timeline: $adopted", adopted >= 0.64)
        assertEquals("The call start plays as the engine is up", 1, played)
        harness.waitUntil(ready + 150.milliseconds)
        assertTrue("The missed turnaround doesn't tick", sounds.snapshot().scheduledCues.isEmpty())

        // The first tick is the next turnaround the beam reaches: End's, at 1.12 s.
        harness.waitUntil(tapped + (DotScopeSimulation.SoundScheduler.pingTime(2) + 0.2).seconds)
        val snapshot = sounds.snapshot()
        harness.with { sounds.endCallClock(id, playEndCue = false) }
        assertEquals(listOf(Cue.sweepLeft), snapshot.scheduledCues.map { it.cue })
        val ping = required(snapshot.scheduledCues.firstOrNull())
        assertTrue("On the tap's timeline: ${ping.time}", abs(ping.time - DotScopeSimulation.SoundScheduler.pingTime(2)) < 0.1)
        assertEquals(listOf(VoiceCallHaptic.callStarted, VoiceCallHaptic.sweepPass), snapshot.hapticEvents.map { it.haptic })
        assertEquals("Turnarounds make no sound over the connecting loop", 1, played)
    }

    @Test fun theFirstCueIsReportedOnceFromTheTap() = runTest {
        val harness = SoundsHarness(this)
        val sounds = harness.sounds()
        val reports = Channel<VoiceCallSounds.FirstCue>(Channel.UNLIMITED)
        val tapped = harness.now
        sounds.recordHaptic(VoiceCallHaptic.callStarted)
        // Audio up well before the first turnaround (Mute, 0.57 s).
        harness.waitUntil(tapped + 200.milliseconds)
        val id = UUID.randomUUID()
        harness.with { sounds.startCallClock(id, latency = 0.03, reportFirstCue = { reports.trySend(it) }) {} }
        runCurrent()
        val first = reports.receive()
        assertEquals(VoiceCallCue.callStart, first.cue)
        assertTrue("Tap → call start heard: ${first.milliseconds} ms", abs(first.milliseconds - (first.audioReadyMilliseconds + 30)) < 10)
        assertTrue(first.audioReadyMilliseconds in 200 until 300)
        assertTrue(first.logLine.startsWith("Call cue: first call_start heard"))

        // The connect cue after it is not a first cue.
        harness.with {
            sounds.noteConnected(id)
            sounds.endCallClock(id, playEndCue = false)
        }
        runCurrent()
        reports.close()
        assertNull(reports.receiveCatching().getOrNull())
    }

    @Test fun aHangUpBeforeTheEngineOnASecondCallSweepsFromTheHangUp() = runTest {
        val harness = SoundsHarness(this)
        val sounds = harness.sounds()
        val first = UUID.randomUUID()
        sounds.recordHaptic(VoiceCallHaptic.callStarted)
        harness.with { sounds.startCallClock(first) {} }
        harness.with { sounds.endCallClock(first, playEndCue = false) }
        harness.sleep(300.milliseconds)

        // The second call is hung up during preflight.
        sounds.recordHaptic(VoiceCallHaptic.callStarted)
        assertNull("The last call's ending no longer shows", sounds.stageElapsed(DotScopeSimulation.Stage.ending))
        harness.sleep(60.milliseconds)
        sounds.recordHaptic(VoiceCallHaptic.ended)
        val ending = required(sounds.stageElapsed(DotScopeSimulation.Stage.ending))
        assertTrue("The sweep runs from this hang-up, not the last call's: $ending", ending < 0.05)
        assertNull(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        // Its cancelled start leaves the sweep running.
        sounds.cancelPendingStart()
        assertTrue(required(sounds.stageElapsed(DotScopeSimulation.Stage.ending)) < 0.1)
    }

    @Test fun aFailedPreflightLeavesTheNextCallToStartFresh() = runTest {
        val harness = SoundsHarness(this)
        val sounds = harness.sounds(FakeVoiceHapticEngine())
        // Tapped while the app wasn't active: nothing plays, but the scanner's timeline starts.
        harness.with { sounds.voice(VoiceCallHaptic.callStarted) }
        assertNotNull(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        harness.sleep(200.milliseconds)

        // Preflight failed.
        sounds.cancelPendingStart()
        assertNull("Connecting stops with the failed start", sounds.stageElapsed(DotScopeSimulation.Stage.connecting))

        // The next call's clock doesn't inherit the failed one's tap.
        val id = UUID.randomUUID()
        harness.with { sounds.startCallClock(id) {} }
        val snapshot = sounds.snapshot()
        val elapsed = required(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        harness.with { sounds.endCallClock(id, playEndCue = false) }
        assertTrue("$elapsed", elapsed < 0.05)
        assertTrue(snapshot.hapticEvents.isEmpty())
    }

    @Test fun aDeniedMicrophoneOrCancelledStartEndsThePendingStart() = runTest {
        for (failure in listOf<Throwable>(VoiceAudioError.microphoneDenied, CancellationException())) {
            val harness = SoundsHarness(this)
            val sounds = harness.sounds()
            sounds.recordHaptic(VoiceCallHaptic.callStarted)
            try {
                sounds.pendingStart<Unit> { throw failure }
                fail("Expected $failure")
            } catch (_: Throwable) {
            }
            assertNull("$failure", sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
            val id = UUID.randomUUID()
            harness.with { sounds.startCallClock(id) {} }
            val snapshot = sounds.snapshot()
            harness.with { sounds.endCallClock(id, playEndCue = false) }
            assertTrue("The next call doesn't adopt the failed start's tap", snapshot.hapticEvents.isEmpty())
        }
    }

    @Test fun aStartFailingAfterANewerTapLeavesThatTapAlone() = runTest {
        val harness = SoundsHarness(this)
        val sounds = harness.sounds()
        sounds.recordHaptic(VoiceCallHaptic.callStarted)
        try {
            sounds.pendingStart<Unit> {
                harness.sleep(10.milliseconds)
                // A newer call was tapped while this start was still in flight.
                sounds.recordHaptic(VoiceCallHaptic.callStarted)
                throw CancellationException()
            }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertNotNull(sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
    }

    @Test fun aHangUpBeforeTheEngineSweepsTheLaunchingBeamFromWhereItIs() = runTest {
        val harness = SoundsHarness(this)
        val engine = FakeVoiceHapticEngine()
        // Kept for a call so the hang-up's pattern can be read back.
        engine.isCallActive = true
        val sounds = harness.sounds(engine)
        val tapped = harness.now
        harness.with { sounds.voice(VoiceCallHaptic.callStarted) }
        harness.waitUntil(tapped + 100.milliseconds)
        harness.with { sounds.voice(VoiceCallHaptic.ended) }
        val pattern = required(engine.plays.lastOrNull())
        val width = 400f
        assertNotEquals("It sweeps the beam, not a braid", VoiceHapticDesign.hangUp(drawn = true, origin = 0f, width = width), pattern)
        fun arrival(elapsed: Float): Double {
            val beam = Scan.connectingBeam(elapsed)
            return VoiceHapticDesign.hangUp(drawn = false, origin = beam.progress, overshoot = beam.overshoot, width = width).transients[0]
        }
        val landed = required(pattern.transients.firstOrNull())
        assertTrue("From where the beam was at the hang-up: $landed", landed >= arrival(0.1f) - 1e-6 && landed <= arrival(0.3f))
    }
}

/** The haptics engine plays only for a call whose clock is running. */
class VoiceHangUpEngineTests {
    /**
     * End's press plays `.cancel` at once; the call's landing still follows
     * as the sweep reaches End, unless it would land on top of the press.
     */
    @Test fun aPressedHangUpLandsOnlyClearOfThePress() = runTest {
        val harness = SoundsHarness(this)
        val engine = FakeVoiceHapticEngine()
        engine.isCallActive = true
        val sounds = harness.sounds(engine)
        harness.with {
            sounds.cancelPressed()
            assertTrue("A braid's sweep lands well after the press", sounds.voiceHangUp(drawn = true, origin = 0f, overshoot = 0f, width = 408f, at = harness.now))
            assertEquals(1, engine.plays.size)
            sounds.cancelPressed()
            assertFalse("A beam already at End would land on the press", sounds.voiceHangUp(drawn = false, origin = 1f, overshoot = 20f, width = 408f, at = harness.now))
            assertEquals(1, engine.plays.size)
        }
    }

    @Test fun aHangUpBeforeTheCallClockFallsBackWithoutStartingTheEngine() = runTest {
        val harness = SoundsHarness(this)
        val engine = FakeVoiceHapticEngine()
        val sounds = harness.sounds(engine)
        harness.with {
            // An earlier call created and released the engine.
            engine.prepare()
            engine.endCall()
            // The next call is hung up during preflight or the microphone prompt.
            assertFalse(sounds.voiceHangUp(drawn = false, origin = 0.3f, overshoot = 0f, width = 408f, at = harness.now))
            sounds.voice(VoiceCallHaptic.ended)
            sounds.voice(VoiceCallHaptic.muted)
        }
        assertTrue(engine.plays.isEmpty())
    }

    @Test fun aHangUpDuringACallPlaysOnTheEngine() = runTest {
        val harness = SoundsHarness(this)
        val engine = FakeVoiceHapticEngine()
        val sounds = harness.sounds(engine)
        harness.with {
            engine.prepare()
            assertTrue(sounds.voiceHangUp(drawn = true, origin = 0f, overshoot = 0f, width = 408f, at = harness.now))
            assertEquals(listOf(VoiceHapticDesign.hangUp(drawn = true, origin = 0f, width = 408f)), engine.plays)
            engine.endCall()
        }
    }

    /** Android only: a Galaxy S24 took ~20 ms per waveform step, so 1 ms steps stretched the 0.8 s connect into 17 s. */
    @Test fun aWaveformPlaysInStepsTheVibratorCanKeepUpWith() {
        val connect = IntArray(842) { i -> if (i in 39..829) (40 + (i % 200)).coerceAtMost(255) else 0 }
        val (timings, levels) = VoiceHapticEngine.waveformSteps(connect)
        assertTrue("${timings.size} steps", timings.size <= 34)
        assertTrue(timings.all { it >= 1 } && timings.dropLast(1).all { it % 25 == 0L })
        assertTrue(timings.sum() in 830L..850L)
        assertEquals(0, levels.first())

        val tick = IntArray(561).also { for (i in 2 until 12) it[i] = 128; it[5] = 200 }
        val (tickTimings, tickLevels) = VoiceHapticEngine.waveformSteps(tick)
        assertEquals(listOf(25L), tickTimings.toList())
        assertEquals(listOf(200), tickLevels.toList())
    }
}
