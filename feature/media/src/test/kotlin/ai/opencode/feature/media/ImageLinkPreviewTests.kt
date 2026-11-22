package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * The link policy, the read queue and [ImageLinkLoader]. Runs under Robolectric: bare-URL detection
 * reads the framework's `Patterns.WEB_URL`, and the loader decodes real PNGs (native graphics) into
 * the pixel cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLinkPreviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun links(blocks: List<LinkTextBlock>, growing: Boolean = false): List<String> {
        val plan = ImageLinkPolicy.plan(blocks, growing = growing)
        return blocks.flatMap { plan[it.id] ?: emptyList() }
    }

    private fun text(value: String) = listOf(LinkBlocks.paragraph(0, LinkBlocks.text(value)))

    @Test
    fun detectsBareMarkdownQueryAndUppercaseImages() {
        for (suffix in listOf("png", "jpg", "jpeg", "gif", "webp", "PNG", "JpEg")) {
            val url = "https://example.com/photo.$suffix?width=800#detail"
            assertEquals(listOf(url), links(text(url)))
            assertEquals(listOf(url), links(listOf(LinkBlocks.paragraph(0, LinkBlocks.link("screenshot", url)))))
        }
        assertTrue(links(text("https://example.com/movie.mp4 https://example.com/page.html https://example.com/readme.pdf")).isEmpty())
        assertTrue(links(listOf(LinkBlocks.paragraph(0, LinkBlocks.code("https://example.com/photo.png")))).isEmpty())
        // `![image](…)` is an image block, not prose.
        assertTrue(links(listOf(LinkBlocks.other(0))).isEmpty())
    }

    @Test
    fun optInAllowsTailnetHTTPSButKeepsMetadataPolicyUnchanged() {
        val url = "https://studio.tail1234.ts.net:8444/shot.png?width=800"
        assertTrue(ImageLinkPolicy.allows(url))
        assertFalse(LinkPreviewPolicy.isPreviewable(url))
        for (source in listOf(
            "http://studio.tail1234.ts.net/shot.png", "https://127.0.0.1/a.png", "https://nas.local/a.png",
            "https://user:pass@example.com/a.png", "https://example.com/a.png?token=secret",
            "https://studio.tail1234.ts.net/a.png?signature=secret",
        )) {
            assertFalse(source, ImageLinkPolicy.allows(source))
        }
    }

    @Test
    fun completedLinkAndBlockIdentitySurviveEveryLaterDelta() {
        assertTrue(links(text("https://example.com/shot.png"), growing = true).isEmpty())
        assertTrue(links(text("https://example.com/shot.png?width=1"), growing = true).isEmpty())
        assertEquals(1, links(text("https://example.com/shot.png done"), growing = true).size)
    }

    @Test
    fun boundsAndDeduplicatesLinksAcrossTheMessage() {
        val runs = (0 until 8).flatMap { listOf(LinkBlocks.link("image", "https://example.com/$it.png"), LinkBlocks.text(" ")) }
        assertEquals(3, links(listOf(LinkBlocks.paragraph(0, *runs.toTypedArray()))).size)
        assertEquals(
            1,
            links(listOf(LinkBlocks.paragraph(0, LinkBlocks.link("a", "https://example.com/a.png"), LinkBlocks.text(" "), LinkBlocks.link("b", "https://example.com/a.png")))).size,
        )
        assertEquals(3, links((0 until 8).map { LinkBlocks.paragraph(it, LinkBlocks.link("image", "https://example.com/$it.png")) }).size)
        val headed = listOf(
            LinkBlocks.paragraph(0, LinkBlocks.link("a", "https://example.com/a.png")),
            LinkBlocks.paragraph(1, LinkBlocks.link("same", "https://example.com/a.png")),
            LinkBlocks.paragraph(2, LinkBlocks.text("https://example.com/b.png")),
        )
        assertEquals(listOf("a.png", "b.png"), links(headed).map { it.substringAfterLast('/') })
        assertTrue(links(text("example.com/a.png")).isEmpty())
        assertEquals(listOf("b.png"), links(text("example.com/a.png and https://example.com/b.png")).map { it.substringAfterLast('/') })
    }

    /** Android checks the response before reading its body. */
    @Test
    fun rejectsMissingOrNonImageMIMEBeforeReceivingTheBody() {
        val request = Request.Builder().url("https://example.com/shot.png").build()
        for (mime in listOf(null, "text/html", "application/octet-stream", "image/png", "IMAGE/JPEG")) {
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("")
            mime?.let { builder.header("Content-Type", it) }
            val response = builder.body(ByteArray(0).toResponseBody(null)).build()
            assertEquals("$mime", mime?.lowercase()?.startsWith("image/") == true, AgentImageDownload.isImageResponse(response))
        }
    }

    private fun file(data: ByteArray) = AgentImageResponse.File(data, AgentImageValidators())

    @Test
    fun cachesCoalescesAndKeepsFailuresFromRefetchingOnDeltas() = runTest {
        val image = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val data = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val reads = Locked(0)
        val loader = ImageLinkLoader(DecodedImageCache(), fetch = { request, _ ->
            reads.update { it + 1 }
            assertFalse(request.allowsConstrainedNetworkAccess)
            assertNull(request.request.header("Authorization"))
            if (request.request.url.pathSegments.last() == "bad.png") throw AgentImageDownload.FailureException(AgentImageDownload.Failure.NotImage)
            file(data)
        })
        val url = "https://example.com/shot.png"
        val one = async { loader.load(url) }
        val two = async { loader.load(url) }
        assertTrue(one.await() != null && two.await() != null)
        assertNotNull(loader.load(url))
        assertEquals(1, reads.current)
        val bad = "https://example.com/bad.png"
        repeat(10) { assertNull(loader.load(bad)) }
        assertEquals(2, reads.current)
    }

    private val imageData: ByteArray get() = LinkPreviewFixtures.iconPNG(side = 16, letter = "I")

    @Test
    fun offscreenCancellationStopsTransportAndUnblocksNextURL() = runTest {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val cancelled = Locked(false)
        val reads = Locked(0)
        val image = imageData
        val loader = ImageLinkLoader(DecodedImageCache(), fetch = { request, _ ->
            val attempt = reads.withValue { (it + 1) to (it + 1) }
            if (request.request.url.pathSegments.last() == "slow.png" && attempt == 1) {
                try {
                    started.send(Unit)
                    delay(30_000)
                    fail("Off-screen image transport was not cancelled")
                } catch (error: CancellationException) {
                    cancelled.set(true)
                    throw error
                }
            }
            file(image)
        })
        val slow = "https://example.com/slow.png"
        val first = async { loader.load(slow) }
        started.receive()
        val next = async { loader.load("https://other.example.com/next.png") }
        first.cancel() // Same cancellation the visibility-keyed effect receives.
        assertNull(runCatching { first.await() }.getOrNull())
        assertNotNull(next.await())
        assertTrue(cancelled.current)
        assertNotNull("Cancellation must not enter the failure cache", loader.load(slow))
        assertEquals(3, reads.current)
    }

    @Test
    fun cancellingOneCoalescedWaiterKeepsTheOtherAndReplaysHeader() = runTest {
        val header = AgentImageSize(640.0, 480.0)
        val firstHeader = Channel<AgentImageSize>(Channel.UNLIMITED)
        val secondHeader = Channel<AgentImageSize>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val cancelled = Locked(false)
        val reads = Locked(0)
        val image = imageData
        val loader = ImageLinkLoader(DecodedImageCache(), fetch = { _, report ->
            reads.update { it + 1 }
            try {
                report(header)
                release.await()
                file(image)
            } catch (error: CancellationException) {
                cancelled.set(true)
                throw error
            }
        })
        val url = "https://example.com/shared.png"
        val first = async { loader.load(url, onSize = { firstHeader.trySend(it) }) }
        assertEquals(header, firstHeader.receive())
        val second = async { loader.load(url, onSize = { secondHeader.trySend(it) }) }
        assertEquals(header, secondHeader.receive())
        first.cancel()
        assertNull(runCatching { first.await() }.getOrNull())
        assertFalse(cancelled.current)
        release.complete(Unit)
        assertNotNull(second.await())
        assertEquals(1, reads.current)
    }

    @Test
    fun cancellingAQueuedReadReturnsBeforeTheActiveReadFinishes() = runTest {
        val queue = CoalescedReadQueue<Int, Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            queue.load("first", report = {}) {
                started.complete(Unit)
                release.await()
                1
            }
        }
        started.await()
        val second = async {
            queue.load("second", report = {}) {
                fail("Cancelled queued read reached the transport")
                2
            }
        }
        // Let it queue behind the active read first.
        yield()
        second.cancel()
        try {
            second.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }
        release.complete(Unit)
        assertEquals(1, first.await())
    }

    @Test
    fun constrainedRefusalRetriesOnTheNextAppearance() = runTest {
        val reads = Locked(0)
        val image = imageData
        val loader = ImageLinkLoader(DecodedImageCache(), fetch = { _, _ ->
            if (reads.withValue { (it + 1) to (it + 1) } == 1) throw AgentImageDownload.FailureException(AgentImageDownload.Failure.Constrained)
            file(image)
        })
        val url = "https://example.com/retry.png"
        assertNull(loader.load(url))
        assertNotNull(loader.load(url))
        assertEquals(2, reads.current)
    }

    @Test
    fun deferralWhileQueuedDoesNotCacheAFailure() = runTest {
        val checks = Locked(0)
        val shouldDefer = Locked(false)
        val started = Channel<Unit>(Channel.UNLIMITED)
        val queued = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val image = imageData
        val reads = Locked(0)
        val loader = ImageLinkLoader(
            DecodedImageCache(),
            deferred = {
                val count = checks.withValue { (it + 1) to (it + 1) }
                // First load checks before enqueuing and before transport. Second load
                // enters normally, but the path becomes constrained while it queues.
                if (count == 3) {
                    shouldDefer.set(true)
                    queued.trySend(Unit)
                    false
                } else {
                    shouldDefer.current
                }
            },
            fetch = { request, _ ->
                reads.update { it + 1 }
                if (request.request.url.pathSegments.last() == "first.png") {
                    started.send(Unit)
                    release.await()
                }
                file(image)
            },
        )
        val first = async { loader.load("https://example.com/first.png") }
        started.receive()
        val url = "https://example.com/queued.png"
        val second = async { loader.load(url) }
        queued.receive()
        release.complete(Unit)
        assertNotNull(first.await())
        assertNull(second.await())
        shouldDefer.set(false)
        assertNotNull(loader.load(url))
        assertEquals(2, reads.current)
    }

    @Test
    fun downsamplesBeforePuttingLargeImagesInTheCache() = runTest {
        val data = LinkPreviewFixtures.iconPNG(side = 3000, letter = "I")
        val cache = DecodedImageCache()
        val loader = ImageLinkLoader(cache, fetch = { _, _ -> file(data) })
        val url = "https://example.com/large.png"
        val image = loader.load(url)
        assertNotNull(image)
        assertTrue(image!!.width <= ImageLinkLoader.MAX_PIXEL_SIZE)
        assertTrue(image.height <= ImageLinkLoader.MAX_PIXEL_SIZE)
        assertTrue(cache.image(ImageLinkLoader.key(url)) === image)
    }
}
