package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Runs under Robolectric (native graphics) so `loadsFileBackedAndUIImageProviders` reads real
 * `ContentResolver` streams and encodes/decodes real JPEGs: a `file:` Uri stands in for the
 * file-backed provider and a `content:` Uri for the file-URL one. Dropped:
 * `textViewRoutesImagePasteWithoutChangingText`. `imageImportPreservesMixedSourceOrder` keeps its
 * mixed order with a loader in place of the item provider (the importer resolves item providers
 * through the `AppContext` dependency).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerItemProviderLoaderTests {
    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun attachment(data: ByteArray, id: UUID) = OpenCodeOutgoingAttachment(id = id.toString(), data = data, mime = "image/test")

    private val process: suspend (ByteArray, UUID) -> OpenCodeOutgoingAttachment? = { data, id -> attachment(data, id) }

    /** The importer's callbacks run on Main; it shares the test's scheduler. */
    private fun TestScope.importer(): ComposerImageImporter {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ComposerImageImporter(this)
    }

    private suspend fun waitForImport(importer: ComposerImageImporter) {
        while (importer.isLoading) yield()
    }

    @Test fun loadsFileBackedAndUIImageProviders() = runTest {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 165, 0)) }
        val jpeg = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.toByteArray()
        }
        val file = File.createTempFile("loader", ".jpg").apply { writeBytes(jpeg) }
        try {
            val context = RuntimeEnvironment.getApplication()
            val contentUri = Uri.parse("content://ai.opencode.test/photo.jpg")
            shadowOf(context.contentResolver).registerInputStream(contentUri, ByteArrayInputStream(jpeg))

            val loaded = listOf(Uri.fromFile(file), contentUri).mapNotNull { ComposerItemProviderLoader.load(it, context) }

            assertEquals(2, loaded.size)
            assertTrue(loaded.all { BitmapFactory.decodeByteArray(it, 0, it.size) != null })
        } finally {
            file.delete()
        }
    }

    @Test fun libraryMovieAttachesOnlyTheEncodedPoster() = runTest {
        val uri = "content://media/external/video/media/42"
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val poster = java.io.ByteArrayOutputStream().use { bytes ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bytes.toByteArray()
        }
        val importer = importer()
        val added = mutableListOf<OpenCodeOutgoingAttachment>()
        importer.start(listOf(ComposerImageSource.Loader(videoDuration = 14.0, localVideoURI = uri) { poster }), onAttachment = { added += it })
        waitForImport(importer)

        val attachment = added.single()
        assertEquals(uri, attachment.localVideoURI)
        assertEquals(14.0, attachment.videoDuration)
        assertEquals("image/jpeg", attachment.mime)
        assertTrue(attachment.promptURI.startsWith("data:image/jpeg;base64,"))
        assertTrue(BitmapFactory.decodeByteArray(attachment.data, 0, attachment.data.size) != null)
    }

    @Test fun imageImportPreservesMixedSourceOrder() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            listOf(
                ComposerImageSource.Data(byteArrayOf(1)),
                ComposerImageSource.Loader { yield(); byteArrayOf(2) },
                ComposerImageSource.Loader { byteArrayOf(3) },
            ),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(1, 2, 3), imported)
    }

    /** The importer's own default (pastes, shares, quotes) is 4; the + menu passes 8. */
    @Test fun imageImportEnforcesSelectionLimitOnce() = runTest {
        for (limit in listOf(4, 8)) {
            if (limit == 8) assertEquals(8, AttachmentPickerButton.MAX_PHOTO_SELECTION)
            val importer = importer()
            val imported = mutableListOf<Byte>()

            importer.start(
                (1..(limit + 2)).map { ComposerImageSource.Data(byteArrayOf(it.toByte())) },
                process = process,
                onAttachment = { imported += it.data[0] },
                limit = limit,
            )
            waitForImport(importer)

            assertEquals((1..limit).map { it.toByte() }, imported)
        }
    }

    @Test fun secondImageImportCancelsFirst() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            listOf(
                ComposerImageSource.Loader {
                    delay(10.seconds)
                    byteArrayOf(1)
                },
            ),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        yield()
        importer.start(
            listOf(ComposerImageSource.Data(byteArrayOf(2))),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(2), imported)
    }

    @Test fun partialImageImportFailureKeepsSuccessfulImages() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            listOf(ComposerImageSource.Data(byteArrayOf(1)), ComposerImageSource.Loader { null }, ComposerImageSource.Data(byteArrayOf(3))),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(1, 3), imported)
    }

    @Test fun failedImportSettlesSoTheNextImportCanStart() = runTest {
        val importer = importer()
        val results = mutableListOf<ComposerImageImporter.Result>()
        val imported = mutableListOf<Byte>()

        importer.start(listOf(ComposerImageSource.Loader { null }), onAttachment = {}, onCompletion = { results += it })
        assertTrue(importer.isLoading)
        waitForImport(importer)
        assertEquals(listOf(0), results.map { it.addedCount })

        importer.start(
            listOf(ComposerImageSource.Data(byteArrayOf(7))),
            process = process,
            onAttachment = { imported += it.data[0] },
            onCompletion = { results += it },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(7), imported)
        assertEquals(listOf(0, 1), results.map { it.addedCount })
    }

    @Test fun cancelledImportStopsLoadingAndNeverCompletes() = runTest {
        val importer = importer()
        val gate = CompletableDeferred<Unit>()
        var completions = 0

        importer.start(
            listOf(
                ComposerImageSource.Loader {
                    gate.await()
                    byteArrayOf(1)
                },
            ),
            process = process,
            onAttachment = { fail("A cancelled import must not attach") },
            onCompletion = { completions += 1 },
        )
        yield()
        importer.cancel()
        assertFalse(importer.isLoading)
        gate.complete(Unit)
        repeat(10) { yield() }
        assertEquals(0, completions)
    }

    @Test fun attachmentCallbackCanEndTheImport() = runTest {
        for (supersede in listOf(false, true)) {
            val importer = importer()
            var loads = 0
            var completions = 0
            val imported = mutableListOf<Byte>()

            importer.start(
                listOf(
                    ComposerImageSource.Data(byteArrayOf(1)),
                    ComposerImageSource.Loader {
                        loads += 1
                        byteArrayOf(2)
                    },
                ),
                process = process,
                onAttachment = { attachment ->
                    imported += attachment.data[0]
                    if (attachment.data[0] == 1.toByte()) {
                        if (supersede) {
                            importer.start(listOf(ComposerImageSource.Data(byteArrayOf(9))), process = process, onAttachment = { imported += it.data[0] })
                        } else {
                            importer.cancel()
                        }
                    }
                },
                onCompletion = { completions += 1 },
            )
            waitForImport(importer)
            repeat(10) { yield() }

            assertEquals("supersede=$supersede", 0, loads)
            assertEquals("supersede=$supersede", 0, completions)
            assertEquals("supersede=$supersede", if (supersede) listOf<Byte>(1, 9) else listOf<Byte>(1), imported)
        }
    }
}
