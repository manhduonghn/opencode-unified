package ai.opencode.feature.drawer.motion

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The drawer's card never keeps a pose nothing will undo. */
class DrawerTouchStateTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aDrawerOpenedBeforeItHadAWidthLandsOpenOnceItHasOne() = runTest {
        val motion = DrawerMotionState(backgroundScope, initiallyPresented = true, drawerWidth = 0f)
        motion.reducedMotion = true
        motion.updateWidth(300f)
        assertTrue(motion.isPresented)
        assertEquals("the card is drawn where the model says it rests", 300f, motion.offset)
        assertTrue(motion.isScrimInteractive.value)
        motion.setPresented(false, animated = false)
        assertFalse(motion.isContentSuspended)

        // Opened by the button before the first layout gave it a width.
        val opened = DrawerMotionState(backgroundScope, initiallyPresented = false, drawerWidth = 0f)
        opened.reducedMotion = true
        val arrivals = mutableListOf<Boolean>()
        opened.callbacks = DrawerMotionState.Callbacks(onArrived = { arrivals.add(it) })
        opened.setPresented(true, animated = false)
        opened.updateWidth(300f)
        assertTrue(opened.isPresented)
        assertEquals(300f, opened.offset)
        assertTrue("the open drawer's card takes no touches", opened.isContentSuspended)
        assertTrue(opened.isScrimInteractive.value)
        assertEquals(listOf(true), arrivals)
    }

    @Test
    fun anOpenDrawerDrawnOpenStaysTheReaders() = runTest {
        val motion = DrawerMotionState(backgroundScope, initiallyPresented = false, drawerWidth = 300f)
        motion.reducedMotion = true
        motion.setPresented(true, animated = false)
        motion.updateWidth(300f)
        assertTrue(motion.isPresented)
        assertEquals(300f, motion.offset)
        // A new width at rest keeps it open.
        motion.updateWidth(320f)
        assertTrue(motion.isPresented)
        assertEquals(320f, motion.offset)
    }
}
