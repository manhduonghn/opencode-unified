package ai.opencode.feature.composer

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.eventually
import ai.opencode.feature.voice.AudioSessionOwner
import ai.opencode.server.model.OpenCodeDelivery
import android.media.AudioDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Dictation's audio session against a fake: which mic it picks, how long it's
 * held after each kind of press, and what lets it go.
 *
 * Android has no app-wide audio session (see [DictationAudioSessionControlling]): there is no
 * category, mode or options to set, no `allowHapticsAndSystemSoundsDuringRecording`, no
 * `prefersNoInterruptionsFromSystemAlerts`, and deactivation has no `.notifyOthersOnDeactivation`.
 * Assertions on those are dropped; everything else is kept. Dropped whole for the same reason:
 * `aStoppedMicUnderACallLeavesItsSettings` and `anOlderRecordingsStopNeverTouchesTheNewerOne` (each
 * only checks `prefersNoInterruptionsFromSystemAlerts`).
 */
class DictationAudioTests {
    class FakeSession : DictationAudioSessionControlling {
        private val lock = Any()
        private val recorded = mutableListOf<String>()
        val calls: List<String> get() = synchronized(lock) { recorded.toList() }

        override var isOtherAudioPlaying = false
        override var sampleRate = 44_100.0
        override var ioBufferDuration = 0.010
        /** Settable backing for [preferredSampleRate]. */
        var preferredRate = 0.0
        var preferredBuffer = 0.0
        override val preferredSampleRate: Double get() = preferredRate
        override val preferredIOBufferDuration: Double get() = preferredBuffer
        override var preferredInputPortType: String? = null
        override var availableInputPortTypes = listOf(DictationPort.builtInMic)
        override var outputPortTypes = listOf(DictationPort.bluetoothA2DP)
        override var inputPortTypes = listOf(DictationPort.builtInMic)
        private var activated = false
        override val isActive: Boolean get() = activated

        /** The platform refusing to deactivate while something still plays. */
        var refusesDeactivation = false

        private fun record(call: String) = synchronized(lock) { recorded += call }
        fun clearCalls() = synchronized(lock) { recorded.clear() }

        override fun setPreferredSampleRate(sampleRate: Double) {
            record("rate=${sampleRate.toInt()}")
            preferredRate = sampleRate
        }

        override fun setPreferredIOBufferDuration(duration: Double) {
            record("buffer=${Math.round(duration * 1000)}")
            preferredBuffer = duration
        }

        override fun setPreferredInput(portType: String) {
            record("input=$portType")
            preferredInputPortType = portType
        }

        override fun setActive(active: Boolean) {
            record(if (active) "active" else "inactive")
            if (!active && refusesDeactivation) throw IllegalStateException("560030580")
            activated = active
        }

        override fun preferredInputDevice(): AudioDeviceInfo? = null
    }

    /** A hold's timer, fired by the test. */
    class Timers {
        private val lock = Any()
        private val waiting = mutableListOf<Pair<Duration, CompletableDeferred<Unit>>>()

        val requested: List<Duration> get() = synchronized(lock) { waiting.map { it.first } }

        suspend fun sleep(duration: Duration) {
            val fired = CompletableDeferred<Unit>()
            synchronized(lock) { waiting += duration to fired }
            fired.await()
        }

        /** Returns once [count] holds are waiting: a hold's timer starts on its own coroutine. */
        suspend fun requests(count: Int = 1): List<Duration> {
            eventually(wallClock = true) { requested.size >= count }
            return requested
        }

        fun fireAll() {
            val fired = synchronized(lock) { waiting.toList().also { waiting.clear() } }
            fired.forEach { it.second.complete(Unit) }
        }
    }

    class Harness(owner: AudioSessionOwner? = null) {
        val session = FakeSession()

        @Volatile var generation = 1

        @Volatile var now = 100.0
        val timers = Timers()
        private var observer: ((DictationAudioEvent) -> Unit)? = null
        private val logged = mutableListOf<String>()
        val lines: List<String> get() = synchronized(logged) { logged.toList() }
        val audio = DictationAudio(
            DictationAudio.Environment(
                session = session,
                generation = { generation },
                uptime = { now },
                sleep = { timers.sleep(it) },
                observe = { observer = it },
                queue = null,
                log = { line -> synchronized(logged) { logged += line } },
                owner = owner,
            ),
        )

        fun post(event: DictationAudioEvent) = observer!!.invoke(event)

        /** One press: the mic on for [seconds], then released as [end]. */
        fun press(end: DictationEnd, seconds: Double = 3.0): DictationAudio.Hold? {
            val id = audio.activate()
            now += seconds
            audio.recordingStopped(id)
            audio.release(end, owner = generation)
            return audio.hold
        }

        /** A press whose mic has stopped and that's still finishing. */
        fun finishing(seconds: Double = 3.0) {
            val id = audio.activate()
            now += seconds
            audio.recordingStopped(id)
        }

        /** Media playing at 48 kHz with a 23 ms buffer. */
        fun playMedia() {
            session.isOtherAudioPlaying = true
            session.sampleRate = 48_000.0
            session.ioBufferDuration = 0.023
        }

        fun wait(seconds: Double) {
            now += seconds
        }
    }

    /**
     * Here the call is a holder on the same shared [AudioSessionOwner]: dictation coming and going
     * never releases the call's session, and the call's own release deactivates it once.
     */
    @Test fun aCallRecoversAndReleasesAfterNewerDictationComesAndGoes() {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        var callDeactivations = 0
        val call = owner.holder { callDeactivations += 1 }
        call.activate {}
        val dictation = Harness(owner = owner)
        dictation.audio.activate()
        assertEquals(2, owner.activeCount)
        dictation.audio.deactivate()
        assertEquals(1, owner.activeCount)
        assertFalse(dictation.session.calls.contains("inactive"))
        call.release()
        assertEquals(0, owner.activeCount)
        assertEquals(1, callDeactivations)
        call.release()
        assertEquals(1, callDeactivations)
    }

    // Setup

    @Test fun activatesA2DPPlayAndRecordWithTheSpeakerAndNeverHFP() {
        val harness = Harness()
        val session = harness.session
        harness.audio.activate()
        assertTrue(session.isActive)
        // The input is chosen once the session is active (QA1799).
        val calls = session.calls
        assertTrue(calls.indexOf("active") < calls.indexOf("input=MicrophoneBuiltIn"))
        assertTrue(calls.indexOf("active") >= 0)
        assertEquals(
            "dictation_route out=BluetoothA2DPOutput in=MicrophoneBuiltIn other=0 preferred=0Hz/0ms format=44100Hz/10ms",
            harness.lines.first(),
        )
    }

    @Test fun aWiredOrUSBMicWinsOverTheBuiltInOne() {
        val harness = Harness()
        harness.session.availableInputPortTypes = listOf(DictationPort.builtInMic, DictationPort.headsetMic)
        harness.audio.activate()
        assertEquals(DictationPort.headsetMic, harness.session.preferredInputPortType)

        val usb = Harness()
        usb.session.availableInputPortTypes = listOf(DictationPort.builtInMic, DictationPort.usbAudio)
        usb.audio.activate()
        assertEquals(DictationPort.usbAudio, usb.session.preferredInputPortType)

        // Already in place: nothing is set.
        val builtIn = Harness()
        builtIn.session.preferredInputPortType = DictationPort.builtInMic
        builtIn.audio.activate()
        assertFalse(builtIn.session.calls.any { it.startsWith("input=") })
    }

    @Test fun matchesWhatsPlayingAndKeepsItUntilTheSessionIsDeactivated() {
        val harness = Harness()
        val session = harness.session
        session.isOtherAudioPlaying = true
        session.sampleRate = 48_000.0
        session.ioBufferDuration = 0.023
        val id = harness.audio.activate()
        assertEquals(48_000.0, session.preferredSampleRate, 0.0)
        assertEquals(0.023, session.preferredIOBufferDuration, 0.0)

        // The mic stopping changes nothing else.
        harness.wait(3.0)
        harness.audio.recordingStopped(id)
        assertEquals(48_000.0, session.preferredSampleRate, 0.0)
        assertTrue(session.isActive)

        // Held (an insert with media playing): still matched, still active.
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertEquals(8.0, harness.audio.hold?.seconds)
        assertEquals(48_000.0, session.preferredSampleRate, 0.0)
        assertEquals(0.023, session.preferredIOBufferDuration, 0.0)
        assertTrue(session.isActive)

        // Released: deactivated first, then the preferences cleared.
        session.clearCalls()
        harness.audio.endHold(DictationHoldRelease.Background)
        assertEquals(listOf("inactive", "rate=0", "buffer=0"), session.calls)
        assertFalse(session.isActive)
    }

    @Test fun withNothingPlayingNoStaleRateIsPinned() {
        val harness = Harness()
        harness.session.sampleRate = 16_000.0
        harness.session.ioBufferDuration = 0.016
        harness.session.preferredRate = 24_000.0
        harness.session.preferredBuffer = 0.02
        harness.audio.activate()
        assertEquals(0.0, harness.session.preferredSampleRate, 0.0)
        assertEquals(0.0, harness.session.preferredIOBufferDuration, 0.0)
    }

    // How long it's held

    @Test fun thePolicy() {
        data class Case(val end: DictationEnd, val recorded: Double, val otherAudio: Boolean, val backToBack: Boolean, val seconds: Double)
        val cases = listOf(
            Case(DictationEnd.Cancel, 3.0, false, false, 10.0),
            Case(DictationEnd.Cancel, 3.0, true, false, 10.0),
            Case(DictationEnd.Insert, 1.0, false, false, 10.0),
            Case(DictationEnd.Send, 1.0, true, false, 10.0),
            Case(DictationEnd.Insert, 3.0, false, false, 15.0),
            Case(DictationEnd.Insert, 3.0, true, false, 8.0),
            Case(DictationEnd.Send, 3.0, true, false, 0.0),
            Case(DictationEnd.Send, 3.0, true, true, 6.0),
            Case(DictationEnd.Send, 3.0, false, false, 12.0),
            Case(DictationEnd.Send, 3.0, false, true, 12.0),
            Case(DictationEnd.Failed, 3.0, false, false, 0.0),
            Case(DictationEnd.Failed, 1.0, true, false, 0.0),
        )
        for (case in cases) {
            assertEquals(
                case.toString(),
                case.seconds,
                DictationHoldPolicy.seconds(case.end, case.recorded, case.otherAudio, case.backToBack),
                0.0,
            )
        }
    }

    @Test fun eachEndHoldsForItsTime() = runBlocking {
        val cancel = Harness()
        assertEquals(10.0, cancel.press(DictationEnd.Cancel)?.seconds)
        assertEquals(listOf(10.seconds), cancel.timers.requests())

        val short = Harness()
        assertEquals(10.0, short.press(DictationEnd.Insert, seconds = 1.2)?.seconds)

        val insert = Harness()
        assertEquals(15.0, insert.press(DictationEnd.Insert)?.seconds)

        val sendSilent = Harness()
        assertEquals(12.0, sendSilent.press(DictationEnd.Send)?.seconds)
        assertEquals("dictation_session_held end=send hold=12s other=0 recorded=3s", sendSilent.lines.last())
    }

    @Test fun aSendWhileMediaPlaysLetsGoAtOnceUnlessItsASecondPress() {
        val harness = Harness()
        harness.session.isOtherAudioPlaying = true
        assertNull(harness.press(DictationEnd.Send))
        assertFalse(harness.session.isActive)
        assertEquals("dictation_session_released reason=immediate end=send held=0s other=1", harness.lines.last())

        // Again within 15 s: held briefly for a third.
        harness.wait(10.0)
        assertEquals(6.0, harness.press(DictationEnd.Send)?.seconds)
        assertTrue(harness.session.isActive)

        // Long after: let go at once again.
        harness.audio.cancelHold()
        harness.wait(20.0)
        assertNull(harness.press(DictationEnd.Send))
    }

    @Test fun aHoldExpiresAndDeactivates() = runBlocking {
        val harness = Harness()
        harness.press(DictationEnd.Send)
        assertEquals(listOf(12.seconds), harness.timers.requests())
        harness.wait(12.0)
        harness.timers.fireAll()
        assertTrue(eventually(wallClock = true) { harness.audio.hold == null })
        assertFalse(harness.session.isActive)
        assertEquals("dictation_session_released reason=expired end=send held=12s other=0", harness.lines.last())
    }

    @Test fun aNewPressTakesTheHeldSessionAsItIs() {
        val harness = Harness()
        harness.press(DictationEnd.Insert)
        harness.session.clearCalls()
        harness.audio.activate()
        assertNull(harness.audio.hold)
        assertFalse(harness.session.calls.any { it.startsWith("inactive") })
    }

    // What lets it go early

    @Test fun sendingOrLeavingEndsOnlyAnInsertsHold() {
        val send = Harness()
        send.press(DictationEnd.Send)
        send.audio.endHold(DictationHoldRelease.Sent)
        send.audio.endHold(DictationHoldRelease.Left)
        assertNotNull(send.audio.hold)
        assertTrue(send.session.isActive)

        val insert = Harness()
        insert.press(DictationEnd.Insert)
        insert.audio.endHold(DictationHoldRelease.Sent)
        assertNull(insert.audio.hold)
        assertFalse(insert.session.isActive)

        val left = Harness()
        left.press(DictationEnd.Insert)
        left.audio.endHold(DictationHoldRelease.Left)
        assertNull(left.audio.hold)
        assertFalse(left.session.isActive)
    }

    @Test fun aCallEndsAnyHold() {
        for (end in listOf(DictationEnd.Cancel, DictationEnd.Insert, DictationEnd.Send)) {
            val harness = Harness()
            harness.press(end)
            harness.audio.endHold(DictationHoldRelease.Call)
            assertNull(harness.audio.hold)
            assertFalse(harness.session.isActive)
            assertEquals(0.0, harness.session.preferredSampleRate, 0.0)
        }
    }

    /** Android only reports other audio beginning ([DictationAudioEvent.OtherAudioBegan]). */
    @Test fun anotherAppStartingToPlayEndsTheHold() {
        val harness = Harness()
        harness.press(DictationEnd.Insert)
        assertNotNull(harness.audio.hold)
        harness.wait(4.0)
        harness.post(DictationAudioEvent.OtherAudioBegan)
        assertNull(harness.audio.hold)
        assertFalse(harness.session.isActive)
        assertEquals("dictation_session_released reason=silenced end=insert held=4s other=0", harness.lines.last())
    }

    @Test fun leavingTheAppEndsTheHold() {
        val harness = Harness()
        harness.press(DictationEnd.Cancel)
        harness.post(DictationAudioEvent.DidEnterBackground)
        assertNull(harness.audio.hold)
        assertFalse(harness.session.isActive)
    }

    /**
     * A call claiming the session (bumping the generation) ends the hold and
     * leaves the call's session alone, but for the rate and buffer dictation
     * asked for.
     */
    @Test fun aCallClaimingTheSessionIsLeftAlone() {
        val harness = Harness()
        harness.playMedia()
        harness.press(DictationEnd.Insert)
        harness.session.clearCalls()
        harness.generation += 1
        harness.post(DictationAudioEvent.RouteChanged)
        assertNull(harness.audio.hold)
        assertEquals(listOf("rate=0", "buffer=0"), harness.session.calls)
        assertTrue(harness.session.isActive)
        assertTrue(harness.lines.last().startsWith("dictation_session_released reason=claimed"))

        // Nor does an explicit release once it's the call's.
        val late = Harness()
        late.press(DictationEnd.Send)
        late.generation += 1
        late.session.clearCalls()
        late.audio.endHold(DictationHoldRelease.Call)
        assertTrue(late.session.calls.isEmpty())
    }

    @Test fun capturingThatNeverStartedDeactivatesAtOnce() {
        val harness = Harness()
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertNull(harness.audio.hold)
        assertEquals("inactive", harness.session.calls.first())
    }

    /** A refused deactivation leaves the session as it was, preferences included. */
    @Test fun aRefusedDeactivationLeavesHapticsAndPreferencesOn() {
        val harness = Harness()
        harness.playMedia()
        harness.press(DictationEnd.Insert)
        harness.session.refusesDeactivation = true
        harness.session.clearCalls()
        harness.audio.endHold(DictationHoldRelease.Background)
        assertEquals(listOf("inactive"), harness.session.calls)
        assertTrue(harness.session.isActive)
        assertEquals(48_000.0, harness.session.preferredSampleRate, 0.0)
        // Android has no status code, so the exception's type.
        assertTrue(harness.lines.any { it.startsWith("audio_session_deactivate_failed source=dictation code=") })
    }

    @Test fun aRefusedSharedDeactivationLeavesHapticsOnAndRetries() {
        val scheduled = mutableListOf<() -> Unit>()
        val owner = AudioSessionOwner(schedule = { _, work -> scheduled += work })
        val harness = Harness(owner = owner)
        harness.press(DictationEnd.Insert)
        harness.session.refusesDeactivation = true
        harness.audio.endHold(DictationHoldRelease.Background)
        assertTrue(harness.session.isActive)
        assertEquals(1, scheduled.size)
        harness.session.refusesDeactivation = false
        scheduled.first().invoke()
        assertFalse(harness.session.isActive)
    }

    /**
     * The media server restarting drops every session's setup: the hold and
     * the holder go, so the next press sets it all up again.
     */
    @Test fun aMediaServicesResetDropsTheHoldAndTheHolder() {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val harness = Harness(owner = owner)
        harness.press(DictationEnd.Insert)
        assertEquals(1, owner.activeCount)
        harness.post(DictationAudioEvent.MediaServicesReset)
        assertNull(harness.audio.hold)
        assertEquals(0, owner.activeCount)
        assertEquals("dictation_session_reset held=1 recording=0", harness.lines.last())

        harness.session.clearCalls()
        harness.audio.activate()
        assertEquals(1, owner.activeCount)
        assertTrue(harness.session.calls.contains("active"))
    }

    @Test fun aMediaServicesResetWhileRecordingRulesOutAHold() {
        val harness = Harness()
        val id = harness.audio.activate()
        harness.post(DictationAudioEvent.MediaServicesReset)
        harness.wait(3.0)
        harness.audio.recordingStopped(id)
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertNull(harness.audio.hold)
        assertEquals("dictation_session_released reason=interrupted end=insert held=0s other=0", harness.lines.last())
    }

    // What rules out a hold while it records or finalizes

    @Test fun anInterruptionWhileRecordingRulesOutAHold() {
        val harness = Harness()
        val id = harness.audio.activate()
        harness.post(DictationAudioEvent.InterruptionBegan)
        harness.wait(3.0)
        harness.audio.recordingStopped(id)
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertNull(harness.audio.hold)
        assertFalse(harness.session.isActive)
        assertEquals("dictation_session_released reason=interrupted end=insert held=0s other=0", harness.lines.last())

        // The next recording isn't affected.
        assertEquals(15.0, harness.press(DictationEnd.Insert)?.seconds)
    }

    /**
     * The mic stalling (an interruption, a route change) finalizes what was
     * heard, and the session is released as failed, whatever the reducer said.
     */
    @Test fun anInterruptedRecordingIsReleasedAsFailed() = runBlocking {
        val fake = LiveDictationTests.Fake()
        val dictation = LiveDictation(fake.engine)
        val press = dictation.begin()
        val stream = dictation.start(press)
        val ended = drain(stream)
        repeat(500) { if (fake.started.value.isEmpty()) delay(2) }
        dictation.ends(press, DictationEnd.Send)
        fake.events.value.first().invoke(DictationCaptureEvent.Interrupted)
        ended.await()
        repeat(500) { if (fake.releasedEnds.value.isEmpty()) delay(2) }
        assertEquals(listOf(DictationEnd.Failed), fake.releasedEnds.value)
    }

    @Test fun leavingTheAppWhileFinishingRulesOutAHold() {
        val harness = Harness()
        harness.finishing()
        harness.post(DictationAudioEvent.DidEnterBackground)
        harness.audio.release(DictationEnd.Send, owner = 1)
        assertNull(harness.audio.hold)
        assertFalse(harness.session.isActive)
        assertEquals("dictation_session_released reason=background end=send held=0s other=0", harness.lines.last())

        // Back in front: a recording ends held again.
        harness.post(DictationAudioEvent.WillEnterForeground)
        assertEquals(12.0, harness.press(DictationEnd.Send)?.seconds)
    }

    @Test fun sendingWhileAnInsertFinishesRulesOutItsHold() {
        val harness = Harness()
        harness.finishing()
        harness.audio.endHold(DictationHoldRelease.Sent)
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertNull(harness.audio.hold)
        assertFalse(harness.session.isActive)
        assertEquals("dictation_session_released reason=sent end=insert held=0s other=0", harness.lines.last())

        // Leaving the session does the same.
        val left = Harness()
        left.finishing()
        left.audio.endHold(DictationHoldRelease.Left)
        left.audio.release(DictationEnd.Insert, owner = 1)
        assertNull(left.audio.hold)

        // A dictated send keeps its own hold.
        val send = Harness()
        send.finishing()
        send.audio.endHold(DictationHoldRelease.Sent)
        send.audio.release(DictationEnd.Send, owner = 1)
        assertEquals(12.0, send.audio.hold?.seconds)
    }

    /**
     * A call starting while a recording finalizes: no hold after it, and only
     * the rate and buffer dictation asked for are cleared, never the call's
     * session itself.
     */
    @Test fun aCallWhileFinishingRulesOutAHoldAndClearsDictationsPreferences() {
        val harness = Harness()
        harness.playMedia()
        harness.finishing()
        harness.audio.endHold(DictationHoldRelease.Call)
        harness.generation += 1
        harness.session.clearCalls()
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertNull(harness.audio.hold)
        assertEquals(listOf("rate=0", "buffer=0"), harness.session.calls)
        assertTrue(harness.session.isActive)
        assertEquals("dictation_session_released reason=claimed end=insert held=0s other=1", harness.lines.last())

        // Before the call has claimed it: the same, never a deactivation the call's setup could race.
        val early = Harness()
        early.playMedia()
        early.finishing()
        early.audio.endHold(DictationHoldRelease.Call)
        early.session.clearCalls()
        early.audio.release(DictationEnd.Send, owner = 1)
        assertNull(early.audio.hold)
        assertEquals(listOf("rate=0", "buffer=0"), early.session.calls)
        assertEquals("dictation_session_released reason=call end=send held=0s other=1", early.lines.last())

        // A rate and buffer the call set since are its own.
        val theirs = Harness()
        theirs.playMedia()
        theirs.finishing()
        theirs.audio.endHold(DictationHoldRelease.Call)
        theirs.generation += 1
        theirs.session.preferredRate = 24_000.0
        theirs.session.preferredBuffer = 0.02
        theirs.session.clearCalls()
        theirs.audio.release(DictationEnd.Insert, owner = 1)
        assertTrue(theirs.session.calls.isEmpty())
    }

    /** A newer press's session is never touched by an older press's release. */
    @Test fun anOlderPressesReleaseLeavesTheNewerRecording() {
        val harness = Harness()
        harness.finishing()
        harness.generation += 1
        harness.audio.activate()
        harness.session.clearCalls()
        harness.audio.release(DictationEnd.Insert, owner = 1)
        assertTrue(harness.session.calls.isEmpty())
        assertTrue(harness.audio.isInUse)
    }

    /** A call starting discards a recording still finalizing, so it's released (as a cancel) before the call takes the session. */
    @Test fun aCallDiscardsARecordingStillFinalizing() = runBlocking {
        val fake = LiveDictationTests.Fake(finishes = false)
        val dictation = LiveDictation(fake.engine)
        val press = dictation.begin()
        val stream = dictation.start(press)
        val ended = drain(stream)
        repeat(500) { if (fake.started.value.isEmpty()) delay(2) }
        dictation.ends(press, DictationEnd.Insert)
        val stopping = background.launch { dictation.stop(press) }
        repeat(500) { if (fake.micsStopped.get() == 0) delay(2) }
        dictation.cancel(press)
        assertEquals(listOf(DictationEnd.Cancel), fake.releasedEnds.value)
        ended.await()
        fake.finishGate.open()
        stopping.join()
        assertEquals(listOf(DictationEnd.Cancel), fake.releasedEnds.value)
    }

    private val background = CoroutineScope(Dispatchers.Default)

    /** Reads a transcript stream to its end in the background (errors included). */
    private fun drain(stream: ReceiveChannel<String>): CompletableDeferred<Unit> {
        val ended = CompletableDeferred<Unit>()
        background.launch {
            try {
                for (ignored in stream) Unit
            } catch (_: Throwable) {
            }
            ended.complete(Unit)
        }
        return ended
    }
}

/** How the reducer tells the client a press ended, which picks its hold. */
class DictationHoldReportingTests {
    data class Ended(val press: Int, val end: DictationEnd)

    @Test fun eachWayOfFinishingSaysHowItEnded() = runTest {
        val ends = mutableListOf<Ended>()
        val stops = mutableListOf<Int>()
        fun store(state: DictationFeature.State) = TestStore(state, DictationFeature, this) {
            this[DictationClient] = DictationClient(
                ends = { press, end -> synchronized(ends) { ends += Ended(press, end) } },
                stop = { press -> synchronized(stops) { stops += press } },
            )
        }
        val finishing = { send: OpenCodeDelivery? -> DictationFeature.Phase.Finishing(send) }

        // Stop: an insert.
        val insert = store(DictationFeature.State(phase = DictationFeature.Phase.Recording, press = 1))
        insert.send(DictationFeature.Action.InsertTapped) { it.copy(phase = finishing(null)) }
        insert.finish()
        // Then Send while its last words land: now a send.
        insert.send(DictationFeature.Action.SendTapped) { it.copy(phase = finishing(OpenCodeDelivery.Steer)) }
        insert.finish()
        assertEquals(listOf(Ended(1, DictationEnd.Insert), Ended(1, DictationEnd.Send)), ends)

        // Send and Queue from the bar.
        ends.clear()
        val send = store(DictationFeature.State(phase = DictationFeature.Phase.Recording, press = 2))
        send.send(DictationFeature.Action.SendTapped) { it.copy(phase = finishing(OpenCodeDelivery.Steer)) }
        send.finish()
        val queue = store(DictationFeature.State(phase = DictationFeature.Phase.Recording, press = 3))
        queue.send(DictationFeature.Action.QueueTapped) { it.copy(phase = finishing(OpenCodeDelivery.Queue)) }
        queue.finish()
        assertEquals(listOf(Ended(2, DictationEnd.Send), Ended(3, DictationEnd.Send)), ends)

        // The Live Activity's Stop while a send waits on its last words: an insert.
        ends.clear()
        val activity = store(DictationFeature.State(phase = finishing(OpenCodeDelivery.Steer), press = 4))
        activity.send(DictationFeature.Action.StopRequested) { it.copy(phase = finishing(null)) }
        activity.finish()
        assertEquals(listOf(Ended(4, DictationEnd.Insert)), ends)
        assertEquals(listOf(1, 2, 3), stops)
    }
}
