package ai.opencode.core.tuning

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/*
 * The TuningStore, Tunable, TuningContext and AnimationTimeScale tests. `withObservationTracking` →
 * Compose snapshot read/apply observers ([changes]); `store.events()` → the `events` SharedFlow.
 *
 * Deleted: the `CALayer.speed`/`convertTime` assertions and `referenceNow` (`Date` clock) of the
 * AnimationTimeScale tests (Android has one monotonic clock and no layer speed; per-frame `sample`
 * is [AnimationTimeScale.scaledDelta]), and `InternalBuild.isDebug` being true (a JVM unit test has
 * no debuggable app context, so it's not a debug build).
 */

private enum class EdgeStyle(override val rawValue: String) : TunableChoice {
    Soft("soft"),
    Hard("hard"),
}

private object Knobs {
    val fade = Tunable("drawer.edge.fade", "Fade", default = 28.dp, range = 0.0..80.0, step = 1.0)
    val wash = Tunable("drawer.edge.wash", "Wash", default = 0.7, range = 0.0..1.0)
    val rows = Tunable("drawer.list.rows", "Rows", default = 4, range = 1..10)
    val hairline = Tunable("drawer.edge.hairline", "Hairline", default = false)
    val style = Tunable("drawer.edge.style", "Style", default = EdgeStyle.Soft)
    val morph = TunableSpring("drawer.search.morph", "Search morph", duration = 0.3, bounce = 0.15)
}

private fun <T> Tunable<T>.read(store: TuningStore): T = store.value(this)

/** A `UserDefaults` suite for [TuningStore]: kept in memory, shared by every store built on it. */
private class MemoryPersistence : TuningPersistence {
    private var overrides: String? = null
    private var hidden: Set<String> = emptySet()
    override fun loadOverrides(): String? = overrides
    override fun saveOverrides(json: String?) { overrides = json }
    override fun loadHiddenGroups(): Set<String> = hidden
    override fun saveHiddenGroups(groups: Set<String>) { hidden = groups }
}

/**
 * How many times [mutate] changed state that [read] observed (Compose snapshot reads, as a
 * composable would).
 */
private fun changes(read: () -> Unit, mutate: () -> Unit): Int {
    val observed = HashSet<Any>()
    val snapshot = Snapshot.takeMutableSnapshot(readObserver = { observed += it })
    try {
        snapshot.enter(read)
        snapshot.apply().check()
    } finally {
        snapshot.dispose()
    }
    Snapshot.sendApplyNotifications()
    var count = 0
    val handle = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in observed }) count++ }
    try {
        mutate()
        Snapshot.sendApplyNotifications()
    } finally {
        handle.dispose()
    }
    return count
}

class TuningStoreTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun offReadsEveryDefaultAndRegistersNothing() {
        val store = TuningStore(null)
        store.set("drawer.edge.fade", TuneValue.Number(50.0))
        assertEquals(28.dp, Knobs.fade.read(store))
        assertTrue(store.descriptors.isEmpty())
    }

    @Test
    fun onReadsOverridesAndRegistersWhatWasRead() {
        val store = TuningStore(null)
        store.setEnabled(true)
        // Set before it registered (from the Mac): kept, applied once read.
        store.set("drawer.edge.fade", TuneValue.Number(50.0))
        assertEquals(50.dp, Knobs.fade.read(store))
        assertEquals(EdgeStyle.Soft, Knobs.style.read(store))
        assertEquals(listOf("drawer.edge.fade", "drawer.edge.style"), store.descriptors.map { it.key })
        assertEquals(listOf("drawer", "drawer"), store.descriptors.map { it.group })
        assertEquals("drawer", store.lastReadGroup)
    }

    @Test
    fun valuesAreMadeValidForTheirTunable() {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.register(Knobs.fade)
        store.register(Knobs.rows)
        store.register(Knobs.style)

        assertEquals(TuningStore.SetResult.Applied(TuneValue.Number(80.0)), store.set("drawer.edge.fade", TuneValue.Number(500.0)))
        assertEquals(TuningStore.SetResult.Applied(TuneValue.Number(0.0)), store.set("drawer.edge.fade", TuneValue.Number(-3.0)))
        assertEquals(TuningStore.SetResult.Refused, store.set("drawer.edge.fade", TuneValue.Bool(true)))
        assertEquals(TuningStore.SetResult.Applied(TuneValue.Number(7.0)), store.set("drawer.list.rows", TuneValue.Number(6.6)))
        assertEquals(TuningStore.SetResult.Refused, store.set("drawer.edge.style", TuneValue.Choice("glass")))
        assertEquals(TuningStore.SetResult.Applied(TuneValue.Choice("hard")), store.set("drawer.edge.style", TuneValue.Choice("hard")))
        assertEquals(EdgeStyle.Hard, Knobs.style.read(store))
        // The default itself isn't an override.
        assertEquals(TuningStore.SetResult.Applied(null), store.set("drawer.edge.fade", TuneValue.Number(28.0)))
        assertNull(store.overrides["drawer.edge.fade"])
    }

    @Test
    fun resetAllTakesOneGroupOrEverything() {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.set("drawer.edge.fade", TuneValue.Number(40.0))
        store.set("send.flight.duration", TuneValue.Number(0.5))
        store.resetAll(group = "drawer")
        assertEquals(mapOf("send.flight.duration" to TuneValue.Number(0.5)), store.overrides)
        store.resetAll()
        assertTrue(store.overrides.isEmpty())
    }

    @Test
    fun aPresetCommitsValidValuesAndResetsInOneRevisionAndEcho() = runTest {
        val defaults = MemoryPersistence()
        val store = TuningStore(defaults)
        store.setEnabled(true)
        store.register(Knobs.fade)
        store.register(Knobs.wash)
        store.register(Knobs.style)
        store.set("drawer.edge.wash", TuneValue.Number(0.4))
        val events = mutableListOf<TuningEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { events += it } }
        val revision = store.revision
        store.set(mapOf("drawer.edge.fade" to TuneValue.Number(500.0), "drawer.edge.wash" to null, "drawer.edge.style" to TuneValue.Number(1.0)))
        assertEquals(revision + 1, store.revision)
        assertEquals(mapOf("drawer.edge.fade" to TuneValue.Number(80.0)), store.overrides)
        assertEquals(store.overrides, TuningStore(defaults).overrides)
        assertEquals(TuningEvent.Values(mapOf("drawer.edge.fade" to TuneValue.Number(80.0), "drawer.edge.wash" to null)), events.removeAt(0))
        store.set(mapOf("drawer.edge.fade" to TuneValue.Number(500.0), "drawer.edge.wash" to null))
        assertEquals("An unchanged preset neither redraws nor echoes", revision + 1, store.revision)
        store.resetAll()
        assertEquals(revision + 2, store.revision)
        assertEquals(TuningEvent.Values(mapOf("drawer.edge.fade" to null)), events.removeAt(0))
    }

    @Test
    fun overridesPersistAndAStaleOneIsMadeValid() {
        val defaults = MemoryPersistence()

        val first = TuningStore(defaults)
        first.setEnabled(true)
        first.set("drawer.edge.fade", TuneValue.Number(120.0))
        first.set("drawer.edge.style", TuneValue.Number(3.0))

        val second = TuningStore(defaults)
        assertEquals(mapOf("drawer.edge.fade" to TuneValue.Number(120.0), "drawer.edge.style" to TuneValue.Number(3.0)), second.overrides)
        second.setEnabled(true)
        assertEquals("Clamped to the range once it registers", 80.dp, Knobs.fade.read(second))
        assertEquals("The wrong kind is dropped", EdgeStyle.Soft, Knobs.style.read(second))
        assertEquals(mapOf("drawer.edge.fade" to TuneValue.Number(80.0)), second.overrides)
        assertEquals("What was made valid is saved", mapOf("drawer.edge.fade" to TuneValue.Number(80.0)), TuningStore(defaults).overrides)
    }

    @Test
    fun aDefaultReachedBySliderStepsIsNoOverride() {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.register(Knobs.morph)
        assertEquals(TuningStore.SetResult.Applied(null), store.set("drawer.search.morph.duration", TuneValue.Number(0.05 + 25 * 0.01)))
        assertEquals(TuningStore.SetResult.Applied(null), store.set("drawer.search.morph.duration", TuneValue.Number(0.1 + 0.2)))
        assertTrue(store.overrides.isEmpty())
    }

    @Test
    fun aSavedOverrideMadeValidIsAnnounced() = runTest {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.set("drawer.edge.fade", TuneValue.Number(200.0))
        val received = mutableListOf<TuningEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { received += it } }
        Knobs.fade.read(store)
        assertEquals(
            listOf(TuningEvent.Registered(Knobs.fade.descriptor), TuningEvent.Changed("drawer.edge.fade", TuneValue.Number(80.0))),
            received.take(2),
        )
    }

    @Test
    fun changesAndRegistrationsAreStreamed() = runTest {
        val store = TuningStore(null)
        store.setEnabled(true)
        val received = mutableListOf<TuningEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { received += it } }
        Knobs.wash.read(store)
        store.set("drawer.edge.wash", TuneValue.Number(0.4))
        store.set("drawer.edge.wash", TuneValue.Number(0.4))
        store.reset("drawer.edge.wash")
        assertEquals(
            listOf(
                TuningEvent.Registered(Knobs.wash.descriptor),
                TuningEvent.Changed("drawer.edge.wash", TuneValue.Number(0.4)),
                TuningEvent.Changed("drawer.edge.wash", null),
            ),
            received.take(3),
        )
    }

    /** Android copies Kotlin declarations. */
    @Test
    fun copyAsSwiftWritesTheTunedDeclarations() {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.register(Knobs.fade)
        store.register(Knobs.wash)
        store.register(Knobs.hairline)
        store.register(Knobs.style)
        store.register(Knobs.morph)
        assertTrue(store.kotlinSnippet().isEmpty())

        store.set("drawer.edge.fade", TuneValue.Number(36.0))
        store.set("drawer.edge.wash", TuneValue.Number(0.55))
        store.set("drawer.edge.hairline", TuneValue.Bool(true))
        store.set("drawer.edge.style", TuneValue.Choice("hard"))
        store.set("drawer.search.morph.bounce", TuneValue.Number(0.2))
        assertEquals(
            """
            Tunable("drawer.edge.fade", "Fade", default = 36.0.dp, range = 0.0..80.0, step = 1.0)  // was 28.0.dp
            Tunable("drawer.edge.wash", "Wash", default = 0.55, range = 0.0..1.0)  // was 0.7
            Tunable("drawer.edge.hairline", "Hairline", default = true)  // was false
            Tunable("drawer.edge.style", "Style", default = EdgeStyle.Hard)  // was EdgeStyle.Soft
            TunableSpring("drawer.search.morph", "Search morph", duration = 0.3, bounce = 0.2)  // was duration = 0.3, bounce = 0.15
            """.trimIndent(),
            store.kotlinSnippet(),
        )
        assertTrue(store.kotlinSnippet(group = "send").isEmpty())
    }

    @Test
    fun copyValuesWritesCompactGroupAndBuildBlock() {
        val store = TuningStore(null, appBuild = { "0.2.0 (1) · 709091b6" })
        store.setEnabled(true)
        store.register(Knobs.fade)
        store.register(Knobs.wash)
        store.register(Knobs.hairline)
        store.register(Knobs.style)
        val sessionRadius = Tunable("session.header.blurRadius", "Radius", default = 8.dp, range = 0.0..24.0, step = 0.5)
        store.register(sessionRadius)
        assertTrue(store.valuesSnippet().isEmpty())

        store.set("drawer.edge.fade", TuneValue.Number(36.0))
        store.set("drawer.edge.wash", TuneValue.Number(0.55))
        store.set("drawer.edge.hairline", TuneValue.Bool(true))
        store.set("drawer.edge.style", TuneValue.Choice("hard"))
        store.set("session.header.blurRadius", TuneValue.Number(14.5))

        assertEquals(
            """
            # Tuning · Drawer (0.2.0 (1) · 709091b6)
            drawer.edge.fade = 36
            drawer.edge.wash = 0.55
            drawer.edge.hairline = true
            drawer.edge.style = hard
            """.trimIndent(),
            store.valuesSnippet(group = "drawer"),
        )
        assertEquals(
            """
            # Tuning · Drawer, Session (0.2.0 (1) · 709091b6)
            drawer.edge.fade = 36
            drawer.edge.wash = 0.55
            drawer.edge.hairline = true
            drawer.edge.style = hard
            session.header.blurRadius = 14.5
            """.trimIndent(),
            store.valuesSnippet(),
        )
        assertTrue(store.valuesSnippet(group = "catchUp").isEmpty())
    }

    @Test
    fun randomizeStaysInRangeSnapsStepsAndUndoesLastRoll() {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.register(Knobs.fade)
        store.register(Knobs.wash)
        store.register(Knobs.rows)
        store.register(Knobs.hairline)
        store.register(Knobs.style)
        store.register(Knobs.morph)
        val untouched = Tunable("session.header.blurRadius", "Radius", default = 8.dp, range = 0.0..24.0, step = 0.5)
        store.register(untouched)
        store.set("session.header.blurRadius", TuneValue.Number(12.0))
        store.set("drawer.edge.fade", TuneValue.Number(42.0))

        assertFalse(store.canUndoRoll("drawer"))
        val rng = Random(0xC0FFEE)

        repeat(40) {
            store.randomize("drawer", rng)
            assertTrue(store.canUndoRoll("drawer"))
            assertFalse(store.canUndoRoll("session"))
            assertEquals("Other groups are untouched", 12.dp, untouched.read(store))

            val fade = Knobs.fade.read(store).value
            assertTrue("Step-snapped in 0...80: $fade", fade in 0f..80f && fade == Math.round(fade).toFloat())
            val wash = Knobs.wash.read(store)
            assertTrue("In 0...1: $wash", wash in 0.0..1.0)
            val rows = Knobs.rows.read(store)
            assertTrue("Int in 1...10: $rows", rows in 1..10)
            val duration = Knobs.morph.duration.read(store)
            assertTrue(duration in 0.05..2.0)
            assertTrue("Step 0.01: $duration", abs((duration * 100).roundToLong() - duration * 100) < 1e-6)
            val bounce = Knobs.morph.bounce.read(store)
            assertTrue(bounce in -0.5..0.9)
            assertTrue("Step 0.01: $bounce", abs((bounce * 100).roundToLong() - bounce * 100) < 1e-6)
            assertTrue(Knobs.style.read(store) in listOf(EdgeStyle.Soft, EdgeStyle.Hard))
        }

        // Undo restores the state right before the last roll; rolling once from a
        // known state and undoing brings that exact state back.
        store.resetAll(group = "drawer")
        assertFalse(store.canUndoRoll("drawer"))
        store.set("drawer.edge.fade", TuneValue.Number(42.0))
        store.set("drawer.edge.style", TuneValue.Choice("hard"))
        val beforeOverrides = store.overrides

        store.randomize("drawer", rng)
        assertTrue(store.canUndoRoll("drawer"))
        assertNotEquals(beforeOverrides, store.overrides)

        store.undoRoll("drawer")
        assertFalse(store.canUndoRoll("drawer"))
        assertEquals(beforeOverrides, store.overrides)
        assertEquals(42.dp, Knobs.fade.read(store))
        assertEquals(EdgeStyle.Hard, Knobs.style.read(store))

        // Reset still restores defaults and clears undo.
        store.randomize("drawer", rng)
        assertTrue(store.canUndoRoll("drawer"))
        store.resetAll(group = "drawer")
        assertFalse(store.canUndoRoll("drawer"))
        assertEquals(mapOf("session.header.blurRadius" to TuneValue.Number(12.0)), store.overrides)

        // Edge cases where step exceeds the range stay snapped to the lower bound.
        val narrow = TunableDescriptor(
            key = "test.narrow", label = "Narrow", kind = TuneKind.Number, valueType = "Double",
            defaultValue = TuneValue.Number(0.0), range = 0.0..0.2, step = 1.0, choices = null,
        )
        assertFalse(narrow.hasMultipleValues)
        repeat(10) {
            assertEquals(TuneValue.Number(0.0), narrow.randomValue(rng))
            assertEquals(TuneValue.Number(0.0), narrow.alternateValue(TuneValue.Number(0.0), rng))
        }
        val narrowInt = TunableDescriptor(
            key = "test.narrowInt", label = "Narrow Int", kind = TuneKind.Number, valueType = "Int",
            defaultValue = TuneValue.Number(0.0), range = 0.0..1.0, step = 2.0, choices = null,
        )
        assertFalse(narrowInt.hasMultipleValues)
        repeat(10) {
            assertEquals(TuneValue.Number(0.0), narrowInt.randomValue(rng))
            assertEquals(TuneValue.Number(0.0), narrowInt.alternateValue(TuneValue.Number(0.0), rng))
        }
    }

    private data class ObserveScenario(val initiallyEnabled: Boolean, val initialValue: TuneValue?, val mutateTo: TuneValue?, val expectedChanges: Int)

    @Test
    fun readersObserveChanges() {
        val scenarios = listOf(
            ObserveScenario(initiallyEnabled = true, initialValue = null, mutateTo = TuneValue.Number(40.0), expectedChanges = 1),
            ObserveScenario(initiallyEnabled = false, initialValue = null, mutateTo = null, expectedChanges = 1),
            ObserveScenario(initiallyEnabled = true, initialValue = TuneValue.Number(40.0), mutateTo = TuneValue.Number(40.0), expectedChanges = 0),
        )
        for (scenario in scenarios) {
            val store = TuningStore(null)
            if (scenario.initiallyEnabled) store.setEnabled(true)
            scenario.initialValue?.let { store.set("drawer.edge.fade", it) }
            val changed = changes(read = { Knobs.fade.read(store) }) {
                val mutateTo = scenario.mutateTo
                if (mutateTo != null) store.set("drawer.edge.fade", mutateTo) else store.setEnabled(true)
            }
            assertEquals("$scenario", scenario.expectedChanges, changed)
        }
    }

    @Test
    fun springsReadBothHalves() {
        val store = TuningStore(null)
        store.setEnabled(true)
        store.set("drawer.search.morph.duration", TuneValue.Number(0.5))
        assertEquals(0.5, Knobs.morph.duration.read(store), 0.0)
        assertEquals(0.15, Knobs.morph.bounce.read(store), 0.0)
    }
}

class TuningSummonTests {
    @get:Rule val isolation = TestIsolationRule()

    /**
     * Under the release gate (here: a JVM test, which isn't a debug build) Developer Mode alone
     * opens tuning.
     */
    @Test
    fun onlyDebugBuildsOrDeveloperModeGetTuning() {
        assertEquals((InternalBuild.isDebug && !TuningAccess.actsAsRelease) || DeveloperMode.isEnabled, TuningAccess.isAvailable)
        val developerMode = shared(SharedKeys.developerMode)
        developerMode.set(true)
        try {
            assertTrue(TuningAccess.isAvailable)
        } finally {
            developerMode.set(false)
        }
        assertEquals(InternalBuild.isDebug, TuningAccess.isAvailable)
    }
}

class TuningPanelGroupTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun contextsAreDeclaredAnywhere() {
        val voice = TuningContext("voice", groups = listOf("voice", "scope"))
        assertEquals(listOf("catchUp"), TuningContext("catchUp").groups)
        assertEquals(listOf("voice", "scope"), voice.groups)
    }

    @Test
    fun settledGroupsPersist() {
        val defaults = MemoryPersistence()
        val first = TuningStore(defaults)
        val changed = changes(read = { first.hiddenGroups }) {
            first.setHidden("drawer", true)
            first.setHidden("drawer", true)
        }
        assertEquals(1, changed)
        assertEquals(setOf("drawer"), TuningStore(defaults).hiddenGroups)
        first.setHidden("drawer", false)
        assertTrue(TuningStore(defaults).hiddenGroups.isEmpty())
    }

    @Test
    fun titlesAndSectionsReadAsWords() {
        assertEquals("Catch Up", TuningTitle.format("catchUp"))
        assertEquals("Drawer", TuningTitle.format("drawer"))
        assertEquals(listOf("edge", "search", "list"), listOf(Knobs.fade.descriptor, Knobs.morph.duration.descriptor, Knobs.rows.descriptor).map { it.section })
    }

    @Test
    fun theStoreKnowsTheScreen() {
        val store = TuningStore(null)
        assertEquals(TuningContext.session, store.context)
        val changed = changes(read = { store.context }) {
            store.setContext(TuningContext.drawer)
            store.setContext(TuningContext.drawer)
        }
        assertEquals(TuningContext.drawer, store.context)
        assertEquals(1, changed)
    }

    @Test
    fun playgroundContextPinsAndRestoresTheUnderlyingScreenByOwnerToken() {
        val store = TuningStore(null)
        store.setContext(TuningContext.settings)
        assertEquals(TuningContext.settings, store.context)

        val firstToken = UUID.randomUUID()
        val secondToken = UUID.randomUUID()
        store.setPlaygroundContext(TuningContext.transcriptPlayground, firstToken)
        assertEquals(TuningContext.transcriptPlayground, store.context)
        assertEquals("Transcript", store.context.displayTitle)

        // An app screen changing underneath (or a background view reporting) updates
        // appContext without displacing the active playground.
        store.setContext(TuningContext.drawer)
        assertEquals(TuningContext.transcriptPlayground, store.context)

        // Switching playgrounds hands ownership to the new token; the old token's
        // late teardown cannot clear the new playground's context.
        store.setPlaygroundContext(TuningContext.sessionListPlayground, secondToken)
        assertEquals(TuningContext.sessionListPlayground, store.context)
        assertEquals("Session List", store.context.displayTitle)
        store.clearPlaygroundContext(firstToken)
        assertEquals(TuningContext.sessionListPlayground, store.context)

        // Releasing the active token restores the underlying app context.
        store.clearPlaygroundContext(secondToken)
        assertEquals(TuningContext.drawer, store.context)
    }
}

class AnimationTimeScaleTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun defaultIsOneAndFastPathUntilScaled() {
        val clock = AnimationTimeScale()
        assertFalse(clock.isActive)
        assertFalse(clock.isScaled)
        assertEquals(1.0, clock.scale, 0.0)
        assertEquals(0.016, clock.scaledDelta(0.016), 0.0)
        assertEquals(1_000.0, clock.now(1_000.0), 0.0)
    }

    @Test
    fun virtualClockAndCALayerStayContinuousAndSynchronizedAcrossScaleChanges() {
        val clock = AnimationTimeScale()
        var realTime = 100.0
        val transitions = listOf(
            1.0 to 1.0,
            0.5 to 2.0, // +1.0 virtual s
            0.25 to 4.0, // +1.0 virtual s
            0.1 to 5.0, // +0.5 virtual s
            1.0 to 2.0, // +2.0 virtual s (must not jump forward on return to 1x!)
        )

        var expectedVirtual = realTime

        for ((targetScale, advance) in transitions) {
            val beforeSwitch = clock.now(realTime)
            clock.setScale(targetScale, realTime, bypassAccessCheck = true)
            val afterSwitch = clock.now(realTime)
            assertTrue("Virtual clock must not jump when switching to ${targetScale}x", abs(afterSwitch - beforeSwitch) < 1e-6)

            // Step frame by frame at 60 Hz across `advance` real seconds.
            val steps = Math.round(advance * 60).toInt()
            repeat(steps) {
                assertTrue(abs(clock.scaledDelta(1.0 / 60.0) - (1.0 / 60.0) * targetScale) < 1e-6)
                realTime += 1.0 / 60.0
            }
            expectedVirtual += advance * targetScale

            assertTrue(abs(clock.now(realTime) - expectedVirtual) < 1e-4)
        }
    }

    @Test
    fun labTimeScaleParsesArgumentsAndScalesVirtualWallDurations() {
        assertEquals(0.25, AnimationTimeScale.requestedLabScale("0.25", environment = null))
        assertEquals(0.1, AnimationTimeScale.requestedLabScale(null, environment = "0.1"))
        assertEquals(0.05, AnimationTimeScale.requestedLabScale("0.01", environment = null))
        assertNull(AnimationTimeScale.requestedLabScale(null, environment = null))

        val clock = AnimationTimeScale()
        assertEquals(0.20, clock.wallSeconds(0.20), 0.0)
        clock.setScale(0.25, realSeconds = 10.0, bypassAccessCheck = true)
        assertTrue(abs(clock.wallSeconds(0.20) - 0.80) < 1e-6)
        clock.setScale(0.1, realSeconds = 14.0, bypassAccessCheck = true)
        assertTrue(abs(clock.wallSeconds(0.075) - 0.75) < 1e-6)
    }
}
