package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OnePickerSettlingTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun cancelledDragSettlesAtItsDetentAndStopsTheDisplayLink() {
        val viewport = rect(0, 0, 402, 874)
        val source = rect(20, 786, 362, 56)
        val metrics = OnePickerSimulation.Metrics(viewport = viewport, sourceRect = source, ceiling = 104.0, compactHeight = 460.0)
        val sim = OnePickerSimulation(metrics)
        sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
        sim.launchOpen()
        for (i in 0 until 240) if (sim.step(1.0 / 120.0)) break
        sim.beginDrag()
        sim.updateDrag(40.0)
        val settledWhileDragging = sim.step(1.0 / 120.0)
        assertFalse("A live drag keeps stepping", settledWhileDragging)
        sim.setDetent(ModelListDetent.Large)
        assertFalse("A new detent ends the drag", sim.isDragging)
        var simSettled = false
        for (i in 0 until 480) {
            if (simSettled) break
            simSettled = sim.step(1.0 / 120.0)
        }
        assertTrue(simSettled)
        assertTrue(abs(sim.drawnFrame().rect.top - metrics.restingTopY(ModelListDetent.Large)) < 1)

        val controller = OnePickerController()
        try {
            controller.open(
                sourceRect = source,
                viewport = viewport,
                ceiling = 104.0,
                recentModelIDs = emptyList(),
                hadKeyboard = false,
                initialDetent = ModelListDetent.Medium,
                reduceMotion = false,
                onLaunch = {},
                onOpened = {},
            )
            stepUntilStop(controller, 240)
            val restingTop = controller.pose.rect.top
            controller.beginDrag()
            controller.updateDrag(60.0)
            assertTrue(controller.isDragging)
            val actionWhileDragging = controller.stepFrame(1.0 / 120.0)
            assertEquals(OnePickerController.FrameAction.Continue, actionWhileDragging)
            controller.settleInteraction()
            assertFalse(controller.isDragging)
            assertTrue(controller.isOpen)
            var stopped = false
            for (i in 0 until 480) {
                if (stopped) break
                stopped = controller.stepFrame(1.0 / 120.0) == OnePickerController.FrameAction.Stop
            }
            assertTrue("The display link stops once the cancelled drag settles", stopped)
            assertTrue("It settles back at its medium detent", abs(controller.pose.rect.top - restingTop) < 1)
            assertEquals(ModelListDetent.Medium, controller.detent)
        } finally {
            controller.reset()
        }
    }

    /**
     * A drag settled from outside (the app going inactive) ignores the rest of its gesture
     * instead of resuming from the mid-spring top plus the gesture's whole translation; the next
     * gesture drags normally.
     */
    @Test
    fun externallySettledDragIgnoresItsGestureUntilRelease() {
        val viewport = rect(0, 0, 402, 874)
        val source = rect(20, 786, 362, 56)
        val controller = OnePickerController()
        try {
            controller.open(
                sourceRect = source,
                viewport = viewport,
                ceiling = 104.0,
                recentModelIDs = emptyList(),
                hadKeyboard = false,
                initialDetent = ModelListDetent.Medium,
                reduceMotion = false,
                onLaunch = {},
                onOpened = {},
            )
            stepUntilStop(controller, 240)
            val restingTop = controller.pose.rect.top
            controller.beginDrag()
            controller.updateDrag(120.0)
            controller.settleInteraction()
            assertTrue(controller.ignoresDragUntilRelease)
            repeat(6) { controller.stepFrame(1.0 / 120.0) }
            val midSpringTop = controller.pose.rect.top
            assertTrue("Still springing back", midSpringTop > restingTop + 1)
            controller.updateDrag(160.0)
            assertFalse("A stale sample doesn't restart the drag", controller.isDragging)
            assertEquals("No jump to mid-spring top + 160", midSpringTop, controller.pose.rect.top)
            var dismissed = false
            controller.endDrag(400.0, 3000.0, destinationRect = source) { dismissed = true }
            assertFalse("The stale gesture's release doesn't throw the sheet", dismissed)
            assertFalse(controller.ignoresDragUntilRelease)
            assertTrue(controller.isOpen)
            stepUntilStop(controller, 480)
            controller.beginDrag()
            controller.updateDrag(30.0)
            assertTrue("The next gesture drags", controller.isDragging)
            assertTrue(abs(controller.pose.rect.top - (restingTop + 30)) < 1)
            controller.cancelDrag()
            assertFalse(controller.isDragging)
            assertFalse(controller.ignoresDragUntilRelease)
        } finally {
            controller.reset()
        }
    }

    /**
     * Closing with no keyboard, the controller pins the bottom edge to the live composer every
     * frame. A composer resting off the destination moves the destination onto it, so Closing ends
     * and `top` lands with it.
     */
    @Test
    fun cancelledPullDownSettlesThroughTheLatestCallbacks() {
        val log = mutableListOf<String>()
        fun refreshed(tag: String) = OnePickerListActions().apply {
            onBeginSheetDrag = { log += "$tag begin" }
            onUpdateSheetDrag = { dy -> log += "$tag update ${dy.toInt()}" }
            onEndSheetDrag = { dy, vy -> log += "$tag end ${dy.toInt()} ${vy.toInt()}" }
        }
        val pull = OnePickerPullDown(LazyListState(), density = { 1f }, actions = refreshed("old"))
        // Recomposition hands the remembered pull the new callbacks; nothing else changed.
        pull.actions = refreshed("new")

        pull.startedAtTop = true
        // The list at its top overscrolls by 5: the sheet pull begins, anchored there.
        pull.connection.onPostScroll(Offset.Zero, Offset(0f, 5f), NestedScrollSource.UserInput)
        // The gesture is torn down twice; it settles once.
        pull.cancel()
        pull.cancel()

        assertEquals(listOf("new begin", "new update 0", "new end 0 0"), log)
    }

    @Test
    fun closePinnedToComposerRestingOffTargetLandsBothEdgesOnIt() {
        val source = rect(20, 790, 362, 56)
        val live = rect(20, 474, 362, 56)

        val stuck = openedSimulation(source)
        stuck.startClose(source, closeDuration = 0.267, contentOutDuration = 0.16)
        repeat(600) {
            stuck.bottom.value = live.bottom.toDouble()
            stuck.step(DT)
            stuck.bottom.value = live.bottom.toDouble()
        }
        assertEquals("Value-only pinning spins forever", OnePickerSimulation.Phase.Closing, stuck.phase)

        val sim = openedSimulation(source)
        sim.startClose(source, closeDuration = 0.267, contentOutDuration = 0.16)
        var frames = 0
        var last = sim.drawnFrame().rect
        var lastClosing = last
        var maxStep = 0f
        while (sim.phase == OnePickerSimulation.Phase.Closing && frames < 240) {
            sim.pinClosingToComposer(live)
            sim.step(DT)
            if (sim.phase == OnePickerSimulation.Phase.Closing) {
                sim.bottom.value = live.bottom.toDouble()
                val rect = sim.drawnFrame().rect
                maxStep = max(maxStep, abs(rect.top - last.top))
                last = rect
                lastClosing = rect
            }
            frames += 1
        }
        assertEquals("Close settles once the composer stops (after $frames frames)", OnePickerSimulation.Phase.Closed, sim.phase)
        assertTrue("Settles within a second at 120 Hz ($frames frames)", frames < 120)
        assertEquals("Bottom never jumps off the live composer", live.bottom.toDouble(), sim.bottom.value, 0.0)
        assertTrue(
            "The glass lands on the composer, both edges: $lastClosing",
            abs(lastClosing.top - live.top) <= 1 && abs(lastClosing.bottom - live.bottom) <= 1,
        )
        assertTrue("Top never jumps as it retargets (largest step $maxStep pt)", maxStep < 30)
    }

    /**
     * The keyboard leaving mid-close (a second tap outside) or arriving (focus returning as a
     * sheet leaves) moves the composer; the fold follows it onto where the composer is headed
     * instead of the edge it left, with no jump.
     */
    @Test
    fun keyboardChangingMidCloseRetargetsTheFoldOntoTheComposer() {
        val keyboardUp = rect(20, 474, 362, 56)
        val dropping = openController(keyboardUp, hadKeyboard = true)
        try {
            val resting = dropping.restingBottomComposer
            assertTrue(resting.bottom > keyboardUp.bottom + 200)
            val dropRects = closeWhileKeyboardChanges(dropping, keyboardUp, keyboardEnd = rect(0, 874, 402, 336))
            assertEquals(OnePickerSimulation.Phase.Closed, dropping.simulation.phase)
            assertEquals("The fold lands on the resting composer", resting, dropping.simulation.metrics.sourceRect)
            dropRects.lastOrNull()?.let { final ->
                assertTrue("Last closing frame $final", abs(final.bottom - resting.bottom) <= 1 && abs(final.top - resting.top) <= 1)
            }
            val dropSteps = dropRects.zipWithNext { a, b -> max(abs(b.top - a.top), abs(b.bottom - a.bottom)) }
            assertTrue("No frame jumps (${dropSteps.maxOrNull() ?: 0f} pt)", (dropSteps.maxOrNull() ?: 0f) < 30)
        } finally {
            dropping.reset()
        }

        val atRest = rect(20, 790, 362, 56)
        val rising = openController(atRest, hadKeyboard = false)
        try {
            val keyboard = rect(0, 538, 402, 336)
            closeWhileKeyboardChanges(rising, atRest, keyboardEnd = keyboard)
            assertEquals(OnePickerSimulation.Phase.Closed, rising.simulation.phase)
            val gap = rising.simulation.metrics.keyboardGap
            assertTrue(
                "The fold lands above the keyboard: ${rising.simulation.metrics.sourceRect}",
                abs(rising.simulation.metrics.sourceRect.bottom - (keyboard.top - gap)) <= 0.5,
            )
        } finally {
            rising.reset()
        }
    }

    /**
     * With the composer probe attached: the composer has rested on the close's edge, then the
     * keyboard rises. The fold follows the composer up, not back to the edge it's leaving.
     */
    @Test
    fun keyboardRisingAfterTheComposerRestedFollowsTheLiveComposer() {
        val atRest = rect(20, 790, 362, 56)
        val keyboard = rect(0, 538, 402, 336)
        val controller = openController(atRest, hadKeyboard = false)
        try {
            val maxY = keyboard.top - controller.simulation.metrics.keyboardGap.toFloat()
            val composerEnd = Rect(Offset(atRest.left, maxY - atRest.height), atRest.size)
            val frames = closeFollowingComposer(controller, atRest, composerEnd, keyboard, notifyAt = 6) {
                controller.close(atRest, freezeSections = { emptyList() })
            }
            expectLandsOnComposer(controller, frames, composerEnd, neverReturnsTo = atRest, after = 6)
        } finally {
            controller.reset()
        }
    }

    /**
     * A close handing the keyboard back waits up to 75 ms for it; the composer sitting still
     * meanwhile doesn't count as rest.
     */
    @Test
    fun lateKeyboardDuringTheCloseSyncWaitStillRidesToTheKeyboardComposer() {
        val atRest = rect(20, 804, 362, 56)
        val keyboardUp = rect(20, 474, 362, 56)
        val keyboard = rect(0, 536, 402, 338)
        for (notifyAt in 4..8) {
            val controller = openController(atRest, hadKeyboard = false)
            try {
                val frames = closeFollowingComposer(controller, atRest, keyboardUp, keyboard, notifyAt) {
                    controller.close(keyboardUp, matchingKeyboardSpring = true, freezeSections = { emptyList() })
                }
                expectLandsOnComposer(controller, frames, keyboardUp, neverReturnsTo = atRest, after = 0)
            } finally {
                controller.reset()
            }
        }
    }

    /**
     * A reopen mid-close starts from what's on screen: the last close's frozen rows and recents
     * go, and the composer it returns to follows the keyboard as it is now.
     */
    @Test
    fun reopeningMidCloseDropsTheLastClosesState() {
        val keyboardUp = rect(20, 474, 362, 56)
        val atRest = rect(20, 790, 362, 56)
        val controller = openController(keyboardUp, recents = listOf("a"), hadKeyboard = true)
        try {
            assertEquals(keyboardUp, controller.openedFromComposer)

            controller.restoresComposerFocus = false
            controller.close(atRest, freezeSections = { emptyList() })
            repeat(4) { controller.stepFrame(DT) }
            assertEquals(OnePickerSimulation.Phase.Closing, controller.simulation.phase)
            assertNotNull(controller.frozenSections)

            controller.open(
                sourceRect = atRest, viewport = VIEWPORT, ceiling = 104.0, recentModelIDs = listOf("b"), hadKeyboard = false,
                initialDetent = ModelListDetent.Medium, reduceMotion = false, onLaunch = {}, onOpened = {},
            )
            assertNull(controller.frozenSections)
            assertEquals(listOf("b"), controller.recentsAtOpen)
            assertEquals("No keyboard now, so it returns to the resting composer", atRest, controller.openedFromComposer)

            controller.close(atRest, freezeSections = { emptyList() })
            repeat(4) { controller.stepFrame(DT) }
            controller.open(
                sourceRect = atRest, viewport = VIEWPORT, ceiling = 104.0, recentModelIDs = listOf("b"), hadKeyboard = false,
                restoresFocusTo = keyboardUp, initialDetent = ModelListDetent.Medium, reduceMotion = false,
                onLaunch = {}, onOpened = {},
            )
            assertEquals("It owes the keyboard back, so it returns above it", keyboardUp, controller.openedFromComposer)
            assertTrue(controller.restoresComposerFocus)
        } finally {
            controller.reset()
        }
    }

    private class Frame(val glass: Rect, val composer: Rect, val destination: Rect)

    /** A composer's live rect: still until [move], then a 30-frame smoothstep that starts `after` frames late. */
    private class ComposerTrack(var rect: Rect) {
        private var from = Rect.Zero
        private var to = Rect.Zero
        private var frame = 0
        private var delay = Int.MAX_VALUE

        fun move(end: Rect, after: Int) {
            from = rect
            to = end
            frame = 0
            delay = after
        }

        fun tick() {
            frame += 1
            if (frame <= delay) return
            val t = min(1f, (frame - delay) / 30f)
            val eased = t * t * (3 - 2 * t)
            rect = Rect(Offset(from.left, from.top + (to.top - from.top) * eased), from.size)
        }
    }

    private companion object {
        val VIEWPORT = Rect(Offset.Zero, Size(402f, 874f))
        const val DT = 1.0 / 120.0

        fun rect(x: Int, y: Int, width: Int, height: Int): Rect =
            Rect(Offset(x.toFloat(), y.toFloat()), Size(width.toFloat(), height.toFloat()))

        fun stepUntilStop(controller: OnePickerController, frames: Int) {
            for (i in 0 until frames) if (controller.stepFrame(DT) != OnePickerController.FrameAction.Continue) break
        }

        fun openedSimulation(source: Rect): OnePickerSimulation {
            val metrics = OnePickerSimulation.Metrics(
                viewport = VIEWPORT, sourceRect = source, sourceRadius = 28.0, ceiling = 104.0, compactHeight = 460.0,
                sideInset = 8.0, bottomInset = 8.0, keyboardGap = 6.0, keyboardTop = null,
            )
            val sim = OnePickerSimulation(metrics)
            sim.arm(metrics, ModelListDetent.Medium, reduceMotion = false)
            sim.launchOpen(openDuration = 0.35, openBounce = 0.06, widthDuration = 0.25, bottomDuration = 0.267, contentDuration = 0.24)
            for (i in 0 until 240) if (sim.step(DT)) break
            return sim
        }

        fun openController(source: Rect, recents: List<String> = emptyList(), hadKeyboard: Boolean): OnePickerController {
            val controller = OnePickerController()
            controller.open(
                sourceRect = source, viewport = VIEWPORT, ceiling = 104.0, recentModelIDs = recents, hadKeyboard = hadKeyboard,
                initialDetent = ModelListDetent.Medium, reduceMotion = false, onLaunch = {}, onOpened = {},
            )
            stepUntilStop(controller, 240)
            return controller
        }

        /** Closes [controller] while the keyboard changes [framesIn] frames into the fold, returning every closing frame's rect. */
        fun closeWhileKeyboardChanges(
            controller: OnePickerController,
            destination: Rect,
            keyboardEnd: Rect,
            framesIn: Int = 6,
        ): List<Rect> {
            controller.close(destination, freezeSections = { emptyList() })
            val rects = mutableListOf<Rect>()
            var frame = 0
            while (controller.simulation.phase == OnePickerSimulation.Phase.Closing && frame < 480) {
                if (frame == framesIn) controller.handleKeyboardFrameChange(keyboardEnd)
                controller.stepFrame(DT)
                if (controller.simulation.phase == OnePickerSimulation.Phase.Closing) rects += controller.pose.rect
                frame += 1
            }
            return rects
        }

        /**
         * Steps a close while a stand-in composer follows the keyboard: [notifyAt] frames in, the
         * keyboard change lands and the composer starts toward [composerEnd] two frames later.
         */
        fun closeFollowingComposer(
            controller: OnePickerController,
            start: Rect,
            composerEnd: Rect,
            keyboardEnd: Rect,
            notifyAt: Int,
            close: () -> Unit,
        ): List<Frame> {
            val composer = ComposerTrack(start)
            controller.composerRectSource = { composer.rect }
            close()
            val frames = mutableListOf<Frame>()
            var frame = 0
            while (controller.simulation.phase == OnePickerSimulation.Phase.Closing && frame < 480) {
                if (frame == notifyAt) {
                    controller.handleKeyboardFrameChange(keyboardEnd)
                    composer.move(composerEnd, after = 2)
                }
                composer.tick()
                controller.stepFrame(DT)
                if (controller.simulation.phase == OnePickerSimulation.Phase.Closing) {
                    frames += Frame(controller.pose.rect, composer.rect, controller.simulation.metrics.sourceRect)
                }
                frame += 1
            }
            return frames
        }

        fun expectLandsOnComposer(
            controller: OnePickerController,
            frames: List<Frame>,
            composerEnd: Rect,
            neverReturnsTo: Rect,
            after: Int,
        ) {
            assertEquals("Close settles (keyboard at frame $after)", OnePickerSimulation.Phase.Closed, controller.simulation.phase)
            assertTrue(
                "Lands on the composer: ${controller.simulation.metrics.sourceRect}",
                abs(controller.simulation.metrics.sourceRect.top - composerEnd.top) <= 0.5,
            )
            frames.forEachIndexed { index, frame ->
                if (index >= after) {
                    assertTrue(
                        "Frame $index went back to the edge the composer left (keyboard at frame $after)",
                        abs(frame.destination.top - neverReturnsTo.top) > 1,
                    )
                }
            }
            frames.forEachIndexed { index, frame ->
                assertTrue(
                    "Frame $index: glass top ${frame.glass.top} detached below the composer at ${frame.composer.top}",
                    frame.glass.top <= frame.composer.top + 8,
                )
            }
            val steps = frames.zipWithNext { a, b -> abs(b.glass.top - a.glass.top) }
            assertTrue("Top never jumps (${steps.maxOrNull() ?: 0f} pt)", (steps.maxOrNull() ?: 0f) < 30)
        }
    }
}
