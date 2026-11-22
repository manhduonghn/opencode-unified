package ai.opencode.feature.drawer.icons

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeProjectIcon
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectIconRenderTests {
    @get:Rule val folder = TemporaryFolder()

    private fun png(width: Int, height: Int, draw: (Canvas) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun fill(canvas: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) =
        canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun tileLightingIsBrightAtTheTopAndShared() {
        val key = ai.opencode.feature.drawer.list.ProjectTileLighting.Key(side = 20f, radius = 5.5f, scale = 3f, highlight = 0.3f, edgeOpacity = 0.6f)
        val image = checkNotNull(ai.opencode.feature.drawer.list.ProjectTileLighting.image(key))
        assertTrue(ai.opencode.feature.drawer.list.ProjectTileLighting.image(key) === image)
        assertEquals(60, image.width)
        assertEquals(60, image.height)
        val bitmap = image.asAndroidBitmap()
        val alpha = { x: Int, y: Int -> Color.alpha(bitmap.getPixel(x, y)) }
        // Highlight: strong near the top, gone by the bottom.
        assertTrue(alpha(30, 6) > 30)
        assertEquals(0, alpha(30, 50))
        // Edge: brighter on the top row than the bottom row, both drawn.
        assertTrue(alpha(30, 0) > alpha(30, 59))
        assertTrue(alpha(30, 59) > 0)
        // Nothing to draw (Increase Contrast with Reduce Transparency): no bitmap.
        assertNull(
            ai.opencode.feature.drawer.list.ProjectTileLighting.image(
                ai.opencode.feature.drawer.list.ProjectTileLighting.Key(side = 20f, radius = 5.5f, scale = 3f, highlight = 0f, edgeOpacity = 0f),
            ),
        )
    }

    @Test
    fun rasterizesSVGAndClassifiesFullBleedAndTransparentGlyphs() {
        // 1. Full-bleed OpenCode SVG -> fullBleed.
        val openCodeSVG = """
            data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
            <rect width="512" height="512" fill="#131010"/>
            <path d="M320 224V352H192V224H320Z" fill="#5A5858"/>
            <path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/>
            </svg>
        """.trimIndent()
        val fullBleed = ProjectIcon.fromDataURL(openCodeSVG)
        assertNotNull(fullBleed)
        assertEquals(ProjectIcon.Backing.FullBleed, fullBleed!!.backing)
        assertEquals(ProjectIcon.maximumPixels, fullBleed.bitmap.width)

        // 2. Transparent light glyph with offset viewBox (`opencode-ios` logo) -> paddedDark.
        val transparentLightSVG = """
            data:image/svg+xml;utf8,<svg width="820" height="820" viewBox="46 46 420 420" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M320 224V352H192V224H320Z" fill="#5A5858"/>
            <path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/>
            </svg>
        """.trimIndent()
        assertEquals(ProjectIcon.Backing.PaddedDark, ProjectIcon.fromDataURL(transparentLightSVG)?.backing)

        // 3. Transparent dark glyph -> paddedLight.
        val transparentDarkSVG = """
            data:image/svg+xml;utf8,<svg viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path fill-rule="evenodd" clip-rule="evenodd" d="M48 52H16V12H48V52ZM40 20H24V44H40V20Z" fill="#111111"/>
            </svg>
        """.trimIndent()
        assertEquals(ProjectIcon.Backing.PaddedLight, ProjectIcon.fromDataURL(transparentDarkSVG)?.backing)

        // 4. Flat single-color or empty image is rejected so the fallback initial renders instead.
        val flatSVG = """data:image/svg+xml;utf8,<svg viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg"><rect width="32" height="32" fill="#111111"/></svg>"""
        assertNull(ProjectIcon.fromDataURL(flatSVG))
        assertNull(ProjectIcon.decode("not an image".toByteArray()))

        // 5. Non-finite or huge SVG numbers and oversized radii never trap.
        val extremeSVG = """
            data:image/svg+xml;utf8,<svg viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
            <rect x="0" y="0" width="64" height="64" rx="999999" ry="1e999" fill="#0b0c0e"/>
            <circle cx="1e999" cy="NaN" r="1e999" fill="#ffffff"/>
            <path d="M 16 16 L 48 16 L 1e999 48 L 48 48 Z" stroke="#ffffff" stroke-width="6"/>
            </svg>
        """.trimIndent()
        assertNotNull(ProjectIcon.fromDataURL(extremeSVG))

        // 6. Rasters larger than maximumPixels are thumbnailed to it; > 4096 is rejected.
        val png256 = png(256, 256) { canvas ->
            fill(canvas, Color.rgb(26, 51, 77), 0f, 0f, 256f, 256f)
            fill(canvas, Color.WHITE, 64f, 64f, 192f, 192f)
        }
        val thumbnailed = ProjectIcon.decode(png256)
        assertNotNull(thumbnailed)
        assertEquals(ProjectIcon.maximumPixels, thumbnailed!!.bitmap.width)
        assertEquals(ProjectIcon.maximumPixels, thumbnailed.bitmap.height)

        // Patch PNG IHDR width/height (bytes 16..23) to 8192×8192 (> maximumSourceDimension 4096).
        val oversized = png256.copyOf()
        byteArrayOf(0, 0, 0x20, 0, 0, 0, 0x20, 0).copyInto(oversized, 16)
        assertNull(ProjectIcon.decode(oversized))
    }

    @Test
    fun composerWrapperRendersItsLayerAndBackground() {
        // Exact layer and wrapper shape the server emits.
        val layer = """<svg width="820" height="820" viewBox="46 46 420 420" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M320 224V352H192V224H320Z" fill="#5A5858"/><path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/></svg>"""
        val source = "data:image/svg+xml;base64," + b64(layer.toByteArray())
        for (background in listOf(
            """<rect width="128" height="128" rx="28" fill="color(display-p3 0.075 0.063 0.063 / 1)"/>""",
            """<defs><linearGradient id="bg" x2="0" y2="1"><stop offset="0" stop-color="color(srgb 1 0 0 / 1)"/><stop offset="1" stop-color="color(srgb 0 0 1 / 1)"/></linearGradient></defs><rect width="128" height="128" rx="28" fill="url(#bg)"/>""",
        )) {
            val data = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">$background<image width="128" height="128" href="$source"/></svg>""".toByteArray()
            val image = ProjectSVGRasterizer.rasterize(data, 96)
            assertNotNull(image)
            val pixels = IntArray(96 * 96).also { image!!.getPixels(it, 0, 96, 0, 0, 96, 96) }
            // The white logo must survive, not just its flat or gradient backdrop.
            val white = pixels.count { Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 }
            assertTrue("white pixels $white", white > 500)
            if (background.contains("linearGradient")) {
                // Mid-edge samples outside the logo prove both gradient stops are applied.
                val top = pixels[2 * 96 + 48]
                val bottom = pixels[93 * 96 + 48]
                assertTrue(Color.red(top) > Color.blue(top))
                assertTrue(Color.blue(bottom) > Color.red(bottom))
            }
            assertNotNull(ProjectIcon.decode(data))
        }
    }

    @Test
    fun embeddedImagesStayLocalAndBounded() {
        fun wrapper(source: String) = """<svg viewBox="0 0 128 128"><image width="128" height="128" href="$source"/></svg>""".toByteArray()
        for (source in listOf("https://example.com/logo.svg", "file:///etc/passwd", "data:image/svg+xml;base64,invalid")) {
            assertNull(source, ProjectSVGRasterizer.rasterize(wrapper(source), 96))
        }
        var data = """<svg viewBox="0 0 128 128"><rect width="64" height="128" fill="white"/></svg>""".toByteArray()
        repeat(3) { data = wrapper("data:image/svg+xml;base64," + b64(data)) }
        assertNull(ProjectSVGRasterizer.rasterize(data, 96))
        assertNull(ProjectSVGPaint.color("color(srgb nan 0 0 / 1)"))
        assertNull(ProjectSVGPaint.color("color(display-p3 2 0 0 / 1)"))

        // An asymmetric embedded bitmap catches a vertically flipped <image>.
        val png = png(32, 32) { canvas ->
            fill(canvas, Color.RED, 0f, 0f, 32f, 16f)
            fill(canvas, Color.BLUE, 0f, 16f, 32f, 32f)
        }
        val image = ProjectSVGRasterizer.rasterize(wrapper("data:image/png;base64," + b64(png)), 96)
        assertNotNull(image)
        assertTrue(Color.red(image!!.getPixel(48, 10)) > 240)
        assertTrue(Color.blue(image.getPixel(48, 85)) > 240)
    }

    private val cachedSVG = "data:image/svg+xml;base64," + b64(
        """<svg viewBox="0 0 64 64"><rect width="64" height="64" fill="#111111"/><rect x="16" y="16" width="32" height="32" fill="white"/></svg>""".toByteArray(),
    )

    @Test
    fun serverDataURLsCacheLocallyAndRefreshWhenContentChanges() = runTest {
        val directory = folder.newFolder("data-url-icons")
        val fetches = AtomicInteger(0)
        val offline = ProjectIconLoader.Fetch { _, _, _ -> fetches.incrementAndGet(); throw IOException("offline") }
        val loader = ProjectIconLoader(offline, directory, isConstrained = { true }, scope = backgroundScope)
        val client = ProjectIconClient(loader)
        val server = OpenCodeServer(url = "https://example.com", username = "opencode", password = "")
        val old = OpenCodeProject(id = "p", name = "Project", directory = "/code", icon = OpenCodeProjectIcon(url = cachedSVG))
        val request = requireNotNull(old.iconRequest(server))
        val loaded = client.load(request)
        assertNotNull(loaded.icon)
        assertEquals(loaded, client.cached(request))
        val cold = ProjectIconLoader(offline, directory, scope = backgroundScope)
        assertEquals(loaded, cold.load(request))
        val markup = String(requireNotNull(ProjectIcon.decodeDataURL(cachedSVG)), Charsets.UTF_8)
        val changed = old.copy(icon = OpenCodeProjectIcon(url = "data:image/svg+xml;base64," + b64(markup.replace("white", "#00ff00").toByteArray())))
        val next = requireNotNull(changed.iconRequest(server))
        assertNotEquals(request.cacheKey, next.cacheKey)
        assertNotEquals(loaded, client.load(next))
        assertEquals(0, fetches.get())
    }

    @Test
    fun loaderCachesOnDiskCoalescesAndRespectsLowDataMode() = runTest {
        val directory = folder.newFolder("project-icons-test")
        val svgBytes = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
              <rect width="64" height="64" rx="14" fill="#0b0c0e"/>
              <path d="M17 16v32M47 16v32M17 32h30" fill="none" stroke="#f2f2ef" stroke-width="6" stroke-linecap="round"/>
            </svg>
        """.trimIndent().toByteArray()
        val server = OpenCodeServer(
            url = "https://studio.tail.ts.net:4096", username = "opencode", password = "secret-password",
            headers = listOf(OpenCodeServer.Header("CF-Access-Client-Id", "id-1")),
        )
        val request = ProjectIconRequest(server = server, projectID = "prj_hex", source = "/assets/hex.svg?v=hash1")
        val fetchCount = AtomicInteger(0)
        var constrained = true
        val capturedAuth = AtomicReference<String?>(null)
        val capturedHeader = AtomicReference<String?>(null)
        val loader = ProjectIconLoader(
            fetch = { urlRequest, _, isServerRoute ->
                fetchCount.incrementAndGet()
                capturedAuth.set(urlRequest.header("Authorization"))
                capturedHeader.set(urlRequest.header("CF-Access-Client-Id"))
                assertTrue(isServerRoute)
                delay(20)
                ProjectIconLoader.FetchResponse(svgBytes, 200, Headers.headersOf("Content-Type", "image/svg+xml"))
            },
            directory = directory,
            now = { Instant.ofEpochSecond(1_700_000_000) },
            isConstrained = { constrained },
            scope = backgroundScope,
        )

        // Under Low Data Mode, network fetches defer without hitting the network or caching `missing`.
        assertEquals(ProjectIconResult.Deferred, loader.load(request))
        assertEquals(0, fetchCount.get())
        assertNull(loader.cached(request))

        // Once unconstrained, concurrent loads coalesce into one authenticated server request.
        constrained = false
        val first = async { loader.load(request) }
        val second = async { loader.load(request) }
        val r1 = first.await()
        val r2 = second.await()
        assertNotNull(r1.icon)
        assertEquals(r1, r2)
        assertEquals(1, fetchCount.get())
        assertNotNull(capturedAuth.get())
        assertEquals("id-1", capturedHeader.get())
        assertNotNull(loader.cached(request)?.icon)

        // A fresh loader over the same disk directory reads from disk without fetching again.
        val coldLoader = ProjectIconLoader(
            fetch = { _, _, _ -> fetchCount.incrementAndGet(); throw IOException("offline") },
            directory = directory,
            now = { Instant.ofEpochSecond(1_700_000_100) },
            scope = backgroundScope,
        )
        assertEquals(r1, coldLoader.load(request))
        assertEquals(1, fetchCount.get())

        // External URLs never receive the server's Authorization or custom headers, and
        // scheme-relative `//host` URLs are rejected outright.
        val externalBuilt = ProjectIconLoader.buildURLRequest("https://opencode.ai/favicon.svg", server)
        assertEquals(false, externalBuilt?.second)
        assertNull(externalBuilt?.first?.header("Authorization"))
        assertNull(externalBuilt?.first?.header("CF-Access-Client-Id"))
        assertNull(ProjectIconLoader.buildURLRequest("//evil.example.com/icon.svg", server))
        val schemeRelativeReq = ProjectIconRequest(server = server, projectID = "prj_1", source = "//evil.example.com/icon.svg?v=1")
        assertFalse(schemeRelativeReq.isVersioned)
        assertEquals("//evil.example.com/icon.svg?v=1", schemeRelativeReq.resolvedURLString)

        // A cached `missing` (e.g. 404) is answered synchronously by `cached`, so the drawer's
        // project tile doesn't start a load on every appearance.
        val missingLoader = ProjectIconLoader(
            fetch = { _, _, _ -> fetchCount.incrementAndGet(); ProjectIconLoader.FetchResponse(ByteArray(0), 404, Headers.headersOf()) },
            directory = directory,
            now = { Instant.ofEpochSecond(1_700_000_150) },
            scope = backgroundScope,
        )
        val missingRequest = ProjectIconRequest(server = server, projectID = "prj_404", source = "/assets/missing.svg?v=none")
        val before404 = fetchCount.get()
        assertEquals(ProjectIconResult.Missing, missingLoader.load(missingRequest))
        assertEquals(before404 + 1, fetchCount.get())
        assertEquals(ProjectIconResult.Missing, missingLoader.cached(missingRequest))

        // Built-in OpenCode project IDs resolve without network access even when constrained.
        constrained = true
        val beforeOpenCode = fetchCount.get()
        val openCodeRequest = requireNotNull(
            OpenCodeProject(id = "012780c4098d08caa4ea8c479ed0a4690489f38d", name = "opencode", directory = "/code/opencode").iconRequest(server),
        )
        assertNotNull(loader.load(openCodeRequest).icon)
        assertEquals(beforeOpenCode, fetchCount.get())

        // Unversioned image URLs (`private, no-cache` + `ETag`) send `If-None-Match` and reuse on 304.
        constrained = false
        val ifNoneMatch = AtomicReference<String?>(null)
        val unversionedCalls = AtomicInteger(0)
        val etagLoader = ProjectIconLoader(
            fetch = { urlRequest, _, _ ->
                val call = unversionedCalls.incrementAndGet()
                ifNoneMatch.set(urlRequest.header("If-None-Match"))
                if (call == 1) {
                    ProjectIconLoader.FetchResponse(
                        svgBytes, 200,
                        Headers.headersOf("Content-Type", "image/svg+xml", "ETag", "\"etag-v1\"", "Cache-Control", "private, no-cache"),
                    )
                } else {
                    ProjectIconLoader.FetchResponse(ByteArray(0), 304, Headers.headersOf("ETag", "\"etag-v1\"", "Cache-Control", "private, no-cache"))
                }
            },
            directory = directory,
            now = { Instant.ofEpochSecond(1_700_000_200) },
            scope = backgroundScope,
        )
        val unversionedRequest = ProjectIconRequest(server = server, projectID = "prj_unversioned", source = "/assets/unversioned.svg")
        val firstUnversioned = etagLoader.load(unversionedRequest)
        assertNotNull(firstUnversioned.icon)
        assertNull(ifNoneMatch.get())
        val secondUnversioned = etagLoader.load(unversionedRequest)
        assertEquals(firstUnversioned, secondUnversioned)
        assertEquals("\"etag-v1\"", ifNoneMatch.get())
        assertEquals(2, unversionedCalls.get())
    }
}
