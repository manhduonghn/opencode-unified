package ai.opencode.feature.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// `runTest`'s virtual time is the clock: it moves only when the test advances it. The coordinator
// runs on a `StandardTestDispatcher` of the test's scheduler and reads time from the `Clock`
// dependency bound to it (`virtualClock()`), so `advanceTimeBy` + `runCurrent` wakes every timer
// due by the new time.

/**
 * A live call on the test's virtual time, with steps that return once the
 * call has taken each event in, so the test advances time only between them.
 */
class VoiceClockedCall private constructor(
    val test: TestScope,
    val harness: LiveHarness,
    val socket: FakeLiveSocket,
    val events: ClockedEvents,
    val timing: VoiceCallTiming,
) {
    val coordinator: LiveCallCoordinator get() = harness.coordinator

    /** What went to the voice agent to speak. */
    fun spoken(): List<String> = socket.spoken()

    fun advance(milliseconds: Int) {
        test.testScheduler.advanceTimeBy(milliseconds.toLong())
        test.testScheduler.runCurrent()
    }

    /**
     * The reconnect after a lost session first sleeps its retry delay. The Kotlin coordinator
     * sleeps every timer on the one `Clock`, so the test steps virtual time over the delay.
     */
    fun retryDelayPasses() = advance((timing.retryDelays.first() * 1_000).roundToInt())

    /** Advance the clock between two of the user's words. */
    suspend fun userSays(delta: String, from: Int, to: Int? = null) {
        val now = test.now
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = delta, startMilliseconds = from, endMilliseconds = to ?: (from + 50)))
        until { coordinator.serialized { coordinator.quietGate.lastUserSpeechAt } == now }
    }

    /** With the setup's zero settle and acknowledgement wait, it goes out at once. */
    suspend fun delegates(id: String, at: Int) {
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = id, target = "client", offsetMilliseconds = at))
        until { coordinator.serialized { id in coordinator.openDelegations } }
    }

    /**
     * A frame of the voice agent's speech, after its words when given.
     * Advance the clock between two frames.
     */
    suspend fun agentSpeaks(words: String? = null, from: Int, to: Int? = null) {
        val now = test.now
        if (words != null) {
            socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = words, startMilliseconds = from, endMilliseconds = to ?: (from + 100)))
        }
        socket.yieldEvent(LiveServerEvent.OutputAudio(pcm = LiveCallCoordinatorTests.speechPCM, startMilliseconds = from, endMilliseconds = from + 100))
        until {
            val gate = coordinator.serialized { coordinator.quietGate }
            gate.assistantSpeaking && gate.lastAssistantSpeechAt == now
        }
    }

    /** A loud chunk of the user's voice on the microphone. */
    suspend fun micHearsUser() {
        val now = test.now
        harness.capture(LiveCallCoordinatorTests.speechPCM)
        until { coordinator.serialized { coordinator.micVoiceLastAt } == now }
    }

    suspend fun playbackDrains() {
        val now = test.now
        harness.audioEvent(VoiceAudioEvent.playbackDrained)
        until {
            val gate = coordinator.serialized { coordinator.quietGate }
            !gate.assistantSpeaking && gate.lastAssistantSpeechAt == now
        }
    }

    /**
     * What the call does once it's due, which takes a moment of work but no
     * time: runs whatever is queued at the current virtual time until
     * [condition] holds, for up to 5 s of real time.
     */
    suspend fun waitFor(condition: () -> Boolean) {
        val mark = TimeSource.Monotonic.markNow()
        while (!condition() && mark.elapsedNow() < 5.seconds) yield()
    }

    /** The call does all the work it can while the clock stands still. */
    suspend fun settle() {
        repeat(50) {
            test.testScheduler.runCurrent()
            yield()
        }
    }

    /** Waits for [job] without moving the clock. */
    suspend fun join(job: Job) = until { job.isCompleted }

    /**
     * Polls the call's own state, which moves without the clock. Fails after
     * 5 s of real time instead of hanging.
     */
    suspend fun until(condition: suspend () -> Boolean) {
        val mark = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (mark.elapsedNow() > 5.seconds) throw AssertionError("The call never reached the awaited state")
            yield()
        }
    }

    companion object {
        suspend fun start(
            test: TestScope,
            timing: VoiceCallTiming,
            delegation: LiveDelegation = LiveDelegation.client,
            unboundSpeechUsesInstructions: Boolean = false,
        ): VoiceClockedCall {
            val harness = LiveHarness(test, timing)
            val events = ClockedEvents(
                harness.start(delegation = delegation, unboundSpeechUsesInstructions = unboundSpeechUsesInstructions),
                test.backgroundScope,
            )
            val socket = harness.nextOpened()
            socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_1", expiresAt = null))
            assertEquals(VoiceCallEvent.Connected, events.next())
            return VoiceClockedCall(test, harness, socket, events, timing)
        }

        /**
         * Results and answers wait out a 150 ms pause, 300 ms for a reply the
         * user is owed, and 400 ms for an answer to start.
         */
        suspend fun idle(
            test: TestScope,
            resultMaxHold: Duration = 1.seconds,
            answerSpeakingHold: Duration = 4.seconds,
            progress: VoiceProgressLine.Timing = VoiceProgressLine.Timing(),
            delegation: LiveDelegation = LiveDelegation.client,
        ): VoiceClockedCall = start(
            test,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 40.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ).copy(
                liveAppendQuiet = 150.milliseconds,
                liveAppendReplyWait = 300.milliseconds,
                liveResultMaxHold = resultMaxHold,
                liveAnswerSpeakingHold = answerSpeakingHold,
                liveAnswerStartWait = 400.milliseconds,
                liveProgress = progress,
            ),
            delegation,
        )
    }
}

suspend fun TestScope.clockedCall(timing: VoiceCallTiming): VoiceClockedCall = VoiceClockedCall.start(this, timing)

suspend fun TestScope.idleCall(
    resultMaxHold: Duration = 1.seconds,
    answerSpeakingHold: Duration = 4.seconds,
    progress: VoiceProgressLine.Timing = VoiceProgressLine.Timing(),
    delegation: LiveDelegation = LiveDelegation.client,
): VoiceClockedCall = VoiceClockedCall.idle(this, resultMaxHold, answerSpeakingHold, progress, delegation)

/**
 * A clocked call's events, read without moving time: a test body suspended on a channel would
 * let `runTest` skip ahead to the next timer while the test clock should stand still.
 */
class ClockedEvents(flow: Flow<VoiceCallEvent>, scope: CoroutineScope) {
    private val received = mutableListOf<VoiceCallEvent>()
    private var cursor = 0
    private var ended = false
    private var failure: Throwable? = null
    val job: Job = scope.launch {
        try {
            flow.collect { received += it }
            ended = true
        } catch (error: CancellationException) {
            ended = true
            throw error
        } catch (error: Throwable) {
            failure = error
            ended = true
        }
    }

    /** The next event: null once the stream ends, throws why it dropped. Fails after 5 s of real time. */
    suspend fun next(): VoiceCallEvent? {
        val mark = TimeSource.Monotonic.markNow()
        while (cursor >= received.size && !ended) {
            if (mark.elapsedNow() > 5.seconds) throw AssertionError("No call event arrived")
            yield()
        }
        if (cursor < received.size) return received[cursor++]
        failure?.let { throw it }
        return null
    }

    /**
     * Every event not yet taken with [next], as a consumer iterating
     * `call.events` from here would have collected them so far.
     */
    fun remaining(): List<VoiceCallEvent> = received.drop(cursor)
}
