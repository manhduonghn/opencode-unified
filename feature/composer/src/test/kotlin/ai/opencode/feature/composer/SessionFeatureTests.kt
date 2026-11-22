package ai.opencode.feature.composer

import ai.opencode.core.testing.TestIsolationRule
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Robolectric native graphics: real PNG encode and `ImageDecoder` decode. A small drawn PNG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun imageImportDecodesImagesAndRejectsOversizedSources() = runBlocking {
        val bitmap = Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 122, 255)) }
        val source = ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        val attachment = ImageAttachmentImporter.process(source)
        assertNotNull(attachment)
        assertEquals("image/jpeg", attachment!!.mime)
        assertTrue(attachment.data.isNotEmpty())

        val oversized = ByteArray(ImageAttachmentImporter.MAX_SOURCE_BYTES + 1)
        assertNull(ImageAttachmentImporter.process(oversized))
    }
}
