package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.eventually
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Timed reads (the pending-work polls and the watchdog) go quiet while the server isn't answering
 * and while the app is in the background, since traffic into a dead route can stall a whole VPN
 * tunnel. A reconnect and the return start them again at once.
 */
class SessionFeatureTestsQuietPolling {
    /**
     * Every read the session makes is logged and then held open until it's cancelled, so a test
     * sees which reads started and which were stopped.
     */
    private fun DependencyValues.Builder.holdReads(gate: TestClock, log: LockIsolated<List<String>>) {
        suspend fun hold(name: String): Nothing {
            log.append(name)
            try {
                gate.sleep(1.seconds)
            } catch (cancelled: CancellationException) {
                log.append("$name cancelled")
                throw cancelled
            }
            throw OpenCodeClientError(message = "Held")
        }
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            familyPendingRequests = { _, _, _ -> hold("requests") },
            pendingInputs = { _, _ -> hold("inbox") },
            getSession = { _, _ -> hold("session") },
            transcript = { _, _, _ -> hold("transcript") },
        )
    }

    private fun count(name: String, log: LockIsolated<List<String>>): Int {
        var count = 0
        for (entry in log.value) {
            if (entry == name) count += 1
        }
        return count
    }

    private fun DependencyValues.Builder.answerPendingWork(gate: TestClock, log: LockIsolated<List<String>>) {
        holdReads(gate, log)
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            familyPendingRequests = { _, _, _ ->
                log.append("requests")
                OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
            },
            pendingInputs = { _, _ ->
                log.append("inbox")
                emptyList()
            },
        )
    }

    private suspend fun TestStore<State, Action>.settles(condition: () -> Boolean): Boolean {
        repeat(300) {
            skipReceivedActions()
            if (condition()) return true
            delay(10.milliseconds)
        }
        return false
    }

    private fun quietState(h: SessionTestSupport.Harness, session: OpenCodeSession, isRunning: Boolean = false): State =
        h.makeState(session = session, isRunning = isRunning, hasRenderedFirstContentFrame = true)
            .withRequestsConnectionIsAvailable(false)
            .copy(streamIsHealthy = true, streamSupportsProjection = true)

    @Test fun aSettledSessionOnAHealthyStreamPollsOnlyAsASafetyNet() = sessionTest { h ->
        val session = testSession(id = "ses_settled", title = "Settled")
        val clock = TestClock()
        val gate = TestClock()
        val log = LockIsolated<List<String>>(emptyList())
        val store = h.store(quietState(h, session)) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            answerPendingWork(gate, log)
        }
        store.exhaustive = false
        val watchdog = 120 + abs(session.id.hashCode() % 25)

        store.send(Action.RequestsConnectionChanged(true))
        assertEquals(60, store.state.pendingWorkPollSeconds)
        assertEquals(watchdog.seconds, store.state.watchdogSchedule?.interval)
        assertTrue(store.settles { count("requests", log) == 1 && count("inbox", log) == 1 })

        clock.advance(59.seconds)
        store.skipReceivedActions()
        assertEquals(1, count("requests", log))
        assertEquals(1, count("inbox", log))
        clock.advance(1.seconds)
        assertTrue(store.settles { count("requests", log) == 2 && count("inbox", log) == 2 })
        clock.advance((watchdog - 61).seconds)
        store.skipReceivedActions()
        assertEquals(0, count("transcript", log))
        clock.advance(1.seconds)
        assertTrue(store.settles { count("transcript", log) == 1 })
        assertEquals(3, count("requests", log))

        store.send(Action.ExternalRunStateChanged(isRunning = true))
        assertEquals(12, store.state.pendingWorkPollSeconds)
        assertEquals((20 + abs(session.id.hashCode() % 5)).seconds, store.state.watchdogSchedule?.interval)
        assertTrue(store.settles { count("requests", log) == 4 && count("inbox", log) == 4 })
        clock.advance(12.seconds)
        assertTrue(store.settles { count("requests", log) == 5 })
        h.skipInFlightEffects()
    }

    @Test fun pendingWorkKeepsTheActiveCadences() = sessionTest { h ->
        var state = h.makeState(session = testSession(id = "ses_cadence", title = "Cadence"))
        assertEquals(2, state.pendingWorkPollCadence)
        assertEquals(30, state.watchdogSeconds)
        state = state.copy(streamIsHealthy = true)
        assertEquals(60, state.pendingWorkPollCadence)
        assertEquals(120, state.watchdogSeconds)
        state = state.copy(queuedInputs = listOf(OpenCodePendingInput(id = "in_1", text = "Later")))
        assertEquals(12, state.pendingWorkPollCadence)
        assertEquals(30, state.watchdogSeconds)
        state = state.copy(queuedInputs = emptyList(), activeFamilySessionIDs = setOf("ses_child"))
        assertEquals(12, state.pendingWorkPollCadence)
        state = state.copy(activeFamilySessionIDs = emptySet(), isExternallyActive = true, streamSupportsProjection = true)
        assertEquals(12, state.pendingWorkPollCadence)
        assertEquals(20, state.watchdogSeconds)
        state = state.copy(streamSupportsProjection = false)
        assertEquals(3, state.watchdogSeconds)
    }

    @Test fun aCoveredScreenStopsItsTimersAndUncoveringReadsAtOnce() = sessionTest { h ->
        val session = testSession(id = "ses_cover", title = "Cover")
        val clock = TestClock()
        val gate = TestClock()
        val log = LockIsolated<List<String>>(emptyList())
        val store = h.store(quietState(h, session, isRunning = true)) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            answerPendingWork(gate, log)
        }
        store.exhaustive = false
        store.send(Action.RequestsConnectionChanged(true))
        assertTrue(store.settles { count("requests", log) == 1 && count("inbox", log) == 1 })

        store.send(Action.PollingPauseChanged(true))
        assertTrue(!store.state.pollsServer)
        assertNull(store.state.pendingWorkPollSeconds)
        assertNull(store.state.watchdogSchedule)
        clock.advance(120.seconds)
        store.skipReceivedActions()
        assertEquals(1, count("requests", log))
        assertEquals(1, count("inbox", log))
        assertEquals(0, count("transcript", log))
        store.send(Action.RequestsChanged)
        assertTrue(store.settles { count("requests", log) == 2 })

        store.send(Action.PollingPauseChanged(false))
        assertEquals(12, store.state.pendingWorkPollSeconds)
        assertNotNull(store.state.watchdogSchedule)
        assertTrue(store.settles { count("requests", log) == 3 && count("inbox", log) == 2 && count("transcript", log) == 1 })
        h.skipInFlightEffects()
    }

    @Test fun aBriefCoverReadsNoTranscriptOnTheWayBack() = sessionTest { h ->
        val session = testSession(id = "ses_brief", title = "Brief")
        val clock = TestClock()
        val gate = TestClock()
        val log = LockIsolated<List<String>>(emptyList())
        val store = h.store(quietState(h, session)) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            answerPendingWork(gate, log)
        }
        store.exhaustive = false
        store.send(Action.RequestsConnectionChanged(true))
        assertTrue(store.settles { count("requests", log) == 1 && count("inbox", log) == 1 })

        for (reads in 2..3) {
            store.send(Action.PollingPauseChanged(true))
            clock.advance(2.seconds)
            store.send(Action.PollingPauseChanged(false))
            assertTrue(store.settles { count("requests", log) == reads && count("inbox", log) == reads })
        }
        assertNotNull(store.state.watchdogSchedule)
        assertEquals(0, count("transcript", log))
        h.skipInFlightEffects()
    }

    /**
     * A running session whose stream drops asks nothing until Home hears from the server again;
     * then the polls and the 3 s watchdog start at once.
     */
    @Test fun aServerThatStopsAnsweringGetsNoTimedReadsUntilItAnswersAgain() = sessionTest { h ->
        val session = testSession(id = "ses_quiet", title = "Quiet")
        val clock = TestClock()
        val gate = TestClock()
        val log = LockIsolated<List<String>>(emptyList())
        val state = h.makeState(
            session = session,
            cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi")),
            isRunning = true,
            hasRenderedFirstContentFrame = true,
        )
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            holdReads(gate, log)
        }
        val start = clock.now()

        store.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false).withRequestFailureAwaitingReconnect(true)
        }
        // A long outage: no watchdog, no polls.
        clock.advance(120.seconds)
        assertTrue(log.value.isEmpty())

        // Home heard from the server: the request read it was owed, the polls and the watchdog at
        // the running cadence (3 s; jitter is 0 at 3 s).
        store.send(Action.RequestsConnectionChanged(true)) {
            it.withRequestsConnectionIsAvailable(true)
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Reconnect)
                .copy(
                    pendingWorkPollSeconds = 2,
                    watchdogSchedule = WatchdogSchedule(sessionID = session.id, interval = 3.seconds, deadline = start + 123.seconds),
                    watchdogGeneration = 1,
                )
        }
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        assertTrue(eventually { count("requests", log) == 1 && count("inbox", log) == 1 })
        clock.advance(3.seconds)
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        store.receive(Action.WatchdogElapsed(generation = 1)) {
            it.copy(watchdogSchedule = it.watchdogSchedule?.copy(deadline = start + 126.seconds))
        }
        store.receive(Action.RefreshRequested(RefreshReason.Watchdog)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Watchdog))
        }
        assertTrue(eventually { count("transcript", log) == 1 })
        h.skipInFlightEffects()
    }

    /**
     * The background stops the polls and the watchdog, a read in flight included; a glance at the
     * notification shade doesn't. The return starts them again at once.
     */
    @Test fun theBackgroundStopsTimedReadsAndTheReturnStartsThemAgain() = sessionTest { h ->
        val session = testSession(id = "ses_away", title = "Away")
        val clock = TestClock()
        val gate = TestClock()
        val log = LockIsolated<List<String>>(emptyList())
        val state = h.makeState(session = session, isRunning = true, hasRenderedFirstContentFrame = true)
            .withRequestsConnectionIsAvailable(false)
            .copy(streamIsHealthy = true, streamSupportsProjection = true)
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            holdReads(gate, log)
        }
        val start = clock.now()
        val interval = (20 + abs(session.id.hashCode() % 5)).seconds

        store.send(Action.RequestsConnectionChanged(true)) {
            it.withRequestsConnectionIsAvailable(true)
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
                .copy(
                    pendingWorkPollSeconds = 12,
                    watchdogSchedule = WatchdogSchedule(sessionID = session.id, interval = interval, deadline = start + interval),
                    watchdogGeneration = 1,
                )
        }
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        assertTrue(eventually { count("requests", log) == 1 && count("inbox", log) == 1 })

        // The notification shade comes and goes: nothing restarts.
        store.send(Action.SceneActiveChanged(false)) { it.copy(isSceneActive = false) }
        store.send(Action.SceneActiveChanged(true)) { it.copy(isSceneActive = true) }

        store.send(Action.SceneActiveChanged(false)) { it.copy(isSceneActive = false) }
        store.send(Action.SceneEnteredBackground) {
            it.copy(
                isBackgrounded = true,
                newMessagesBaseline = emptySet(),
                pendingWorkPollSeconds = null,
                watchdogSchedule = null,
                watchdogGeneration = 2,
            )
        }
        assertTrue(eventually { count("requests cancelled", log) == 1 && count("inbox cancelled", log) == 1 })
        // Away for a while: no watchdog read, no poll.
        clock.advance(120.seconds)
        assertEquals(1, count("requests", log))
        assertEquals(0, count("transcript", log))

        store.send(Action.SceneActiveChanged(true)) {
            it.copy(
                isSceneActive = true,
                isBackgrounded = false,
                pendingWorkPollSeconds = 12,
                watchdogSchedule = WatchdogSchedule(sessionID = session.id, interval = interval, deadline = start + 120.seconds + interval),
                watchdogGeneration = 3,
            )
        }
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        assertTrue(eventually { count("requests", log) == 2 && count("inbox", log) == 2 })
        h.skipInFlightEffects()
    }

    /**
     * A reconnect while in the background (a call keeps the app running) reads pending work once,
     * so the call hears of a request, and starts no timer: those reads finish and nothing is left
     * running. The return starts the polls and the watchdog.
     */
    @Test fun aReconnectInTheBackgroundReadsOnceAndTheReturnStartsTheTimers() = sessionTest { h ->
        val session = testSession(id = "ses_call", title = "Call")
        val clock = TestClock()
        val gate = TestClock()
        val reads = LockIsolated<List<String>>(emptyList())
        val state = h.makeState(session = session, isRunning = true, hasRenderedFirstContentFrame = true)
            .withRequestsConnectionIsAvailable(false)
            .copy(isBackgrounded = true)
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            // The request read answers when the gate opens, so its answer lands after the
            // inbox's and the order is fixed.
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, _ ->
                    reads.append("requests")
                    gate.sleep(1.seconds)
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                pendingInputs = { _, _ ->
                    reads.append("inbox")
                    emptyList()
                },
            )
        }
        val start = clock.now()

        store.send(Action.RequestsConnectionChanged(true)) {
            it.withRequestsConnectionIsAvailable(true).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        store.receive(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        assertTrue(eventually { "requests" in reads.value })
        gate.advance(1.seconds)
        store.receive(
            Action.PendingRequestsResponse(
                sessionID = session.id,
                result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())),
            ),
        ) {
            it.withRequestLoadAttempt(null).withRequestArrivalsAreLive(true)
        }
        // One read each, and nothing left running: no poll, no watchdog.
        store.finish()
        assertEquals(listOf("inbox", "requests"), reads.value.sorted())

        store.send(Action.SceneActiveChanged(true)) {
            it.copy(
                isBackgrounded = false,
                pendingWorkPollSeconds = 2,
                watchdogSchedule = WatchdogSchedule(sessionID = session.id, interval = 3.seconds, deadline = start + 3.seconds),
                watchdogGeneration = 1,
            )
        }
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        store.receive(Action.PendingInputsResponse(sessionID = session.id, inputs = emptyList()))
        assertTrue(eventually { count("requests", reads) == 2 })
        h.skipInFlightEffects()
    }

    /**
     * An open shell sheet asks a failing server less and less often (3, 6, 12, 24, then every
     * 30 s) and goes back to every second once it answers.
     */
    @Test fun aShellSheetBacksOffAServerThatKeepsFailing() = sessionTest { h ->
        val delays = ArrayList<kotlin.time.Duration>()
        for (failures in 1..6) delays += ShellOutputFeature.retryDelay(afterFailures = failures)
        assertEquals(listOf(3, 6, 12, 24, 30, 30).map { it.seconds }, delays)
        val clock = TestClock()
        val failure = OpenCodeClientError(message = "Offline", statusCode = 503)
        val reads = LockIsolated(0)
        val state = ShellOutputFeature.State(
            server = testServer,
            directory = "/project",
            shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = "ses_shell", status = OpenCodeShell.Status.Running),
        ).copy(endCursor = 0)
        val store = h.store(state, { ShellOutputFeature() }) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                shell = { _, _, _ ->
                    reads.increment()
                    throw failure
                },
            )
        }
        store.send(ShellOutputFeature.Action.Task)
        store.receive(ShellOutputFeature.Action.PollFailed(failure)) { it.copy(errorMessage = "Couldn’t refresh output") }
        val schedule = listOf(3, 6, 12)
        for (delay in schedule) {
            clock.advance((delay - 1).seconds)
            var earlier = 0
            for (other in schedule) {
                if (other < delay) earlier += 1
            }
            assertEquals("No read before $delay s", 1 + earlier, reads.value)
            clock.advance(1.seconds)
            store.receive(ShellOutputFeature.Action.PollFailed(failure))
        }
        store.send(ShellOutputFeature.Action.TaskEnded)
    }

    /** The shell glyph's rate probe stops while the server isn't answering; the glyph keeps moving on what it last measured. */
    @Test fun theShellGlyphProbesOnlyWhileTheServerAnswers() = sessionTest { h ->
        var state = h.makeState(session = testSession(id = "ses_glyph", title = "Glyph"))
        assertNotNull(state.shellGlyphProbe)
        state = state.withRequestsConnectionIsAvailable(false)
        assertNull(state.shellGlyphProbe)
    }
}
