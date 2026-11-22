package ai.opencode.feature.transcript.rows

import ai.opencode.feature.media.MermaidDiagramMetrics
import ai.opencode.feature.transcript.markdown.ui.MarkdownImage
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Port of iOS `TranscriptLayoutTests.swift`.
 *
 * Dropped with row growth (deliberate Android divergence, see docs/parity/transcript.md: the
 * bottom-anchored list lays rows out at their full height, so no edge fades in what a growth
 * brings): growthSoftEdgeNeverDimsWhatTheRowAlreadyShowed, growthSoftEdgeWidensWithTheRowAndEasesAway,
 * aPromotionThatTakesTheGrowthOverEasesItsEdgeAway.
 *
 * Dropped (`TranscriptLayoutTests`, UIKit layout probes):
 * - layoutDuringScrollCommandDoesNotRecursivelyScroll (content-offset KVO re-entering
 *   `viewDidLayoutSubviews`; Compose layout passes aren't re-entrant)
 * - reconfiguredTableSettlesAtFractionalOffsets (`UIHostingConfiguration` cell self-sizing depth)
 * - hairlineSnapsWithoutMovingItsMeasurement (`HairlineRule.pixelOffset`; Compose places the
 *   one-pixel rule on whole pixels, so Android has no offset to compute)
 */
class JustifiedImageRowsTests {
    private val width = 402f

    @Test
    fun aLoneImageFillsTheWidthAtItsOwnShape() {
        val rows = JustifiedImageRows(listOf(16f / 9), width)
        assertEquals(1, rows.frames.size)
        assertEquals(width, rows.frames[0].width, 0.01f)
        assertEquals(width * 9 / 16, rows.frames[0].height, 0.01f)
    }

    @Test
    fun aLoneTallScreenshotStopsAtTheCapWithoutCropping() {
        val aspect = 1179f / 2556
        val rows = JustifiedImageRows(listOf(aspect), width)
        val frame = rows.frames[0]
        assertEquals(JustifiedImageRows.singleMaxHeight, frame.height)
        assertEquals("same shape, just smaller", aspect, frame.width / frame.height, 0.001f)
        assertEquals(0f, frame.left)
    }

    @Test
    fun everyImageKeepsItsShapeAndFullRowsFillTheWidth() {
        val aspects = listOf(1f, 1179f / 2556, 4f / 3, 4f, 1f, 0.75f, 16f / 9)
        val rows = JustifiedImageRows(aspects, width)
        assertEquals("nothing hidden", aspects.size, rows.frames.size)
        for ((frame, aspect) in rows.frames.zip(aspects)) {
            assertEquals(aspect, frame.width / frame.height, 0.001f)
            assertTrue(frame.right <= width + 0.01f)
        }
        // Rows: frames sharing a y. Each fills the width, unless capped.
        val byRow = rows.frames.groupBy { (it.top * 100).roundToInt() }
        for (row in byRow.values) {
            val right = row.maxOf { it.right }
            val height = row[0].height
            assertTrue("a row shares one height", row.all { abs(it.height - height) < 0.01f })
            assertTrue(abs(right - width) < 0.01f || height >= width * 0.9f - 0.01f)
        }
        assertEquals(rows.frames.maxOf { it.bottom }, rows.height, 0.01f)
    }

    @Test
    fun aPanoramaTakesARowToItself() {
        // The four images one step read in the image test session.
        val rows = JustifiedImageRows(listOf(4f / 3, 4f, 1f, 0.75f), width)
        val panorama = rows.frames[1]
        assertEquals(width, panorama.width, 0.01f)
        assertEquals("icon and portrait share a row", rows.frames[2].top, rows.frames[3].top)
    }

    @Test
    fun aMediaTileWithoutDimensionsTakesItsShapeOnceLoaded() {
        val hash = "a1".repeat(32)
        val images = listOf(
            OpenCodeImageAttachment(id = "inline"),
            OpenCodeImageAttachment(id = "sized", mediaHash = hash, pixelWidth = 400, pixelHeight = 300),
            OpenCodeImageAttachment(id = "unsized", mediaHash = hash),
        )
        val decoded = listOf(IntSize(1179, 2556), null, null)
        val phone = 1179f / 2556
        val sized = 4f / 3
        val wide = 16f / 9
        val before = ToolImageMosaic.aspects(images, decoded, emptyMap())
        assertEquals(listOf(phone, sized, 1f), before)
        val after = ToolImageMosaic.aspects(images, decoded, mapOf("unsized" to wide, "inline" to 3f))
        assertEquals(listOf(phone, sized, wide), after)
    }

    @Test
    fun aTilesNewShapeRevisesItsRowOnlyWhenTheLayoutChanges() {
        val size = IntSize(1600, 900)
        val wide = 16f / 9
        // Loaded cold, laid out square: remember it and revise the row.
        val cold = ToolImageMosaic.change(size, known = null, laidOutAt = 1f)
        assertEquals(wide, cold.record)
        assertTrue(cold.revisesRow)
        // Shown warm at its shape: remember it for after a purge, nothing moves.
        val warm = ToolImageMosaic.change(size, known = null, laidOutAt = wide)
        assertEquals(wide, warm.record)
        assertFalse(warm.revisesRow)
        // Reloaded after a purge, laid out at the remembered shape: nothing at all.
        val reloaded = ToolImageMosaic.change(size, known = wide, laidOutAt = wide)
        assertNull(reloaded.record)
        assertFalse(reloaded.revisesRow)
        // Reported again before the mosaic re-laid out: already known, nothing moves.
        val again = ToolImageMosaic.change(size, known = wide, laidOutAt = 1f)
        assertNull(again.record)
        assertFalse(again.revisesRow)
        assertNull(ToolImageMosaic.change(IntSize.Zero, known = null, laidOutAt = 1f).record)
    }

    @Test
    fun aMarkdownImageOutsideTheFolderWaitsForATap() {
        val server = OpenCodeServer(url = "https://example.invalid", username = "user", password = "secret")
        val directory = "/work/repo"
        val outside = "/tmp/shots/a.png"
        assertEquals("a.png", MarkdownImage.outsideFile(outside, AgentFileReference.fromLink(outside), server, directory)?.name)
        for (inside in listOf("/work/repo/docs/a.png", "docs/a.png")) {
            assertNull(MarkdownImage.outsideFile(inside, AgentFileReference.fromLink(inside), server, directory))
        }
        val remote = "https://example.com/a.png"
        assertNull(MarkdownImage.outsideFile(remote, AgentFileReference.fromLink(remote), server, directory))
    }

    @Test
    fun markdownImageAndMermaidDiagramReviseRowsOnAsyncHeightChanges() {
        // Cached Markdown images laid out on Frame 0 do not revise the row when the load runs.
        assertFalse(MarkdownImage.revisesRow(hadImage = true, wasFailed = false, nextImage = true, nextFailed = false))
        // Cold placeholder (120 dp) -> loaded image or failed card (48 dp) revises the row.
        assertTrue(MarkdownImage.revisesRow(hadImage = false, wasFailed = false, nextImage = true, nextFailed = false))
        assertTrue(MarkdownImage.revisesRow(hadImage = false, wasFailed = false, nextImage = false, nextFailed = true))
        // Retrying from failed card -> loading placeholder revises the row.
        assertTrue(MarkdownImage.revisesRow(hadImage = false, wasFailed = true, nextImage = false, nextFailed = false))

        // Mermaid cards sized from a prepared diagram do not revise when the render confirms the same size.
        val preloaded = Size(320f, 180f)
        assertFalse(MermaidDiagramMetrics.revisesRow(laidOutSize = preloaded, renderedSize = preloaded))
        // Uncached placeholder or changed diagram dimensions revise the row.
        assertTrue(MermaidDiagramMetrics.revisesRow(laidOutSize = null, renderedSize = preloaded))
        assertTrue(MermaidDiagramMetrics.revisesRow(laidOutSize = preloaded, renderedSize = Size(320f, 240f)))
    }
}
