package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
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

/**
 * Test images: a side × side square, transparent outside an inset mark. Needs Robolectric's native
 * graphics.
 */
internal object IconFixture {
    data class Mark(val r: Float, val g: Float, val b: Float, val a: Float)

    fun image(side: Int, mark: Mark = Mark(1f, 0.42f, 0.1f, 1f), fill: Boolean = false, inset: Boolean = true): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(mark.a, mark.r, mark.g, mark.b) }
        val s = side.toFloat()
        if (fill) {
            canvas.drawRect(0f, 0f, s, s, paint)
        } else {
            canvas.drawOval(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f), paint)
            if (!inset) canvas.drawRect(0f, s * 0.9f, s, s, paint)
        }
        return bitmap
    }

    fun png(image: Bitmap): ByteArray = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** An ICO whose frames are PNGs (Vista's format, which Skia's ICO codec reads), in order. */
    fun ico(images: List<Bitmap>): ByteArray {
        val frames = images.map { png(it) }
        val out = ByteArrayOutputStream()
        fun short(value: Int) { out.write(value and 0xFF); out.write((value ushr 8) and 0xFF) }
        fun int(value: Int) { short(value and 0xFFFF); short(value ushr 16) }
        short(0); short(1); short(frames.size)
        var offset = 6 + 16 * frames.size
        for ((index, frame) in frames.withIndex()) {
            val side = images[index].width
            out.write(if (side >= 256) 0 else side); out.write(if (side >= 256) 0 else side)
            out.write(0); out.write(0)
            short(1); short(32)
            int(frame.size); int(offset)
            offset += frame.size
        }
        frames.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun png(side: Int): ByteArray = png(image(side))
}

/** Real decoding and encoding under Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteIconImageTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aLargeEnoughMarkIsKept() {
        val icon = required(SiteIcon.of(IconFixture.png(side = 64)))
        assertEquals(64, icon.image.width)
        assertEquals(64, icon.image.height)
        assertEquals("The cached PNG reads back the same", icon, SiteIcon.of(icon.png))
        val large = required(SiteIcon.of(IconFixture.png(side = 256)))
        assertEquals(SiteIcon.maximumPixels, large.image.width)
    }

    @Test
    fun tinyBlankAndFlatIconsAreRefused() {
        assertNull("16 px is blurry at the card's size", SiteIcon.of(IconFixture.png(side = 16)))
        assertNull(SiteIcon.of(IconFixture.png(side = 31)))
        assertNotNull(SiteIcon.of(IconFixture.png(side = SiteIcon.minimumPixels)))
        assertNull("Fully transparent", SiteIcon.of(IconFixture.png(IconFixture.image(side = 64, mark = IconFixture.Mark(0f, 0f, 0f, 0f)))))
        assertNull("One flat colour", SiteIcon.of(IconFixture.png(IconFixture.image(side = 64, mark = IconFixture.Mark(1f, 1f, 1f, 1f), fill = true))))
        assertNull(SiteIcon.of("<html>not an icon</html>".toByteArray()))
    }

    @Test
    fun theLargestFrameOfAnICOIsUsed() {
        val ico = IconFixture.ico(listOf(IconFixture.image(side = 16), IconFixture.image(side = 48)))
        val icon = required(SiteIcon.of(ico))
        assertEquals(48, icon.image.width)
        assertNull(SiteIcon.of(IconFixture.ico(listOf(IconFixture.image(side = 16)))))
    }

    @Test
    fun marksThatWouldVanishGetATone() {
        val dark = required(SiteIcon.of(IconFixture.png(IconFixture.image(side = 64, mark = IconFixture.Mark(0.05f, 0.05f, 0.05f, 1f)))))
        assertEquals(SiteIcon.Tone.Dark, dark.tone)
        val light = required(SiteIcon.of(IconFixture.png(IconFixture.image(side = 64, mark = IconFixture.Mark(1f, 1f, 1f, 1f)))))
        assertEquals(SiteIcon.Tone.Light, light.tone)
        assertNull(required(SiteIcon.of(IconFixture.png(side = 64))).tone)
    }

    @Test
    fun dataURLsDecode() {
        val base64 = Base64.encodeToString(IconFixture.png(side = 64), Base64.NO_WRAP)
        assertNotNull(SiteIcon.ofDataURL("data:image/png;base64,$base64"))
        assertNull(SiteIcon.ofDataURL("data:text/html;base64,$base64"))
        assertNull("Never a URL to fetch", SiteIcon.ofDataURL("https://example.com/favicon.ico"))
        assertNull(SiteIcon.ofDataURL("data:image/png;base64,${Base64.encodeToString(IconFixture.png(side = 16), Base64.NO_WRAP)}"))
    }
}

/**
 * The `SiteIconStoreTests` case that decodes a real icon from supplied metadata; the rest of that
 * suite runs on the JVM with stub icons ([SiteIconStoreTests]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteIconStoreFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aSuppliedIconWins() = runTest {
        onMain {
            val loads = Locked(listOf<String>())
            val store = LinkPreviewStore(
                client = LinkPreviewClient(
                    cachedIcon = { null },
                    loadIcon = { domain ->
                        loads.update { it + domain }
                        delay(20)
                        SiteIconResult.Missing
                    },
                ),
            )
            val supplied = required(SiteIcon.of(IconFixture.png(side = 48)))
            val blog = LinkPreviewTarget.of("https://blog.example.com/posts/1")!!
            store.supply(
                LinkPreviewMetadata(
                    kind = LinkPreviewMetadata.Kind.Page,
                    title = "Scroll physics",
                    site = "Example Blog",
                    icon = "data:image/png;base64,${Base64.encodeToString(supplied.png, Base64.NO_WRAP)}",
                ),
                url = "https://blog.example.com/posts/1",
            )
            store.appeared(blog, row = "reply-end-a")
            delay(60)
            assertTrue("The icon service isn't asked", loads.current.isEmpty())
            assertEquals(supplied, store.preview(blog).icon)
            store.close()
        }
    }
}

internal fun <T : Any> required(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
}
