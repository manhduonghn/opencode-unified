package ai.opencode.devtools.tuning

import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionHeaderStyleTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun tuningTabsKeepTheirWholeHitTargetOnScreen() {
        val screen = Rect(0f, 0f, 402f, 874f)
        val layout = TuningPlacementLayout.of(safeArea = screen, screen = screen)
        for (side in listOf(TuningPlacement.Side.Leading, TuningPlacement.Side.Trailing)) {
            val size = TuningPlacementLayout.TabSize
            val center = layout.center(TuningPlacement(side = side, isTucked = true), size)
            val target = Rect(center.x - size.width / 2, center.y - size.height / 2, center.x + size.width / 2, center.y + size.height / 2)
            assertTrue(target.intersect(screen).width >= 44)
            if (side == TuningPlacement.Side.Leading) assertEquals(2f, target.left) else assertEquals(screen.right - 2, target.right)
        }
    }
}
