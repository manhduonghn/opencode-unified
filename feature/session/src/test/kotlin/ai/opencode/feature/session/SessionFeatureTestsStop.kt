package ai.opencode.feature.session

import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.eventually
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertFalse
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.flush
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.hang
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.delay
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionFeatureTestsStop {
    private val couldntStop = OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t stop session")
    private val missing = OpenCodeClientError(message = "Missing", statusCode = 404)
    private val unavailable = OpenCodeClientError(message = "The server returned HTTP 503.", statusCode = 503)

    private val stoppedRunText = "Interrupted"

    /** Settle effects (20 yields, then 30 ms) and reduce what they sent ([flush]). */
    private suspend fun TestStore<State, Action>.settleEffects() {
        flush()
        delay(30.milliseconds)
        flush()
    }

    private fun State.withPromptState(id: String, prompt: State.PromptState) = copy(promptStates = promptStates + (id to prompt))

    @Test
    fun stopRetriesWithBackoffUntilTheServerTakesIt() = sessionTest { h ->
        val clock = TestClock()
        val attempts = LockIsolated(0)
        val store = h.store(h.makeState(isRunning = true)) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    val attempt = attempts.increment()
                    if (attempt < 3) throw unavailable
                    true
                },
                isSessionActive = { _, _ -> true },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        assertTrue(eventually { attempts.value == 1 })
        store.settleEffects()
        // The shared request policy: half to all of 1 s, then of 2 s.
        clock.advance(499.milliseconds)
        store.settleEffects()
        assertEquals(1, attempts.value)
        clock.advance(501.milliseconds)
        assertTrue(eventually { attempts.value == 2 })
        // The second try went between 0.5 and 1 s, so the third goes between
        // 1.5 and 3 s.
        store.settleEffects()
        clock.advance(499.milliseconds)
        store.settleEffects()
        assertEquals(2, attempts.value)
        clock.advance(2001.milliseconds)
        store.receive(Action.InterruptAcknowledged(sessionID = "ses_1", generation = 1, interrupted = true))
        assertEquals(3, attempts.value)
        assertTrue(store.state.isInterrupting, "Stopping… until the run ends")
        h.skipInFlightEffects()
    }

    @Test
    fun stopThatIsntTakenAtAllComesBackAtOnce() = sessionTest { h ->
        val store = h.store(h.makeState(isRunning = true)) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> throw OpenCodeClientError(message = "The server returned HTTP 404.", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptFailed(sessionID = "ses_1", generation = 1)) { it.copy(isInterrupting = false) }
        assertEquals(couldntStop, store.state.items.lastOrNull())
    }

    /**
     * The stream misses the run's end: reading the session settles it, and
     * Working leaves in the same action as Stopping… does.
     */
    @Test
    fun stopSettlesByReadingTheSessionWhenTheStreamMissesTheEnd() = sessionTest { h ->
        val clock = TestClock()
        val reads = LockIsolated(0)
        val session = testSession()
        val store = h.store(h.makeState(session = session, isRunning = true)) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                isSessionActive = { _, _ -> reads.increment() < 2 },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptAcknowledged(sessionID = "ses_1", generation = 1, interrupted = true))
        store.settleEffects()
        clock.advance(1.seconds)
        assertTrue(eventually { reads.value == 1 })
        assertTrue(store.state.isInterrupting, "Still running at the first read")
        store.settleEffects()
        clock.advance(2.seconds)
        store.receive(Action.RunStateRead(sessionID = "ses_1", generation = 1, isActive = false)) {
            it.copy(isInterrupting = false, isExternallyActive = false)
        }
        assertFalse(store.state.isRunning)
        assertNull(store.state.workingRun.finale, "Stopped: the line leaves at once")
        store.receive(Action.ExternalRunStateChanged(isRunning = false))
        h.skipInFlightEffects()
    }

    @Test
    fun stopThatNeverEndsTheRunComesBackAfterItsReads() = sessionTest { h ->
        val clock = TestClock()
        val store = h.store(h.makeState(isRunning = true)) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                isSessionActive = { _, _ -> true },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptAcknowledged(sessionID = "ses_1", generation = 1, interrupted = true))
        for (delay in listOf(1, 2, 4)) {
            store.settleEffects()
            clock.advance(delay.seconds)
        }
        store.settleEffects()
        clock.advance(7.seconds)
        store.settleEffects()
        assertTrue(store.state.isInterrupting, "Still within its reads")
        clock.advance(1.seconds)
        store.receive(Action.InterruptFailed(sessionID = "ses_1", generation = 1)) { it.copy(isInterrupting = false) }
        assertEquals(couldntStop, store.state.items.lastOrNull())
        assertTrue(store.state.isRunning)
    }

    /**
     * Steer, then Stop: the server runs the steering message next (resume),
     * so Working carries on into its run and the message is delivered.
     */
    @Test
    fun steerThenStopRunsTheSteerNext() = sessionTest { h ->
        val session = testSession()
        val earlier = OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Check the build")
        val steer = OpenCodeTranscriptItem.UserText(id = "msg_steer", text = "Use staging")
        val state = h.makeState(session = session, cachedItems = listOf(earlier), isRunning = true).copy(
            isAwaitingReply = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)),
        ).withPromptState("msg_steer", State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted))
        val transcript = LockIsolated(listOf(earlier, OpenCodeTranscriptItem.Info(id = "msg_reply-error", text = stoppedRunText)))
        val store = h.store(state) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                isSessionActive = { _, _ -> true },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> transcript.value },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        assertEquals(listOf("msg_steer"), store.state.queuedInputs.map { it.id }, "The steering message stays: it isn't stopped with the run")
        assertTrue(store.state.failedSends.isEmpty())
        store.receive(Action.InterruptAcknowledged(sessionID = "ses_1", generation = 1, interrupted = true))
        // `execution.interrupted`: the steering message keeps Working up.
        store.send(Action.ExternalRunStateChanged(isRunning = false)) { it.copy(isExternallyActive = false, isInterrupting = false) }
        assertTrue(store.state.isRunning)
        assertNull(store.state.workingRun.finale)
        // `execution.started` for the next run, which takes it.
        transcript.setValue(listOf(earlier, OpenCodeTranscriptItem.Info(id = "msg_reply-error", text = stoppedRunText), steer))
        store.send(Action.ExternalRunStateChanged(isRunning = true)) { it.copy(isExternallyActive = true) }
        store.send(Action.PromptEchoResponse(sessionID = session.id, messageID = "msg_steer", requestEpoch = 1, items = listOf(steer)))
        assertTrue(store.state.queuedInputs.isEmpty(), "Delivered")
        assertTrue(store.state.isRunning)
        assertFalse(store.state.isInterrupting)
        h.skipInFlightEffects()
    }

    /** A running session with a steering message from here waiting in the inbox. */
    private fun Harness.steeringState(session: OpenCodeSession? = null, externallyActive: Boolean = true): State =
        makeState(session = session, isRunning = externallyActive).copy(
            isAwaitingReply = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)),
        ).withPromptState("msg_steer", State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted))

    /**
     * Stop's first try landed but its answer was lost, and the server already
     * runs the steering message: the retry must not interrupt that run.
     */
    @Test
    fun aLostStopAnswerNeverInterruptsTheResumedSteer() = sessionTest { h ->
        val interrupts = LockIsolated(0)
        val store = h.store(h.steeringState()) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    interrupts.increment()
                    throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                },
                // The steering message's run: active, and it left the inbox.
                isSessionActive = { _, _ -> true },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> testSession() },
                transcript = { _, _, _ -> emptyList() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptResumed(sessionID = "ses_1", generation = 1)) { it.copy(isInterrupting = false) }
        assertEquals(1, interrupts.value, "Never a second interrupt into the resumed run")
        assertTrue(store.state.isRunning)
        h.skipInFlightEffects()
    }

    /**
     * The answer was lost and the inbox can't be read: Stop can't tell the
     * resumed run from the old one, so it never sends a second interrupt.
     */
    @Test
    fun aFailedInboxReadNeverSendsASecondInterrupt() = sessionTest { h ->
        val interrupts = LockIsolated(0)
        val store = h.store(h.steeringState()) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    interrupts.increment()
                    throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                },
                isSessionActive = { _, _ -> true },
                pendingInputs = { _, _ -> throw OpenCodeClientError.unreachable(TransportFailure.NotSent) },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.receive(Action.InterruptFailed(sessionID = "ses_1", generation = 1)) { it.copy(isInterrupting = false) }
        assertEquals(1, interrupts.value, "No blind second interrupt")
        h.skipInFlightEffects()
    }

    /** The answer was lost but the run ended: the retry settles instead. */
    @Test
    fun aLostStopAnswerForARunThatEndedSettlesWithoutRetrying() = sessionTest { h ->
        val interrupts = LockIsolated(0)
        val store = h.store(h.makeState(isRunning = true)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    interrupts.increment()
                    throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                },
                isSessionActive = { _, _ -> false },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> testSession() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        store.receive(Action.RunStateRead(sessionID = "ses_1", generation = 1, isActive = false)) {
            it.copy(isInterrupting = false, isExternallyActive = false)
        }
        assertEquals(1, interrupts.value)
        assertFalse(store.state.isRunning)
        h.skipInFlightEffects()
    }

    /**
     * A run starts while Stop waits to try again: Stop is about the run
     * before it, so it stops trying and never interrupts the new one.
     */
    @Test
    fun aRunThatStartsDuringStopsBackoffIsNeverInterrupted() = sessionTest { h ->
        val clock = TestClock()
        val interrupts = LockIsolated(0)
        val state = h.makeState().copy(isAwaitingReply = true)
        val session = testSession()
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    interrupts.increment()
                    throw unavailable
                },
                isSessionActive = { _, _ -> true },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        assertTrue(eventually { interrupts.value == 1 })
        store.settleEffects()
        // `execution.started`
        store.send(Action.ExternalRunStateChanged(isRunning = true)) {
            it.copy(isExternallyActive = true, isInterrupting = false, runWorkGeneration = 2)
        }
        clock.advance(30.seconds)
        store.settleEffects()
        assertEquals(1, interrupts.value)
        assertTrue(store.state.isRunning)
        h.skipInFlightEffects()
    }

    /**
     * The steering message is delivered while Stop is still reading: Stop is
     * over at once, and its later reads change nothing.
     */
    @Test
    fun aDeliveredSteerEndsStopAndItsReadsDoNothing() = sessionTest { h ->
        val clock = TestClock()
        val session = testSession()
        val store = h.store(h.steeringState(session = session)) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                isSessionActive = { _, _ -> true },
                pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)) },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        store.receive(Action.InterruptAcknowledged(sessionID = session.id, generation = 1, interrupted = true))
        store.send(
            Action.PromptEchoResponse(
                sessionID = session.id,
                messageID = "msg_steer",
                requestEpoch = 1,
                items = listOf(OpenCodeTranscriptItem.UserText(id = "msg_steer", text = "Use staging")),
            ),
        ) {
            it.copy(isInterrupting = false, runWorkGeneration = 2)
        }
        clock.advance(20.seconds)
        store.settleEffects()
        assertFalse(
            store.state.items.any { it is OpenCodeTranscriptItem.Info && it.text == SessionFeature.stopFailedText },
            "No “Couldn’t stop session” for a Stop that took",
        )
        h.skipInFlightEffects()
    }

    /**
     * A send right after a run's end: the read the end scheduled is about the
     * run before, and never lets the new send's Working go.
     */
    @Test
    fun aSendSupersedesTheRunEndCheck() = sessionTest { h ->
        val clock = TestClock()
        val reads = LockIsolated(0)
        val session = testSession()
        val store = h.store(h.steeringState(session = session)) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = clock
            this[SessionTimers] = this[SessionTimers].copy(runEndCheck = true)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                isSessionActive = { _, _ ->
                    reads.increment()
                    false
                },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false

        store.send(Action.ExternalRunStateChanged(isRunning = false))
        store.settleEffects()
        store.send(Action.Binding(BindingAction.Draft("Then check the widget")))
        store.send(Action.SendTapped)
        store.settleEffects()
        clock.advance(3.seconds)
        store.settleEffects()
        assertEquals(0, reads.value, "The end's read was cancelled")
        assertTrue(store.state.isRunning, "The send's Working stays")
        h.skipInFlightEffects()
    }

    /** A send while Stop waits to try again: Stop stops trying. */
    @Test
    fun aSendDuringStopsBackoffCancelsTheRetry() = sessionTest { h ->
        val clock = TestClock()
        val interrupts = LockIsolated(0)
        val store = h.store(h.makeState(isRunning = true)) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                interrupt = { _, _ ->
                    interrupts.increment()
                    throw unavailable
                },
                isSessionActive = { _, _ -> true },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
                getSession = { _, _ -> testSession() },
                transcript = { _, _, _ -> emptyList() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        assertTrue(eventually { interrupts.value == 1 })
        store.settleEffects()
        store.send(Action.Binding(BindingAction.Draft("Actually, keep going")))
        store.send(Action.SendTapped)
        assertFalse(store.state.isInterrupting)
        clock.advance(30.seconds)
        store.settleEffects()
        assertEquals(1, interrupts.value, "No second interrupt into the run the send joins")
        h.skipInFlightEffects()
    }

    /** A stale read never settles a newer generation's state. */
    @Test
    fun aRunStateReadFromAnEarlierGenerationDoesNothing() = sessionTest { h ->
        val state = h.steeringState().copy(runWorkGeneration = 3)
        val store = h.store(state)
        store.send(Action.RunStateRead(sessionID = "ses_1", generation = 2, isActive = false))
        assertTrue(store.state.isRunning)
    }

    /** A running session with a queued message from here parked in the inbox. */
    private fun Harness.parkedQueueState(session: OpenCodeSession? = null, externallyActive: Boolean = true): State =
        makeState(session = session, isRunning = externallyActive).copy(
            isAwaitingReply = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_queued", text = "Then deploy", delivery = OpenCodeDelivery.Queue)),
        ).withPromptState("msg_queued", State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted))

    /**
     * Nothing ran when Stop landed, and only a parked queued message made
     * the session look busy: it settles at once, never wedged on Working.
     */
    @Test
    fun stopWithOnlyAParkedQueuedMessageSettlesAtOnce() = sessionTest { h ->
        val store = h.store(h.parkedQueueState(externallyActive = false)) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> false },
                isSessionActive = { _, _ -> false },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        assertFalse(store.state.isRunning, "A queued message never holds Working")
        store.skipReceivedActions()
        assertFalse(store.state.isInterrupting)
        assertEquals(listOf("msg_queued"), store.state.queuedInputs.map { it.id }, "Still parked")
        h.skipInFlightEffects()
    }

    /**
     * `interrupted: false` while the stream (stale) still says it runs: one
     * read settles it idle straight away, with no wait for reads or the stream.
     */
    @Test
    fun anIdleStopAnswerSettlesWithOneReadEvenWhenTheStreamIsStale() = sessionTest { h ->
        val reads = LockIsolated(0)
        val session = testSession()
        val store = h.store(h.parkedQueueState(session = session)) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> false },
                isSessionActive = { _, _ ->
                    reads.increment()
                    false
                },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        store.receive(Action.InterruptAcknowledged(sessionID = session.id, generation = 1, interrupted = false))
        assertTrue(store.state.isInterrupting, "The stream says it runs: Stop's read decides")
        store.receive(Action.RunStateRead(sessionID = session.id, generation = 1, isActive = false)) {
            it.copy(isInterrupting = false, isExternallyActive = false, isAwaitingReply = false)
        }
        assertEquals(1, reads.value)
        assertFalse(store.state.isRunning)
        h.skipInFlightEffects()
    }

    /**
     * Stop with a parked queued message: when the run ends, the stopped
     * state shows at once. Stopping… never turns back into Working.
     */
    @Test
    fun stopWithAParkedQueuedMessageNeverFlipsBackToWorking() = sessionTest { h ->
        val session = testSession()
        val store = h.store(h.parkedQueueState(session = session)) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        assertTrue(store.state.isInterrupting)
        // `execution.interrupted`
        store.send(Action.ExternalRunStateChanged(isRunning = false)) {
            it.copy(isExternallyActive = false, isInterrupting = false, isAwaitingReply = false)
        }
        assertFalse(store.state.isRunning, "Stopped, not Working, in the same action")
        assertNull(store.state.workingRun.finale)
        h.skipInFlightEffects()
    }

    /** A store whose Stop keeps failing transiently, so it sits in backoff. */
    private fun Harness.backoffStore(state: State, clock: TestClock, interrupts: LockIsolated<Int>): TestStore<State, Action> {
        val session = state.session ?: testSession()
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    interrupts.increment()
                    throw unavailable
                },
                isSessionActive = { _, _ -> true },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }
        store.exhaustive = false
        return store
    }

    /** Switching to a subagent: Stop stays with the session it was for. */
    @Test
    fun switchingSessionsCancelsStop() = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root")
        val child = testSession(id = "ses_child", title = "Child", updatedAt = distantPast, parentID = root.id)
        val state = h.makeState(session = root, isRunning = true).copy(familySessions = listOf(root, child))
        val clock = TestClock()
        val interrupts = LockIsolated(0)
        val store = h.backoffStore(state, clock, interrupts)

        store.send(Action.InterruptTapped)
        assertTrue(eventually { interrupts.value == 1 })
        store.settleEffects()
        store.send(Action.FamilyPicked(child.id))
        assertFalse(store.state.isInterrupting)
        clock.advance(30.seconds)
        store.settleEffects()
        assertEquals(1, interrupts.value)
        h.skipInFlightEffects()
    }

    /**
     * Stop, then lock the phone at once: the request is still sent, and
     * retried, under a background task that ends once the server has it.
     * Only the reads wait for the return, which settles the stop.
     */
    @Test
    fun stopThenBackgroundStillSendsAndRetriesTheInterrupt() = sessionTest { h ->
        val clock = TestClock()
        val interrupts = LockIsolated(0)
        val reads = LockIsolated(0)
        val tasks = LockIsolated<List<String>>(emptyList())
        val session = testSession()
        val store = h.store(h.makeState(session = session, isRunning = true)) {
            this[Clock] = clock
            this[BackgroundTaskClient] = BackgroundTaskClient { name ->
                tasks.append("begin $name")
                val end: () -> Unit = { tasks.append("end") }
                end
            }
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ ->
                    val attempt = interrupts.increment()
                    if (attempt == 1) throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
                    true
                },
                isSessionActive = { _, _ -> reads.increment() < 3 },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped) { it.copy(isInterrupting = true) }
        store.send(Action.SceneActiveChanged(false))
        store.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true) }
        assertTrue(store.state.isInterrupting, "Leaving the app never cancels Stop")
        assertTrue(eventually { interrupts.value == 1 })
        store.settleEffects()
        clock.advance(1.seconds)
        store.receive(Action.InterruptAcknowledged(sessionID = session.id, generation = 1, interrupted = true))
        assertEquals(2, interrupts.value, "Retried in the background")
        assertTrue(eventually { tasks.value == listOf("begin Stop session", "end") }, "Held a background task until the server had it")
        val readsInBackground = reads.value
        store.settleEffects()
        clock.advance(15.seconds)
        store.settleEffects()
        assertEquals(readsInBackground, reads.value, "No reads in the background")
        assertTrue(store.state.isInterrupting)

        // Back in the app: the reads resume and settle the stop.
        store.send(Action.SceneActiveChanged(true)) { it.copy(isBackgrounded = false) }
        for (delay in listOf(1, 2)) {
            store.settleEffects()
            clock.advance(delay.seconds)
        }
        store.receive(Action.RunStateRead(sessionID = session.id, generation = 1, isActive = false)) {
            it.copy(isInterrupting = false, isExternallyActive = false)
        }
        h.skipInFlightEffects()
    }

    /** A new run while Stop retries in the background still ends Stop's work. */
    @Test
    fun aNewRunEndsStopsBackgroundRetries() = sessionTest { h ->
        val clock = TestClock()
        val interrupts = LockIsolated(0)
        val ended = LockIsolated(false)
        val state = h.makeState().copy(isAwaitingReply = true)
        val store = h.backoffStore(state, clock, interrupts)
        store.updateDependencies {
            this[BackgroundTaskClient] = BackgroundTaskClient {
                val end: () -> Unit = { ended.setValue(true) }
                end
            }
        }

        store.send(Action.InterruptTapped)
        store.send(Action.SceneEnteredBackground)
        assertTrue(eventually { interrupts.value == 1 })
        store.settleEffects()
        store.send(Action.ExternalRunStateChanged(isRunning = true))
        clock.advance(30.seconds)
        store.settleEffects()
        assertEquals(1, interrupts.value)
        assertTrue(eventually { ended.value }, "The background task ends with the cancelled request")
        h.skipInFlightEffects()
    }

    /**
     * A detail that inherits a live call's identity starts its session's
     * work afresh: the previous session's Stop is superseded.
     */
    @Test
    fun inheritingLiveWorkSupersedesStop() = sessionTest { h ->
        val state = h.makeState(isRunning = true).copy(isInterrupting = true, inheritsLiveWork = true)
        val session = testSession()
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> emptyList() },
                getSession = { _, _ -> session },
            )
        }
        store.exhaustive = false
        store.send(Action.Task) { it.copy(isInterrupting = false, runWorkGeneration = 1) }
        h.skipInFlightEffects()
    }

    /**
     * Back in the app with Working up and nothing running: the read that
     * failed while away is asked again, so Working never stays stuck.
     */
    @Test
    fun returningToTheAppReadsTheRunStateAgain() = sessionTest { h ->
        val session = testSession()
        val state = h.parkedQueueState(session = session, externallyActive = false).copy(isSceneActive = false)
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[SessionTimers] = this[SessionTimers].copy(runEndCheck = true)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                isSessionActive = { _, _ -> false },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false

        store.send(Action.SceneActiveChanged(true))
        store.receive(Action.RunStateRead(sessionID = session.id, generation = 0, isActive = false)) { it.copy(isAwaitingReply = false) }
        assertFalse(store.state.isRunning)
        h.skipInFlightEffects()
    }

    /**
     * Send Now on a queued message parked with nothing running: it's
     * Working at once, steering it wakes the session, and the read follows.
     */
    @Test
    fun sendNowOnAParkedQueuedMessageWhileIdleWakesTheSession() = sessionTest { h ->
        val changes = LockIsolated<List<OpenCodeDelivery>>(emptyList())
        val session = testSession()
        val state = h.makeState(session = session)
            .copy(queuedInputs = listOf(OpenCodePendingInput(id = "msg_queued", text = "Then deploy", delivery = OpenCodeDelivery.Queue)))
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                setPendingInputDelivery = { _, _, _, delivery -> changes.append(delivery) },
                pendingInputs = { _, _ -> emptyList() },
                isSessionActive = { _, _ -> true },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.PendingInputSendNowTapped("msg_queued")) { it.copy(isAwaitingReply = true) }
        assertTrue(store.state.isRunning)
        store.receive(Action.RunStateRead(sessionID = session.id, generation = 1, isActive = true))
        assertEquals(listOf(OpenCodeDelivery.Steer), changes.value, "Queued already: steering it is enough")
        h.skipInFlightEffects()
    }

    /**
     * Send Now's wake belongs to its generation: a failure that lands after
     * the session moved on reports itself but leaves Working alone.
     */
    @Test
    fun aStaleSendNowFailureLeavesTheNewRunAlone() = sessionTest { h ->
        val state = h.steeringState().copy(runWorkGeneration = 2)
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                pendingInputs = { _, _ -> emptyList() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false
        store.send(
            Action.StrandedSteerSendFailed(sessionID = "ses_1", generation = 1, inputID = "msg_steer", error = OpenCodeClientError(message = "Offline")),
        )
        assertTrue(store.state.isAwaitingReply)
        h.skipInFlightEffects()
    }

    /**
     * The stream missed both ends: the read finds the next run going and the
     * steering messages gone from the inbox, so Stop took.
     */
    @Test
    fun stopSettlesWhenTheNextRunHasTakenTheSteer() = sessionTest { h ->
        val clock = TestClock()
        val state = h.makeState(isRunning = true).copy(
            isAwaitingReply = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Use staging", delivery = OpenCodeDelivery.Steer)),
        ).withPromptState("msg_steer", State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted))
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, _ -> true },
                isSessionActive = { _, _ -> true },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> testSession() },
                transcript = { _, _, _ -> emptyList() },
                message = { _, _, _ -> throw missing },
            )
        }
        store.exhaustive = false

        store.send(Action.InterruptTapped)
        store.receive(Action.InterruptAcknowledged(sessionID = "ses_1", generation = 1, interrupted = true))
        store.settleEffects()
        clock.advance(1.seconds)
        store.receive(Action.InterruptResumed(sessionID = "ses_1", generation = 1)) { it.copy(isInterrupting = false) }
        assertTrue(store.state.isRunning)
        h.skipInFlightEffects()
    }
}
