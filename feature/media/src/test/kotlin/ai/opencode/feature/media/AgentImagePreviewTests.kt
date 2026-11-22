package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.AgentFileReference
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Test images for the previews: real PNG headers (signature and `IHDR`, so the shape reads from
 * the first bytes as on a device) standing for files whose pixels decode to [AgentImageFixtures.bitmap]
 * (one `Bitmap` per file, as the previews compare images by identity): a real one of the header's
 * size under Robolectric, where the pixel cache weighs it, else a stub.
 */
internal object AgentImageFixtures {
    fun png(width: Int, height: Int, tag: Int = 0): ByteArray {
        fun word(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = word(13) + "IHDR".toByteArray() + word(width) + word(height) + byteArrayOf(8, 6, 0, 0, 0) + word(tag)
        return signature + ihdr
    }

    val red: ByteArray = png(40, 30, tag = 1)
    val blue: ByteArray = png(30, 40, tag = 2)
    private val bitmaps = HashMap<String, Bitmap>()

    /** The decoded image of [data]: the same instance for the same bytes. */
    fun bitmap(data: ByteArray): Bitmap = synchronized(bitmaps) {
        bitmaps.getOrPut(data.contentToString()) {
            val size = AgentImageHeader.pngSize(data)
            if (Build.FINGERPRINT == "robolectric" && size != null) {
                Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888)
            } else {
                stubBitmap()
            }
        }
    }

    val decoder: AgentImageDecoder = { bitmap(it) }
}

/** Lets the previews' tasks run until [condition] holds (iOS `settle`). */
internal suspend fun settle(condition: () -> Boolean = { false }) {
    repeat(200) {
        if (condition()) return
        yield()
        delay(1)
    }
}

internal fun request(path: String) = AgentImageRequest(Request.Builder().url("http://lab/api/fs/read/$path").build())

/** Port of iOS `AgentImagePreviewTests` (`AgentImagePreviewTests.swift`); `aJPEGsShapeTurnsWithItsOrientation` decodes a real JPEG in [AgentImagePreviewFrameworkTests]. */
class AgentImagePreviewTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aPNGsShapeIsKnownFromItsFirstBytes() {
        val data = AgentImageFixtures.png(640, 360)
        assertEquals(AgentImageSize(640.0, 360.0), AgentImageHeader.size(data.copyOf(minOf(64, data.size))))
        assertNull("the header isn't there yet", AgentImageHeader.size(data.copyOf(12)))
        assertEquals(AgentImageSize(640.0, 360.0), AgentImageHeader.pngSize(data))
    }

    @Test
    fun aStartOfFrameIsFoundPastOtherSegments() {
        // SOI, an APP0 of 16 bytes, then SOF0: 300 tall, 400 wide.
        val bytes = intArrayOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10) + IntArray(14) +
            intArrayOf(0xFF, 0xC0, 0x00, 0x11, 0x08, 0x01, 0x2C, 0x01, 0x90, 0x03, 0x01, 0x22, 0x00)
        assertEquals(AgentImageSize(400.0, 300.0), AgentImageHeader.jpegSize(ByteArray(bytes.size) { bytes[it].toByte() }))
        assertNull(AgentImageHeader.jpegSize(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)))
    }

    // The plan

    private val directory = "/Users/kit/game"

    private fun plan(
        paths: List<String>,
        phases: Map<String, AgentImagePreviews.Phase> = emptyMap(),
        allowed: Set<String> = emptySet(),
    ): AgentImagePlan = AgentImagePlan.make(
        files = paths.map { AgentFileReference(path = it) },
        directory = directory,
        phase = { file, _ -> phases[file.path] ?: AgentImagePreviews.Phase.Waiting },
        allowed = { it.absolutePath in allowed },
    )

    @Test
    fun imagesInTheSessionFolderPreviewInOrder() {
        val result = plan(listOf("concept/hero.png", "docs/brief.pdf", "/Users/kit/game/shots/s003.jpg"))
        assertEquals(listOf("concept/hero.png", "/Users/kit/game/shots/s003.jpg"), result.previews.map { it.path })
        assertEquals("other files keep today's card", listOf("docs/brief.pdf"), result.cards.map { it.path })
        assertTrue(result.notFound.isEmpty() && result.outside.isEmpty())
    }

    @Test
    fun anImageOutsideTheFolderWaitsForATap() {
        val outside = "~/Desktop/refs/mood.jpg"
        assertEquals("never read on its own", listOf(outside), plan(listOf(outside)).outside.map { it.path })
        assertEquals(1, plan(listOf("/tmp/x/mood.png")).outside.size)
        assertEquals(listOf(outside), plan(listOf(outside), allowed = setOf("/Users/kit/Desktop/refs/mood.jpg")).previews.map { it.path })
    }

    @Test
    fun aMissingFileSaysSoAndAnUnreadableOneKeepsItsCard() {
        val result = plan(
            listOf("a.png", "b.png", "c.png"),
            phases = mapOf("a.png" to AgentImagePreviews.Phase.Missing, "b.png" to AgentImagePreviews.Phase.Unavailable, "c.png" to AgentImagePreviews.Phase.Loaded),
        )
        assertEquals(listOf("a.png"), result.notFound.map { it.path })
        assertEquals(listOf("b.png"), result.cards.map { it.path })
        assertEquals(listOf("c.png"), result.previews.map { it.path })
    }

    @Test
    fun withoutASessionFolderAnImageKeepsItsCard() {
        val result = AgentImagePlan.make(
            files = listOf(AgentFileReference(path = "hero.png")),
            directory = null,
            phase = { _, _ -> AgentImagePreviews.Phase.Waiting },
            allowed = { false },
        )
        assertEquals(1, result.cards.size)
    }

    @Test
    fun aPreviewHoldsItsShapeOrThePlaceholders() {
        assertEquals(16.0 / 9, AgentImagePlan.aspect(AgentImageSize(1600.0, 900.0)), 1e-12)
        assertEquals(AgentImagePlan.PLACEHOLDER_ASPECT, AgentImagePlan.aspect(null), 0.0)
        assertEquals(AgentImagePlan.PLACEHOLDER_ASPECT, AgentImagePlan.aspect(AgentImageSize(0.0, 0.0)), 0.0)
    }

    @Test
    fun shapesReadBeforeAreRemembered() {
        val cache = AgentImageSizeCache.inMemory
        val key = AgentImageKey(server = "s", path = "/Users/kit/game/a.png")
        assertNull(cache.size(key))
        cache.store(AgentImageSize(640.0, 360.0), key)
        assertEquals(AgentImageSize(640.0, 360.0), cache.size(key))
        for (index in 0..AgentImageSizeCache.LIMIT) {
            cache.store(AgentImageSize(10.0, 10.0), AgentImageKey(server = "s", path = "/$index.png"))
        }
        assertNull("the oldest go first", cache.size(key))
    }

    @Test
    fun aRememberedShapeIsReadOnceOffTheMainActor() = runBlocking {
        val file = File(Files.createTempDirectory("agent-image-sizes").toFile(), "AgentImageSizes.json")
        try {
            val key = AgentImageKey(server = "s", path = "/Users/kit/game/a.png")
            val cache = AgentImageSizeCache(file)
            cache.warm()?.join()
            assertNull(cache.size(key))

            // A shape written straight to the file afterwards is never read while a composable
            // draws: the cache read the file once, off the main thread.
            val hash = MessageDigest.getInstance("SHA-256").digest("${key.server}|${key.path}".toByteArray())
                .take(10).joinToString("") { "%02x".format(it) }
            file.writeText("""{"sizes":{"$hash":[640,360]},"order":["$hash"]}""")
            assertNull("no read while drawing", cache.size(key))

            val next = AgentImageSizeCache(file)
            next.warm()?.join()
            assertEquals("the warm reads it", AgentImageSize(640.0, 360.0), next.size(key))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aReadLooksAtTheFirst256KBOnly() = runBlocking {
        // A header in the first bytes answers once, however many chunks arrive.
        val png = AgentImageFixtures.png(640, 360) + ByteArray(1_000)
        val sizes = ArrayList<AgentImageSize>()
        AgentImageDownload.load(request("a.png"), validators = null, maxBytes = 10 shl 20, client = chunkedClient(png, 64)) { sizes.add(it) }
        assertEquals(listOf(AgentImageSize(640.0, 360.0)), sizes)

        // A header past the inspected window waits for the finished file: the read stops
        // looking at 256 KB rather than searching the whole buffer on every chunk.
        val late = java.io.ByteArrayOutputStream()
        late.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        while (late.size() < AgentImageDownload.HEADER_INSPECTION_BYTES + 16) {
            late.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xFF.toByte())) // APP0, length 65,535
            late.write(ByteArray(65_533))
        }
        late.write(intArrayOf(0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x02, 0x58, 0x03, 0x20, 0x01).map { it.toByte() }.toByteArray())
        val lateSizes = ArrayList<AgentImageSize>()
        AgentImageDownload.load(request("b.jpg"), validators = null, maxBytes = 10 shl 20, client = chunkedClient(late.toByteArray(), 16 * 1024)) { lateSizes.add(it) }
        assertTrue(lateSizes.isEmpty())
    }

    /** A client that answers every request with [body], [chunk] bytes per read. */
    private fun chunkedClient(body: ByteArray, chunk: Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val stream = object : InputStream() {
                private var position = 0
                override fun read(): Int = if (position < body.size) body[position++].toInt() and 0xFF else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= body.size) return -1
                    val count = minOf(length, chunk, body.size - position)
                    System.arraycopy(body, position, buffer, offset, count)
                    position += count
                    return count
                }
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(stream.source().buffer().asResponseBody(null, body.size.toLong()))
                .build()
        }
        .build()

    @Test
    fun aNewEntryStartsAtARememberedShape() {
        val cache = AgentImageSizeCache.inMemory
        val key = AgentImageKey(server = "s", path = "/Users/kit/game/a.png")
        cache.store(AgentImageSize(300.0, 400.0), key)
        val previews = AgentImagePreviews(sizes = cache, pathUpdates = { emptyFlow() }, dispatcher = Dispatchers.Unconfined)
        assertEquals(AgentImagePreviews.Entry(phase = AgentImagePreviews.Phase.Waiting, size = AgentImageSize(300.0, 400.0)), previews.entry(key))
        assertFalse(previews.isAllowed(key))
        previews.allow(key)
        assertTrue(previews.isAllowed(key))
        previews.close()
    }
}

/** A loader the test answers by hand, read by read (iOS `AgentImagePreviewReadTests.Loader`). Never cancelled: a read ends when the test answers it. */
internal class ManualImageLoader {
    class Call(
        val request: AgentImageRequest,
        val validators: AgentImageValidators?,
        val onSize: (AgentImageSize) -> Unit,
        val continuation: Continuation<AgentImageResponse>,
    ) {
        fun resume(response: AgentImageResponse) = continuation.resume(response)
        fun fail(error: Throwable) = continuation.resumeWithException(error)
        fun fail(failure: AgentImageDownload.Failure) = fail(AgentImageDownload.FailureException(failure))
    }

    private val pending = ArrayList<Call>()
    private val names = ArrayList<String>()
    var count = 0
        private set

    val load: AgentImageLoader = { request, validators, onSize ->
        suspendCoroutine { continuation ->
            synchronized(this) {
                count += 1
                names += request.request.url.encodedPath
                pending += Call(request, validators, onSize, continuation)
            }
        }
    }

    val waiting: List<Call> get() = synchronized(this) { pending.toList() }

    /** How many reads of [name] were asked for. */
    fun calls(name: String): Int = synchronized(this) { names.count { it.endsWith(name) } }

    fun take(path: String): Call? = synchronized(this) {
        val index = pending.indexOfFirst { it.request.request.url.encodedPath.endsWith(path) }
        if (index < 0) null else pending.removeAt(index)
    }
}

/** A sleep the test ends (iOS `Gate`). */
internal class Gate {
    private val opened = CompletableDeferred<Unit>()
    suspend fun wait() = opened.await()
    fun open() {
        opened.complete(Unit)
    }
}

internal fun TestScope.makePreviews(
    loader: ManualImageLoader,
    sizes: AgentImageSizeCache = AgentImageSizeCache.inMemory,
    sleep: AgentImageSleep = {},
    prober: AgentImageProber? = null,
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
): AgentImagePreviews = if (prober != null) {
    AgentImagePreviews(sizes = sizes, loader = loader.load, decoder = AgentImageFixtures.decoder, sleep = sleep, prober = prober, pathUpdates = { emptyFlow() }, dispatcher = dispatcher)
} else {
    AgentImagePreviews(sizes = sizes, loader = loader.load, decoder = AgentImageFixtures.decoder, sleep = sleep, pathUpdates = { emptyFlow() }, dispatcher = dispatcher)
}

/**
 * Port of iOS `AgentImagePreviewReadTests`: rechecks per place, retries, cancellation, limits.
 * iOS's `evict(key)` (the `NSCache` letting one image go) is [AgentImagePreviews.trimMemory]
 * here, which lets every unpinned image go; `isPinned` has no Android counterpart, so its
 * asserts read whether the pixels survive instead. Runs under Robolectric so the previews' pixel
 * cache is the framework's `LruCache`, weighing real bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImagePreviewReadTests {
    @get:Rule val isolation = TestIsolationRule()

    private val red get() = AgentImageFixtures.bitmap(AgentImageFixtures.red)
    private val blue get() = AgentImageFixtures.bitmap(AgentImageFixtures.blue)

    private class Harness(val loader: ManualImageLoader, val previews: AgentImagePreviews) {
        fun sighting(path: String, occurrence: String = "reply-1") = AgentImageSighting(AgentImageKey(server = "s", path = "/game/$path"), occurrence)
        fun show(path: String, occurrence: String = "reply-1") = previews.show(sighting(path, occurrence), request(path))

        suspend fun answer(path: String, response: AgentImageResponse) {
            settle { loader.waiting.any { it.request.request.url.encodedPath.endsWith(path) } }
            val call = loader.take(path) ?: throw AssertionError("a read of $path is waiting")
            call.resume(response)
            settle()
        }

        suspend fun fail(path: String, failure: AgentImageDownload.Failure) {
            settle { loader.waiting.any { it.request.request.url.encodedPath.endsWith(path) } }
            val call = loader.take(path) ?: throw AssertionError("a read of $path is waiting")
            call.fail(failure)
            settle()
        }
    }

    private fun TestScope.harness(sleep: AgentImageSleep = {}): Harness {
        val loader = ManualImageLoader()
        return Harness(loader, makePreviews(loader, sleep = sleep))
    }

    private fun file(data: ByteArray, tag: String? = null) = AgentImageResponse.File(data, AgentImageValidators(entityTag = tag))

    @Test
    fun aNewPlaceRechecksWhileThePixelsStayUp() = runTest {
        with(harness()) {
            val key = sighting("render.png").key
            show("render.png")
            answer("render.png", file(AgentImageFixtures.red, "\"v1\""))
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            assertTrue(previews.image(key) === red)
            assertEquals(1, loader.count)

            // The same place scrolling away and back reads nothing.
            previews.hide(sighting("render.png"))
            show("render.png")
            settle()
            assertEquals(1, loader.count)

            // A later reply naming it rechecks with the validators, pixels kept meanwhile.
            show("render.png", "reply-2")
            settle { loader.count == 2 }
            assertEquals("\"v1\"", loader.waiting.firstOrNull()?.validators?.entityTag)
            assertTrue(previews.entry(key).rechecking)
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            assertNotNull(previews.image(key))
            assertFalse(previews.isFresh(sighting("render.png", "reply-2")))

            // The file was overwritten: the new pixels and shape replace the old.
            answer("render.png", file(AgentImageFixtures.blue, "\"v2\""))
            assertTrue(previews.image(key) === blue)
            assertEquals(AgentImageSize(30.0, 40.0), previews.entry(key).size)
            assertFalse(previews.entry(key).rechecking)
            assertTrue(previews.isFresh(sighting("render.png", "reply-2")))

            // Unchanged: a 304 keeps them.
            show("render.png", "reply-3")
            answer("render.png", AgentImageResponse.NotModified)
            assertTrue(previews.image(key) === blue)
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            previews.close()
        }
    }

    @Test
    fun aMissingFileIsTriedAgainWhereItsNamedNext() = runTest {
        with(harness()) {
            val key = sighting("later.png").key
            show("later.png")
            fail("later.png", AgentImageDownload.Failure.NotFound)
            assertEquals(AgentImagePreviews.Phase.Missing, previews.entry(key).phase)
            show("later.png")
            settle()
            assertEquals("the same place doesn't ask again", 1, loader.count)
            show("later.png", "reply-2")
            answer("later.png", file(AgentImageFixtures.red))
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            previews.close()
        }
    }

    @Test
    fun aReadThatFailedOnTheWayIsRetriedWhileOnScreen() = runTest {
        with(harness()) {
            val key = sighting("flaky.png").key
            show("flaky.png")
            fail("flaky.png", AgentImageDownload.Failure.Failed)
            assertNotEquals("a retry is coming: still a preview, not a card", AgentImagePreviews.Phase.Unavailable, previews.entry(key).phase)
            // The backoff (immediate here) asks again.
            answer("flaky.png", file(AgentImageFixtures.red))
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            assertEquals(2, loader.count)
            previews.close()
        }
    }

    @Test
    fun aRecheckThatFailsKeepsThePixelsButStaysUnconfirmed() = runTest {
        with(harness()) {
            val key = sighting("render.png").key
            show("render.png")
            answer("render.png", file(AgentImageFixtures.red, "\"v1\""))
            show("render.png", "reply-2")
            fail("render.png", AgentImageDownload.Failure.Failed)
            assertEquals("the pixels stay up", AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            assertTrue(previews.image(key) === red)
            assertFalse("never confirmed, so a tap reads the file itself", previews.isFresh(sighting("render.png", "reply-2")))
            // The retry (immediate here) brings the overwritten render.
            answer("render.png", file(AgentImageFixtures.blue, "\"v2\""))
            assertTrue(previews.image(key) === blue)
            assertTrue(previews.isFresh(sighting("render.png", "reply-2")))
            previews.close()
        }
    }

    @Test
    fun aNotModifiedAnswerRestoresPixelsTheCacheLetGo() = runTest {
        with(harness()) {
            val key = sighting("render.png").key
            show("render.png")
            answer("render.png", file(AgentImageFixtures.red, "\"v1\""))
            show("render.png", "reply-2")
            settle { loader.count == 2 }
            previews.trimMemory()
            answer("render.png", AgentImageResponse.NotModified)
            assertTrue("the recheck's own copy comes back", previews.image(key) === red)
            assertEquals(AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            previews.close()
        }
    }

    @Test
    fun pixelsOnScreenSurviveTheCacheLettingGo() = runTest {
        with(harness()) {
            val key = sighting("on.png").key
            show("on.png")
            answer("on.png", file(AgentImageFixtures.red))
            previews.trimMemory()
            assertNotNull("still on screen, still drawn", previews.image(key))
            previews.hide(sighting("on.png"))
            settle()
            previews.trimMemory()
            assertNull("off screen, only the cache kept it", previews.image(key))
            previews.close()
        }
    }

    @Test
    fun showingTheSameSightingAgainAsksOnce() = runTest {
        with(harness()) {
            // A loading card becoming a tile: one marker goes, another comes.
            show("slow.png")
            settle { loader.count == 1 }
            previews.hide(sighting("slow.png"))
            show("slow.png")
            answer("slow.png", file(AgentImageFixtures.red))
            settle()
            assertEquals(1, loader.count)
            previews.close()
        }
    }

    @Test
    fun aReplacementMarkerWaitsForTheBackoff() = runTest {
        val gate = Gate()
        with(harness(sleep = { gate.wait() })) {
            val flaky = sighting("flaky.png")
            previews.show(flaky, request("flaky.png"))
            settle { loader.count == 1 }
            loader.take("flaky.png")?.fail(AgentImageDownload.Failure.Failed)
            settle { previews.activeReads == 0 }
            // The row reloads: its marker is replaced while the retry waits.
            previews.hide(flaky)
            previews.show(flaky, request("flaky.png"))
            settle()
            assertEquals("no read before the backoff", 1, loader.count)
            gate.open()
            settle { loader.count == 2 }
            assertEquals(2, loader.count)
            previews.close()
        }
    }

    @Test
    fun fullScreenNeverShowsAFileSinceGone() = runTest {
        with(harness()) {
            val one = sighting("one.png")
            val two = sighting("two.png")
            show("one.png")
            show("two.png")
            answer("one.png", file(AgentImageFixtures.red))
            answer("two.png", file(AgentImageFixtures.blue))
            assertTrue(previews.isConfirmed(one) && previews.isConfirmed(two))
            // A later reply finds two.png deleted.
            show("two.png", "reply-2")
            fail("two.png", AgentImageDownload.Failure.NotFound)
            assertNull("its pixels are gone", previews.image(two.key))
            assertFalse(previews.isConfirmed(two))
            assertTrue(previews.isConfirmed(one))
            previews.close()
        }
    }

    @Test
    fun pixelsOwedAfterEvictionSurviveACancelledRead() = runTest {
        with(harness()) {
            val one = sighting("e.png")
            show("e.png")
            answer("e.png", file(AgentImageFixtures.red))
            // Off screen long enough to unpin, then the cache lets it go.
            previews.hide(one)
            settle()
            previews.trimMemory()
            // Back: a read for the pixels starts, then it scrolls away and is cancelled.
            show("e.png")
            settle { loader.count == 2 }
            previews.hide(one)
            settle { previews.activeReads == 1 && loader.waiting.size == 1 }
            loader.take("e.png")?.fail(CancellationException())
            settle { previews.activeReads == 0 }
            // Back again: the pixels are still owed, so it reads.
            show("e.png")
            settle { loader.count == 3 }
            assertEquals(3, loader.count)
            answer("e.png", file(AgentImageFixtures.red))
            assertNotNull(previews.image(one.key))
            previews.close()
        }
    }

    @Test
    fun pixelsOwedAfterEvictionSurviveLeavingTheQueue() = runTest {
        with(harness()) {
            val one = sighting("q.png")
            show("q.png")
            answer("q.png", file(AgentImageFixtures.red))
            previews.hide(one)
            settle()
            previews.trimMemory()
            // Three reads hold every slot: its read waits in the queue.
            for (name in listOf("x.png", "y.png", "z.png")) show(name)
            settle { loader.count == 4 }
            show("q.png")
            previews.hide(one)
            settle()
            answer("x.png", file(AgentImageFixtures.red))
            settle()
            assertEquals("it left the queue with the screen, unread", 4, loader.count)
            // Back: still owed.
            show("q.png")
            answer("q.png", file(AgentImageFixtures.red))
            assertNotNull(previews.image(one.key))
            previews.close()
        }
    }

    @Test
    fun aNewPlaceGetsItsOwnRetries() = runTest {
        with(harness()) {
            val key = sighting("down.png").key
            show("down.png")
            // An outage: the first read and all three retries fail; only then a card.
            repeat(4) { fail("down.png", AgentImageDownload.Failure.Failed) }
            settle()
            assertEquals(4, loader.count)
            assertEquals(AgentImagePreviews.Phase.Unavailable, previews.entry(key).phase)
            previews.hide(sighting("down.png"))
            // A later reply names it; its first read fails too, then the network's back.
            show("down.png", "reply-2")
            fail("down.png", AgentImageDownload.Failure.Failed)
            answer("down.png", file(AgentImageFixtures.red))
            assertEquals("its own retry brought it", AgentImagePreviews.Phase.Loaded, previews.entry(key).phase)
            previews.close()
        }
    }

    @Test
    fun aRemountedMarkerKeepsTheRetryBudget() = runTest {
        val gate = Gate()
        with(harness(sleep = { gate.wait() })) {
            val flaky = sighting("flaky.png", "reply")
            previews.show(flaky, request("flaky.png"))
            settle { loader.count == 1 }
            loader.take("flaky.png")?.fail(AgentImageDownload.Failure.Failed)
            settle { previews.activeReads == 0 }
            // Every slot fills; then its backoff ends and its retry waits in the queue.
            for (name in listOf("x.png", "y.png", "z.png")) previews.show(sighting(name, "reply"), request(name))
            settle { loader.count == 4 }
            gate.open()
            settle()
            assertEquals("queued behind the full slots", 1, loader.calls("flaky.png"))
            // The row reloads: its marker is replaced while the retry waits.
            previews.hide(flaky)
            previews.show(flaky, request("flaky.png"))
            // Slots free up; every try fails. The original three retries are the limit.
            for (name in listOf("x.png", "y.png", "z.png")) loader.take(name)?.resume(AgentImageResponse.NotModified)
            repeat(6) {
                settle { loader.waiting.any { it.request.request.url.encodedPath.endsWith("flaky.png") } }
                loader.take("flaky.png")?.fail(AgentImageDownload.Failure.Failed)
                settle()
            }
            assertEquals("one read and three retries, not a fresh budget", 4, loader.calls("flaky.png"))
            previews.close()
        }
    }

    @Test
    fun aTooLargeImageIsNotRetried() = runTest {
        with(harness()) {
            show("huge.png")
            fail("huge.png", AgentImageDownload.Failure.TooLarge)
            settle()
            assertEquals(AgentImagePreviews.Phase.Unavailable, previews.entry(sighting("huge.png").key).phase)
            assertEquals(1, loader.count)
            previews.close()
        }
    }

    @Test
    fun aCancelledReadNeverClearsItsReplacement() = runTest {
        with(harness()) {
            val key = sighting("a.png").key
            show("a.png")
            settle { loader.count == 1 }
            val first = loader.take("a.png")!!
            // Off screen: the read is cancelled but still counted until it ends.
            previews.hide(sighting("a.png"))
            settle { previews.entry(key).phase == AgentImagePreviews.Phase.Waiting }
            assertEquals(1, previews.activeReads)
            // Back on screen before it ended: no second read yet.
            show("a.png")
            settle()
            assertEquals(1, loader.count)
            // A late shape from the cancelled read is ignored.
            first.onSize(AgentImageSize(999.0, 1.0))
            settle()
            assertNull(previews.entry(key).size)
            // It ends; the replacement starts and nothing clears it.
            first.fail(CancellationException())
            settle { loader.count == 2 }
            assertEquals(1, previews.activeReads)
            assertEquals(AgentImagePreviews.Phase.Loading, previews.entry(key).phase)
            val second = loader.take("a.png")!!
            second.onSize(AgentImageSize(40.0, 30.0))
            settle { previews.entry(key).size != null }
            assertEquals(AgentImageSize(40.0, 30.0), previews.entry(key).size)
            second.resume(file(AgentImageFixtures.red))
            settle { previews.entry(key).phase == AgentImagePreviews.Phase.Loaded }
            assertEquals(0, previews.activeReads)
            previews.close()
        }
    }

    @Test
    fun neverMoreThanThreeReadsAtOnce() = runTest {
        with(harness()) {
            for (name in listOf("1.png", "2.png", "3.png", "4.png", "5.png")) show(name)
            settle { loader.count == 3 }
            settle()
            assertEquals(3, loader.count)
            assertEquals(3, previews.activeReads)
            answer("1.png", file(AgentImageFixtures.red))
            settle { loader.count == 4 }
            assertEquals(4, loader.count)
            assertEquals(3, previews.activeReads)
            previews.close()
        }
    }

    @Test
    fun readsInViewGoBeforeTheMargins() = runTest {
        with(harness()) {
            // Three slow reads from the margin fill the slots; then two in view queue.
            for (name in listOf("m1.png", "m2.png", "m3.png", "m4.png")) previews.show(sighting(name), request(name))
            settle { loader.count == 3 }
            previews.enterView(sighting("v.png").key)
            previews.show(sighting("v.png"), request("v.png"))
            answer("m1.png", file(AgentImageFixtures.red))
            settle { loader.count == 4 }
            assertTrue("in view goes ahead of the margin's m4", loader.waiting.any { it.request.request.url.encodedPath.endsWith("v.png") })
            assertFalse(loader.waiting.any { it.request.request.url.encodedPath.endsWith("m4.png") })
            previews.close()
        }
    }

    @Test
    fun onlyVisibleSightingsHoldPixels() = runTest {
        with(harness()) {
            val one = sighting("v.png")
            val two = sighting("v.png", "reply-2")
            show("v.png")
            assertTrue(previews.isVisible(one))
            assertFalse(previews.isVisible(two))
            previews.hide(one)
            assertFalse(previews.isVisible(one))
            assertNotEquals("two replies naming one path fly from their own thumbnails", two.mediaID, one.mediaID)
            previews.close()
        }
    }

    /** iOS checks the reads' `URLSession` keeps no `URLCache`; Android's reads client has no HTTP cache. */
    @Test
    fun theReadsSessionKeepsNothing() {
        assertNull(AgentImageDownload.client.cache)
    }

    @Test
    fun onScreenMeansWithinHalfAScreenOfWhatsVisible() {
        val visible = Rect(0f, 100f, 400f, 700f)
        fun rect(y: Float) = Rect(0f, y, 400f, y + 200f)
        assertTrue(AgentImageVisibility.isOnScreen(rect(300f), visible))
        assertTrue("just below: about to scroll in", AgentImageVisibility.isOnScreen(rect(950f), visible))
        assertFalse(AgentImageVisibility.isOnScreen(rect(1500f), visible))
        assertFalse(AgentImageVisibility.isOnScreen(rect(-800f), visible))
        assertFalse(AgentImageVisibility.isOnScreen(rect(300f), Rect.Zero))
    }
}

/**
 * Port of iOS `AgentImageGrowthAndMarkerTests`: the plan's order, bentos and batches.
 *
 * Dropped with row growth (the transcript list no longer grows rows at their images; AGENTS.md,
 * Android-native transcript motion): eachImageHoldsOnlyTheTextBelowIt,
 * anEarlierGrowthEndingNeverLetsGoOfANewChange, aChangeReportedJustAfterItsGrowthBeganJoinsIt,
 * cancellingChangesDuringAGrowthNeverFreezeIt, absorbingANeighbourKeepsTheHoldContinuous,
 * aGrowthDroppedOffScreenLetsGoOfItsHold, imagesGrowingAndShrinkingTogetherNeverFreeze,
 * aShrinkHoldsTheTextBelowDown, and the framework cases aReportAfterAGrowthEndedNeverJoinsIt
 * and anUnmeasuredChangeExpiresOnItsOwn.
 */
class AgentImageGrowthAndMarkerTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun previews() = AgentImagePreviews(sizes = AgentImageSizeCache.inMemory, pathUpdates = { emptyFlow() }, dispatcher = Dispatchers.Unconfined)

    private val sized = AgentImageItem.Preview(sized = true)

    @Test
    fun filesKeepTheOrderTheyreNamedIn() {
        // The first image's shape isn't known yet: held out, the others draw in order.
        val items = listOf(AgentImageItem.Preview(sized = false), sized, sized)
        assertEquals(listOf(AgentImageSegment.Run(listOf(1, 2))), AgentImagePlan.segments(items))
        assertEquals(listOf(0), AgentImagePlan.held(items))
        // Once it has one it joins the run ahead of them.
        assertEquals(listOf(AgentImageSegment.Run(listOf(0, 1, 2))), AgentImagePlan.segments(listOf(sized, sized, sized)))
        // One batch is one bento where its first image is named; a card named between follows it.
        assertEquals(
            listOf(AgentImageSegment.Run(listOf(0, 2)), AgentImageSegment.NotFound(1)),
            AgentImagePlan.segments(listOf(sized, AgentImageItem.NotFound, sized)),
        )
        // Images named later, in a batch of their own, make a bento of their own.
        assertEquals(
            listOf(AgentImageSegment.Run(listOf(0, 1)), AgentImageSegment.Run(listOf(2))),
            AgentImagePlan.segments(listOf(sized, sized, sized)) { if (it < 2) 0 else 1 },
        )
        assertEquals(emptyList<AgentImageSegment>(), AgentImagePlan.segments(emptyList()))
    }

    @Test
    fun aFailingImageStaysWhereItsNamed() {
        val files = listOf("a.png", "b.png", "c.png", "d.pdf").map { AgentFileReference(path = it) }
        fun segments(phases: Map<String, AgentImagePreviews.Phase>) = AgentImagePlan.segments(
            AgentImagePlan.make(
                files = files, directory = "/game",
                phase = { file, _ -> phases[file.path] ?: AgentImagePreviews.Phase.Loaded },
                allowed = { false }, sized = { true },
            ).items,
        )
        // A, first, answers 404 while B and C are up: its card stays first.
        assertEquals(
            listOf(AgentImageSegment.NotFound(0), AgentImageSegment.Run(listOf(1, 2)), AgentImageSegment.Cards(listOf(3))),
            segments(mapOf("a.png" to AgentImagePreviews.Phase.Missing)),
        )
        // B fails on the way: it stays between A and C, a card among cards.
        assertEquals(
            listOf(AgentImageSegment.Run(listOf(0, 2)), AgentImageSegment.Cards(listOf(1, 3))),
            segments(mapOf("b.png" to AgentImagePreviews.Phase.Unavailable)),
        )
        // Recovered, all three are one bento again, in order.
        assertEquals(listOf(AgentImageSegment.Run(listOf(0, 1, 2)), AgentImageSegment.Cards(listOf(3))), segments(emptyMap()))
    }

    @Test
    fun bentosArrangeByCountAndShape() {
        fun bento(aspects: List<Double>) = AgentImageBento(aspects, width = 361.0)
        // One: full width up to 360 pt; a tall capture keeps its shape, leading.
        assertEquals(listOf(BentoRect(0.0, 0.0, 361.0, 361 * 0.75)), bento(listOf(4.0 / 3)).frames)
        val phone = bento(listOf(0.46))
        assertTrue(phone.arrangement == AgentImageBento.Arrangement.Single && phone.height == 360.0 && abs(phone.frames[0].width - 360 * 0.46) < 0.01)
        // Two: side by side, or stacked when both are wide.
        assertEquals(AgentImageBento.Arrangement.Pair, bento(listOf(4.0 / 3, 0.75)).arrangement)
        assertEquals(AgentImageBento.Arrangement.Pair, bento(listOf(1.0, 1.0)).arrangement)
        assertEquals(AgentImageBento.Arrangement.Stack, bento(listOf(16.0 / 9, 1.9)).arrangement)
        // Three: a hero beside two stacked; a wide hero above them.
        assertEquals(AgentImageBento.Arrangement.HeroLeading, bento(listOf(0.75, 4.0 / 3, 4.0 / 3)).arrangement)
        assertEquals(AgentImageBento.Arrangement.HeroTop, bento(listOf(2.2, 1.0, 1.0)).arrangement)
        // Four and six: a grid.
        assertEquals(AgentImageBento.Arrangement.Rows, bento(listOf(4.0 / 3, 4.0 / 3, 4.0 / 3, 4.0 / 3)).arrangement)
        assertEquals(AgentImageBento.Arrangement.Rows, bento(List(6) { 1.5 }).arrangement)
    }

    private fun intersects(a: BentoRect, b: BentoRect): Boolean =
        a.minX < b.maxX && b.minX < a.maxX && a.minY < b.maxY && b.minY < a.maxY

    @Test
    fun aBentoKeepsShapesFillsItsWidthAndNeverOverlaps() {
        val shapes = listOf(
            listOf(4.0 / 3, 0.75), listOf(1.0, 4.0 / 3, 16.0 / 9), listOf(0.75, 4.0 / 3, 4.0 / 3), listOf(4.0 / 3, 0.75, 1.5, 1.0),
            listOf(0.66, 1.5, 1.5, 1.5), listOf(4.0 / 3, 1.5, 16.0 / 9, 1.0, 0.75, 4.0 / 3), List(6) { 1.5 }, listOf(0.46, 0.46),
            listOf(3.0, 1.0, 1.0, 1.0, 1.0),
        )
        for (aspects in shapes) {
            val bento = AgentImageBento(aspects, width = 361.0, spacing = 4.0)
            assertEquals(aspects.size, bento.tiles.size)
            // Every edge meets the width or a gutter, and nothing overlaps.
            assertTrue("$aspects fills the width", abs((bento.frames.maxOfOrNull { it.maxX } ?: 0.0) - 361) < 0.01)
            for ((i, a) in bento.frames.withIndex()) {
                assertTrue(a.minX >= -0.01 && a.maxX <= 361.01)
                val inset = BentoRect(a.x + 1, a.y + 1, a.width - 2, a.height - 2)
                for (b in bento.frames.drop(i + 1)) assertFalse("$aspects: $a overlaps $b", intersects(inset, b))
            }
            // Gentle at worst: no tile crops past the point where it shows its image whole.
            for ((frame, aspect) in bento.frames.zip(aspects)) {
                assertTrue("$aspects: $frame for $aspect", AgentImageBento.crop(frame.width, frame.height, aspect) <= 0.12)
                assertTrue("$aspects: $frame under minTile", min(frame.width, frame.height) >= max(56.0, 361 * 0.2) - 0.01)
            }
            assertTrue(bento.height <= 361 * 1.2 + 0.01)
            // Its outer corners, and only those, are rounded like a card.
            val rounded = bento.tiles.sumOf { tile -> listOf(tile.corners.topStart, tile.corners.topEnd, tile.corners.bottomStart, tile.corners.bottomEnd).count { it } }
            assertEquals("$aspects rounds its four corners", 4, rounded)
            // The same shapes always make the same bento.
            assertEquals(bento, AgentImageBento(aspects, width = 361.0, spacing = 4.0))
        }
    }

    @Test
    fun aPanoramaPairedWithATallScreenshotRespectsMinTile() {
        for (width in listOf(361.0, 408.0)) {
            val minTile = max(56.0, width * 0.2)
            for (aspects in listOf(listOf(3.0, 9.0 / 19.5), listOf(9.0 / 19.5, 3.0))) {
                val bento = AgentImageBento(aspects, width = width, spacing = 4.0)
                assertEquals(2, bento.tiles.size)
                for ((frame, aspect) in bento.frames.zip(aspects)) {
                    assertTrue("width $width, aspects $aspects: $frame under $minTile", min(frame.width, frame.height) >= minTile - 0.01)
                    assertTrue(
                        "width $width, aspects $aspects: $frame crops past ${AgentImageBento.MAX_CROP}",
                        AgentImageBento.crop(frame.width, frame.height, aspect) <= AgentImageBento.MAX_CROP,
                    )
                }
            }
        }
    }

    @Test
    fun aDecidedBatchNeverChanges() {
        val previews = previews()
        val a = AgentImageKey(server = "s", path = "/game/a.png")
        val b = AgentImageKey(server = "s", path = "/game/b.png")
        val c = AgentImageKey(server = "s", path = "/game/c.png")
        // A shape still unknown holds the whole batch.
        assertTrue(previews.settleBento("p", pending = listOf(a to 1.5, b to null), gathering = false, now = 10.0).isEmpty())
        // Still streaming: it gathers a moment after the first was seen, then decides.
        assertTrue(previews.settleBento("p", pending = listOf(a to 1.5, b to 0.75), gathering = true, now = 10.2).isEmpty())
        val decided = previews.settleBento("p", pending = listOf(a to 1.5, b to 0.75), gathering = true, now = 10.8)
        assertTrue(decided.map { it.paths } == listOf(listOf(a.path, b.path)) && decided[0].aspects == listOf(1.5, 0.75))
        // Named later: a batch of its own; the first keeps its shapes.
        val later = previews.settleBento("p", pending = listOf(c to 1.0), gathering = false, now = 12.0)
        assertEquals(listOf(listOf(a.path, b.path), listOf(c.path)), later.map { it.paths })
        assertEquals(decided[0], previews.bentoBatches("p")[0])
        // Pixels fade in staggered from when the batch was decided.
        assertTrue(AgentImagePreviews.pixelsNotBefore(decided[0], index = 1) > AgentImagePreviews.pixelsNotBefore(decided[0], index = 0))
        previews.close()
    }

    @Test
    fun aPlaceholdersShapeIsGuessedFromItsName() {
        assertTrue(AgentImagePlan.guessedAspect(AgentFileReference(path = "shots/simulator-home.png")) < 0.5)
        assertEquals(3.0, AgentImagePlan.guessedAspect(AgentFileReference(path = "concept/title-banner.png")), 0.0)
        assertEquals(1.0, AgentImagePlan.guessedAspect(AgentFileReference(path = "assets/app-icon.png")), 0.0)
        assertEquals(AgentImagePlan.PLACEHOLDER_ASPECT, AgentImagePlan.guessedAspect(AgentFileReference(path = "renders/atrium.png")), 0.0)
        // A known shape always wins.
        assertEquals(16.0 / 9, AgentImagePlan.aspect(AgentImageSize(16.0, 9.0), guessing = AgentFileReference(path = "app-icon.png")), 1e-12)
    }

    @Test
    fun failedImagesKeepTheirMarkers() {
        val plan = AgentImagePlan.make(
            files = listOf("a.png", "b.png", "c.png", "d.pdf").map { AgentFileReference(path = it) },
            directory = "/game",
            phase = { file, _ ->
                mapOf("a.png" to AgentImagePreviews.Phase.Missing, "b.png" to AgentImagePreviews.Phase.Unavailable)[file.path]
                    ?: AgentImagePreviews.Phase.Loaded
            },
            allowed = { false },
        )
        assertEquals("missing and unreadable images are still watched", listOf("a.png", "b.png", "c.png"), plan.watched.map { it.path }.sorted())
        assertEquals(listOf("b.png", "d.pdf"), plan.cards.map { it.path })
    }

    @Test
    fun aReloadedTileNeverReplaysItsFade() {
        val previews = previews()
        val landed = 1000.0
        // First drawn 0.2 s after landing: the fade starts then.
        assertEquals(1000.2, previews.fadeStart("m", loadedAt = landed, now = 1000.2, fresh = 1.0), 0.0)
        // The cell reloads 0.5 s later, still within a second of landing: same clock, fade done.
        assertEquals(1000.2, previews.fadeStart("m", loadedAt = landed, now = 1000.7, fresh = 1.0), 0.0)
        // New pixels for the same tile get their own fade.
        assertEquals(2000.1, previews.fadeStart("m", loadedAt = 2000.0, now = 2000.1, fresh = 1.0), 0.0)
        // Pixels first drawn long after landing were already there: no fade.
        assertEquals(landed, previews.fadeStart("n", loadedAt = landed, now = 1005.0, fresh = 1.0), 0.0)
        previews.close()
    }

    @Test
    fun aGalleryThatShowedKeepsShowingAcrossReloads() {
        val previews = previews()
        assertFalse(previews.isPresented("s|reply#3"))
        previews.markPresented("s|reply#3")
        assertTrue("kept by the store, not the cell", previews.isPresented("s|reply#3"))
        previews.close()
    }

}
