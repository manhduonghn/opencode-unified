package ai.opencode.core.sound

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SoundEffectsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun soundEffectsAreOffByDefault() {
        DependencyValues.withValues(DependencyValues.testing()) {
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.soundEffects))
            assertFalse(shared(SharedKeys.soundEffectsSend).value)
            assertEquals(1.0, shared(SharedKeys.soundEffectsVolume).value, 0.0)
        }
    }

    // The live gate

    private class Output : SoundEffectOutput {
        val played = mutableListOf<String>()
        override fun prepare() {}
        override fun play(effect: SoundEffect, volume: Float) {
            played.add("${effect.rawValue}@$volume")
        }
    }

    private class Moment {
        var volume = 1.0
        var now: Duration = 1_000_000.milliseconds
    }

    private fun live(moment: Moment, output: Output) =
        LiveSoundEffects(now = { moment.now }, volume = { moment.volume }, output = output)

    @Test
    fun theLiveGateStaysSilentAtZeroVolume() {
        val moment = Moment()
        val output = Output()
        val sounds = live(moment, output)

        moment.volume = 0.0
        assertFalse("at zero volume", sounds.play(SoundEffect.needsInput))
        assertTrue(output.played.isEmpty())

        moment.volume = 0.5
        assertTrue(sounds.play(SoundEffect.needsInput))
        assertEquals(listOf("needsInput@0.5"), output.played)
    }

    /** One sound per burst: inside a second only a more urgent one plays. */
    @Test
    fun burstsPlayOneSoundUnlessAMoreUrgentOneArrives() {
        val moment = Moment()
        val output = Output()
        val sounds = live(moment, output)

        assertTrue(sounds.play(SoundEffect.replyDone))
        moment.now += 200.milliseconds
        assertFalse("two replies in a burst play once", sounds.play(SoundEffect.replyDone))
        moment.now += 100.milliseconds
        assertTrue("a request outranks a reply", sounds.play(SoundEffect.needsInput))
        moment.now += 200.milliseconds
        assertFalse("an error doesn't outrank a request", sounds.play(SoundEffect.error))
        assertFalse(sounds.play(SoundEffect.needsInput))
        moment.now += 900.milliseconds
        assertTrue("a second after the last sound, a reply plays again", sounds.play(SoundEffect.replyDone))
        assertEquals(listOf("replyDone@1.0", "needsInput@1.0", "replyDone@1.0"), output.played)
    }

    @Test
    fun priorityRunsFromSendToNeedsInput() {
        val order = SoundEffect.entries.sortedBy { it.priority }
        assertEquals(
            listOf(SoundEffect.send, SoundEffect.replyDone, SoundEffect.catchUpDone, SoundEffect.error, SoundEffect.needsInput),
            order,
        )
    }
}

/**
 * The session side of Sound Effects, never activated by a preload, borrowed only while idle, given
 * back as a sound ends, and given up at once when anything else needs the audio. Route reasons
 * `oldDeviceUnavailable`/`newDeviceAvailable` are a device removed/added,
 * `categoryChange`/`routeConfigurationChange` our own configuration.
 */
class SoundEffectsPlaybackTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Owner : AudioSessionIdleState {
        private val lock = Any()
        private val active = mutableSetOf<Int>()
        private var nextID = 0
        private var borrowerStop: (() -> Unit)? = null

        override fun <T> withIdleState(body: (isIdle: Boolean) -> T): T = synchronized(lock) { body(active.isEmpty()) }
        override fun onHolderActivating(stop: () -> Unit) = synchronized(lock) { borrowerStop = stop }

        inner class Holder(private val id: Int, private val deactivate: () -> Unit) {
            fun activate(body: () -> Unit) = synchronized(lock) {
                borrowerStop?.invoke()
                active.add(id)
                body()
            }

            fun release() = synchronized(lock) {
                if (active.remove(id) && active.isEmpty()) deactivate()
            }
        }

        fun holder(deactivate: () -> Unit) = synchronized(lock) { Holder(++nextID, deactivate) }
    }

    private class Audio(val log: MutableList<String>) : SoundEffectsAudio {
        val finishes = mutableListOf<() -> Unit>()
        private var loaded = false

        override fun load() {
            if (loaded) return
            loaded = true
            log.add("load")
        }
        override fun activate() { log.add("activate") }
        override fun deactivate() { log.add("deactivate") }
        override fun start(effect: SoundEffect, volume: Float, finished: () -> Unit): Boolean {
            log.add("start ${effect.rawValue}@$volume")
            finishes.add(finished)
            return true
        }
        override fun stop() { log.add("stop") }

        /** The `n`th sound started plays out. */
        fun finish(n: Int) = finishes[n]()
    }

    private class Rig {
        val log = mutableListOf<String>()
        val owner = Owner()
        val center = SoundEffectsEventCenter()
        val audio = Audio(log)
        val playback = SoundEffectsPlayback(owner, audio, center, appIsActive = true) { it() }

        fun holder() = owner.holder { log.add("holder.deactivate") }
        fun post(event: SoundEffectsEvent) = center.post(event)
    }

    @Test
    fun preloadingNeverTouchesTheSession() {
        val rig = Rig()
        rig.playback.prepare()
        assertEquals(listOf("load"), rig.log)
    }

    @Test
    fun aPermittedSoundActivatesAmbientThenGivesTheSessionBack() {
        val rig = Rig()
        rig.playback.play(SoundEffect.replyDone, 0.5f)
        assertEquals(listOf("load", "activate", "start replyDone@0.5"), rig.log)
        assertTrue(rig.playback.isPlaying)
        rig.audio.finish(0)
        assertEquals(listOf("load", "activate", "start replyDone@0.5", "deactivate"), rig.log)
        assertFalse(rig.playback.isPlaying)
    }

    @Test
    fun aHeldSessionIsNeverActivated() {
        val rig = Rig()
        val holder = rig.holder()
        holder.activate { rig.log.add("holder.activate") }
        rig.playback.play(SoundEffect.needsInput, 1f)
        assertEquals(listOf("holder.activate", "load"), rig.log)
        holder.release()
        assertEquals(listOf("holder.activate", "load", "holder.deactivate"), rig.log)
    }

    /**
     * A call, dictation or media stops the sound before it touches the session, and keeps the
     * session: no deactivation under it.
     */
    @Test
    fun aHolderActivatingStopsTheSoundFirst() {
        val rig = Rig()
        rig.playback.play(SoundEffect.catchUpDone, 1f)
        val holder = rig.holder()
        holder.activate { rig.log.add("holder.activate") }
        assertEquals(listOf("load", "activate", "start catchUpDone@1.0", "stop", "holder.activate"), rig.log)
        assertFalse(rig.playback.isPlaying)
        rig.audio.finish(0)
        assertEquals("a late finish leaves the holder's session alone", "holder.activate", rig.log.last())
        holder.release()
    }

    @Test
    fun leavingOrARouteChangeStopsAndGivesTheSessionBack() {
        val events = listOf(
            SoundEffectsEvent.EnteredBackground,
            SoundEffectsEvent.RouteChange(), // oldDeviceUnavailable
            SoundEffectsEvent.RouteChange(), // newDeviceAvailable
        )
        for (event in events) {
            val rig = Rig()
            rig.playback.play(SoundEffect.replyDone, 1f)
            rig.post(event)
            assertEquals(listOf("load", "activate", "start replyDone@1.0", "stop", "deactivate"), rig.log)
            assertFalse(rig.playback.isPlaying)
            rig.audio.finish(0)
            assertEquals("no second deactivation", 5, rig.log.size)
        }
    }

    /** An interruption has already taken the session: stop, nothing to give back. */
    @Test
    fun anInterruptionStopsWithoutDeactivating() {
        val rig = Rig()
        rig.playback.play(SoundEffect.error, 1f)
        rig.post(SoundEffectsEvent.Interruption(began = true))
        assertEquals(listOf("load", "activate", "start error@1.0", "stop"), rig.log)
        assertFalse(rig.playback.isPlaying)
    }

    /**
     * Activating for a sound posts a category change; it and an interruption ending leave the
     * sound playing.
     */
    @Test
    fun ourOwnCategoryChangeKeepsPlaying() {
        val rig = Rig()
        rig.playback.play(SoundEffect.replyDone, 1f)
        rig.post(SoundEffectsEvent.RouteChange(ownConfiguration = true)) // categoryChange
        rig.post(SoundEffectsEvent.RouteChange(ownConfiguration = true)) // routeConfigurationChange
        rig.post(SoundEffectsEvent.Interruption(began = false))
        assertEquals(listOf("load", "activate", "start replyDone@1.0"), rig.log)
        assertTrue(rig.playback.isPlaying)
    }

    /**
     * Leaving lands between `play` and its queued work: the work rechecks and never activates,
     * so nothing plays in the background.
     */
    @Test
    fun leavingBeforeTheQueuedPlayRunsKeepsItSilent() {
        val log = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val center = SoundEffectsEventCenter()
        val playback = SoundEffectsPlayback(Owner(), Audio(log), center, appIsActive = true) { queued.add(it) }
        val runQueued = {
            val work = queued.toList()
            queued.clear()
            work.forEach { it() }
        }

        playback.play(SoundEffect.needsInput, 1f)
        center.post(SoundEffectsEvent.ResignedActive)
        center.post(SoundEffectsEvent.EnteredBackground)
        runQueued()
        assertEquals(listOf("load"), log)
        assertFalse(playback.isPlaying)

        // Back in front, sounds play again.
        center.post(SoundEffectsEvent.BecameActive)
        playback.play(SoundEffect.needsInput, 1f)
        runQueued()
        assertEquals(listOf("load", "activate", "start needsInput@1.0"), log)
    }

    /** Inactive (Control Center, a system alert) holds new sounds too. */
    @Test
    fun anInactiveAppPlaysNothing() {
        val log = mutableListOf<String>()
        val playback = SoundEffectsPlayback(Owner(), Audio(log), SoundEffectsEventCenter(), appIsActive = false) { it() }
        playback.play(SoundEffect.replyDone, 1f)
        assertEquals(listOf("load"), log)
    }

    @Test
    fun nothingPlayingMeansNothingToStop() {
        val rig = Rig()
        rig.post(SoundEffectsEvent.EnteredBackground)
        assertTrue(rig.log.isEmpty())
    }

    /**
     * A more urgent sound inside a burst replaces the first; only the last one's end gives the
     * session back.
     */
    @Test
    fun aSecondSoundReplacesTheFirst() {
        val rig = Rig()
        rig.playback.play(SoundEffect.replyDone, 1f)
        rig.playback.play(SoundEffect.needsInput, 1f)
        assertEquals(
            listOf("load", "activate", "start replyDone@1.0", "stop", "activate", "start needsInput@1.0"),
            rig.log,
        )
        rig.audio.finish(0)
        assertEquals("the replaced sound's end changes nothing", "start needsInput@1.0", rig.log.last())
        rig.audio.finish(1)
        assertEquals("deactivate", rig.log.last())
        assertFalse(rig.playback.isPlaying)
    }
}
