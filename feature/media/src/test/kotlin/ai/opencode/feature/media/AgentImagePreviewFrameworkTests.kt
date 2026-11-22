package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `AgentImagePreviewTests` (`AgentImagePreviewTests.swift`) case that reads a real JPEG's
 * EXIF orientation through the platform decoder, under Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImagePreviewFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS writes `kCGImagePropertyOrientation` with ImageIO; here `ExifInterface` writes it into the encoded file. */
    private fun jpeg(width: Int, height: Int, orientation: Int): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 149, 0)) }
        val file = temporaryDirectory("agent-image-jpeg").apply { mkdirs() }.resolve("image.jpg")
        try {
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aJPEGsShapeTurnsWithItsOrientation() {
        val upright = jpeg(400, 300, orientation = 1)
        val turned = jpeg(400, 300, orientation = 6)
        assertEquals(AgentImageSize(400.0, 300.0), AgentImageHeader.size(upright, count = minOf(4096, upright.size)))
        assertEquals("shown a quarter turn", AgentImageSize(300.0, 400.0), AgentImageHeader.size(turned, count = minOf(4096, turned.size)))
        assertEquals(AgentImageSize(400.0, 300.0), AgentImageHeader.jpegSize(upright))
    }
}

// The `AgentImageGrowthAndMarkerTests` growth cases (aReportAfterAGrowthEndedNeverJoinsIt,
// anUnmeasuredChangeExpiresOnItsOwn) are dropped: the transcript list no longer grows rows at their
// images (AGENTS.md, Android-native transcript motion), so `AgentImageRowGrowth` is gone.
