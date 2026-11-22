package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.internal.WireJson
import ai.opencode.server.live.FilePayload
import ai.opencode.server.live.MediaReferenceFetch
import ai.opencode.server.live.decodeTranscript
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeMediaReference
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.util.Base64

/**
 * The capabilities header, decoding inline and `media:<hash>` shapes and unavailable placeholders,
 * and the live fetch.
 */
class MediaReferenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private companion object {
        val hashA = "a1".repeat(32)
        val hashB = "b2".repeat(32)

        /** A 12×8 PNG's signature and `IHDR`: the server never decodes the pixels. */
        fun samplePNGData(): ByteArray {
            fun word(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            return signature + word(13) + "IHDR".toByteArray() + word(12) + word(8) + byteArrayOf(8, 6, 0, 0, 0) + word(0)
        }

        fun base64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    }

    private suspend fun <T> withFixture(fixture: WireFixture, block: suspend (WireFixture) -> T): T =
        try { block(fixture) } finally { fixture.close() }

    private fun List<OpenCodeTranscriptItem>.userImages() = filterIsInstance<OpenCodeTranscriptItem.UserImages>().first().images
    private fun List<OpenCodeTranscriptItem>.userFiles() = filterIsInstance<OpenCodeTranscriptItem.UserFiles>().first().files
    private fun List<OpenCodeTranscriptItem>.toolImages() = filterIsInstance<OpenCodeTranscriptItem.Tool>().mapNotNull { it.call.images }.first()

    @Test
    fun videoBlobToolResultDoesNotMasqueradeAsAMosaicImage() {
        val payload = """
            {"data":[{"id":"msg","type":"assistant","content":[{"id":"demo","type":"tool","name":"render","state":{"content":[
              {"type":"text","text":"Done"},
              {"type":"file","mime":"video/mp4","uri":"media:invalid"},
              {"type":"file","mime":"Video/QuickTime","uri":"media:$hashA","bytes":9876},
              {"type":"file","mime":"video/mp4","uri":"data:video/mp4;base64,AQID"}
            ]}}]}, {"id":"prompt","type":"user","files":[
              {"name":"clip.mov","mime":"video/quicktime","uri":"media:$hashA","source":{"type":"inline"}}
            ]}]}
        """.trimIndent().toByteArray()
        val items = decodeTranscript(payload)
        val call = items.filterIsInstance<OpenCodeTranscriptItem.Tool>().single().call
        assertTrue(call.imageAttachments.isEmpty())
        assertEquals(listOf("Done"), call.output)
        val video = requireNotNull(call.videos).single()
        assertEquals("msg-tool-demo-video-2", video.id)
        assertEquals("Video", video.name)
        assertEquals(9876L, video.byteCount)
        assertEquals(hashA, video.mediaViewerReference?.media?.hash)
        assertEquals(video.mediaViewerReference?.id, items.userFiles().single().mediaViewerReference?.id)
    }

    @Test
    fun capabilityHeaderIsAlwaysSent() = runTest {
        withFixture(WireFixture { Reply.Json(200, """{"data":[]}""") }) { fixture ->
            OpenCodeClient.liveValue.transcript(fixture.server, "ses_1", 30)

            val requests = fixture.requests
            assertEquals(1, requests.size)
            assertEquals("experimental-media-uri", requests[0].header("X-OpenCode-Capabilities"))
        }
    }

    @Test
    fun decodesInlineAndMediaReferenceShapesInMessagesAndToolItems() {
        val inlinePNG = samplePNGData()
        val inlineBase64 = base64(inlinePNG)
        val payload = """
            {
              "data": [
                {
                  "id": "msg_assistant",
                  "type": "assistant",
                  "content": [
                    {
                      "id": "item_tool",
                      "type": "tool",
                      "name": "screenshot",
                      "state": {
                        "status": "completed",
                        "content": [
                          { "type": "file", "mime": "image/png", "uri": "data:image/png;base64,$inlineBase64" },
                          {
                            "type": "file",
                            "mime": "image/png",
                            "name": "render.png",
                            "uri": "media:$hashA",
                            "bytes": 2048,
                            "width": 400,
                            "height": 300
                          }
                        ]
                      }
                    }
                  ]
                },
                {
                  "id": "msg_user",
                  "type": "user",
                  "text": "Compare these",
                  "files": [
                    {
                      "data": "$inlineBase64",
                      "mime": "image/png",
                      "name": "inline.png",
                      "bytes": ${inlinePNG.size},
                      "width": 12,
                      "height": 8,
                      "source": { "type": "inline" }
                    },
                    {
                      "uri": "media:$hashA",
                      "mime": "image/png",
                      "name": "diagram.png",
                      "bytes": 4096,
                      "width": 800,
                      "height": 600,
                      "source": { "type": "inline", "uri": "media:$hashA" }
                    },
                    {
                      "uri": "media:$hashB",
                      "mime": "application/pdf",
                      "name": "spec.pdf",
                      "bytes": 12345,
                      "source": { "type": "inline", "uri": "media:$hashB" }
                    }
                  ]
                }
              ]
            }
        """.trimIndent().toByteArray()

        val items = decodeTranscript(payload)
        val userImages = items.userImages()
        assertEquals(2, userImages.size)
        assertArrayEquals(inlinePNG, userImages[0].data)
        assertNull(userImages[0].mediaHash)
        assertEquals("inline.png", userImages[0].name)
        assertTrue(userImages[1].data.isEmpty())
        assertEquals(hashA, userImages[1].mediaHash)
        assertEquals("diagram.png", userImages[1].name)
        assertEquals("image/png", userImages[1].mime)
        assertEquals(4096L, userImages[1].byteCount)
        assertEquals(800, userImages[1].pixelWidth)
        assertEquals(600, userImages[1].pixelHeight)
        assertEquals(800.0 / 600.0, userImages[1].aspectRatio!!, 0.0)

        val userFiles = items.userFiles()
        assertEquals(1, userFiles.size)
        assertEquals("spec.pdf", userFiles[0].name)
        assertEquals(hashB, userFiles[0].mediaHash)
        assertEquals(12345L, userFiles[0].byteCount)
        assertFalse(userFiles[0].isUnavailable)

        val toolImages = items.toolImages()
        assertEquals(2, toolImages.size)
        assertArrayEquals(inlinePNG, toolImages[0].data)
        assertNull(toolImages[0].mediaHash)
        assertEquals(hashA, toolImages[1].mediaHash)
        assertEquals("render.png", toolImages[1].name)
        assertEquals(2048L, toolImages[1].byteCount)
        assertEquals(400, toolImages[1].pixelWidth)
        assertEquals(300, toolImages[1].pixelHeight)
    }

    @Test
    fun decodesUnavailablePlaceholders() = runTest {
        val missingImageText = "[media unavailable: chart.png (image/png)]"
        val missingImagePlaceholder = base64(missingImageText.toByteArray())
        val missingPDFPlaceholder = base64("[media unavailable: report.pdf (application/pdf)]".toByteArray())

        val messagePayload = """
            {
              "data": [
                {
                  "id": "msg_tool_missing",
                  "type": "assistant",
                  "content": [
                    {
                      "id": "tool_1",
                      "type": "tool",
                      "name": "screenshot",
                      "state": {
                        "status": "completed",
                        "content": [ { "type": "text", "text": "[media unavailable: image/png]" } ]
                      }
                    }
                  ]
                },
                {
                  "id": "msg_missing",
                  "type": "user",
                  "text": "Check the attachments",
                  "files": [
                    { "data": "$missingImagePlaceholder", "mime": "text/plain", "name": "chart.png", "source": { "type": "inline" } },
                    { "data": "$missingPDFPlaceholder", "mime": "text/plain", "name": "report.pdf", "source": { "type": "inline" } }
                  ]
                }
              ]
            }
        """.trimIndent().toByteArray()

        // Decodes placeholders into unavailable images and files.
        val items = decodeTranscript(messagePayload)
        assertEquals(
            listOf(OpenCodeImageAttachment(id = "msg_missing-img-0", name = "chart.png", mime = "image/png", isUnavailable = true)),
            items.userImages(),
        )

        val userFiles = items.userFiles()
        assertEquals(1, userFiles.size)
        assertEquals("report.pdf", userFiles[0].name)
        assertEquals("application/pdf", userFiles[0].mime)
        assertTrue(userFiles[0].isUnavailable)
        assertEquals("Media unavailable", userFiles[0].typeLabel)

        val toolImages = items.toolImages()
        assertEquals(1, toolImages.size)
        assertTrue(toolImages[0].isUnavailable)
        assertEquals("image/png", toolImages[0].mime)

        val inbox = """
            {
              "data": [
                {
                  "id": "in_1",
                  "type": "user",
                  "delivery": "queue",
                  "payload": {
                    "text": "Queued with media",
                    "files": [
                      {
                        "uri": "media:$hashA",
                        "mime": "image/jpeg",
                        "name": "photo.jpg",
                        "bytes": 1024,
                        "source": { "type": "inline", "uri": "media:$hashA" }
                      },
                      { "data": "$missingImagePlaceholder", "mime": "text/plain", "name": "chart.png", "source": { "type": "inline" } }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        withFixture(WireFixture { Reply.Json(200, inbox) }) { inboxFixture ->
            val inputs = OpenCodeClient.liveValue.pendingInputs(inboxFixture.server, "ses_1")
            assertEquals(1, inputs.size)
            assertEquals(2, inputs[0].images.size)
            assertEquals(hashA, inputs[0].images[0].mediaHash)
            assertEquals("photo.jpg", inputs[0].images[0].name)
            assertFalse(inputs[0].images[0].isUnavailable)
            assertEquals("chart.png", inputs[0].images[1].name)
            assertTrue(inputs[0].images[1].isUnavailable)
        }
    }

    @Test
    fun placeholderCheckSkipsInlineImagesAndLargePayloads() {
        val largeImage = samplePNGData() + ByteArray(16_384) { 0x7F }
        val placeholderText = "[media unavailable: chart.png (image/png)]"
        val longText = "[media unavailable: " + "x".repeat(8_000) + " (image/png)]"
        val files = WireJson.decodeFromString(
            ListSerializer(FilePayload.serializer()),
            """
            [
              { "data": "${base64(largeImage)}", "mime": "image/png", "name": "big.png", "source": { "type": "inline" } },
              { "data": "${base64(placeholderText.toByteArray())}", "mime": "image/png", "name": "shot.png", "source": { "type": "inline" } },
              { "data": "${base64(placeholderText.toByteArray())}", "mime": "text/plain", "name": "chart.png", "source": { "type": "inline" } },
              { "data": "${base64(longText.toByteArray())}", "mime": "text/plain", "name": "notes.txt", "source": { "type": "inline" } }
            ]
            """.trimIndent(),
        )

        // Image payloads and payloads over the cap are never decoded as placeholders.
        assertNull(files[0].unavailablePlaceholder)
        assertNull(files[1].unavailablePlaceholder)
        assertNull(files[3].unavailablePlaceholder)
        assertEquals(OpenCodeMediaReference.UnavailablePlaceholder(name = "chart.png", mime = "image/png"), files[2].unavailablePlaceholder)

        // Old servers' inline images still decode, once, at their own bytes.
        val images = FilePayload.attachedImages(files, messageID = "msg")
        assertEquals(listOf("msg-img-0", "msg-img-1", "msg-img-2"), images.map { it.id })
        assertArrayEquals(largeImage, images[0].data)
        assertFalse(images[0].isUnavailable)
        assertArrayEquals(placeholderText.toByteArray(), images[1].data)
        assertTrue(images[2].isUnavailable)
        assertEquals("chart.png", images[2].name)

        // A long paste that merely looks like a placeholder stays a Pasted Text card.
        val attached = FilePayload.attachedFiles(files, messageID = "msg")
        assertEquals(1, attached.size)
        assertEquals("msg-file-inline-3", attached[0].id)
        assertFalse(attached[0].isUnavailable)
        assertEquals(longText, attached[0].text)
    }

    /**
     * OkHttp has no per-request cache policy, so the `media` pool having no cache is the whole
     * check.
     */
    @Test
    fun liveFetchUsesUncachedMediaSessionAndReloadIgnoringLocalCacheData() = runTest {
        assertNull(OpenCodeTransport.liveValue.media.cache)

        val fixture = WireFixture { request ->
            if (request.path.endsWith(hashB)) {
                Reply.status(404)
            } else {
                Reply.Json(200, "raw-bytes", headers = mapOf("Cache-Control" to "private, max-age=31536000, immutable"))
            }
        }
        withFixture(fixture) {
            val response200 = MediaReferenceFetch.live(fixture.server, hashA)
            assertEquals(200, response200.statusCode)
            assertArrayEquals("raw-bytes".toByteArray(), response200.data)

            try {
                MediaReferenceFetch.live(fixture.server, hashB)
                fail("Expected HTTP 404 to throw OpenCodeClientError")
            } catch (error: OpenCodeClientError) {
                assertEquals(404, error.statusCode)
            }

            val requests = fixture.requests
            assertEquals(2, requests.size)
            assertEquals("GET /api/experimental/media/$hashA", requests[0].route)
            assertEquals("experimental-media-uri", requests[0].header("X-OpenCode-Capabilities"))
            assertNull(requests[0].header("If-None-Match"))
            assertEquals("GET /api/experimental/media/$hashB", requests[1].route)
        }
    }
}
