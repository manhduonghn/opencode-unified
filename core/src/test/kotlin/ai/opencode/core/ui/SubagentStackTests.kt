package ai.opencode.core.ui

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The decision distance is passed. */
class SubagentStackTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun decision(dx: Float, dy: Float, atEdge: Boolean, claimed: Boolean, heldFor: Double, holdBegan: Boolean) =
        BackSwipeRules.decision(dx, dy, decisionDistancePx = 8f, atEdge = atEdge, claimed = claimed, heldForSeconds = heldFor, holdBegan = holdBegan)

    @Test
    fun backRulesLetTheEdgeWinAndContentKeepItsClaims() {
        val wait = BackSwipeRules.Decision.Wait
        val begin = BackSwipeRules.Decision.Begin
        val fail = BackSwipeRules.Decision.Fail
        // Too little travel: wait.
        assertEquals(wait, decision(3f, 2f, atEdge = false, claimed = false, heldFor = 0.0, holdBegan = false))
        // Plain content: a rightward drag goes back; leftward or vertical never.
        assertEquals(begin, decision(20f, 2f, atEdge = false, claimed = false, heldFor = 0.05, holdBegan = false))
        assertEquals(fail, decision(-20f, 2f, atEdge = false, claimed = false, heldFor = 0.0, holdBegan = false))
        assertEquals(fail, decision(9f, 12f, atEdge = false, claimed = false, heldFor = 0.0, holdBegan = false))
        // A scroller or control under the finger keeps it, except from the edge.
        assertEquals(fail, decision(20f, 2f, atEdge = false, claimed = true, heldFor = 0.0, holdBegan = false))
        assertEquals(begin, decision(20f, 2f, atEdge = true, claimed = true, heldFor = 0.0, holdBegan = false))
        // A rested finger keeps its hold, except from the edge.
        assertEquals(fail, decision(20f, 2f, atEdge = false, claimed = false, heldFor = 0.6, holdBegan = false))
        assertEquals(fail, decision(20f, 2f, atEdge = false, claimed = false, heldFor = 0.1, holdBegan = true))
        assertEquals(begin, decision(20f, 2f, atEdge = true, claimed = false, heldFor = 0.6, holdBegan = true))
        // The drawer's release rule.
        assertTrue(BackSwipeRules.goesBack(offset = 250f, width = 440f, velocity = 0f))
        assertFalse(BackSwipeRules.goesBack(offset = 150f, width = 440f, velocity = 0f))
        assertTrue(BackSwipeRules.goesBack(offset = 40f, width = 440f, velocity = 900f))
        assertFalse(BackSwipeRules.goesBack(offset = 400f, width = 440f, velocity = -900f))
    }
}
