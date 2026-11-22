package ai.opencode.feature.drawer

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Home owns the sidebar toggle (`reducePreferences`, `State.isWindowLocal`). */
class MultipleWindowsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")

    @Test
    fun aFurtherWindowKeepsItsOwnSidebar() = runTest {
        // A further window's Home holds its sidebar in memory; toggling it leaves the saved
        // setting the first window reads alone.
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
            .copy(isWindowLocal = true, isSidebarShown = true)
        val store = TestStore(home, HomeFeature(), this)
        store.withDependencies { shared(SharedKeys.sidebarShown).set(true) }
        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.SidebarToggled)) { it.copy(isSidebarShown = false) }
        assertEquals(true, store.withDependencies { shared(SharedKeys.sidebarShown).value })
        store.finish()
    }
}
