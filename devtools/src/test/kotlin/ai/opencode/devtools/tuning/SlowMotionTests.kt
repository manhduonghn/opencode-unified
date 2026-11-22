package ai.opencode.devtools.tuning

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.TuningStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Slow motion needs tuning access, so the store runs with Developer Mode on (JVM tests aren't a
 * debuggable build).
 */
class SlowMotionTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun theToggleReturnsToTheLastSlowSpeed() {
        var slowMotion = SlowMotion()
        assertEquals(1.0, slowMotion.scale, 0.0)
        assertFalse(slowMotion.isSlowed)
        slowMotion = slowMotion.toggle()
        assertEquals(0.25, slowMotion.scale, 0.0)
        slowMotion = slowMotion.toggle()
        assertEquals(1.0, slowMotion.scale, 0.0)
        slowMotion = slowMotion.select(0.1).select(1.0)
        assertEquals(0.1, slowMotion.lastSlowScale, 0.0)
        slowMotion = slowMotion.toggle()
        assertEquals(0.1, slowMotion.scale, 0.0)
        slowMotion = slowMotion.select(0.49)
        assertEquals(0.5, slowMotion.scale, 0.0)
        assertEquals(0.5, slowMotion.lastSlowScale, 0.0)
    }

    @Test
    fun labelsReadAsSpeeds() {
        assertEquals(listOf("1×", "0.5×", "0.25×", "0.1×"), SlowMotion.scales.map(SlowMotion::label))
    }

    @Test
    fun thePanelAndQuickToggleSlowTheAppUntilTuningTurnsOff() = runBlocking {
        val job = Job()
        val played = mutableListOf<Haptic>()
        val shared = SharedStore.inMemory()
        shared.reference(SharedKeys.developerMode).set(true)
        val store = TestStore(TuningFeature.State(), TuningFeature(), CoroutineScope(coroutineContext + job)) {
            this[SharedStore] = shared
            this[TuningStore] = TuningStore(null)
            this[HapticsClient] = HapticsClient(play = { synchronized(played) { played += it } })
        }
        try {
            store.send(TuningFeature.Action.SlowMotionToggled) { it.copy(slowMotion = it.slowMotion.select(0.25)) }
            assertEquals(0.25, AnimationTimeScale.shared.scale, 0.0)
            store.send(TuningFeature.Action.SlowMotionSelected(0.1)) { it.copy(slowMotion = it.slowMotion.select(0.1)) }
            assertEquals(0.1, AnimationTimeScale.shared.scale, 0.0)
            // The same speed again changes nothing and plays nothing.
            store.send(TuningFeature.Action.SlowMotionSelected(0.1))
            store.send(TuningFeature.Action.SlowMotionToggled) { it.copy(slowMotion = it.slowMotion.select(1.0)) }
            assertEquals(1.0, AnimationTimeScale.shared.scale, 0.0)
            store.send(TuningFeature.Action.SlowMotionToggled) { it.copy(slowMotion = it.slowMotion.select(0.1)) }
            assertEquals(listOf(Haptic.selection, Haptic.selection, Haptic.selection, Haptic.selection), synchronized(played) { played.toList() })
            store.send(TuningFeature.Action.TurnOffTapped) { it.copy(slowMotion = it.slowMotion.select(1.0)) }
            assertEquals(false, shared.reference(ai.opencode.core.tuning.TuningSharedKeys.tuningEnabled).value)
            assertEquals(1.0, AnimationTimeScale.shared.scale, 0.0)
            store.finish()
        } finally {
            AnimationTimeScale.shared.reset()
            job.cancel()
        }
    }
}
