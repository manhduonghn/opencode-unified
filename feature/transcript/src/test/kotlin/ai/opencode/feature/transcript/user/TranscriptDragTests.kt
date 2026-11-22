package ai.opencode.feature.transcript.user

import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test

/**
 * The drag's data itself is a `ClipData` and `content://` URIs, and names from MIME types use
 * `MimeTypeMap`; both are stubs in plain JVM tests, so the class runs under Robolectric. The
 * `ClipData` is built when the drag starts and media's `AgentFileDragProvider` serves bytes on the
 * reader's thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptDragTests {
    private companion object {
        const val PLAIN = "text/plain"
    }

    @Test
    fun codePayloadRegistersLanguageUTTypeAndPlainTextOffMainThread() {
        val source = "struct Greeting {\n  let message = \"Hello\"\n}"
        val payload = TranscriptDragPayload.Code(source = source, language = "swift")

        assertEquals("snippet.swift", payload.suggestedName)
        assertEquals(listOf("text/x-swift", PLAIN), payload.mimeTypes)
    }

    @Test
    fun codePayloadRegistersLanguageUTTypeAndPlainTextOffMainThreadFramework() {
        val source = "struct Greeting {\n  let message = \"Hello\"\n}"
        val clip = TranscriptDragPayload.Code(source = source, language = "swift").clipData()!!
        assertEquals("text/x-swift", clip.description.getMimeType(0))
        assertTrue(clip.description.hasMimeType(PLAIN))
        assertEquals(source, clip.getItemAt(0).text.toString())
    }

    @Test
    fun codeLanguageMappingCoversCommonFenceTags() {
        assertEquals("text/x-swift", TranscriptDragPayload.codeMimeType("swift"))
        assertEquals("text/javascript", TranscriptDragPayload.codeMimeType("js"))
        assertEquals("application/json", TranscriptDragPayload.codeMimeType("json"))
        assertEquals("text/x-python", TranscriptDragPayload.codeMimeType("py"))
        assertEquals("application/x-sh", TranscriptDragPayload.codeMimeType("sh"))
        assertEquals("application/yaml", TranscriptDragPayload.codeMimeType("yaml"))
        assertEquals("text/html", TranscriptDragPayload.codeMimeType("html"))
        assertEquals("application/xml", TranscriptDragPayload.codeMimeType("xml"))
        assertEquals("text/x-csrc", TranscriptDragPayload.codeMimeType("c"))
        assertEquals("text/x-c++src", TranscriptDragPayload.codeMimeType("cpp"))
        assertEquals("text/x-objcsrc", TranscriptDragPayload.codeMimeType("objc"))
        assertNull(TranscriptDragPayload.codeMimeType(null))

        val untagged = TranscriptDragPayload.Code(source = "echo hi", language = null)
        assertEquals("snippet.txt", untagged.suggestedName)
        assertTrue(untagged.mimeTypes.contains(PLAIN))
    }

    /**
     * `ts` and `tsx` conform to `.sourceCode`; Android has no source-code supertype, so a text
     * type.
     */
    @Test
    fun codeLanguageMappingCoversCommonFenceTagsFramework() {
        for (tag in listOf("ts", "tsx")) {
            val mime = TranscriptDragPayload.codeMimeType(tag)
            assertTrue("$tag: $mime", mime != null && mime.startsWith("text/"))
        }
    }

    @Test
    fun imageAttachmentSniffsMagicBytesAndLoadsOffMainThread() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)
        val gifHeader = "GIF89a\u0000\u0000".toByteArray()
        val webpHeader = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()

        assertEquals("image/png", TranscriptDragPayload.imageMimeType(pngHeader, "capture"))
        assertEquals("image/jpeg", TranscriptDragPayload.imageMimeType(jpegHeader, "photo.png"))
        assertEquals("image/gif", TranscriptDragPayload.imageMimeType(gifHeader, "anim"))
        assertEquals("image/webp", TranscriptDragPayload.imageMimeType(webpHeader, "frame"))

        val payload = TranscriptDragPayload.ImageAttachment(id = "img-1", data = jpegHeader, name = "camera-roll")
        assertEquals(listOf("image/jpeg"), payload.mimeTypes)
    }

    @Test
    fun imageAttachmentSniffsMagicBytesAndLoadsOffMainThreadFramework() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)
        val name = TranscriptDragPayload.ImageAttachment(id = "img-1", data = jpegHeader, name = "camera-roll").suggestedName
        assertTrue(name, name == "camera-roll.jpeg" || name == "camera-roll.jpg")
    }

    @Test
    fun pastedTextURLAndMessagePayloadsProvideExpectedRepresentations() {
        val paste = TranscriptDragPayload.PastedText(text = "line 1\nline 2", name = "Pasted Text")
        assertEquals(listOf(PLAIN), paste.mimeTypes)

        val text = TranscriptDragPayload.Text("Whole assistant reply")
        assertEquals("message.txt", text.suggestedName)
        assertEquals(listOf(PLAIN), text.mimeTypes)

        val blocks = OpenCodeMarkdownBlock.blocks("Paragraph one.\n\n```swift\nlet y = 2\n```")
        val markdown = TranscriptDragPayload.MarkdownBlocks(blocks)
        assertEquals("message.txt", markdown.suggestedName)
        assertEquals(listOf(PLAIN), markdown.mimeTypes)
    }

    @Test
    fun pastedTextURLAndMessagePayloadsProvideExpectedRepresentationsFramework() {
        val paste = TranscriptDragPayload.PastedText(text = "line 1\nline 2", name = "Pasted Text")
        assertEquals("Pasted Text.txt", paste.suggestedName)
        assertEquals("line 1\nline 2", paste.clipData()!!.getItemAt(0).text.toString())

        val url = TranscriptDragPayload.Url("https://opencode.ai/docs")
        assertEquals("opencode.ai", url.suggestedName)
        assertTrue(url.mimeTypes.contains("text/uri-list"))
        assertEquals("https://opencode.ai/docs", url.clipData()!!.getItemAt(0).text.toString())

        assertEquals("Whole assistant reply", TranscriptDragPayload.Text("Whole assistant reply").clipData()!!.getItemAt(0).text.toString())

        val blocks = OpenCodeMarkdownBlock.blocks("Paragraph one.\n\n```swift\nlet y = 2\n```")
        assertEquals(
            OpenCodeMarkdownBlock.markdown(blocks),
            TranscriptDragPayload.MarkdownBlocks(blocks).clipData()!!.getItemAt(0).text.toString(),
        )
    }
}
