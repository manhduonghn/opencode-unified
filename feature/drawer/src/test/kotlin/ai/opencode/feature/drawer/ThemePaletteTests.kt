package ai.opencode.feature.drawer

import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemePaletteTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun appearanceSettingsActionsPersistViaSharedAndPlaySelectionHaptic() = runTest {
        val recordedHaptics = Collections.synchronizedList(mutableListOf<String>())
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()))
        val store = TestStore(state, HomeFeature(), this) {
            this[HapticsClient] = HapticsClient(play = { recordedHaptics += it.name })
        }
        fun appTheme() = store.withDependencies { shared(ThemeSharedKeys.appTheme).value }
        fun colorScheme() = store.withDependencies { shared(ThemeSharedKeys.appearanceColorScheme).value }
        fun returnKeySends() = store.withDependencies { shared(SharedKeys.returnKeySends).value }
        store.withDependencies {
            shared(ThemeSharedKeys.appTheme).set(AppTheme.System)
            shared(ThemeSharedKeys.appearanceColorScheme).set(AppearanceColorScheme.System)
            shared(SharedKeys.returnKeySends).set(true)
        }

        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.AppThemeChanged(AppTheme.TokyoNight)))
        assertEquals(AppTheme.TokyoNight, appTheme())
        assertEquals(listOf("selection"), recordedHaptics.toList())

        // Sending the same theme is a quiet no-op.
        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.AppThemeChanged(AppTheme.TokyoNight)))
        assertEquals(listOf("selection"), recordedHaptics.toList())

        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(AppearanceColorScheme.Dark)))
        assertEquals(AppearanceColorScheme.Dark, colorScheme())
        assertEquals(listOf("selection", "selection"), recordedHaptics.toList())

        // Sending the same mode is a quiet no-op.
        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(AppearanceColorScheme.Dark)))
        assertEquals(listOf("selection", "selection"), recordedHaptics.toList())

        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.ReturnKeySendsChanged(false)))
        assertFalse(returnKeySends())
        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.ReturnKeySendsChanged(false)))
        assertFalse(returnKeySends())
        store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.ReturnKeySendsChanged(true)))
        assertTrue(returnKeySends())
        store.finish()
    }
}
