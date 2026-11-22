package ai.opencode.feature.session

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ImageAttachmentImporter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import ai.opencode.feature.composer.SharedImageEncoder as ComposerImageEncoder
import ai.opencode.feature.system.share.SharedImageEncoder as ShareImageEncoder

/**
 * An image shared through the share sheet and the same image imported in the composer become the
 * same bytes. Android has two copies of the encoder (`:feature:system`'s for the share sheet,
 * `:feature:composer`'s for imports); `:feature:session` is the lowest module that sees both.
 * Robolectric native graphics (real `ImageDecoder`/`Bitmap` codecs, `ExifInterface`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImageEncoderTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A 3000×2000 JPEG tagged as rotated, with camera and GPS metadata. */
    private fun cameraPhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(51, 102, 204))
        val file = File.createTempFile("camera-", ".jpg")
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MAKE, "TestCam")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,19/1,48/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    @Test
    fun shareReencodingMatchesTheComposerAndStripsMetadata() = runBlocking {
        val original = cameraPhoto()
        val shared = ShareImageEncoder.jpeg(original)
        assertNotNull(shared)
        val composer = ImageAttachmentImporter.process(original)
        assertNotNull(composer)
        assertArrayEquals(shared, composer!!.data)
        assertEquals(ShareImageEncoder.MIME, composer.mime)
        assertEquals(ShareImageEncoder.MAX_SOURCE_BYTES, ComposerImageEncoder.MAX_SOURCE_BYTES)
    }
}
