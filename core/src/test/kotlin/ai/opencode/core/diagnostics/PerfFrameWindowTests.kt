package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * `PerfFrameClock` and `PerfFrameWindow` count only callbacks that came past their deadline,
 * whatever rate the display ran.
 */
class PerfFrameWindowTests {
    @get:Rule val isolation = TestIsolationRule()

    /** Feeds callbacks at [timestamps], each scheduled for the next by [periods] (seconds), and sums every frame after the first. */
    private fun window(timestamps: List<Double>, periods: List<Double>): Pair<PerfFrameWindow, List<PerfFrameClock.Frame>> {
        val clock = PerfFrameClock()
        val window = PerfFrameWindow()
        val frames = mutableListOf<PerfFrameClock.Frame>()
        for ((timestamp, period) in timestamps.zip(periods)) {
            val frame = clock.tick(timestamp = timestamp, target = timestamp + period) ?: continue
            window.add(frame)
            frames += frame
        }
        return window to frames
    }

    /** Callbacks on every refresh of [period], starting at [start]. */
    private fun steady(count: Int, period: Double, start: Double = 100.0): List<Double> = (0 until count).map { start + it * period }

    @Test
    fun onTimeFramesCountNothing() {
        val period = 1.0 / 60
        val (window, _) = window(steady(31, period), List(31) { period })
        assertEquals(30, window.frames)
        assertTrue(window.long == 0 && window.dropped == 0 && window.hitchMs == 0.0)
        assertEquals(listOf(0, 0, 0), window.stalls.toList())
        assertTrue(abs(window.spanMs - 500) < 0.01)
    }

    @Test
    fun sixtyToOneTwentyIsNoDrop() {
        val slow = 1.0 / 60
        val fast = 1.0 / 120
        val timestamps = steady(10, slow) + steady(20, fast, start = 100 + 10 * slow)
        val periods = List(10) { slow } + List(20) { fast }
        val (window, frames) = window(timestamps, periods)
        assertTrue(window.long == 0 && window.dropped == 0 && window.stalls.toList() == listOf(0, 0, 0))
        assertTrue(frames.all { it.lateMs == 0.0 })
    }

    @Test
    fun oneTwentyToSixtyIsNoDrop() {
        val slow = 1.0 / 60
        val fast = 1.0 / 120
        val timestamps = steady(20, fast) + steady(10, slow, start = 100 + 20 * fast)
        val periods = List(20) { fast } + List(10) { slow }
        val (window, _) = window(timestamps, periods)
        assertTrue(window.long == 0 && window.dropped == 0 && window.stalls.toList() == listOf(0, 0, 0))
    }

    @Test
    fun aSteadyThirtyOrTwentyHertzCadenceIsNoStall() {
        for (period in listOf(1.0 / 30, 0.05)) {
            val (window, _) = window(steady(20, period), List(20) { period })
            assertTrue("$period", window.long == 0 && window.dropped == 0)
            assertEquals("a 50 ms cadence isn't a 50 ms stall", listOf(0, 0, 0), window.stalls.toList())
            assertEquals(0.0, window.maxLateMs, 0.0)
        }
    }

    @Test
    fun aLateCallbackCountsItsLatenessAndTheFramesItDropped() {
        val period = 1.0 / 60
        // Due at 100 + 2p; the main thread was busy and it came four refreshes later.
        val timestamps = listOf(100.0, 100 + period, 100 + 6 * period, 100 + 7 * period)
        val (window, frames) = window(timestamps, List(4) { period })
        assertTrue(abs(frames[1].lateMs - 4_000 * period) < 0.01)
        assertTrue(abs(frames[1].intervalMs - 5_000 * period) < 0.01)
        assertTrue(window.long == 1 && window.dropped == 4)
        assertTrue(abs(window.hitchMs - 4_000 * period) < 0.01)
        assertEquals(listOf(1, 0, 0), window.stalls.toList())
    }

    @Test
    fun stallsCountAgainstEveryThresholdTheyPass() {
        val period = 1.0 / 60
        val window = PerfFrameWindow()
        val stalled = listOf(40.0, 50.0, 120.0, 300.0).map {
            window.add(PerfFrameClock.Frame(intervalMs = it + 1_000 * period, periodMs = 1_000 * period, lateMs = it))
        }
        assertEquals(listOf(false, true, true, true), stalled)
        assertEquals(listOf(3, 2, 1), window.stalls.toList())
        assertEquals(300.0, window.maxLateMs, 0.0)
    }
}
