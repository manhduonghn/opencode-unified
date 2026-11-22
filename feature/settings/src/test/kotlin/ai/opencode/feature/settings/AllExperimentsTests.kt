package ai.opencode.feature.settings

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalGroup
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.AllExperiments
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.adoptNewExperimentsIntoAll
import ai.opencode.core.sharing.allExperimentsSaved
import ai.opencode.core.sharing.allExperimentsSkips
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.joiningAll
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings › Experimental › All Experiments. */
class AllExperimentsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://example.test", username = "", password = "")

    private fun TestScope.store(
        shared: SettingsTestShared,
        log: Locked<List<String>>,
    ): TestStore<HomeSettingsFeature.State, HomeSettingsFeature.Action> {
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[HapticsClient] = HapticsRecorder().client
            this[SoundEffectsClient] = SoundEffectsClient(
                play = { effect -> log.update { it + "sound.${effect.rawValue}" } },
                prepare = { log.update { it + "sound.prepare" } },
            )
        }
        store.exhaustive = false
        return store
    }

    private fun SettingsTestShared.on(feature: ExperimentalFeature): Boolean = get(experimental(feature))

    private val listed: List<ExperimentalFeature>
        get() {
            val result = mutableListOf<ExperimentalFeature>()
            for (group in ExperimentalGroup.entries) result.addAll(group.features)
            return result
        }

    /** On turns on every experiment in the pane, except the ones that ask for a permission or share data, and runs what each switch does. */
    @Test fun onTurnsOnEveryExperimentThatJoinsAll() = runTest {
        val shared = SettingsTestShared()
        shared.set(allExperimentsSkips(ExperimentalFeature.inboxSidebar), true)
        val log = Locked(listOf<String>())
        val store = store(shared, log)
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(true))
        store.finish()
        val left = setOf(ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.shareScreenWithAgents, ExperimentalFeature.inboxSidebar)
        for (feature in listed) {
            assertEquals("$feature", feature !in left, shared.on(feature))
        }
        assertFalse(ExperimentalFeature.backgroundCallIntent.joinsAll)
        assertFalse(ExperimentalFeature.shareScreenWithAgents.joinsAll)
        assertFalse("switches outside the pane aren't in it", ExperimentalFeature.welcomePreview.joinsAll)
        assertFalse(shared.on(ExperimentalFeature.welcomePreview))
        assertEquals("Sound Effects preloads as All turns it on", listOf("sound.prepare"), log.value)
        assertTrue(shared.get(AllExperiments.key))
    }

    /** While it's on, one experiment can still be switched off, and stays off the next time All turns on. */
    @Test fun aSwitchTurnedOffWhileAllIsOnIsSkipped() = runTest {
        val shared = SettingsTestShared()
        val store = store(shared, Locked(listOf()))
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(true))
        store.finish()
        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.inboxSidebar, false))
        assertFalse(shared.on(ExperimentalFeature.inboxSidebar))
        assertTrue(shared.get(allExperimentsSkips(ExperimentalFeature.inboxSidebar)))
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(false))
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(true))
        store.finish()
        assertFalse("skipped stays off", shared.on(ExperimentalFeature.inboxSidebar))
        assertTrue(shared.on(ExperimentalFeature.sessionLiveActivity))
        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.inboxSidebar, true))
        assertFalse("switching it back on unskips it", shared.get(allExperimentsSkips(ExperimentalFeature.inboxSidebar)))
        store.finish()
    }

    /** Off puts back each switch as it was before, and runs what the switches that change do. */
    @Test fun offRestoresEachEarlierSwitch() = runTest {
        val shared = SettingsTestShared()
        shared.set(experimental(ExperimentalFeature.hideModels), true)
        shared.set(experimental(ExperimentalFeature.soundEffects), true)
        shared.set(experimental(ExperimentalFeature.backgroundCallIntent), true)
        val log = Locked(listOf<String>())
        val store = store(shared, log)
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(true))
        store.finish()
        store.send(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.soundEffects, false))
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(false))
        store.finish()
        val before = setOf(ExperimentalFeature.hideModels, ExperimentalFeature.soundEffects, ExperimentalFeature.backgroundCallIntent)
        for (feature in listed) {
            assertEquals("$feature", feature in before, shared.on(feature))
        }
        assertEquals("back on as before, preloaded again", listOf("sound.prepare"), log.value)
        assertNull("nothing saved while it's off", shared.get(allExperimentsSaved(ExperimentalFeature.hideModels)))
        assertTrue("the skip is kept for next time", shared.get(allExperimentsSkips(ExperimentalFeature.soundEffects)))
    }

    /** An experiment that arrives while All is on (nothing saved for it) starts on at launch, unless it was skipped, and goes back off with All. */
    @Test fun aNewExperimentStartsOnWhileAllIsOn() = runTest {
        val shared = SettingsTestShared()
        shared.set(AllExperiments.key, true)
        for (feature in ExperimentalFeature.joiningAll) {
            if (feature == ExperimentalFeature.inAppBrowser || feature == ExperimentalFeature.inboxSidebar) continue
            shared.set(experimental(feature), true)
            shared.set(allExperimentsSaved(feature), false)
        }
        shared.set(allExperimentsSkips(ExperimentalFeature.inboxSidebar), true)
        shared.read { ExperimentalFeature.adoptNewExperimentsIntoAll() }
        assertTrue(shared.on(ExperimentalFeature.inAppBrowser))
        assertFalse("skipped before, still off", shared.on(ExperimentalFeature.inboxSidebar))
        assertEquals(false, shared.get(allExperimentsSaved(ExperimentalFeature.inAppBrowser)))
        shared.read { ExperimentalFeature.adoptNewExperimentsIntoAll() }
        assertTrue(shared.on(ExperimentalFeature.inAppBrowser))

        val store = store(shared, Locked(listOf()))
        store.send(HomeSettingsFeature.Action.AllExperimentsChanged(false))
        store.finish()
        assertFalse(shared.on(ExperimentalFeature.inAppBrowser))
    }

    /** With All off, launch changes nothing. */
    @Test fun launchLeavesSwitchesAloneWhileAllIsOff() {
        val shared = SettingsTestShared()
        shared.read { ExperimentalFeature.adoptNewExperimentsIntoAll() }
        for (feature in ExperimentalFeature.allCases) {
            assertFalse("$feature", shared.on(feature))
            assertFalse("$feature", shared.store.contains(allExperimentsSaved(feature)))
        }
    }
}
