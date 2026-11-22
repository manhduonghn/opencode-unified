package ai.opencode.feature.voice

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class IdleVoiceSensorsTests {
    @Test fun aWarmWindowWarmsSixTimesAndThenNothing() = runTest {
        var warms = 0
        val window = launch { LiveSocketWarmer.warmWindow { warms += 1 } }
        advanceTimeBy(120.seconds)
        runCurrent()
        assertTrue(window.isCompleted)
        assertEquals(6, warms)
        advanceTimeBy(3_600.seconds)
        assertEquals(6, warms)
    }

    @Test fun aCancelledWindowStopsWarming() = runTest {
        var warms = 0
        val window = launch { LiveSocketWarmer.warmWindow { warms += 1 } }
        advanceTimeBy(25.seconds)
        window.cancel()
        advanceTimeBy(120.seconds)
        assertEquals(3, warms)
    }
}
