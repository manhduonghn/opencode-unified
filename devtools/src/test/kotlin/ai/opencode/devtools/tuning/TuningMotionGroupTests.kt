package ai.opencode.devtools.tuning

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.TuningContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Without the playground-enum and Mac tune page tests. */
class TuningMotionGroupTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun theMotionGroupIsShownOnEveryScreen() {
        val registered = listOf("drawer", "catchUp", "composer", "session", "motion")
        val contexts = listOf(
            TuningContext.session, TuningContext.drawer, TuningContext.catchUp, TuningContext.settings,
            TuningContext.motionPlayground, TuningContext.transcriptPlayground, TuningContext.sessionListPlayground,
            TuningContext.catchUpPlayground, TuningContext.composerPlayground, TuningContext("voice", groups = listOf("voice", "scope")),
        )
        for (context in contexts) {
            val shown = TuningPanelGroups.shown(registered, context, showsAll = false, hidden = emptySet())
            assertTrue(context.id, shown.contains("motion"))
            assertEquals("${context.id} lists Motion once", 1, shown.count { it == "motion" })
        }

        assertEquals(listOf("drawer", "motion"), TuningPanelGroups.shown(registered, TuningContext.drawer, showsAll = false, hidden = emptySet()))
        assertEquals(listOf("motion"), TuningPanelGroups.shown(registered, TuningContext.settings, showsAll = false, hidden = emptySet()))
        assertEquals(listOf("session", "motion", "composer"), TuningPanelGroups.shown(registered, TuningContext.session, showsAll = false, hidden = emptySet()))
        assertEquals(
            listOf("drawer", "motion", "catchUp", "composer", "session"),
            TuningPanelGroups.shown(registered, TuningContext.drawer, showsAll = true, hidden = emptySet()),
        )

        assertEquals(listOf("drawer"), TuningPanelGroups.shown(listOf("drawer"), TuningContext.drawer, showsAll = false, hidden = emptySet()))
        assertEquals(listOf("drawer"), TuningPanelGroups.shown(registered, TuningContext.drawer, showsAll = false, hidden = setOf("motion")))
        assertEquals("a context's own groups stay its own", listOf("drawer"), TuningContext.drawer.groups)
        assertEquals(listOf("drawer", "motion"), TuningContext.drawer.panelGroups)
        assertEquals(TuningContext.session.groups, TuningContext.session.panelGroups)
        assertEquals(listOf("motion"), TuningContext.motionPlayground.groups)
    }
}
