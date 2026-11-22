package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.feature.voice.FakeVoiceAudioSession.Event
import ai.opencode.feature.voice.VoiceTranscriptTail.RecordOutcome
import ai.opencode.feature.voice.VoiceTranscriptTail.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private inline fun <reified E : Throwable> assertThrows(body: () -> Unit): E {
    try {
        body()
    } catch (error: Throwable) {
        if (error is E) return error
        throw AssertionError("Expected ${E::class.simpleName}, got $error", error)
    }
    throw AssertionError("Expected ${E::class.simpleName} to be thrown")
}

private suspend inline fun <reified E : Throwable> assertThrowsSuspending(body: () -> Unit): E {
    try {
        body()
    } catch (error: Throwable) {
        if (error is E) return error
        throw AssertionError("Expected ${E::class.simpleName}, got $error", error)
    }
    throw AssertionError("Expected ${E::class.simpleName} to be thrown")
}

/**
 * Runs an engine test on real threads with test dependencies installed. The engine's own clock
 * settles its short teardown waits at once and never wakes its 3 s backstop or 5 s quality window.
 */
private fun engineTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    val values = DependencyValues.testing().update {
        this[Clock] = TestClock()
        this[UuidClient] = UuidClient.incrementing()
    }
    withContext(values.asContextElement()) { withTimeout(30.seconds) { body() } }
}

private object SettleClock : Clock {
    override fun now(): Duration = Duration.ZERO
    override suspend fun sleep(duration: Duration) {
        if (duration > 1.seconds) awaitCancellation()
    }
}

/** With an external hardware owner. */
private fun engine(
    session: FakeVoiceAudioSession,
    owner: AudioSessionOwner,
    atEar: Boolean = false,
    claimSession: () -> Int = AudioSessionGeneration::bump,
) = VoiceAudioEngine(
    atEar = atEar, demo = false, clock = SettleClock, session = session, owner = owner,
    claimSession = claimSession, note = { _, _ -> }, externalOwner = true,
    externalControl = FakeExternalAudioControl(session), lab = null,
)

private val bluetoothHFP = listOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

class VoiceAudioEngineGuardTests {
    @Test fun raisedExceptionBecomesAnError() {
        DependencyValues.withValues(DependencyValues.testing()) {
            assertThrows<VoiceAudioError> {
                AudioEngineGuard.perform("Raising") {
                    throw IllegalStateException("required condition is false: IsFormatSampleRateAndChannelCountValid(format)")
                }
            }
            val error = assertThrows<VoiceAudioError.engineRejected> {
                AudioEngineGuard.perform("Raising") { throw IllegalStateException("0 Hz") }
            }
            assertEquals("Raising", error.operation)
            assertEquals("0 Hz", error.reason)
        }
    }

    @Test fun valuesAndSwiftErrorsPassThrough() {
        assertEquals(4, AudioEngineGuard.perform("Adding") { 2 + 2 })
        assertThrows<CancellationException> {
            AudioEngineGuard.perform("Throwing") { throw CancellationException() }
        }
    }

    @Test fun hardwareProblemNamesEachFailedCondition() {
        val good = VoiceAudioHardware.Format(sampleRate = 48_000.0, channels = 1)
        assertNull(VoiceAudioHardware.problem(inputAvailable = true, input = good, output = good))
        assertEquals(
            "no audio input available · input 0 Hz × 0 ch · output 48000 Hz × 0 ch",
            VoiceAudioHardware.problem(
                inputAvailable = false,
                input = VoiceAudioHardware.Format(sampleRate = 0.0, channels = 0),
                output = VoiceAudioHardware.Format(sampleRate = 48_000.0, channels = 0),
            ),
        )
    }
}

/** Call audio started at the tap and adopted once the call is set up. */
class VoiceAudioPrestartTests {
    private class Fake {
        val begins = mutableListOf<Boolean>()
        val stops = AtomicInteger(0)
        val atEar = mutableListOf<Boolean>()

        fun session() = VoiceAudioSession(
            capture = emptyFlow(), events = emptyFlow(),
            enqueue = { _, _ -> }, flush = { 0 }, setMuted = {},
            setAtEar = { value -> synchronized(atEar) { atEar += value } },
            stop = { stops.incrementAndGet() },
        )

        /** Prepared starts run here; a test can hold it so a start is still queued. */
        val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

        fun prestart(gate: Channel<Unit>? = null) = VoiceAudioPrestart(executor.asCoroutineDispatcher()) { atEar ->
            synchronized(begins) { begins += atEar }
            if (gate != null) try { gate.receive() } catch (_: CancellationException) {}
            session()
        }
    }

    @Test fun theCallAdoptsTheAudioStartedAtTheTap() = engineTest {
        val fake = Fake()
        val prestart = fake.prestart()
        val token = prestart.prepare(atEar = false)
        // Raised to the ear before the call was set up.
        prestart.start(atEar = true)
        prestart.discard(token)
        delay(50)
        assertEquals("One engine, started at the tap", listOf(false), fake.begins)
        assertEquals(listOf(true), fake.atEar)
        assertEquals("Adopted audio is the call's to stop", 0, fake.stops.get())
    }

    @Test fun withoutATapTheCallStartsItsOwnAudio() = engineTest {
        val fake = Fake()
        val prestart = fake.prestart()
        prestart.start(atEar = true)
        assertEquals(listOf(true), fake.begins)
        assertTrue(fake.atEar.isEmpty())
    }

    @Test fun audioNoCallAdoptedIsStoppedAndANewerTapKeepsItsOwn() = engineTest {
        val fake = Fake()
        val prestart = fake.prestart()
        val first = prestart.prepare(atEar = false)
        delay(20)
        // A newer tap replaced it before the first call's failure discarded it.
        val second = prestart.prepare(atEar = false)
        prestart.discard(first)
        assertTrue("The replaced start is stopped", pollUntil { fake.stops.get() == 1 })
        prestart.start(atEar = false)
        assertEquals("The second call adopts the newer start", 2, fake.begins.size)
        prestart.discard(second)
        delay(50)
        assertEquals(1, fake.stops.get())

        val failed = prestart.prepare(atEar = false)
        delay(20)
        prestart.discard(failed)
        assertTrue("A start no call adopted is stopped", pollUntil { fake.stops.get() == 2 })

        // Discarded before it began: no engine starts at all.
        val begins = fake.begins.size
        val hold = CountDownLatch(1)
        fake.executor.execute { hold.await() }
        prestart.discard(prestart.prepare(atEar = false))
        hold.countDown()
        delay(50)
        assertEquals(begins, fake.begins.size)
        assertEquals(2, fake.stops.get())
    }

    @Test fun aCallCancelledWhileTheAudioStartsStopsIt() = engineTest {
        val fake = Fake()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val prestart = fake.prestart(gate)
        prestart.prepare(atEar = false)
        val call = async(Dispatchers.Default) { prestart.start(atEar = false) }
        delay(20)
        call.cancel()
        gate.trySend(Unit)
        assertThrowsSuspending<CancellationException> { call.await() }
        assertTrue(pollUntil { fake.stops.get() == 1 })
        assertEquals(1, fake.stops.get())
    }
}

/**
 * A start never waits for an earlier engine's stop, and keeps nothing
 * waiting on it: a stop that never returns costs the next call nothing.
 */
class VoiceAudioStuckStopTests {
    private class Payload

    /**
     * A discarded task doesn't keep its successful result while awaiting stop: the session payload
     * and activation both go, not merely the prestart container.
     */
    @Test fun discardedSessionReleasesItsPayloadAndHolderWhileStopIsStuck() = engineTest {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val deactivated = AtomicInteger(0)
        var weakPayload: WeakReference<Payload>? = null
        val ready = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val prestart = VoiceAudioPrestart { _ ->
            val payload = Payload()
            weakPayload = WeakReference(payload)
            val holder = owner.holder { deactivated.incrementAndGet() }
            holder.activate {}
            ready.complete(Unit)
            VoiceAudioSession(
                capture = emptyFlow(), events = emptyFlow(),
                enqueue = { _, _ -> payload.hashCode() }, flush = { 0 }, setMuted = {},
                stop = {
                    stopping.complete(Unit)
                    unblock.await()
                    finished.complete(Unit)
                },
                cancel = { holder.release() },
            )
        }
        val token = prestart.prepare(atEar = false)
        ready.await()
        prestart.discard(token)
        stopping.await()
        assertTrue(
            "The completed start task and its session must be gone",
            pollUntil { System.gc(); weakPayload?.get() == null },
        )
        assertEquals(0, owner.activeCount)
        assertEquals(1, deactivated.get())
        unblock.complete(Unit)
        finished.await()
    }

    private class Ledger {
        val starts = AtomicInteger(0)
        val never = CompletableDeferred<Unit>()

        fun session(): VoiceAudioSession {
            val n = starts.incrementAndGet()
            return VoiceAudioSession(
                capture = emptyFlow(), events = emptyFlow(),
                enqueue = { _, _ -> }, flush = { 0 }, setMuted = {},
                // The first call's stop never returns.
                stop = { if (n == 1) never.await() },
            )
        }
    }

    @Test fun theNextCallsStartAtOnceAndNothingWaitsOnTheStuckStop() = engineTest {
        val ledger = Ledger()
        var prestart: VoiceAudioPrestart? = VoiceAudioPrestart { _ -> ledger.session() }
        val released = WeakReference(prestart)
        val first = prestart!!.start(atEar = false)
        val hung = launch(Dispatchers.Default) { first.stop() }
        delay(10)

        repeat(5) {
            val began = System.nanoTime()
            // A tap, replaced by another, then a call that adopts the second.
            val token = prestart!!.prepare(atEar = false)
            prestart!!.discard(token)
            prestart!!.prepare(atEar = false)
            prestart!!.start(atEar = false)
            if (System.getenv("CI") != "true") {
                assertTrue("No wait for the stuck stop", (System.nanoTime() - began) / 1_000_000 < 200)
            }
        }
        delay(50)
        prestart = null
        assertTrue(
            "No task inside the prestart is left waiting on the stuck stop",
            pollUntil { System.gc(); released.get() == null },
        )
        hung.cancel()
        ledger.never.complete(Unit)
    }

    @Test fun aNewEngineTakesTheSessionWithoutWaitingAndTheStuckOnesTeardownCantTouchIt() = engineTest {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val old = engine(FakeVoiceAudioSession(bluetoothHFP), owner)
        old.start()
        // The old call's stop is stuck; the next call starts anyway.
        val fresh = FakeVoiceAudioSession(bluetoothHFP)
        val new = engine(fresh, owner)
        val session = new.start()
        assertTrue(fresh.events.contains(Event.SetActive(true)))
        assertEquals(2, owner.activeCount)

        // The stuck engine's teardown lands now.
        old.stop()
        assertEquals(1, owner.activeCount)
        assertFalse(fresh.events.contains(Event.Deactivated))
        session.stop()
        old.stop()
    }
}

/** Shared holders serialize activation and last-release deactivation. */
class AudioSessionOwnerTests {
    /** The shared session, as the owner's bodies leave it. */
    private class Session {
        @Volatile var active = false
        private val entries = mutableListOf<String>()
        val log: List<String> get() = synchronized(entries) { entries.toList() }

        fun activate(who: String) { active = true; synchronized(entries) { entries += "activate $who" } }
        fun deactivate(who: String) { active = false; synchronized(entries) { entries += "deactivate $who" } }
    }

    private fun owner(schedule: (Duration, () -> Unit) -> Unit = { _, _ -> }) = AudioSessionOwner(schedule)

    private fun background(body: () -> Unit) { thread { body() } }

    @Test fun oldTeardownBeforeTheNewCallClaims() {
        val owner = owner()
        val session = Session()
        val old = owner.holder { session.deactivate("old") }
        old.activate { session.activate("old") }
        old.release()
        val new = owner.holder { session.deactivate("new") }
        new.activate { session.activate("new") }
        assertTrue(session.active)
    }

    @Test fun oldTeardownAfterTheNewCallClaimsBeforeItActivates() {
        val owner = owner()
        val session = Session()
        val old = owner.holder { session.deactivate("old") }
        old.activate { session.activate("old") }
        val new = owner.holder { session.deactivate("new") }
        old.release()
        new.activate { session.activate("new") }
        assertTrue(session.active)
        assertEquals(listOf("activate old", "deactivate old", "activate new"), session.log)
    }

    @Test fun oldTeardownAfterTheNewCallActivated() {
        val owner = owner()
        val session = Session()
        val old = owner.holder { session.deactivate("old") }
        old.activate { session.activate("old") }
        val new = owner.holder { session.deactivate("new") }
        new.activate { session.activate("new") }
        old.release()
        assertTrue(session.active)
    }

    /**
     * The new call claims and activates while the old deactivation is
     * already running inside the owner: the activation waits for it.
     */
    @Test fun theNewCallActivatesWhileTheOldDeactivationIsRunning() {
        val owner = owner()
        val session = Session()
        val inside = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val old = owner.holder {
            inside.countDown()
            proceed.await()
            session.deactivate("old")
        }
        val new = owner.holder { session.deactivate("new") }
        old.activate { session.activate("old") }
        background { old.release() }
        inside.await()
        background {
            runCatching { new.activate { session.activate("new") } }
            done.countDown()
        }
        Thread.sleep(30)
        assertEquals("The activation waits for the deactivation running in the owner", listOf("activate old"), session.log)
        proceed.countDown()
        done.await()
        assertEquals(listOf("activate old", "deactivate old", "activate new"), session.log)
        assertTrue(session.active)
    }

    /**
     * The old teardown arrives while the new call's activation is running
     * inside the owner: it waits, then releases only its own reference.
     */
    @Test fun theOldTeardownArrivesWhileTheNewActivationIsRunning() {
        val owner = owner()
        val session = Session()
        val old = owner.holder { session.deactivate("old") }
        old.activate { session.activate("old") }
        val new = owner.holder { session.deactivate("new") }
        val inside = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        background {
            runCatching {
                new.activate {
                    inside.countDown()
                    proceed.await()
                    session.activate("new")
                }
            }
        }
        inside.await()
        background {
            old.release()
            done.countDown()
        }
        Thread.sleep(30)
        proceed.countDown()
        done.await()
        assertEquals(1, owner.activeCount)
        assertEquals(listOf("activate old", "activate new"), session.log)
        assertTrue(session.active)
    }

    @Test fun videoKeepsActivationThroughADelayedCallRelease() {
        val owner = owner()
        val session = Session()
        val call = owner.holder { session.deactivate("call") }
        val video = owner.holder { session.deactivate("video") }
        call.activate { session.activate("call") }
        video.activate { session.activate("video") }
        call.release()
        call.release()
        assertTrue(session.active)
        assertEquals(1, owner.activeCount)
        video.release()
        assertFalse(session.active)
        assertEquals(listOf("activate call", "activate video", "deactivate video"), session.log)
    }

    @Test fun aCancelledHolderNeverActivatesAndATailStillYieldsToANewPress() {
        val owner = owner()
        val old = owner.holder {}
        old.release()
        val cancelled = AtomicInteger(0)
        val generation = AudioSessionGeneration.bump()
        AudioSessionGeneration.onNext(generation) { cancelled.incrementAndGet() }
        AudioSessionGeneration.bump()
        assertEquals(1, cancelled.get())
        assertThrows<CancellationException> { old.activate {} }
        AudioSessionGeneration.onNext(generation) { cancelled.incrementAndGet() }
        assertEquals("Already taken: cancelled at once", 2, cancelled.get())
    }

    @Test fun failedActivationBlocksStartsButKeepsTheHolderRetryable() {
        val owner = owner()
        val session = Session()
        val holder = owner.holder { session.deactivate("call") }
        holder.activate { session.activate("call") }
        assertThrows<VoiceAudioError> {
            holder.activate { throw VoiceAudioError.formatUnavailable }
        }
        assertThrows<CancellationException> {
            holder.withActive { fail("Failed activation must block engine start") }
        }
        assertEquals(listOf("activate call"), session.log)
        holder.activate { session.activate("call") }
        holder.withActive {}
        assertTrue(holder.release())
        assertFalse(holder.release())
        assertThrows<CancellationException> { holder.activate {} }
        assertThrows<CancellationException> { holder.withActive {} }
        assertEquals(listOf("activate call", "activate call", "deactivate call"), session.log)
    }

    /** Retries a test schedules, run when the test says. */
    private class Scheduled {
        val delays = mutableListOf<Duration>()
        val pending = mutableListOf<() -> Unit>()

        fun schedule(delay: Duration, work: () -> Unit) {
            delays += delay
            pending += work
        }

        fun runNext() {
            val work = if (pending.isEmpty()) null else pending.removeAt(0)
            work?.invoke()
        }
    }

    /** The platform refused. */
    private class Refused : Exception("Session activation failed")

    /** Swallowing it left the phone in play-and-record until a reboot. */
    @Test fun aRefusedDeactivationRetriesUntilItLands() {
        val scheduled = Scheduled()
        val owner = owner(scheduled::schedule)
        val session = Session()
        var refusals = 2
        val holder = owner.holder {
            refusals -= 1
            if (refusals >= 0) throw Refused()
            session.deactivate("call")
        }
        holder.activate { session.activate("call") }
        assertFalse("A refused deactivation reports it", holder.release())
        assertTrue(session.active)
        scheduled.runNext()
        assertTrue(session.active)
        scheduled.runNext()
        assertFalse(session.active)
        assertEquals(AudioSessionOwner.retryDelays.take(2), scheduled.delays)
        assertTrue(scheduled.pending.isEmpty())
    }

    @Test fun aRetryStandsDownOnceAnythingActivatesAgain() {
        val scheduled = Scheduled()
        val owner = owner(scheduled::schedule)
        val session = Session()
        var refuse = true
        val call = owner.holder {
            if (refuse) throw Refused()
            session.deactivate("call")
        }
        call.activate { session.activate("call") }
        call.release()
        refuse = false
        // Dictation claims the session and lets it go before the retry fires.
        val press = owner.holder { session.deactivate("press") }
        press.activate { session.activate("press") }
        press.release()
        scheduled.runNext()
        assertEquals(listOf("activate call", "activate press", "deactivate press"), session.log)
    }

    @Test fun aRefusalGivesUpAfterTheLastRetry() {
        val scheduled = Scheduled()
        val owner = owner(scheduled::schedule)
        var attempts = 0
        val holder = owner.holder {
            attempts += 1
            throw Refused()
        }
        holder.activate {}
        holder.release()
        repeat(10) { scheduled.runNext() }
        assertEquals(AudioSessionOwner.retryDelays.size + 1, attempts)
    }
}

/** A hang-up releases the shared session only after its end tail and the haptic engine are done. */
class CallAudioTeardownTests {
    private fun teardown(log: MutableList<String>) = CallAudioTeardown(
        sleep = { delay -> log += "sleep $delay" },
        finishTail = { log += "tail" },
        finishHaptics = { log += "haptics" },
        release = { log += "release" },
    )

    @Test fun theSessionGoesLastAfterAnEndTail() = runBlocking {
        val log = mutableListOf<String>()
        teardown(log).run(tail = 900.milliseconds)
        assertEquals(listOf("sleep ${900.milliseconds}", "tail", "sleep ${250.milliseconds}", "haptics", "release"), log)
    }

    @Test fun theSessionGoesLastWithoutATail() = runBlocking {
        val log = mutableListOf<String>()
        teardown(log).run(tail = null)
        assertEquals(listOf("tail", "sleep ${500.milliseconds}", "haptics", "release"), log)
    }

    @Test fun theBackstopOutlastsTheLongestTeardown() {
        assertTrue(CallAudioTeardown.deadline > (900 + 250 + 1000).milliseconds)
    }
}

/** A call claims the session from any dictation hold or earlier hang-up tail. */
class VoiceAudioGenerationTests {
    @Test fun aCallStartEndsTheHoldsBeforeIt() = engineTest {
        val claims = AtomicInteger(0)
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val engine = engine(fake, AudioSessionOwner(schedule = { _, _ -> }), claimSession = { claims.incrementAndGet() })
        val session = engine.start()
        assertEquals(1, claims.get())
        session.stop()
    }
}

/**
 * Interruption recovery. `LiveRTCSetupTests` is dropped: its `LiveRTCLocked` store-unless-finished
 * helper has no Kotlin counterpart type.
 */
class VoiceAudioRecoveryTests {
    @Test fun failedReactivationCanResumeAndDeactivate() {
        for (retryOnRouteChange in listOf(false, true)) engineTest {
            val owner = AudioSessionOwner(schedule = { _, _ -> })
            val fake = FakeVoiceAudioSession(bluetoothHFP)
            val engine = engine(fake, owner)
            val call = engine.start()
            engine.handleInterruption(began = true)
            fake.clearEvents()
            fake.onActivate = { throw VoiceAudioError.formatUnavailable }
            engine.handleInterruption(began = false)
            assertEquals("$retryOnRouteChange", listOf(Event.SetActive(true)), fake.events)
            assertEquals(1, owner.activeCount)

            fake.onActivate = null
            if (retryOnRouteChange) engine.handleConfigurationChange() else engine.handleInterruption(began = false)
            assertEquals("$retryOnRouteChange", 1, fake.events.count { it == Event.PlaybackStarted })
            assertFalse(fake.events.contains(Event.Deactivated))
            call.stop()
            assertEquals(1, fake.events.count { it == Event.Deactivated })
            assertEquals(0, owner.activeCount)
            assertEquals(listOf(VoiceAudioEvent.interrupted, VoiceAudioEvent.resumed), call.events.toList())
        }
    }

    @Test fun configurationChangeAfterFailedReactivationRequiresSuccessfulActivation() {
        for (retrySucceeds in listOf(false, true)) engineTest {
            val owner = AudioSessionOwner(schedule = { _, _ -> })
            val fake = FakeVoiceAudioSession(bluetoothHFP)
            val engine = engine(fake, owner)
            val call = engine.start()
            engine.handleInterruption(began = true)
            fake.clearEvents()
            // A configuration notification during the interruption cannot restart it.
            engine.handleConfigurationChange()
            assertTrue(fake.events.isEmpty())
            fake.onActivate = { throw VoiceAudioError.formatUnavailable }
            engine.handleInterruption(began = false)
            if (retrySucceeds) fake.onActivate = null
            engine.handleConfigurationChange()
            assertEquals("$retrySucceeds", 2, fake.events.count { it == Event.SetActive(true) })
            assertEquals("$retrySucceeds", if (retrySucceeds) 1 else 0, fake.events.count { it == Event.PlaybackStarted })
            assertFalse(fake.events.contains(Event.Deactivated))
            assertEquals(1, owner.activeCount)
            call.stop()
            assertEquals(1, fake.events.count { it == Event.Deactivated })
            assertEquals(0, owner.activeCount)
            assertEquals(
                if (retrySucceeds) listOf(VoiceAudioEvent.interrupted, VoiceAudioEvent.resumed) else listOf(VoiceAudioEvent.interrupted),
                call.events.toList(),
            )
        }
    }

    @Test fun configurationChangeAfterCancellationCannotRestartAnUnheldSession() = engineTest {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val engine = engine(fake, owner)
        val call = engine.start()
        call.cancel()
        fake.clearEvents()
        engine.handleConfigurationChange()
        engine.handleInterruption(began = false)
        assertTrue(fake.events.isEmpty())
        assertEquals(0, owner.activeCount)
        call.stop()
        assertTrue(call.events.toList().isEmpty())
    }

    @Test fun configurationChangeAndAtEarFailuresNeverCrashEngineScope() = engineTest {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val delegate = FakeVoiceAudioSession(emptyList())
        var simulatedMode = android.media.AudioManager.MODE_IN_COMMUNICATION
        var routeError: RuntimeException? = null
        var routeObserver: (() -> Unit)? = null
        val session = object : VoiceAudioSessionControlling by delegate {
            override val mode: Int get() = simulatedMode
            override fun route(atEar: Boolean, demo: Boolean) {
                routeError?.let { throw it }
                delegate.route(atEar, demo)
            }
            override fun observe(id: java.util.UUID, focus: (Int) -> Unit, route: () -> Unit) {
                routeObserver = route
            }
            override fun unobserve(id: java.util.UUID) {
                routeObserver = null
            }
        }
        val engine = VoiceAudioEngine(
            atEar = false, demo = false, clock = SettleClock, session = session, owner = owner,
            note = { _, _ -> }, externalOwner = true, externalControl = FakeExternalAudioControl(delegate), lab = null,
        )
        val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        try {
            val call = engine.start()
            val triggerRoute = requireNotNull(routeObserver)

            // 1. Mode changed to MODE_RINGTONE before audio-focus loss arrives, and Bluetooth/USB
            // communication device disappeared mid-route: session.route throws IllegalArgumentException
            // and session.activate throws VoiceAudioError.engineRejected on the engine's IO scope.
            simulatedMode = android.media.AudioManager.MODE_RINGTONE
            routeError = IllegalArgumentException("Invalid communication device")
            delegate.onActivate = { throw VoiceAudioError.engineRejected("Activating call audio", "audio focus denied") }
            delegate.clearEvents()
            triggerRoute()
            assertTrue(pollUntil { delegate.events.contains(Event.SetActive(true)) })
            delay(40)
            assertTrue("Uncaught on engine scope: $uncaught", uncaught.isEmpty())
            assertTrue(engine.running)

            // 2. RaiseToEar toggling while communication device is invalid does not crash caller.
            call.setAtEar(true)
            call.setAtEar(false)
            assertTrue("Uncaught after setAtEar: $uncaught", uncaught.isEmpty())

            // 3. Once the competing ringtone clears, the next route/mode notification restores communication mode.
            routeError = null
            delegate.onActivate = null
            simulatedMode = android.media.AudioManager.MODE_NORMAL
            delegate.clearEvents()
            triggerRoute()
            assertTrue(pollUntil { delegate.events.contains(Event.Route(false)) })
            call.stop()
            assertEquals(1, delegate.events.count { it == Event.Deactivated })
            assertEquals(0, owner.activeCount)
            assertEquals(listOf(VoiceAudioEvent.routeChanged), call.events.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}

/**
 * The engine runs from the tap for the connecting cues; the microphone
 * reaches the call only once it connects.
 */
class VoiceCaptureGateTests {
    /** A capture buffer of PCM16 samples at [value]. */
    private fun buffer(value: Float, frames: Int = PCM.chunkFrames) = ShortArray(frames) { (value * Short.MAX_VALUE).toInt().toShort() }

    private fun samples(chunk: ByteArray) = (0 until chunk.size / 2).map { PCM.sample(chunk, it) }

    @Test fun framesCapturedBeforeTheCallConnectsAreNeverSent() = runBlocking {
        val gate = CaptureGate()
        val channel = Channel<ByteArray>(50)
        val forwarder = CaptureForwarder(gate = gate, continuation = channel, quality = VoiceAudioQuality(), levels = VoiceAudioLevels())
        // Said while the call was starting, including half a chunk that must not
        // be completed by audio after the connect.
        repeat(3) { buffer(0.5f).let { forwarder.receive(it, it.size) } }
        buffer(0.5f, frames = PCM.chunkFrames / 2).let { forwarder.receive(it, it.size) }
        gate.open()
        repeat(2) { buffer(0.25f).let { forwarder.receive(it, it.size) } }
        channel.close()

        val chunks = mutableListOf<ByteArray>()
        for (chunk in channel) chunks += chunk
        assertEquals(2, chunks.size)
        val all = chunks.flatMap(::samples)
        val early = (0.5f * Short.MAX_VALUE).toInt()
        assertFalse("Nothing from before the connect", all.any { kotlin.math.abs(it - early) < 200 })
        assertTrue(all.all { kotlin.math.abs(it - (0.25f * Short.MAX_VALUE).toInt()) < 200 })
    }

    @Test fun mutedFramesAreDroppedToo() = runBlocking {
        val gate = CaptureGate()
        gate.open()
        val muted = VoiceAudioLevels()
        muted.isMuted = true
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        val forwarder = CaptureForwarder(gate = gate, continuation = channel, quality = VoiceAudioQuality(), levels = muted)
        buffer(0.5f).let { forwarder.receive(it, it.size) }
        channel.close()
        var count = 0
        for (chunk in channel) count += 1
        assertEquals(0, count)
    }
}

/**
 * A start that fails or is cancelled after the session activated releases
 * it, so Bluetooth doesn't stay in call mode, unless a newer claim has it.
 */
class VoiceAudioFailedStartTests {
    @Test fun anActivationFailureReleasesItsReferenceExactlyOnce() = engineTest {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        fake.onActivate = { throw VoiceAudioError.formatUnavailable }
        val engine = engine(fake, owner)
        assertThrowsSuspending<VoiceAudioError> { engine.start() }
        assertEquals(0, owner.activeCount)
        assertEquals(1, fake.events.count { it == Event.Deactivated })
        engine.stop()
        assertEquals(1, fake.events.count { it == Event.Deactivated })
    }

    @Test fun aStartCancelledAfterActivationDeactivatesTheSession() = engineTest {
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val engine = engine(fake, AudioSessionOwner(schedule = { _, _ -> }))
        val start = async(Dispatchers.Default, start = CoroutineStart.LAZY) { engine.start() }
        fake.onActivate = { start.cancel() }
        start.start()
        assertThrowsSuspending<CancellationException> { start.await() }
        val events = fake.events
        val activated = events.indexOf(Event.SetActive(true))
        assertTrue("$events", activated >= 0)
        assertTrue("$events", events.drop(activated).contains(Event.Deactivated))
        assertFalse(engine.running)
    }

    /**
     * Ending a call while it connected deactivated the session from the main thread as the engine
     * enabled voice processing, which deadlocked audiomxd and took the app down with it.
     */
    @Test fun aHangUpMidSetupLeavesDeactivationToTheStart() = engineTest {
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        var atHangUp: List<Event>? = null
        val engine = engine(fake, owner)
        val start = async(Dispatchers.Default, start = CoroutineStart.LAZY) { engine.start() }
        fake.onRoute = {
            if (atHangUp == null) {
                start.cancel()
                atHangUp = fake.events
            }
        }
        start.start()
        assertThrowsSuspending<CancellationException> { start.await() }
        val hangUp = atHangUp ?: throw AssertionError("The engine never read the route")
        assertTrue("$hangUp", hangUp.contains(Event.SetActive(true)))
        assertFalse("The hang-up itself touched the session: $hangUp", hangUp.contains(Event.Deactivated))
        assertEquals("${fake.events}", 1, fake.events.count { it == Event.Deactivated })
        assertFalse(fake.events.contains(Event.PlaybackStarted))
        assertEquals(0, owner.activeCount)
    }

    @Test fun aWithdrawnHolderCannotActivateButStillReleases() {
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        var deactivated = 0
        val holder = owner.holder { deactivated += 1 }
        holder.activate {}
        holder.withdraw()
        assertEquals(0, deactivated)
        assertThrows(CancellationException::class.java) { holder.withActive {} }
        assertThrows(CancellationException::class.java) { holder.activate {} }
        assertTrue(holder.release())
        assertFalse(holder.release())
        assertEquals(1, deactivated)
        assertEquals(0, owner.activeCount)
    }

    @Test fun aStartThatFailsAfterANewerClaimLeavesTheSessionAlone() = engineTest {
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val newer = owner.holder {}
        val engine = engine(fake, owner)
        val start = async(Dispatchers.Default, start = CoroutineStart.LAZY) { engine.start() }
        // A newer call or dictation claims the session as this one activates.
        fake.onActivate = {
            runCatching { newer.activate {} }
            start.cancel()
        }
        start.start()
        assertThrowsSuspending<CancellationException> { start.await() }
        assertFalse("${fake.events}", fake.events.contains(Event.Deactivated))
    }

    @Test fun aCancelledStartScheduledAfterItsReplacementNeverActivates() = engineTest {
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val owner = AudioSessionOwner(schedule = { _, _ -> })
        val engine = engine(fake, owner)
        val fresh = FakeVoiceAudioSession(bluetoothHFP)
        val replacement = engine(fresh, owner).start()
        val delayed = async(Dispatchers.Default, start = CoroutineStart.LAZY) { engine.start() }
        delayed.cancel()
        assertThrowsSuspending<CancellationException> { delayed.await() }
        assertFalse(fake.events.contains(Event.SetActive(true)))
        assertFalse(fake.events.contains(Event.Deactivated))
        assertFalse(fresh.events.contains(Event.Deactivated))
        assertEquals(1, owner.activeCount)
        replacement.stop()
    }

    @Test fun aStartThatRunsDoesNotDeactivate() = engineTest {
        val fake = FakeVoiceAudioSession(bluetoothHFP)
        val engine = engine(fake, AudioSessionOwner(schedule = { _, _ -> }))
        val session = engine.start()
        assertFalse(fake.events.contains(Event.Deactivated))
        session.stop()
    }
}

class VoiceTranscriptTests {
    /** The Kotlin tail returns copies. */
    private class Tail(var value: VoiceTranscriptTail = VoiceTranscriptTail()) {
        fun record(role: Role, text: String, id: String? = null): RecordOutcome =
            value.record(role, text, id).also { value = it.tail }.outcome
        fun capture(): VoiceTranscriptTail.Capture = value.capture().also { value = it.tail }.capture
        fun accept(capture: VoiceTranscriptTail.Capture) { value = value.accept(capture) }
        fun release(capture: VoiceTranscriptTail.Capture) { value = value.release(capture) }
        val lines get() = value.lines
    }

    private val user = Role.user
    private val assistant = Role.assistant

    @Test fun revisionsUpdateLinesInsteadOfAppending() {
        val tail = Tail()
        assertEquals(RecordOutcome.added, tail.record(user, "can you look at, uh, the failing test", id = "item_1"))
        assertEquals(RecordOutcome.added, tail.record(assistant, "On it - checking the failure."))
        assertEquals(RecordOutcome.updated, tail.record(user, "can you look at the failing test in the calculus session", id = "item_1"))
        assertEquals(RecordOutcome.duplicate, tail.record(user, "can you look at the failing test in the calculus session", id = "item_1"))
        assertEquals(RecordOutcome.empty, tail.record(user, "   ", id = "item_2"))
        assertEquals(
            listOf("user: can you look at the failing test in the calculus session", "assistant: On it - checking the failure."),
            tail.lines,
        )
    }

    @Test fun captureDeliversOnlyNewOrRevisedLinesAndMarksUpdates() {
        val tail = Tail()
        tail.record(user, "first request", id = "a")
        val first = tail.capture()
        assertEquals(listOf("user: first request"), first.lines)
        tail.accept(first)

        assertTrue(tail.capture().lines.isEmpty())
        tail.record(user, "first request, but only the tests", id = "a")
        tail.record(assistant, "Sure.")
        val second = tail.capture()
        assertEquals(listOf("user (updated utterance): first request, but only the tests", "assistant: Sure."), second.lines)

        // A failed admission releases the reservation so the next capture retries it.
        tail.release(second)
        assertEquals(second.lines, tail.capture().lines)
    }

    @Test fun evictionKeepsDeliveredContextBoundedAndRefusesEvictedIDs() {
        val tail = Tail()
        for (index in 0 until 20) {
            tail.record(user, "line $index", id = "id_$index")
            tail.accept(tail.capture())
        }
        assertEquals(VoiceTranscriptTail.maxLines, tail.lines.size)
        assertEquals("user: line 8", tail.lines.first())
        assertEquals(RecordOutcome.outsideTail, tail.record(user, "late revision", id = "id_0"))
        val long = Tail()
        long.record(user, "a".repeat(1_500), id = "x")
        long.accept(long.capture())
        long.record(user, "b".repeat(1_500), id = "y")
        assertEquals(1, long.lines.size)
        assertTrue(long.lines.first().startsWith("user: bbb"))
        assertEquals(RecordOutcome.outsideTail, long.record(user, "late revision", id = "x"))
    }

    /**
     * The bounds cap only delivered context and the continuity tail: what was
     * said since the previous delegation is its request, and arrives whole.
     */
    @Test fun undeliveredWordsAreNeverTruncated() {
        val tail = Tail()
        val utterances = (0 until 20).map { "sentence $it " + "word ".repeat(60) }
        for ((index, utterance) in utterances.withIndex()) {
            tail.record(user, utterance, id = "u$index")
            tail.record(assistant, "Mm-hm.")
        }
        val capture = tail.capture()
        assertTrue(capture.userText.length > VoiceTranscriptTail.maxCharacters)
        assertEquals(utterances.map { it.trim() }, capture.userWords)
        assertEquals(40, capture.lines.size)
        // The continuity tail stays bounded.
        assertTrue(tail.lines.size <= VoiceTranscriptTail.maxLines)
        assertTrue(tail.lines.joinToString("\n").length <= VoiceTranscriptTail.maxCharacters)

        // Once delivered, it's only context and is bounded again.
        tail.accept(capture)
        tail.record(user, "and run the tests", id = "next")
        assertEquals("and run the tests", tail.capture().userText)
        assertEquals(RecordOutcome.outsideTail, tail.record(user, "late revision", id = "u0"))

        // One utterance longer than the bound arrives whole.
        val single = Tail()
        val dictated = "dictated message ".repeat(300).trim()
        assertEquals(RecordOutcome.added, single.record(user, dictated, id = "long"))
        assertEquals(dictated, single.capture().userText)
    }

    @Test fun anUtteranceLongerThanTheBoundStaysAndKeepsGrowing() {
        val tail = Tail()
        tail.record(user, "earlier ask", id = "a")
        val long = (0 until 400).joinToString(" ") { "word$it" }
        assertTrue(long.length > VoiceTranscriptTail.maxCharacters)
        tail.record(user, long, id = "u")
        val first = tail.capture()
        assertEquals("earlier ask $long", first.userText)
        tail.accept(first)

        // The continuity tail keeps the newest line, its start elided.
        val lines = tail.lines
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("user: …"))
        assertTrue(lines[0].endsWith("word399"))
        assertEquals(VoiceTranscriptTail.maxCharacters, lines[0].length)

        // Delivered and over the bound, it still takes its revisions.
        assertEquals(RecordOutcome.updated, tail.record(user, "$long and more words", id = "u"))
        assertEquals("and more words", tail.capture().userText)
    }

    @Test fun undeliveredSpeechIsBoundedAndSaysWhatItDropped() {
        val tail = Tail()
        for (index in 0 until 70) tail.record(user, "turn $index", id = "t$index")
        val capture = tail.capture()
        assertEquals(VoiceTranscriptTail.maxUndeliveredEntries + 1, capture.lines.size)
        assertEquals(VoiceTranscriptTail.omittedNote, capture.lines.first())
        assertEquals(VoiceTranscriptTail.omittedNote, capture.userWords.first())
        assertEquals("turn 10", capture.userWords.drop(1).first())
        assertEquals("turn 69", capture.userWords.last())
        // A failed admission brings the note back with the lines.
        tail.release(capture)
        assertEquals(VoiceTranscriptTail.omittedNote, tail.capture().lines.first())

        val long = Tail()
        for (index in 0 until 3) long.record(user, "$index".repeat(9_000), id = "l$index")
        val kept = long.capture()
        assertEquals(VoiceTranscriptTail.omittedNote, kept.lines.first())
        assertEquals(listOf('1', '2'), kept.userWords.drop(1).map { it.first() })
        long.accept(kept)
        long.record(user, "next", id = "n")
        assertEquals("The note travels once", listOf("user: next"), long.capture().lines)
    }

    /**
     * A backchannel after a very long request never pushes the request out:
     * what's sent is the user's words, and the continuity tail keeps them.
     */
    @Test fun aBackchannelNeverDisplacesALongRequest() {
        val request = (0 until 3_000).joinToString(" ") { "word$it" }
        assertTrue(request.length > VoiceTranscriptTail.maxUndeliveredCharacters)
        val tail = Tail()
        tail.record(user, request, id = "u")
        tail.record(assistant, "Mm-hm.")
        val capture = tail.capture()
        assertEquals(request, capture.userText)
        assertEquals(listOf("user: $request", "assistant: Mm-hm."), capture.lines)

        val lines = tail.lines
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("user: …") && lines[0].endsWith("word2999"))
        assertEquals("assistant: Mm-hm.", lines[1])
        assertTrue(lines.joinToString("\n").length <= VoiceTranscriptTail.maxCharacters)

        // An earlier turn over the bound goes, and the request says so but keeps its words.
        val earlier = Tail()
        earlier.record(user, "first ask", id = "a")
        earlier.record(user, request, id = "u")
        earlier.record(assistant, "Mm-hm.")
        val kept = earlier.capture()
        assertEquals(listOf(VoiceTranscriptTail.omittedNote, request), kept.userWords)
        assertEquals(VoiceTranscriptTail.omittedNote, kept.lines.first())
        assertEquals("user: $request", kept.lines.drop(1).first())
    }

    @Test fun evictionDuringAdmissionAcceptsOnlyCapturedLines() {
        val tail = Tail()
        for (index in 0 until VoiceTranscriptTail.maxLines) tail.record(user, "line $index", id = "id_$index")
        val admitted = tail.capture()
        // New speech evicts the oldest line while the admission is in flight.
        tail.record(assistant, "Looking now.")
        tail.accept(admitted)
        assertEquals(listOf("assistant: Looking now."), tail.capture().lines)
    }

    @Test fun releasingAfterEvictionKeepsOverlappingReservations() {
        val tail = Tail()
        for (index in 0 until VoiceTranscriptTail.maxLines) tail.record(user, "line $index", id = "id_$index")
        val first = tail.capture()
        tail.record(user, "line 1, revised", id = "id_1")
        tail.record(user, "line 12", id = "id_12")
        val second = tail.capture()
        assertEquals(listOf("user: line 1, revised", "user: line 12"), second.lines)

        tail.release(first)
        // The first capture's lines return, none lost to the bound; the second
        // still holds its own.
        assertEquals((listOf(0) + (2 until VoiceTranscriptTail.maxLines)).map { "user: line $it" }, tail.capture().lines)
        tail.accept(second)
        assertTrue(tail.capture().lines.isEmpty())
        tail.record(user, "line 12, revised", id = "id_12")
        assertEquals(listOf("user (updated utterance): line 12, revised"), tail.capture().lines)
    }

    @Test fun revisionsRespectTheCharacterBound() {
        val tail = Tail()
        tail.record(user, "short", id = "x")
        tail.record(user, "brief", id = "y")
        tail.accept(tail.capture())
        assertEquals(RecordOutcome.updated, tail.record(user, "y".repeat(1_990), id = "y"))
        assertEquals(1, tail.lines.size)
        assertTrue(tail.lines.first().startsWith("user: yyy"))
        assertTrue(tail.lines.joinToString("\n").length <= VoiceTranscriptTail.maxCharacters)
        assertEquals(RecordOutcome.outsideTail, tail.record(user, "late revision", id = "x"))
    }

    @Test fun handoffMatchesPluginShapeAndBoundsReplies() {
        val text = VoiceHandoff.format(instruction = "Inspect the failing test.", transcriptDelta = listOf("user: look at the failing test"))
        assertTrue(text.startsWith(VoiceHandoff.header + "\n{"))
        val decoded = handoffBody(text) ?: return fail("No body")
        assertEquals(JsonPrimitive("Inspect the failing test."), decoded["instruction"])
        assertEquals(JsonArray(listOf(JsonPrimitive("user: look at the failing test"))), decoded["transcript_delta"])

        assertEquals("[COMPLETE] OpenCode finished without a text reply.", VoiceHandoff.completion("  "))
        assertEquals("[COMPLETE] ".length + VoiceHandoff.replyLimit, VoiceHandoff.completion("x".repeat(VoiceHandoff.replyLimit + 400)).length)

        val codeBlock = "```swift\n" + "let value = 42\n".repeat(120) + "```"
        val replyWithCode = "Updated the parser.\n\n$codeBlock\n\nAll 42 tests in the suite now pass."
        val completion = VoiceHandoff.completion(replyWithCode)
        assertTrue(completion.contains("Updated the parser."))
        assertTrue(completion.contains("All 42 tests in the suite now pass."))
        assertFalse(completion.contains("let value = 42"))
        assertTrue(LiveContentChunker.commentaryChunks(completion).first().contains("All 42 tests in the suite now pass."))
        assertEquals("[COMPLETE] The session returned a code block in the transcript.", VoiceHandoff.completion(codeBlock))
    }

    @Test fun aWatchedSessionsLongReplyIsNotCut() {
        val reply = (1..150).joinToString(" ") { "Part $it of the explanation." }
        assertTrue(reply.length > 4_000)
        assertTrue(ReplyWatch.Outcome.Replied(reply).notice("HOME").endsWith("Its reply: $reply"))
    }

    @Test fun userWordsAreWhatWasSaidSinceThePreviousDelegation() {
        val tail = Tail()
        tail.record(user, "check why the upload test", id = "u1")
        val first = tail.capture()
        assertEquals("check why the upload test", first.userText)
        tail.accept(first)

        // The same utterance kept going after it was delegated: only its new words
        // are new, though the line travels whole as context.
        tail.record(user, "check why the upload test fails on staging", id = "u1")
        tail.record(assistant, "Checking the upload test.")
        tail.record(user, "actually only the last run", id = "u2")
        val second = tail.capture()
        assertEquals("fails on staging actually only the last run", second.userText)
        assertEquals("user (updated utterance): check why the upload test fails on staging", second.lines.first())

        // While that capture is in flight, a later capture starts from what it took.
        tail.record(user, "actually only the last run on CI", id = "u2")
        val third = tail.capture()
        assertEquals("on CI", third.userText)
        tail.accept(second)
        tail.accept(third)

        // A revision that rewrote the utterance travels whole.
        tail.record(user, "actually just the latest run", id = "u2")
        assertEquals("actually just the latest run", tail.capture().userText)
    }

    @Test fun previousTailRestoresAsAlreadySentContext() {
        val tail = Tail(VoiceTranscriptTail(lines = listOf("user: earlier ask", "assistant: earlier answer")))
        assertTrue(tail.capture().lines.isEmpty())
        tail.record(user, "new ask", id = "n")
        assertEquals(listOf("user: new ask"), tail.capture().lines)
    }
}
