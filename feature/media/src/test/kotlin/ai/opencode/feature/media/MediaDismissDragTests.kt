package ai.opencode.feature.media

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class MediaDismissDragTests {
    @get:Rule val isolation = TestIsolationRule()

    private val drag = MediaDismissDrag(screenHeight = 900f)

    @Test
    fun shrinksAndRevealsTheSessionAsItIsPulledDown() {
        assertEquals(1f, drag.scale(0f))
        assertEquals(1f, drag.backdropAlpha(0f))
        assertEquals(0.8f, drag.scale(225f), 0.00001f)
        assertEquals(0.5f, drag.backdropAlpha(225f))
        assertEquals(MediaDismissDrag.minimumScale, drag.scale(450f), 0.00001f)
        assertEquals(0f, drag.backdropAlpha(450f))
        // Past the range it holds; above the start it doesn't grow.
        assertEquals(MediaDismissDrag.minimumScale, drag.scale(2000f), 0.00001f)
        assertEquals(1f, drag.scale(-100f))
        assertEquals(1f, drag.backdropAlpha(-100f))
    }

    @Test
    fun roundsTowardTheThumbnailWithTheScale() {
        assertEquals(0f, drag.cornerRadius(0f, toward = 14f))
        assertEquals(7f, drag.cornerRadius(225f, toward = 14f))
        assertEquals(14f, drag.cornerRadius(450f, toward = 14f))
        assertEquals(14f, drag.cornerRadius(2000f, toward = 14f))
        assertEquals(0f, drag.cornerRadius(-50f, toward = 14f))
        // The radius follows the scale, so where the image is, its shape is too.
        for (distance in listOf(0f, 60f, 180f, 300f, 450f)) {
            val shrunk = (1 - drag.scale(distance)) / (1 - MediaDismissDrag.minimumScale)
            assertTrue(abs(drag.cornerRadius(distance, toward = 20f) - 20 * shrunk) < 0.0001f)
        }
    }

    @Test
    fun aCaughtSpringBackContinuesFromItsScale() {
        for (distance in listOf(0f, 90f, 300f, 450f)) {
            assertTrue("$distance", abs(drag.distance(atScale = drag.scale(distance)) - distance) < 0.001f)
        }
    }

    @Test
    fun letsGoPastTheDistanceOrWithAFlickDismisses() {
        assertFalse(MediaDismissDrag.dismisses(distance = 70f, velocity = 0f))
        assertTrue(MediaDismissDrag.dismisses(distance = 200f, velocity = 0f))
        assertTrue(MediaDismissDrag.dismisses(distance = 40f, velocity = 1200f))
        assertFalse("Flicked back up, it stays", MediaDismissDrag.dismisses(distance = 200f, velocity = -600f))
    }

    /** The rule they apply is [MediaPresenter.isDarkRoom]. */
    @Test
    fun nonMediaViewerInheritsWindowAppearanceWhileMediaForcesDark() {
        assertTrue(MediaPresenter.isDarkRoom(isMedia = true))
        assertFalse(MediaPresenter.isDarkRoom(isMedia = false))
    }

    @Test
    fun viewerControlsHideFromAccessibilityUntilRevealedOrVoiceOverRuns() = runTest {
        assertFalse(controlsAreVisible(isShowingControls = false, voiceOver = false, chromeHidden = false))
        assertTrue(controlsAreVisible(isShowingControls = true, voiceOver = false, chromeHidden = false))
        assertTrue(controlsAreVisible(isShowingControls = false, voiceOver = true, chromeHidden = false))
        assertFalse(controlsAreVisible(isShowingControls = true, voiceOver = true, chromeHidden = true))

        val preview = TranscriptImagePreview(id = "img-1", image = stubBitmap(), label = "shot.png")
        val store = TestStore(FullscreenViewerFeature.State(preview), FullscreenViewerFeature(), this)
        store.send(FullscreenViewerFeature.Action.ImageTapped) { it.copy(isShowingControls = false) }
        store.send(FullscreenViewerFeature.Action.CloseTapped) { it.copy(isDismissed = true) }
        store.finish()
    }
}
