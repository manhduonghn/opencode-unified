package ai.opencode.feature.drawer.catchup

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.catchUpSessions
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.server.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Collections
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Catch Up closing itself after its last card, and the drawer row's All Caught Up moment
 * (`CatchUpFinish.kt`).
 */
class CatchUpFinishTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun CoroutineScope.store(state: CatchUpFeature.State, clock: TestClock, log: MutableList<String>) =
        TestStore(state, CatchUpFeature(), this) {
            this[Clock] = clock
            this[HapticsClient] = HapticsClient(play = { log += it.name })
        }

    private fun log(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test fun finishingShowsTheRowAllCaughtUpThenRests() = runTest {
        val clock = TestClock()
        val log = log()
        val stack = CatchUpFeature.Stack(queue = emptyList(), returnSessionID = "read")
        val store = store(CatchUpFeature.State(stack = stack), clock, log)
        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)

        store.send(CatchUpFeature.Action.Finished(reducedMotion = false)) {
            it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
        }
        clock.advance((timeline.checkTime / 2).seconds)
        assertTrue("the check hasn't started yet", log.isEmpty())
        clock.advance(timeline.checkTime.seconds)
        store.receive(CatchUpFeature.Action.FinishChecked)
        assertEquals("one success as the 0 becomes a check", listOf("success"), log.toList())
        clock.advance(timeline.restEnd.seconds)
        store.receive(CatchUpFeature.Action.FinishRested) { it.copy(justFinished = false) }
        assertEquals(listOf("success"), log.toList())
        store.finish()
    }

    @Test fun reduceMotionChecksSoonerWithTheCalmFallback() = runTest {
        val clock = TestClock()
        val log = log()
        val stack = CatchUpFeature.Stack(queue = emptyList())
        val store = store(CatchUpFeature.State(stack = stack), clock, log)
        val calm = CatchUpFinishTimeline.current(reducedMotion = true)
        val full = CatchUpFinishTimeline.current(reducedMotion = false)
        assertTrue(calm.checkTime < full.checkTime)

        store.send(CatchUpFeature.Action.Finished(reducedMotion = true)) {
            it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
        }
        clock.advance(calm.checkTime.seconds)
        store.receive(CatchUpFeature.Action.FinishChecked)
        assertEquals(listOf("success"), log.toList())
        clock.advance((calm.restEnd - calm.checkTime).seconds)
        store.receive(CatchUpFeature.Action.FinishRested) { it.copy(justFinished = false) }

        // Fades only: no travel, blur, or sweep at any moment.
        var u = -0.5
        while (u <= calm.settlesAt) {
            val frame = calm.frame(u)
            assertTrue(frame.oldTitleOffset == 0.0 && frame.newTitleOffset == 0.0)
            assertTrue(frame.oldTitleBlur == 0.0 && frame.newTitleBlur == 0.0 && frame.countBlur == 0.0)
            assertTrue(frame.sweepOpacity == 0.0 && frame.tint == 0.0)
            u += 0.05
        }
        val settled = calm.frame(calm.settlesAt)
        assertTrue(settled.countOpacity == 0.0 && settled.checkOpacity == 1.0 && settled.newTitleOpacity == 1.0)
        store.finish()
    }

    @Test fun leavingEarlyNeverFinishes() = runTest {
        val clock = TestClock()
        val log = log()
        val stack = CatchUpFeature.Stack(queue = listOf("a", "b"))
        val store = store(CatchUpFeature.State(stack = stack), clock, log)

        // A card arrived before the close: Catch Up stays open on it.
        store.send(CatchUpFeature.Action.Finished(reducedMotion = false))
        // Back with cards left.
        store.send(CatchUpFeature.Action.ExitTapped) { it.copy(stack = null, exitingStack = stack) }
        clock.run()
        assertTrue(log.isEmpty())
        store.finish()
    }

    @Test fun catchingUpAgainEndsTheMomentAtOnce() = runTest {
        val clock = TestClock()
        val log = log()
        val stack = CatchUpFeature.Stack(queue = emptyList())
        val store = store(CatchUpFeature.State(stack = stack), clock, log)

        store.send(CatchUpFeature.Action.Finished(reducedMotion = false)) {
            it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
        }
        store.send(CatchUpFeature.Action.Tapped) { it.copy(justFinished = false) }
        clock.run()
        assertTrue(log.isEmpty())
        store.finish()
    }

    @Test fun workArrivingMidSuccessEndsItWithoutTheHaptic() = runTest {
        val clock = TestClock()
        val log = log()
        val stack = CatchUpFeature.Stack(queue = emptyList())
        val store = store(CatchUpFeature.State(stack = stack), clock, log)
        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)

        store.send(CatchUpFeature.Action.Finished(reducedMotion = false)) {
            it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
        }
        clock.advance((timeline.checkTime / 2).seconds)
        store.send(CatchUpFeature.Action.WorkArrived) { it.copy(justFinished = false) }
        // Cancelled: no check, no rest, and a second arrival is a no-op.
        clock.advance((timeline.restEnd * 2).seconds)
        store.send(CatchUpFeature.Action.WorkArrived)
        assertTrue(log.isEmpty())
        store.finish()
    }

    @Test fun workArrivingAfterTheCheckStillCancelsTheRest() = runTest {
        val clock = TestClock()
        val log = log()
        val stack = CatchUpFeature.Stack(queue = emptyList())
        val store = store(CatchUpFeature.State(stack = stack), clock, log)
        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)

        store.send(CatchUpFeature.Action.Finished(reducedMotion = false)) {
            it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
        }
        clock.advance(timeline.checkTime.seconds)
        store.receive(CatchUpFeature.Action.FinishChecked)
        assertEquals(listOf("success"), log.toList())
        store.send(CatchUpFeature.Action.WorkArrived) { it.copy(justFinished = false) }
        clock.advance((timeline.restEnd * 2).seconds)
        assertEquals(listOf("success"), log.toList())
        store.finish()
    }

    @Test fun theFrameRollsTheCountThenDrawsTheCheckAndSweepsOnce() {
        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)
        val before = timeline.frame(-0.01)
        assertTrue(!before.showsZero && before.oldTitleOpacity == 1.0 && before.newTitleOpacity == 0.0 && before.checkTrim == 0.0)
        val landed = timeline.frame(0.0)
        assertTrue("the count rolls to 0 as the row lands", landed.showsZero && landed.countOpacity == 1.0)
        val settled = timeline.frame(timeline.settlesAt)
        assertEquals(0.0, settled.countOpacity, 0.0)
        assertTrue(settled.checkTrim > 0.99 && settled.newTitleOpacity == 1.0 && settled.oldTitleOpacity == 0.0)
        assertTrue(settled.sweepOpacity < 0.001)
        val peak = timeline.frame(timeline.checkDelay + timeline.glowDelay + timeline.glowDuration / 2)
        assertTrue(abs(peak.sweepOpacity - timeline.glowOpacity) < 0.001)
        assertTrue(timeline.restEnd > timeline.settlesAt)
    }

    /**
     * Never wait for the exit: the check lands, and the glow starts, while Catch Up is still
     * closing (`returnToDrawer`'s 0.45 s), so it reads as one motion; only the slower shimmer runs
     * on past it.
     */
    @Test fun theCheckAndGlowPlayWhileCatchUpCloses() {
        val closing = 0.45
        val full = CatchUpFinishTimeline.current(reducedMotion = false)
        val calm = CatchUpFinishTimeline.current(reducedMotion = true)
        assertTrue(full.checkTime + full.glowDelay < closing)
        assertTrue(calm.checkTime < closing)
        assertTrue(full.settlesAt < 1.8)
    }
}

/**
 * The same moment through `HomeFeature`: only a finished stack with nothing left to catch up on
 * celebrates, and either way Catch Up hands back to the drawer.
 */
class CatchUpFinishHomeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val fixtures = CatchUpTests()

    /** The finish the drawer list hands its Catch Up row. */
    private fun finish(state: HomeFeature.State): Int? {
        val inputs = DrawerListInputs(
            selectedSessionID = null, reducedMotion = false, increasedContrast = false,
        )
        return DrawerListModel.build(
            state = state, inputs = inputs, liftedID = null,
            now = Instant.ofEpochSecond(100), timestamp = { _, _ -> "" }, project = { _, _ -> null },
        ).shortcuts.catchUpFinish
    }

    private fun log(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    private suspend fun CoroutineScope.finishedStore(
        lastDisposition: CatchUpFeature.Disposition,
        log: MutableList<String>,
        clock: TestClock,
    ): TestStore<HomeFeature.State, Action> {
        val state = fixtures.home().copy(attentionSessionIDs = emptySet())
        val store = with(fixtures) { store(state) }
        store.updateDependencies {
            this[OpenCodeClient] = this[OpenCodeClient].copy(homeAttention = { _, _, _ -> emptySet() })
            this[HapticsClient] = HapticsClient(play = { log += it.name })
        }
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.markRead))
        store.send(Action.catchUpCardDismissed("older", lastDisposition))
        store.finish()
        store.updateDependencies { this[Clock] = clock }
        assertEquals(emptyList<String>(), store.state.catchUp?.queue)
        return store
    }

    @Test fun theLastCardReadClosesCatchUpAllCaughtUp() = runTest {
        val log = log()
        val clock = TestClock()
        val store = finishedStore(CatchUpFeature.Disposition.markRead, log, clock)

        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)
        store.send(Action.catchUpFinished(reducedMotion = false))
        assertNull(store.state.catchUp)
        assertTrue("back to the drawer it was opened from", store.state.isDrawerOpen)
        assertEquals("read", store.state.sessionDetail?.session?.id)
        assertTrue(store.state.catchUpState.justFinished)
        clock.advance(timeline.checkTime.seconds)
        store.receive(Action.CatchUp(CatchUpFeature.Action.FinishChecked))
        assertEquals(listOf("success"), log.toList())
        clock.advance(timeline.restEnd.seconds)
        store.receive(Action.CatchUp(CatchUpFeature.Action.FinishRested))
        assertFalse(store.state.catchUpState.justFinished)
        store.finish()
    }

    @Test fun theLastCardKeptUnreadClosesCatchUpWithoutCelebrating() = runTest {
        val log = log()
        val clock = TestClock()
        val store = finishedStore(CatchUpFeature.Disposition.keepUnread, log, clock)

        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)
        store.send(Action.catchUpFinished(reducedMotion = false))
        assertNull(store.state.catchUp)
        assertTrue(store.state.isDrawerOpen)
        assertFalse(store.state.catchUpState.justFinished)
        clock.advance(timeline.checkTime.seconds)
        store.receive(Action.CatchUp(CatchUpFeature.Action.FinishChecked))
        clock.advance(timeline.restEnd.seconds)
        store.receive(Action.CatchUp(CatchUpFeature.Action.FinishRested))
        assertTrue(log.isEmpty())
        store.finish()
    }

    @Test fun unreadArrivingMidSuccessEndsItAndClearingItNeverReplays() = runTest {
        val log = log()
        val clock = TestClock()
        val store = finishedStore(CatchUpFeature.Disposition.markRead, log, clock)
        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)

        store.send(Action.catchUpFinished(reducedMotion = false))
        assertTrue(store.state.catchUpState.justFinished)
        assertEquals(1, finish(store.state))
        clock.advance((timeline.checkTime / 2).seconds)

        // Waiting work lands on "older" before the check.
        store.send(Action.AttentionRefreshed(setOf("older")))
        store.receive(Action.CatchUp(CatchUpFeature.Action.WorkArrived))
        assertFalse(store.state.catchUpState.justFinished)
        assertNull(finish(store.state))

        // Answered elsewhere: nothing to catch up on, but no second finish.
        store.send(Action.AttentionRefreshed(emptySet()))
        assertTrue(store.state.catchUpSessions.isEmpty())
        assertFalse(store.state.catchUpState.justFinished)
        assertEquals(1, store.state.catchUpState.finishes)
        assertNull(finish(store.state))
        clock.advance((timeline.restEnd * 2).seconds)
        store.finish()
        assertTrue("no .success once the finish ended", log.isEmpty())
    }

    @Test fun aFailedMarkAllReceiptDuringTheFinishEndsItWithoutSuccess() = runTest {
        val log = log()
        val clock = TestClock()
        val timeline = CatchUpFinishTimeline.current(reducedMotion = false)
        // Everything read, "older" by a Mark All whose receipt is still in flight, and Catch Up's
        // last card just gone.
        var state = fixtures.home().copy(attentionSessionIDs = emptySet())
        state = state.copy(
            sessions = state.sessions.map { if (it.id in setOf("older", "newer")) it.copy(viewedMilliseconds = 2_000) else it },
        )
        val backup = CatchUpFeature.ReadBackup(
            idle = 2_000, viewedMilliseconds = 1_000, locallyViewedMilliseconds = null, readDate = null, wasManuallyUnread = false,
        )
        state = state.withMarkAllReceipts(mapOf("older" to backup))
            .withCatchUp(CatchUpFeature.Stack(queue = emptyList(), returnSessionID = "read"))
        val store = with(fixtures) { store(state) }
        store.updateDependencies {
            this[HapticsClient] = HapticsClient(play = { log += it.name })
            this[Clock] = clock
        }
        assertTrue(store.state.catchUpSessions.isEmpty())

        store.send(Action.catchUpFinished(reducedMotion = false))
        assertTrue(store.state.catchUpState.justFinished)
        assertEquals(1, store.state.catchUpState.finishes)
        clock.advance((timeline.checkTime / 2).seconds)

        // The receipt fails: "older" is unread again, which ends the finish.
        store.exhaustive = true
        store.send(Action.CatchUp(CatchUpFeature.Action.MarkAllReceiptResponse(sessionID = "older", idle = 2_000, succeeded = false))) {
            it.withMarkAllReceipts(emptyMap())
                .copy(sessions = it.sessions.map { s -> if (s.id == "older") s.copy(viewedMilliseconds = 1_000) else s })
        }
        store.receive(Action.CatchUp(CatchUpFeature.Action.WorkArrived)) {
            it.copy(catchUpState = it.catchUpState.copy(justFinished = false))
        }
        assertEquals(listOf("older"), store.state.catchUpSessions.map { it.id })
        assertNull(finish(store.state))

        // The timer was cancelled: no check, no rest, no `.success`.
        clock.advance((timeline.restEnd * 2).seconds)
        store.finish()
        assertTrue(log.isEmpty())
    }

    @Test fun backAfterTheLastCardDoesNotCelebrate() = runTest {
        val log = log()
        val clock = TestClock()
        val store = finishedStore(CatchUpFeature.Disposition.markRead, log, clock)

        store.send(Action.catchUpExitTapped)
        assertNull(store.state.catchUp)
        assertFalse(store.state.catchUpState.justFinished)
        store.send(Action.catchUpFinished(reducedMotion = false))
        assertFalse("a close already handled is ignored", store.state.catchUpState.justFinished)
        clock.run()
        store.finish()
        assertTrue(log.isEmpty())
    }
}
