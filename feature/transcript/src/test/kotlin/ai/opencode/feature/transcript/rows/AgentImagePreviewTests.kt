package ai.opencode.feature.transcript.rows

import ai.opencode.feature.media.AgentImagePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Points → dp. */
class AgentImagePreviewTests {
    private val loneMaxHeight = AgentImagePlan.LONE_MAX_HEIGHT.value

    @Test
    fun aLoneNamedImageStopsAt360Points() {
        val width = 408f
        val square = JustifiedImageRows(listOf(1f), width, loneMaxHeight)
        assertEquals(360f, square.frames[0].width)
        assertEquals(360f, square.frames[0].height)
        assertEquals("leading, like a tall tool image", 0f, square.frames[0].left)
        val wide = JustifiedImageRows(listOf(16f / 9), width, loneMaxHeight)
        assertTrue(abs(wide.frames[0].width - width) < 0.01f)
        // Several share rows exactly as tool images do.
        val several = JustifiedImageRows(listOf(4f / 3, 16f / 9, 1.5f), width, loneMaxHeight)
        assertEquals(JustifiedImageRows(listOf(4f / 3, 16f / 9, 1.5f), width).frames, several.frames)
    }

    @Test
    fun aStrangeShapeIsShownWholeInItsTile() {
        // Its tile stops at the rows' range, so filling it would crop.
        val rows = JustifiedImageRows(listOf(0.1f), 400f, loneMaxHeight)
        assertTrue(abs(rows.frames[0].width / rows.frames[0].height - JustifiedImageRows.aspectRange.start) < 0.001f)
    }
}
