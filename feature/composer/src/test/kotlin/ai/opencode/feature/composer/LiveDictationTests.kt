package ai.opencode.feature.composer

import android.speech.ModelDownloadListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presses, stops and cancels against a fake microphone and model: each only
 * ever touches its own recording, and the shared audio session is released
 * only while dictation still owns it.
 */
class LiveDictationTests {
    /** Holds an async step until the test opens it. */
    class Gate(open: Boolean = false) {
        private val opened = CompletableDeferred<Unit>().apply { if (open) complete(Unit) }

        suspend fun await() = opened.await()

        fun open() {
            opened.complete(Unit)
        }
    }

    /** A list read and appended from any thread. */
    class Log<T> {
        private val items = mutableListOf<T>()
        val value: List<T> get() = synchronized(items) { items.toList() }
        fun append(item: T) = synchronized(items) { items += item }
    }

    class Fake(
        loads: Boolean = true, finishes: Boolean = true, permits: Boolean = true,
        private val isInstallFallback: Boolean = false,
        private val load: suspend (Boolean) -> Unit = {},
    ) {
        /** The shared session's owner: dictation's claims and a call's both bump it. */
        val owner = AtomicInteger(0)
        val claims = AtomicInteger(0)
        val releases = AtomicInteger(0)
        val releasedEnds = Log<DictationEnd>()

        /** Record permission already granted: the press skips the async ask. */
        val granted = AtomicBoolean(false)
        val loadsStarted = AtomicInteger(0)
        val captures = AtomicInteger(0)
        val micsStopped = AtomicInteger(0)
        val permissionsAsked = AtomicInteger(0)
        val started = Log<Int>()
        val finished = Log<Int>()

        /** What each capture hears from outside: fire `Interrupted` on it. */
        val events = Log<(DictationCaptureEvent) -> Unit>()
        val loadGate = Gate(open = loads)
        val finishGate = Gate(open = finishes)
        val permissionGate = Gate(open = permits)
        private val results = HashMap<Int, Channel<DictationResult>>()

        val engine: DictationEngine
            get() = DictationEngine(
                isPermissionGranted = { granted.get() },
                requestPermission = {
                    permissionsAsked.incrementAndGet()
                    permissionGate.await()
                    true
                },
                claimSession = {
                    claims.incrementAndGet()
                    owner.incrementAndGet()
                },
                sessionOwner = { owner.get() },
                startCapture = { _, onEvent ->
                    captures.incrementAndGet()
                    events.append(onEvent)
                    DictationCapture(format = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)) {
                        micsStopped.incrementAndGet()
                    }
                },
                releaseSession = { end, owner ->
                    // A call or a newer press claimed the session since: theirs.
                    if (owner == this.owner.get()) {
                        releases.incrementAndGet()
                        releasedEnds.append(end)
                    }
                },
                loadModel = { install ->
                    val id = loadsStarted.incrementAndGet()
                    loadGate.await()
                    load(install)
                    model(id)
                },
            )

        private fun model(id: Int) = DictationModel(
            isInstallFallback = isInstallFallback,
            start = { _, _ ->
                val channel = Channel<DictationResult>(Channel.UNLIMITED)
                synchronized(results) { results[id] = channel }
                started.append(id)
                channel
            },
            finish = {
                finishGate.await()
                finished.append(id)
                synchronized(results) { results[id] }?.close()
            },
            cancel = { synchronized(results) { results[id] }?.close() },
        )

        fun say(text: String, model: Int, isFinal: Boolean = true) {
            synchronized(results) { results[model] }?.trySend(DictationResult(text, isFinal))
        }

        /** Mic on and not yet stopped. */
        val micIsOn: Boolean get() = captures.get() > micsStopped.get()
    }

    private val background = CoroutineScope(Dispatchers.Default)

    private suspend fun until(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(2)
        }
    }

    /** A default-start coroutine cancelled before it is dispatched never would. */
    private fun adopt(dictation: LiveDictation, press: Int) =
        background.async(start = CoroutineStart.UNDISPATCHED) { dictation.start(press) }

    /** Lets queued actor work run, for checks that something never happens. */
    private suspend fun settle() = delay(60)

    /** Whether a transcript stream has ended, read in the background. */
    private fun watch(stream: ReceiveChannel<String>): AtomicBoolean {
        val ended = AtomicBoolean(false)
        background.launch {
            try {
                for (ignored in stream) Unit
            } catch (_: Throwable) {
            }
            ended.set(true)
        }
        return ended
    }

    /** A press as the reducer makes one: begun from the touch, then adopted. */
    private suspend fun press(dictation: LiveDictation): Pair<Int, ReceiveChannel<String>> {
        val press = dictation.begin()
        return press to dictation.start(press)
    }

    private suspend fun assertCancels(message: String = "", block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a CancellationException. $message")
        } catch (_: CancellationException) {
        }
    }

    @Test fun aStoppedInstallTimesOutAndIgnoresLateDownloadCallbacks() = runTest {
        val listeners = mutableListOf<ModelDownloadListener>()
        var activeInstalls = 0
        val fake = Fake { install ->
            if (!install) throw DictationModelNotInstalled()
            activeInstalls++
            try { awaitModelDownload { listeners += it } } finally { activeInstalls-- }
        }
        val dictation = LiveDictation(fake.engine, StandardTestDispatcher(testScheduler))
        val (first, stream) = press(dictation)
        testScheduler.advanceTimeBy(30_000) // The ten seconds start at Stop, not at the mic press.
        assertEquals(1, activeInstalls)
        val stopping = launch { dictation.stop(first) }
        testScheduler.advanceTimeBy(9_999)
        assertFalse(stopping.isCompleted)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertTrue(stopping.isCompleted)
        assertEquals("Dictation isn’t available for your language yet.", stream.receiveCatching().exceptionOrNull()?.message)
        assertEquals(0, activeInstalls)
        assertEquals(listOf(DictationEnd.Failed), fake.releasedEnds.value)

        val (second, nextStream) = press(dictation)
        testScheduler.runCurrent()
        assertEquals(2, listeners.size)
        listeners.first().onError(1)
        listeners.first().onSuccess()
        testScheduler.runCurrent()
        assertTrue(fake.started.value.isEmpty())
        assertEquals(1, activeInstalls)
        listeners.last().onSuccess()
        testScheduler.runCurrent()
        fake.say("next press", fake.started.value.single())
        dictation.stop(second)
        assertEquals("next press", nextStream.receive())
        assertTrue(nextStream.receiveCatching().isClosed)
    }

    @Test fun aFallbacksPartialWordsSurviveTheSharedStopDeadline() = runTest {
        lateinit var download: ModelDownloadListener
        val fake = Fake(finishes = false, isInstallFallback = true) { install ->
            if (install) awaitModelDownload { download = it }
        }
        val dictation = LiveDictation(fake.engine, StandardTestDispatcher(testScheduler))
        val (press, stream) = press(dictation)
        val stopping = launch { dictation.stop(press) }
        testScheduler.advanceTimeBy(9_000)
        download.onError(1)
        testScheduler.runCurrent()
        fake.say("buffered words", fake.started.value.single(), isFinal = false)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertTrue("Finishing the fallback must not start a second ten-second wait", stopping.isCompleted)
        assertEquals("buffered words", stream.receive())
        val end = stream.receiveCatching()
        assertTrue(end.isClosed)
        assertEquals(null, end.exceptionOrNull())
    }

    @Test fun anEmptyFallbackReportsTheFailedInstall() = runTest {
        val dictation = LiveDictation(Fake(isInstallFallback = true).engine, StandardTestDispatcher(testScheduler))
        val (press, stream) = press(dictation)
        dictation.stop(press)
        assertEquals("Dictation isn’t available for your language yet.", stream.receiveCatching().exceptionOrNull()?.message)
    }

    /**
     * A stop still waiting on its model while a new press records finishes
     * its own model, never the newer one's, and leaves the newer stream open.
     */
    @Test fun aStopOnlyFinishesItsOwnRecording() = runBlocking {
        val fake = Fake(loads = false)
        val dictation = LiveDictation(fake.engine)
        val (firstPress, firstStream) = press(dictation)
        val first = watch(firstStream)
        until { fake.loadsStarted.get() == 1 }
        val stopping = background.launch { dictation.stop(firstPress) }
        // The stop has turned the first mic off and waits on its model.
        until { fake.micsStopped.get() == 1 }
        val (secondPress, secondStream) = press(dictation)
        val second = watch(secondStream)
        until { fake.loadsStarted.get() == 2 }
        fake.loadGate.open()
        stopping.join()
        until { first.get() }
        assertTrue(first.get())
        assertEquals(1, fake.finished.value.size)
        until { fake.started.value.size == 2 }
        delay(50)
        assertFalse("the older stop ended the newer recording", second.get())
        dictation.stop(secondPress)
        until { second.get() }
        assertEquals(setOf(1, 2), fake.finished.value.toSet())
    }

    /** Finalizing takes a while; a call (or a press) that claims the audio session meanwhile keeps it. */
    @Test fun aFinishingStopLeavesANewerOwnersSession() = runBlocking {
        val fake = Fake(finishes = false)
        val dictation = LiveDictation(fake.engine)
        val (press, stream) = press(dictation)
        watch(stream)
        until { fake.started.value.size == 1 }
        val stopping = background.launch { dictation.stop(press) }
        delay(20)
        fake.owner.incrementAndGet() // a voice call starts
        dictation.cancel(press) // the call's start stops dictation, which is already stopping
        fake.finishGate.open()
        stopping.join()
        assertEquals("the stop deactivated the call's session", 0, fake.releases.get())
    }

    @Test fun aStopReleasesTheSessionItOwns() = runBlocking {
        val fake = Fake()
        val dictation = LiveDictation(fake.engine)
        val (press, stream) = press(dictation)
        watch(stream)
        until { fake.started.value.size == 1 }
        dictation.stop(press)
        assertEquals(1, fake.releases.get())
        dictation.cancel(press)
        assertEquals(1, fake.releases.get())
    }

    /**
     * The session is released with how each press ended: a stop is an insert
     * unless the reducer said send, and a cancel is a cancel.
     */
    @Test fun theSessionIsReleasedWithHowThePressEnded() = runBlocking {
        val fake = Fake()
        val dictation = LiveDictation(fake.engine)

        val (inserted, insertedStream) = press(dictation)
        watch(insertedStream)
        until { fake.started.value.size == 1 }
        dictation.stop(inserted)

        val (sent, sentStream) = press(dictation)
        watch(sentStream)
        until { fake.started.value.size == 2 }
        dictation.ends(sent, DictationEnd.Send)
        dictation.stop(sent)

        // Send after Stop, then the Live Activity's Stop: the last word wins.
        val (changed, changedStream) = press(dictation)
        watch(changedStream)
        until { fake.started.value.size == 3 }
        dictation.ends(changed, DictationEnd.Send)
        dictation.ends(changed, DictationEnd.Insert)
        dictation.stop(changed)

        val (cancelled, cancelledStream) = press(dictation)
        watch(cancelledStream)
        until { fake.captures.get() == 4 }
        dictation.cancel(cancelled)

        assertEquals(
            listOf(DictationEnd.Insert, DictationEnd.Send, DictationEnd.Insert, DictationEnd.Cancel),
            fake.releasedEnds.value,
        )
    }

    /** With record permission already granted, a press never asks. */
    @Test fun aGrantedPressSkipsThePermissionAsk() = runBlocking {
        val fake = Fake(permits = false)
        fake.granted.set(true)
        val dictation = LiveDictation(fake.engine)
        val (press, stream) = press(dictation)
        watch(stream)
        assertEquals(1, fake.captures.get())
        assertEquals(0, fake.permissionsAsked.get())
        dictation.cancel(press)
    }

    /** Every press starts warm: ending one starts loading the next model. */
    @Test fun endingARecordingWarmsTheNextModel() = runBlocking {
        val fake = Fake()
        val dictation = LiveDictation(fake.engine)
        dictation.prepare()
        assertEquals(1, fake.loadsStarted.get())
        val (first, firstStream) = press(dictation)
        watch(firstStream)
        until { fake.started.value.size == 1 }
        assertEquals("the press took the warm model", 1, fake.loadsStarted.get())
        dictation.stop(first)
        until { fake.loadsStarted.get() == 2 }
        assertEquals(2, fake.loadsStarted.get())
        val (second, secondStream) = press(dictation)
        watch(secondStream)
        until { fake.started.value.size == 2 }
        assertEquals("the second press took the rewarmed model", 2, fake.loadsStarted.get())
        dictation.cancel(second)
    }

    /**
     * A press cancelled while its model loads doesn't start loading another; the one in flight goes
     * back to warm and the next press takes it.
     */
    @Test fun aPressCancelledWhileItsModelLoadsLoadsNoSecond() = runBlocking {
        val fake = Fake(loads = false)
        val dictation = LiveDictation(fake.engine)
        val (first, firstStream) = press(dictation)
        watch(firstStream)
        until { fake.loadsStarted.get() == 1 }
        dictation.cancel(first)
        settle()
        assertEquals(1, fake.loadsStarted.get())
        fake.loadGate.open()
        settle()
        val (second, secondStream) = press(dictation)
        watch(secondStream)
        until { fake.started.value.size == 1 }
        assertEquals("The next press takes the model the cancelled one loaded", listOf(1), fake.started.value)
        assertEquals(1, fake.loadsStarted.get())
        dictation.cancel(second)
    }

    /** Pressing again while the cancelled press's load is still under way joins that load. */
    @Test fun aPressAfterACancelJoinsTheLoadStillUnderWay() = runBlocking {
        val fake = Fake(loads = false)
        val dictation = LiveDictation(fake.engine)
        val (first, firstStream) = press(dictation)
        watch(firstStream)
        until { fake.loadsStarted.get() == 1 }
        dictation.cancel(first)
        val (second, secondStream) = press(dictation)
        watch(secondStream)
        settle()
        assertEquals("The load is still held: no second one starts", 1, fake.loadsStarted.get())
        fake.loadGate.open()
        until { fake.started.value.size == 1 }
        assertEquals(listOf(1), fake.started.value)
        assertEquals(1, fake.loadsStarted.get())
        dictation.cancel(second)
    }

    /** A press while the session screen's prepare is still loading joins that load instead of starting another. */
    @Test fun aPressDuringPrepareJoinsItsLoad() = runBlocking {
        val fake = Fake(loads = false)
        val dictation = LiveDictation(fake.engine)
        val preparing = background.launch { dictation.prepare() }
        until { fake.loadsStarted.get() == 1 }
        val (press, transcript) = press(dictation)
        fake.loadGate.open()
        preparing.join()
        until { fake.started.value.size == 1 }
        assertEquals(1, fake.loadsStarted.get())
        fake.say("first words", model = 1)
        assertEquals("first words", transcript.receive())
        dictation.cancel(press)
    }

    /**
     * The touch starts the mic itself; the press's effect, which runs later,
     * adopts that recording rather than starting a second one.
     */
    @Test fun startAdoptsTheRecordingTheTouchBegan() = runBlocking {
        val fake = Fake(loads = false)
        val dictation = LiveDictation(fake.engine)
        val press = dictation.begin()
        until { fake.captures.get() == 1 }
        assertEquals("the mic is live before any start", 1, fake.captures.get())
        val transcript = dictation.start(press)
        assertEquals(1, fake.captures.get())
        fake.loadGate.open()
        until { fake.started.value.size == 1 }
        fake.say("first words", model = 1)
        assertEquals("first words", transcript.receive())
        dictation.cancel(press)
        assertFalse(fake.micIsOn)
    }

    /**
     * Stopped or cancelled before its recording even ran: the mic and the
     * session are never touched, and the press's start ends at once.
     */
    @Test fun endingAPressBeforeItRecordsNeverCaptures() = runBlocking {
        for (end in listOf("stop", "cancel")) {
            // Held at the prompt if it got that far, so the end lands first either way.
            val fake = Fake(permits = false)
            val dictation = LiveDictation(fake.engine)
            val press = dictation.begin()
            if (end == "stop") dictation.stop(press) else dictation.cancel(press)
            fake.permissionGate.open()
            settle()
            assertEquals("$end left the session claimed", 0, fake.claims.get())
            assertEquals("$end left the mic on", 0, fake.captures.get())
            assertCancels(end) { dictation.start(press) }
        }
    }

    /**
     * Ended while the permission prompt is up: once it's answered, nothing
     * starts. The press's start, already waiting on it, ends quietly.
     */
    @Test fun endingAPressDuringThePermissionPromptNeverCaptures() = runBlocking {
        for (end in listOf("stop", "cancel")) {
            val fake = Fake(permits = false)
            val dictation = LiveDictation(fake.engine)
            val press = dictation.begin()
            val adopting = adopt(dictation, press)
            until { fake.permissionsAsked.get() == 1 }
            if (end == "stop") dictation.stop(press) else dictation.cancel(press)
            fake.permissionGate.open()
            assertCancels(end) { adopting.await() }
            settle()
            assertEquals("$end during the prompt claimed the session", 0, fake.claims.get())
            assertEquals("$end during the prompt started the mic", 0, fake.captures.get())
        }
    }

    /** The previous press's cancel or stop arriving after a new press began only ever ends its own press. */
    @Test fun aLateEndForAnEarlierPressLeavesTheNewOneRecording() = runBlocking {
        for (end in listOf("stop", "cancel")) {
            val fake = Fake()
            val dictation = LiveDictation(fake.engine)
            val (first, firstStream) = press(dictation)
            watch(firstStream)
            val second = dictation.begin()
            if (end == "stop") dictation.stop(first) else dictation.cancel(first)
            val stream = dictation.start(second)
            val ended = watch(stream)
            settle()
            assertEquals(2, fake.captures.get())
            assertTrue("press 1's late $end stopped press 2's mic", fake.micIsOn)
            assertFalse(ended.get())
            dictation.cancel(second)
            assertFalse(fake.micIsOn)
        }
    }

    /**
     * A voice call that claims the session while a press waits to record
     * wins it: dictation never claims it, never starts the mic, and never
     * deactivates the call's session.
     */
    @Test fun aCallStartingDuringABeginKeepsTheSession() = runBlocking {
        val fake = Fake(permits = false)
        val dictation = LiveDictation(fake.engine)
        val press = dictation.begin()
        val adopting = adopt(dictation, press)
        until { fake.permissionsAsked.get() == 1 }
        fake.owner.incrementAndGet() // the call's start claims the session
        fake.permissionGate.open()
        assertCancels { adopting.await() }
        dictation.cancel(press)
        settle()
        assertEquals(0, fake.claims.get())
        assertEquals(0, fake.captures.get())
        assertEquals("dictation deactivated the call's session", 0, fake.releases.get())
    }

    /**
     * The press's effect cancelled (leaving the screen) as its recording got
     * going: the recording ends with it, and the mic never stays on.
     */
    @Test fun cancellingTheAdoptingStartCancelsTheRecording() = runBlocking {
        val fake = Fake(permits = false)
        val dictation = LiveDictation(fake.engine)
        val press = dictation.begin()
        val adopting = adopt(dictation, press)
        until { fake.permissionsAsked.get() == 1 }
        adopting.cancel()
        fake.permissionGate.open()
        adopting.join()
        settle()
        assertFalse("the mic outlived its cancelled press", fake.micIsOn)
    }
}
