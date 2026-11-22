package ai.opencode.feature.media

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Android passes pixel sizes with `displayScale = 1`; the geometry is unit-free. */
class MediaZoomGeometryTests {
    @get:Rule val isolation = TestIsolationRule()

    private val page = Size(440f, 956f)

    /** CGPoint equality: `-0` equals `0` (Compose's `Offset` compares bits). */
    private fun assertPoint(expected: Offset, actual: Offset) {
        assertTrue("expected $expected but was $actual", expected.x == actual.x && expected.y == actual.y)
    }

    private fun close(a: Size, b: Size): Boolean = abs(a.width - b.width) < 0.001f && abs(a.height - b.height) < 0.001f

    @Test
    fun movieSurfaceAndItsReportedFrameStayTogetherThroughZoomAndRotation() {
        // No poster/Bitmap is needed to lay out the decoded 16:9 surface.
        val zoom = MediaZoomState(Size(1920f, 1080f))
        val frames = mutableListOf<Rect>()
        zoom.onFrame = { frames.add(it) }
        zoom.layout(Size(320f, 640f))
        assertTrue(close(Size(320f, 180f), frames.last().size))
        assertPoint(Offset(160f, 320f), frames.last().center)

        zoom.pinch(2f, Offset(160f, 320f), Offset.Zero)
        assertTrue(close(Size(640f, 360f), frames.last().size))
        zoom.layout(Size(640f, 320f))
        assertEquals(2f, zoom.zoomScale, 0f)
        assertEquals(zoom.imageFrame, frames.last())
        assertPoint(Offset(320f, 160f), frames.last().center)
    }

    @Test
    fun fitsTheWholeImageInsideThePage() {
        // Landscape: as wide as the page.
        assertTrue(close(MediaZoomGeometry.fittedSize(Size(120f, 90f), page), Size(440f, 330f)))
        // A tall screenshot: as tall as the page.
        assertTrue(close(MediaZoomGeometry.fittedSize(Size(1000f, 4000f), page), Size(239f, 956f)))
        assertEquals(Size.Zero, MediaZoomGeometry.fittedSize(Size.Zero, page))
        assertEquals(Size.Zero, MediaZoomGeometry.fittedSize(Size(10f, 10f), Size.Zero))
    }

    @Test
    fun centresContentSmallerThanThePage() {
        val insets = MediaZoomGeometry.centeringInsets(content = Size(440f, 330f), bounds = page)
        assertEquals(MediaInsets(top = 313f, left = 0f, bottom = 313f, right = 0f), insets)
        // Zoomed past the page along both axes: no insets, the edges are the image's.
        assertEquals(MediaInsets.Zero, MediaZoomGeometry.centeringInsets(content = Size(1300f, 1000f), bounds = page))
        // Pinched below fit, it stays centred.
        val pinched = MediaZoomGeometry.centeringInsets(content = Size(220f, 165f), bounds = page)
        assertEquals(MediaInsets(top = 395.5f, left = 110f, bottom = 395.5f, right = 110f), pinched)
    }

    @Test
    fun zoomsFarEnoughToSeeThePixels() {
        // A 12 MP photo on a 3× screen: one image pixel per screen pixel at
        // about 3×, so it zooms to twice that.
        val fitted = Size(440f, 330f)
        assertTrue(abs(MediaZoomGeometry.maximumScale(pixelSize = Size(4032f, 3024f), fitted = fitted, displayScale = 3f) - 6.109f) < 0.01f)
        // A small image still zooms a useful way.
        assertEquals(MediaZoomGeometry.smallestMaximumScale, MediaZoomGeometry.maximumScale(pixelSize = Size(120f, 90f), fitted = fitted, displayScale = 3f))
        assertEquals(MediaZoomGeometry.smallestMaximumScale, MediaZoomGeometry.maximumScale(pixelSize = Size(120f, 90f), fitted = Size.Zero, displayScale = 3f))
    }

    @Test
    fun doubleTapFillsThePageOrZoomsFurther() {
        // Bars above and below: zoom until it fills the page's height.
        val landscape = MediaZoomGeometry.doubleTapScale(fitted = Size(440f, 330f), bounds = page, maximum = 6f)
        assertTrue(abs(landscape - 956f / 330f) < 0.001f)
        // Already fills it: zoom in a set amount.
        assertEquals(MediaZoomGeometry.doubleTapScale, MediaZoomGeometry.doubleTapScale(fitted = Size(440f, 900f), bounds = page, maximum = 6f))
        // Never past the maximum.
        assertEquals(6f, MediaZoomGeometry.doubleTapScale(fitted = Size(440f, 40f), bounds = page, maximum = 6f))
    }

    @Test
    fun doubleTapZoomsAboutTheTapKeptInsideTheImage() {
        val content = Size(440f, 900f)
        // In the middle: centred on the tap.
        val middle = MediaZoomGeometry.zoomRect(Offset(220f, 450f), scale = 2f, bounds = page, content = content)
        assertEquals(Rect(Offset(110f, 211f), Size(220f, 478f)), middle)
        // Near a corner: slid back inside, so no empty space shows.
        val corner = MediaZoomGeometry.zoomRect(Offset(10f, 890f), scale = 2f, bounds = page, content = content)
        assertEquals(Rect(Offset(0f, 422f), Size(220f, 478f)), corner)
        // Taller than the image at that scale: centred on the image vertically.
        val short = MediaZoomGeometry.zoomRect(Offset(400f, 10f), scale = 2f, bounds = page, content = Size(440f, 330f))
        assertEquals(Rect(Offset(220f, -74f), Size(220f, 478f)), short)
    }

    @Test
    fun keepsAPointCentredWithinTheEdges() {
        val content = Size(1320f, 990f)
        val offset = MediaZoomGeometry.contentOffset(Offset(660f, 495f), bounds = page, content = content, insets = MediaInsets.Zero)
        assertPoint(Offset(440f, 17f), offset)
        // A point near an edge can't pull empty space in.
        val edge = MediaZoomGeometry.contentOffset(Offset(1300f, 0f), bounds = page, content = content, insets = MediaInsets.Zero)
        assertPoint(Offset(880f, 0f), edge)
        // Narrower than the page: held by its centring inset.
        val narrow = MediaZoomGeometry.contentOffset(
            Offset(0f, 500f),
            bounds = page,
            content = Size(300f, 1200f),
            insets = MediaInsets(top = 0f, left = 70f, bottom = 0f, right = 70f),
        )
        assertPoint(Offset(-70f, 22f), narrow)
    }

    @Test
    fun handsOffToThePagerOnlyFromTheEdgeItIsAt() {
        val content = Size(1320f, 990f)
        fun handsOff(x: Float, translation: Offset): Boolean =
            MediaZoomGeometry.handsOffToPager(offset = Offset(x, 0f), content = content, bounds = page, insets = MediaInsets.Zero, translation = translation)
        // At the left edge, dragging right turns back a page; dragging left pans.
        assertTrue(handsOff(0f, Offset(12f, 2f)))
        assertFalse(handsOff(0f, Offset(-12f, 2f)))
        // At the right edge, the other way round.
        assertTrue(handsOff(880f, Offset(-12f, 0f)))
        assertFalse(handsOff(880f, Offset(12f, 0f)))
        // In between, or mostly vertical, it pans.
        assertFalse(handsOff(400f, Offset(12f, 0f)))
        assertFalse(handsOff(0f, Offset(4f, 12f)))
        // Narrower than the page, it's at both edges.
        val narrow = MediaInsets(top = 0f, left = 70f, bottom = 0f, right = 70f)
        assertTrue(MediaZoomGeometry.handsOffToPager(offset = Offset(-70f, 0f), content = Size(300f, 1200f), bounds = page, insets = narrow, translation = Offset(-10f, 0f)))
        assertTrue(MediaZoomGeometry.handsOffToPager(offset = Offset(-70f, 0f), content = Size(300f, 1200f), bounds = page, insets = narrow, translation = Offset(10f, 0f)))
    }
}

class FullscreenViewerFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun controlsStartShownAndToggleOnTap() = runTest {
        val preview = TranscriptImagePreview(id = "img-1", image = stubBitmap(), label = "Tool image 1 of 1")
        val haptics = Locked(listOf<Haptic>())
        val store = TestStore(FullscreenViewerFeature.State(preview), FullscreenViewerFeature(), this) {
            this[HapticsClient] = HapticsClient(play = { haptic -> haptics.update { it + haptic } })
        }

        assertTrue(store.state.isShowingControls)
        store.send(FullscreenViewerFeature.Action.ImageTapped) { it.copy(isShowingControls = false) }
        store.send(FullscreenViewerFeature.Action.ImageTapped) { it.copy(isShowingControls = true) }
        store.send(FullscreenViewerFeature.Action.CloseTapped) { it.copy(isDismissed = true) }
        store.finish()
        assertTrue(haptics.current.isEmpty())
    }

    @Test
    fun savingPlaysSuccessOrErrorHaptics() = runTest {
        val first = stubBitmap()
        val second = stubBitmap()
        val preview = TranscriptImagePreview(
            id = "img-1",
            pages = listOf(
                TranscriptImagePreview.Page(id = "img-1", image = first, label = "Tool image 1 of 2"),
                TranscriptImagePreview.Page(id = "img-2", image = second, label = "Tool image 2 of 2"),
            ),
        )
        val savedImages = Locked(listOf<android.graphics.Bitmap>())
        val sharedLabels = Locked(listOf<String>())
        val haptics = Locked(listOf<Haptic>())
        val shouldFail = Locked(false)

        val store = TestStore(FullscreenViewerFeature.State(preview), FullscreenViewerFeature(), this) {
            this[HapticsClient] = HapticsClient(play = { haptic -> haptics.update { it + haptic } })
            this[PhotoSaveClient] = PhotoSaveClient(saveImage = { image ->
                if (shouldFail.current) throw PhotoSaveClientError.Denied
                savedImages.update { it + image }
            })
            this[ImageShareClient] = ImageShareClient(share = { _, label -> sharedLabels.update { it + label } })
        }

        assertEquals(null, store.state.selectedPage?.displayTitle)
        store.send(FullscreenViewerFeature.Action.PageSelected("img-2")) { it.copy(selectedID = "img-2") }
        assertEquals("lab-iso.png", TranscriptImagePreview.Page(id = "file-1", image = first, label = "lab-iso.png").displayTitle)
        assertEquals(null, TranscriptImagePreview.Page(id = "diag-1", image = first, label = "Diagram, Flowchart").displayTitle)

        store.send(FullscreenViewerFeature.Action.SaveTapped) { it.copy(isSaving = true) }
        store.receive(FullscreenViewerFeature.Action.SaveFinished(true)) { it.copy(isSaving = false) }
        assertEquals(1, savedImages.current.size)
        assertTrue(savedImages.current.first() === second)
        assertEquals(listOf(Haptic.success), haptics.current)

        shouldFail.set(true)
        store.send(FullscreenViewerFeature.Action.SaveTapped) { it.copy(isSaving = true) }
        store.receive(FullscreenViewerFeature.Action.SaveFinished(false)) { it.copy(isSaving = false) }
        assertEquals(listOf(Haptic.success, Haptic.error), haptics.current)

        store.send(FullscreenViewerFeature.Action.ShareTapped())
        store.finish()
        assertEquals(listOf("Tool image 2 of 2"), sharedLabels.current)
    }
}
