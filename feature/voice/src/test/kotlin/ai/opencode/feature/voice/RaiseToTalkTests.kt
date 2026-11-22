package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.feature.voice.RaiseToEarDetector.Gravity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.ref.WeakReference
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The detector runs its dwell on the main dispatcher; here that is [Dispatchers.Unconfined], so a
 * [TestClock.advance] runs the woken dwell inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaiseToTalkTests {
    sealed interface EarRoute {
        data class Near(val near: Boolean) : EarRoute
        data object Raise : EarRoute
    }

    private val raised = listOf(EarRoute.Near(true), EarRoute.Raise)

    private class Listening(
        val detector: RaiseToEarDetector,
        val events: MutableList<EarRoute>,
        val notes: MutableList<String>,
        val clock: TestClock,
    )

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun listen(heldUp: Boolean) = listenWithNotes(isHeldUp = { heldUp })

    private fun listenWithNotes(isHeldUp: (() -> Boolean)?, takesRaise: () -> Boolean = { true }): Listening {
        val clock = TestClock()
        val events = mutableListOf<EarRoute>()
        val notes = mutableListOf<String>()
        val detector = RaiseToEarDetector(clock = clock, heldUp = isHeldUp, onNote = { notes += it })
        detector.start(
            onRaise = { events += EarRoute.Raise; takesRaise() },
            onNear = { events += EarRoute.Near(it) },
        )
        return Listening(detector, events, notes, clock)
    }

    private fun g(x: Double, y: Double, z: Double) = Gravity(x, y, z)

    @Test fun onlyAHeldUpPhoneCountsAsRaised() {
        // At the ear: upright, tilted ~60°, or held to the ear while lying on your side.
        assertTrue(RaiseToEarDetector.isHeldUp(g(-0.2, -0.9, -0.3)))
        assertTrue(RaiseToEarDetector.isHeldUp(g(-0.75, -0.5, -0.3)))
        assertTrue(RaiseToEarDetector.isHeldUp(g(-0.8, -0.4, -0.3)))
        assertTrue(RaiseToEarDetector.isHeldUp(g(-0.85, -0.25, -0.2)))
        // Flat on a table, face up or face down.
        assertFalse(RaiseToEarDetector.isHeldUp(g(0.05, -0.1, -0.99)))
        assertFalse(RaiseToEarDetector.isHeldUp(g(0.0, 0.1, 0.98)))
        // Upside down in a hand or tilted into a pocket with top pointing down.
        assertFalse(RaiseToEarDetector.isHeldUp(g(0.1, 0.9, -0.2)))
        assertFalse(RaiseToEarDetector.isHeldUp(g(0.65, 0.65, -0.2)))
        assertFalse(RaiseToEarDetector.isHeldUp(g(-0.8, 0.1, -0.3)))
    }

    @Test fun aHandOverTheSensorWhileReadingIsNoRaise() {
        // Reading, screen tipped back 45–60°: held up, but not at an ear.
        val reading = listOf(g(0.0, -0.7, -0.7), g(0.1, -0.6, -0.78), g(0.0, -0.75, -0.6))
        for (gravity in reading) {
            assertFalse(RaiseToEarDetector.isAtEarPosture(gravity))
        }
        assertTrue("Still held up, so a call at the ear stays there", RaiseToEarDetector.isHeldUp(g(0.0, -0.75, -0.6)))
        // At the ear, every posture the raise accepted before still counts.
        val ear = listOf(g(-0.2, -0.9, -0.3), g(-0.75, -0.5, -0.3), g(-0.8, -0.4, -0.3), g(-0.85, -0.25, -0.2))
        for (gravity in ear) {
            assertTrue(RaiseToEarDetector.isAtEarPosture(gravity))
        }
    }

    @Test fun readingTiltStartsNoCallButALiveCallStillMovesToTheReceiver() = runBlocking {
        val reading = g(0.0, -0.7, -0.7)
        for (live in listOf(false, true)) {
            val clock = TestClock()
            val events = mutableListOf<EarRoute>()
            val detector = RaiseToEarDetector(clock = clock, gravitySource = { reading }, onNote = {})
            detector.start(
                onRaise = { events += EarRoute.Raise; live },
                onNear = { events += EarRoute.Near(it) },
                callIsLive = { live },
            )
            detector.proximityChanged(true)
            clock.advance(RaiseToEarDetector.dwell)
            val message = if (live) "A live call goes to the receiver" else "No call starts"
            assertEquals(message, if (live) raised else emptyList(), events)
            detector.stop()
        }
    }

    @Test fun aBriefCoverIsNoRaise() = runBlocking {
        // A finger crossing the sensor for 400 ms (over the old 350 ms dwell).
        val l = listen(heldUp = true)
        l.detector.proximityChanged(true)
        l.clock.advance(400.milliseconds)
        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertTrue(l.events.isEmpty())
        assertTrue(RaiseToEarDetector.dwell >= 600.milliseconds)
        l.detector.stop()
    }

    @Test fun pocketingPhoneDoesNotTriggerRaise() = runBlocking {
        var gravity = g(0.65, 0.65, -0.2)
        val l = listenWithNotes(isHeldUp = { RaiseToEarDetector.isHeldUp(gravity) })
        // Covered while upside-down/tilted in a pocket, then jostled upright before dwell ends.
        l.detector.proximityChanged(true)
        l.clock.advance(150.milliseconds)
        gravity = g(-0.2, -0.9, -0.3)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertTrue(l.events.isEmpty())

        // Covered while upright in hand, then inverted into a pocket before dwell ends.
        l.detector.proximityChanged(false)
        gravity = g(-0.2, -0.9, -0.3)
        l.detector.proximityChanged(true)
        l.clock.advance(150.milliseconds)
        gravity = g(0.65, 0.65, -0.2)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertTrue(l.events.isEmpty())
        l.detector.stop()
    }

    @Test fun midCallHeadTiltDoesNotDropEarRouting() = runBlocking {
        var gravity = g(-0.2, -0.9, -0.3)
        val l = listenWithNotes(isHeldUp = { RaiseToEarDetector.isHeldUp(gravity) })
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)

        // Head tilts ~60° (gravity.y ≈ -0.5) and side-lying (gravity.y ≈ -0.25) while proximity flickers clear.
        gravity = g(-0.75, -0.5, -0.3)
        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.lowerDwell + 200.milliseconds)
        assertEquals(raised, l.events)
        gravity = g(-0.85, -0.25, -0.2)
        l.clock.advance(200.milliseconds)
        assertEquals(raised, l.events)
        assertTrue(l.notes.isEmpty())

        // Lowering the phone flat drops ear routing on the next motion poll.
        gravity = g(0.05, -0.1, -0.99)
        l.clock.advance(RaiseToEarDetector.motionPoll)
        assertEquals(raised + EarRoute.Near(false), l.events)
        l.detector.stop()
    }

    @Test fun coveredAndHeldUpReportsAtEarThenRaises() = runBlocking {
        val l = listen(heldUp = true)
        l.detector.proximityChanged(true)
        assertTrue(l.events.isEmpty())
        l.clock.advance(RaiseToEarDetector.dwell - 1.milliseconds)
        assertTrue(l.events.isEmpty())
        l.clock.advance(1.milliseconds)
        assertEquals(raised, l.events)
        l.detector.stop()
    }

    @Test fun coveredAndFlatReportsNothing() = runBlocking {
        val l = listen(heldUp = false)
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertTrue(l.events.isEmpty())
        l.detector.stop()
    }

    @Test fun uncoveringBeforeTheDwellReportsNothing() = runBlocking {
        val l = listen(heldUp = true)
        l.detector.proximityChanged(true)
        l.clock.advance(200.milliseconds)
        l.detector.proximityChanged(false)
        assertTrue(l.events.isEmpty())
        l.clock.advance(RaiseToEarDetector.dwell)
        assertTrue(l.events.isEmpty())
        l.detector.stop()
    }

    @Test fun quickUncoverAndRecoverAtTheEarKeepsTheEar() = runBlocking {
        var heldUp = true
        val l = listenWithNotes(isHeldUp = { heldUp })
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)

        // A brief proximity flicker at the ear (< 0.8 s) even while lowering does not switch to speaker.
        heldUp = false
        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.lowerDwell - 100.milliseconds)
        assertEquals(raised, l.events)

        // Re-covering within the window cancels the lower and logs it.
        heldUp = true
        l.detector.proximityChanged(true)
        assertEquals(raised, l.events)
        assertEquals(listOf("Cancelled lower (proximity re-covered at ear)"), l.notes)

        l.clock.advance(RaiseToEarDetector.lowerDwell)
        assertEquals(raised, l.events)
        l.detector.stop()
    }

    @Test fun sustainedUncoverWhileHeldUpStaysAtTheEarUntilLowered() = runBlocking {
        var heldUp = true
        val l = listenWithNotes(isHeldUp = { heldUp })
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)

        // Proximity clears for over 0.8 s, but the phone is still held up at the ear.
        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.lowerDwell + 400.milliseconds)
        assertEquals(raised, l.events)
        assertTrue(l.notes.isEmpty())

        // Once the phone is no longer held up, it reports lowered.
        heldUp = false
        l.clock.advance(RaiseToEarDetector.motionPoll)
        assertEquals(raised + EarRoute.Near(false), l.events)
        assertEquals(listOf("Debounced lower (800 ms clear, phone lowered)"), l.notes)
        l.detector.stop()
    }

    @Test fun sustainedUncoverAndNotHeldUpLowersAfterLowerDwell() = runBlocking {
        var heldUp = true
        val l = listenWithNotes(isHeldUp = { heldUp })
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)

        heldUp = false
        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.lowerDwell - 1.milliseconds)
        assertEquals(raised, l.events)
        l.clock.advance(1.milliseconds)
        assertEquals(raised + EarRoute.Near(false), l.events)
        assertEquals(listOf("Debounced lower (800 ms clear, phone lowered)"), l.notes)
        l.detector.stop()
    }

    @Test fun noMotionDataLowersAfterLowerDwell() = runBlocking {
        // `heldUp = null` and no motion samples: raising trusts proximity, and lowering uses
        // proximity alone (`lowerDwell` only) without spinning.
        val l = listenWithNotes(isHeldUp = null)
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)

        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.lowerDwell - 1.milliseconds)
        assertEquals(raised, l.events)
        l.clock.advance(1.milliseconds)
        assertEquals(raised + EarRoute.Near(false), l.events)
        assertEquals(listOf("Debounced lower (800 ms clear, phone lowered)"), l.notes)
        l.detector.stop()
    }

    /**
     * A raise no call took (refused while dictating) leaves the phone off the ear: lowering
     * reports nothing, and raising again counts at once rather than waiting out the lower dwell.
     */
    @Test fun aRefusedRaiseDoesNotLeaveThePhoneAtTheEar() = runBlocking {
        var takes = false
        val l = listenWithNotes(isHeldUp = { true }, takesRaise = { takes })
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)
        assertFalse(l.detector.isAtEar)

        l.detector.proximityChanged(false)
        l.clock.advance(100.milliseconds)
        takes = true
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised + raised, l.events)
        assertTrue(l.detector.isAtEar)
        l.detector.stop()
    }

    @Test fun heldUpForeverLowersByTheCap() = runBlocking {
        val l = listenWithNotes(isHeldUp = { true })
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        assertEquals(raised, l.events)

        // Uncovered while held up forever: lowers once `lowerCap` (2.5 s) is reached.
        l.detector.proximityChanged(false)
        l.clock.advance(RaiseToEarDetector.lowerCap - RaiseToEarDetector.motionPoll)
        assertEquals(raised, l.events)
        l.clock.advance(RaiseToEarDetector.motionPoll)
        assertEquals(raised + EarRoute.Near(false), l.events)
        assertEquals(listOf("Debounced lower (2500 ms clear cap reached)"), l.notes)
        l.detector.stop()
    }

    @Test fun releasingDetectorWithoutStopDoesNotLeak() {
        val clock = TestClock()
        val weakDetector = raiseAndLowerReleasedDetector(clock)
        repeat(20) {
            if (weakDetector.get() == null) return@repeat
            System.gc()
            Thread.sleep(10)
        }
        assertNull(weakDetector.get())
    }

    /**
     * Its own `runBlocking`, so no spilled local of the test's coroutine still holds the detector
     * once it returns.
     */
    private fun raiseAndLowerReleasedDetector(clock: TestClock): WeakReference<RaiseToEarDetector> = runBlocking {
        val detector = RaiseToEarDetector(clock = clock, heldUp = { true }, onNote = {})
        detector.proximityChanged(true)
        clock.advance(RaiseToEarDetector.dwell)
        detector.proximityChanged(false)
        clock.advance(RaiseToEarDetector.lowerDwell + RaiseToEarDetector.motionPoll)
        WeakReference(detector)
    }

    @Test fun stoppingAtTheEarLeavesTheEarpiece() = runBlocking {
        val l = listen(heldUp = true)
        l.detector.proximityChanged(true)
        l.clock.advance(RaiseToEarDetector.dwell)
        l.detector.stop()
        assertEquals(raised + EarRoute.Near(false), l.events)
    }

    @Test fun stoppingBeforeTheDwellReportsNothing() = runBlocking {
        val l = listen(heldUp = true)
        l.detector.proximityChanged(true)
        l.clock.advance(200.milliseconds)
        l.detector.stop()
        assertTrue(l.events.isEmpty())
        l.clock.advance(RaiseToEarDetector.dwell)
        assertTrue(l.events.isEmpty())
    }

    /**
     * `.playAndRecord`, active, then the `.none` port override before any playback. Android's seam
     * activates and routes in one step ([VoiceAudioSessionControlling.activate] then
     * [VoiceAudioSessionControlling.route]); the call's playback belongs to the external (WebRTC)
     * owner, so the order checked is activation → route to the earpiece, with no speaker route.
     */
    @Test fun raiseStartedCallConfiguresReceiverBeforeAnyPlayback() = routingTest {
        val fake = FakeVoiceAudioSession(builtInReceiver)
        val notes = mutableListOf<String>()
        val engine = routingEngine(fake, RampClock(), atEar = true, notes = notes)
        val audio = engine.start()
        audio.enqueue(byteArrayOf(1, 2, 3, 4), "item-1")

        assertEquals(listOf(FakeVoiceAudioSession.Event.SetActive(true), FakeVoiceAudioSession.Event.Route(atEar = true)), fake.events)
        assertFalse(fake.events.contains(FakeVoiceAudioSession.Event.Route(atEar = false)))
        assertTrue("$notes", synchronized(notes) { notes.firstOrNull() }?.startsWith("At the ear: earpiece · ") == true)
        audio.stop()

        // Manual start (call button): same activation, explicit speaker route before playback.
        val manualFake = FakeVoiceAudioSession(builtInReceiver)
        val manualAudio = routingEngine(manualFake, RampClock(), atEar = false).start()
        assertEquals(listOf(FakeVoiceAudioSession.Event.SetActive(true), FakeVoiceAudioSession.Event.Route(atEar = false)), manualFake.events)
        manualAudio.stop()
    }
}

internal val builtInReceiver = listOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

/**
 * Runs an engine test on real threads with test dependencies installed. The app clock never
 * moves, so the call sounds never schedule a cue (their `AudioTrack` can't play on the JVM).
 */
internal fun routingTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    val values = DependencyValues.testing().update {
        this[Clock] = TestClock()
        this[DateClient] = DateClient.constant(Instant.EPOCH)
        this[UuidClient] = UuidClient.incrementing()
    }
    withContext(values.asContextElement()) { withTimeout(30.seconds) { body() } }
}

/** Android's engine owns no hardware in these tests: it runs with the external (WebRTC) owner. */
internal fun routingEngine(
    session: FakeVoiceAudioSession,
    clock: Clock,
    atEar: Boolean,
    notes: MutableList<String>? = null,
) = VoiceAudioEngine(
    atEar = atEar, demo = false, clock = clock, session = session, owner = AudioSessionOwner(schedule = { _, _ -> }),
    note = { _, text -> notes?.let { synchronized(it) { it += text } } }, externalOwner = true,
    externalControl = RecordingAudioControl(session), lab = null,
)
