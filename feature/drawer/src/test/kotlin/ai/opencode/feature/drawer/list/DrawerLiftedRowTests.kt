package ai.opencode.feature.drawer.list

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningStore
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Where a cancelled release still lands, and the lifted row's shadow. */
class DrawerLiftedRowTests {
    @get:Rule val isolation = TestIsolationRule()

    private val window = Rect(0f, 0f, 440f, 956f)
    private val list = Rect(0f, 0f, 300f, 956f)

    private fun lands(x: Float, y: Float): Boolean =
        DrawerPinDrag.landsOnCancel(Offset(x, y), list, headerBottom = 120f, footerTop = 870f, window = window)

    @Test
    fun aCancelledReleaseOverTheHeaderOrFooterLands() {
        assertTrue(lands(150f, 52f))
        assertTrue(lands(150f, 119f))
        assertTrue(lands(150f, 900f))
        assertTrue("A little past the column's edge still counts", lands(320f, 52f))
    }

    @Test
    fun anyOtherCancelSpringsBack() {
        // Escape with the finger over the list's body.
        assertFalse(lands(150f, 400f))
        assertFalse(lands(150f, 120f))
        assertFalse(lands(150f, 870f))
        // Off the window, above or below.
        assertFalse(lands(150f, -10f))
        assertFalse(lands(150f, 970f))
        // Beside the list's column.
        assertFalse(lands(380f, 52f))
    }

    @Test
    fun theShadowFollowsItsTunables() {
        val store = TuningStore.shared
        val wasEnabled = store.isEnabled
        store.setEnabled(true)
        DrawerTunables.register(store)
        val keys = listOf(DrawerTunables.pinLiftShadowY.key, DrawerTunables.pinLiftShadowRadius.key)
        try {
            var look = DrawerLiftedRowView.apply(lift = 1f)
            assertEquals(6f, look.shadowOffsetY)
            assertEquals(14f, look.shadowRadius)
            store.set(DrawerTunables.pinLiftShadowY.key, TuneValue.Number(10.0))
            store.set(DrawerTunables.pinLiftShadowRadius.key, TuneValue.Number(20.0))
            look = DrawerLiftedRowView.apply(lift = 1f)
            assertEquals(10f, look.shadowOffsetY)
            assertEquals(20f, look.shadowRadius)
        } finally {
            keys.forEach(store::reset)
            store.setEnabled(wasEnabled)
        }
    }
}
