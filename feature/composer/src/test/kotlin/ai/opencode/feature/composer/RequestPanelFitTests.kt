package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * The request panel fits under the header as a pure function of the room (`requestPanelCap`).
 * [layOut] composes [RequestPanelFit.stack] the way `RequestPanelFallback`/`RequestPanelStack` do:
 * the pinned footer takes its height first, the column (a 50-tall header over the parts) gets the
 * rest, and the panel is at most the cap.
 */
class RequestPanelFitTests {
    private data class PartSpec(val id: String, val natural: Int, val floor: Int, val priority: Double)

    private val fixedHeader = 50
    private val footer = 30

    /** A permission (a fixed caption and buttons around a command) or a question page (a fixed header over its question and choices), capped. */
    private fun layOut(cap: Float?, parts: List<PartSpec>): Map<String, Float> {
        val capPx = cap?.let { kotlin.math.max(Math.round(it), 0) }
        val room = capPx?.let { kotlin.math.max(it - footer, 0) }
        val stack = RequestPanelFit.stack(
            room = room,
            fixed = fixedHeader,
            parts = parts.map { RequestPanelFit.Part(it.natural, it.floor, it.priority) },
        )
        val values = mutableMapOf<String, Float>()
        parts.forEachIndexed { index, part -> values[part.id] = stack.heights[index].toFloat() }
        val natural = stack.height + footer
        values["panel"] = (capPx?.let { min(natural, it) } ?: natural).toFloat()
        values["footerTop"] = stack.height.toFloat()
        values["footerBottom"] = (stack.height + footer).toFloat()
        return values
    }

    private val scrollFloor = RequestDockMetrics.scrollFloor.value.toInt()
    private val questionFloor = RequestDockMetrics.questionFloor.value.toInt()
    private val command get() = PartSpec("command", 180, scrollFloor, RequestPanelFit.Priority.command)
    private val choices get() = PartSpec("choices", 150, scrollFloor, RequestPanelFit.Priority.choices)
    private val question get() = PartSpec("question", 110, questionFloor, RequestPanelFit.Priority.question)

    @Test fun aPanelThatFitsKeepsItsNaturalHeights() {
        val heights = layOut(400f, listOf(choices, question))
        assertEquals(150f, heights["choices"])
        assertEquals(110f, heights["question"])
        assertEquals(340f, heights["panel"])
        assertEquals(180f, layOut(null, listOf(command))["command"])
    }

    @Test fun overflowComesOffTheCommandOrTheChoicesFirst() {
        val permission = layOut(182f, listOf(command))
        assertEquals(102f, permission["command"])
        assertEquals(182f, permission["panel"])
        // Choices give up room before the question.
        val page = layOut(280f, listOf(choices, question))
        assertEquals(90f, page["choices"])
        assertEquals(110f, page["question"])
        val tighter = layOut(200f, listOf(choices, question))
        assertEquals(64f, tighter["choices"])
        assertEquals(56f, tighter["question"])
        assertEquals(200f, tighter["panel"])
    }

    /** Past the floors the panel scrolls, capped at the room, its actions pinned below the scroll and fully on screen. */
    @Test fun pastTheFloorsThePanelScrollsWithItsActionsPinned() {
        val heights = layOut(120f, listOf(choices, question))
        assertTrue("$heights", abs((heights["panel"] ?: 0f) - 120f) < 0.5f)
        assertTrue("$heights", abs((heights["footerBottom"] ?: 0f) - 120f) < 0.5f)
        assertTrue("$heights", abs((heights["footerTop"] ?: 0f) - 90f) < 0.5f)
        // Exactly at the floors it still squeezes, without scrolling.
        val atFloors = layOut(188f, listOf(choices, question))
        assertEquals(64f, atFloors["choices"])
        assertEquals(44f, atFloors["question"])
        assertTrue("$atFloors", abs((atFloors["panel"] ?: 0f) - 188f) < 0.5f)
    }

    @Test fun aNewPageFitsFromItsOwnHeightsAlone() {
        val first = layOut(240f, listOf(PartSpec("choices", 200, 64, RequestPanelFit.Priority.choices), question))
        assertEquals(64f, first["choices"])
        assertEquals(96f, first["question"])
        // Nothing carries over from the squeezed page before it.
        val second = layOut(
            240f,
            listOf(
                PartSpec("choices", 70, 64, RequestPanelFit.Priority.choices),
                PartSpec("question", 50, 44, RequestPanelFit.Priority.question),
            ),
        )
        assertEquals(70f, second["choices"])
        assertEquals(50f, second["question"])
    }

    @Test fun theCapIsTheRoomLessTheHeaderAndTheComposersChrome() {
        // iPhone 17 Pro, keyboard up: 484 pt of room, a 52 pt header, 154 pt of pills, bar and margins.
        assertEquals(278f, RequestPanelFit.panelCap(room = 484f, header = 52f, chrome = 154f))
        assertEquals(0f, RequestPanelFit.panelCap(room = 100f, header = 52f, chrome = 154f))
        // Unmeasured: no cap.
        assertNull(RequestPanelFit.panelCap(room = 0f, header = 52f, chrome = 154f))
        assertNull(RequestPanelFit.panelCap(room = 484f, header = 52f, chrome = 0f))
    }

    /**
     * The keyboard rising, an interactive dismissal dragging it back down and up, and
     * settling: each frame's panel depends only on that frame's room, so the same room always
     * gives the same panel and nothing oscillates.
     */
    @Test fun thePanelFollowsTheKeyboardWithoutHysteresis() {
        val header = 52f
        val chrome = 254f
        fun commandHeight(room: Float): Float? =
            layOut(RequestPanelFit.panelCap(room = room, header = header, chrome = chrome), listOf(command))["command"]
        val rising = (700 downTo 484 step 24).map { it.toFloat() }
        val dragged = rising.reversed().take(5) + rising.takeLast(5)
        val seen = mutableMapOf<Float, Float>()
        for (room in rising + dragged + listOf(484f)) {
            val height = commandHeight(room)
            seen[room]?.let { earlier -> assertEquals(earlier, height) }
            seen[room] = height ?: -1f
        }
        assertEquals(98f, commandHeight(484f))
        assertEquals(180f, commandHeight(700f))
    }

    /**
     * The composer's chrome is the composer area less the panel's room, the room read from
     * the capsule with and without it in the same frame: as the room animates (or the panel
     * changes height) the chrome holds still, so the cap one frame computes is the cap the
     * next frame computes.
     */
    @Test fun theCapDoesNotDependOnThePanelsOwnHeight() {
        val bar = 60f
        val pills = 94f
        var room = 0f
        while (room <= 356f) {
            val chrome = RequestPanelFit.chrome(composer = pills + bar + room, capsule = bar + room, bar = bar)
            assertEquals(154f, chrome)
            assertEquals(278f, RequestPanelFit.panelCap(room = 484f, header = 52f, chrome = chrome))
            room += 17.8f
        }
        // Unmeasured: no chrome, so no cap.
        assertEquals(0f, RequestPanelFit.chrome(composer = 0f, capsule = 0f, bar = 0f))
    }

    /** One measured height through `Easing` (it mutates). */
    private fun step(easing: RequestPanelFit.Easing, height: Float, cap: Float?, keyboardUp: Boolean): Boolean =
        easing.eases(height, cap, keyboardUp)

    /**
     * The keyboard changes the panel's height without ever reaching its cap: the command's own
     * cap drops 300 → 180 as the bar takes the keyboard. That change, like one the cap made,
     * lands with the room at once; only the panel's own content (arriving, a question
     * expanding) eases.
     */
    @Test fun aKeyboardChangeBelowTheCapFollowsAtOnce() {
        val easing = RequestPanelFit.Easing()
        // Arriving, then the panel's own content growing, keyboard down.
        assertTrue(step(easing, 380f, 600f, keyboardUp = false))
        assertTrue(step(easing, 420f, 600f, keyboardUp = false))
        // The keyboard rises: the command 300 → 180, the room 600 → 400, the panel 420 → 300, nowhere near the cap.
        assertFalse(step(easing, 300f, 400f, keyboardUp = true))
        // The room still settling as the keyboard finishes.
        assertFalse(step(easing, 300f, 384f, keyboardUp = true))
        // A question expanding with the keyboard up and the room still.
        assertTrue(step(easing, 340f, 384f, keyboardUp = true))
        // The keyboard falls: the panel grows back, below the cap.
        assertFalse(step(easing, 460f, 600f, keyboardUp = false))
        // Without a cap (unmeasured), the keyboard alone still counts.
        val uncapped = RequestPanelFit.Easing()
        assertTrue(step(uncapped, 420f, null, keyboardUp = false))
        assertFalse(step(uncapped, 300f, null, keyboardUp = true))
        assertTrue(step(uncapped, 320f, null, keyboardUp = true))
    }

    /** A height the cap set never eases, and nor does the next change after it. */
    @Test fun aCappedHeightFollowsAtOnce() {
        val easing = RequestPanelFit.Easing()
        assertTrue(step(easing, 200f, 400f, keyboardUp = true))
        assertFalse(step(easing, 400f, 400f, keyboardUp = true))
        assertFalse(step(easing, 380f, 400f, keyboardUp = true))
        assertTrue(step(easing, 360f, 400f, keyboardUp = true))
    }
}
