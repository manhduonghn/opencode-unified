package ai.opencode.feature.settings

import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Home's `.settings(…)` actions are [HomeSettingsFeature]'s here, so that reducer is driven. */
class ThemePaletteTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun appearanceSettingsActionsPersistViaSharedAndPlaySelectionHaptic() = runTest {
        val recordedHaptics = HapticsRecorder()
        val shared = SettingsTestShared()
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[HapticsClient] = recordedHaptics.client
        }
        shared.set(ThemeSharedKeys.appTheme, AppTheme.System)
        shared.set(ThemeSharedKeys.appearanceColorScheme, AppearanceColorScheme.System)
        shared.set(SharedKeys.returnKeySends, true)

        store.send(HomeSettingsFeature.Action.AppThemeChanged(AppTheme.TokyoNight))
        assertEquals(AppTheme.TokyoNight, shared.get(ThemeSharedKeys.appTheme))
        assertEquals(listOf("selection"), recordedHaptics.value)

        // Sending the same theme is a quiet no-op.
        store.send(HomeSettingsFeature.Action.AppThemeChanged(AppTheme.TokyoNight))
        assertEquals(listOf("selection"), recordedHaptics.value)

        store.send(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(AppearanceColorScheme.Dark))
        assertEquals(AppearanceColorScheme.Dark, shared.get(ThemeSharedKeys.appearanceColorScheme))
        assertEquals(listOf("selection", "selection"), recordedHaptics.value)

        // Sending the same mode is a quiet no-op.
        store.send(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(AppearanceColorScheme.Dark))
        assertEquals(listOf("selection", "selection"), recordedHaptics.value)

        store.send(HomeSettingsFeature.Action.ReturnKeySendsChanged(false))
        assertFalse(shared.get(SharedKeys.returnKeySends))
        store.send(HomeSettingsFeature.Action.ReturnKeySendsChanged(false))
        assertFalse(shared.get(SharedKeys.returnKeySends))
        store.send(HomeSettingsFeature.Action.ReturnKeySendsChanged(true))
        assertTrue(shared.get(SharedKeys.returnKeySends))
        store.finish()
    }
}
