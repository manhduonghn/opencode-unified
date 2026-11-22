package ai.opencode.feature.session

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.media.AgentFileDestination
import ai.opencode.feature.media.AgentImageKey
import ai.opencode.feature.media.AgentImageSighting
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SessionVisualMediaTests {
    @get:Rule val isolation = TestIsolationRule()
    private val server = OpenCodeServer("https://fixture.invalid", "", "")
    private val dir = "/project"
    private val hash = "b".repeat(64)
    private fun tool(path: String, images: List<OpenCodeImageAttachment> = emptyList(), videos: List<OpenCodeTranscriptFile> = emptyList()) =
        OpenCodeTranscriptItem.Tool("tool", OpenCodeToolCall("call", name = "read", status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(mapOf("path" to OpenCodeJSONValue.Str(path))), output = emptyList(), metadata = emptyMap(),
            images = images, videos = videos))

    @Test fun collectsAcrossRowsPreservingTappedPixelsAndFileAliases() {
        val bytes = byteArrayOf(1, 2)
        val items = listOf(
            OpenCodeTranscriptItem.UserImages("user", listOf(OpenCodeImageAttachment("att", bytes))),
            OpenCodeTranscriptItem.UserFiles("files", listOf(OpenCodeTranscriptFile("file", "shot.png", "image/png", "/project/img/shot.png"))),
            OpenCodeTranscriptItem.AssistantMarkdown("reply", "[picture](img/shot.png)\n\n[video](demo.mov)\n\n[notes](readme.md)"),
            tool("./demo.mov", listOf(OpenCodeImageAttachment("result", mediaHash = hash))),
        )
        val selected = AgentImageSighting(AgentImageKey(server.persistenceID, "$dir/img/shot.png"), "reply").mediaID
        val shown = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val preview = SessionVisualMedia.preview(TranscriptImagePreview(selected, shown, "shot.png"), items, server, dir)
        assertEquals(listOf("att", selected, "file:demo.mov", "result"), preview.pages.map { it.id })
        assertSame(shown, preview.pages[1].image)
        assertNull(preview.pages[1].file)
        assertSame(bytes, preview.pages[0].original)
        assertTrue(preview.pages[2].isVideo)
        assertEquals(hash, preview.pages[3].mediaHash)
    }

    @Test fun resolvesLinksAgainstTheDocumentWithoutRedirectingSiblingFiles() {
        val items = listOf(tool("clip.mp4"))
        fun open(path: String) = SessionVisualMedia.preview(AgentFileDestination(AgentFileReference(path), "$dir/docs"), items, server, dir)
        val existing = open("../clip.mp4")!!
        assertEquals("file:clip.mp4", existing.id)
        assertEquals(1, existing.pages.size)
        val inserted = open("clip.mp4")!!
        assertEquals(listOf("file:clip.mp4", "file:/project/docs/clip.mp4"), inserted.pages.map { it.id })
        assertEquals("$dir/docs", inserted.pages.last().file?.directory)
        assertNull(open("report.pdf"))
        assertNull(open("sound.m4a"))
    }

    @Test fun videoBlobToolResultDoesNotMasqueradeAsAMosaicImage() {
        val movie = OpenCodeTranscriptFile("demo", "clip.mov", "video/quicktime", mediaHash = hash)
        val item = tool("notes.md", videos = listOf(movie, movie))
        val entry = TranscriptEntry.Single(item)
        assertTrue(entry.toolImageAttachments.isEmpty())
        val card = entry.toolVideoReferences.single()
        val preview = SessionVisualMedia.preview(AgentFileDestination(card, dir), listOf(item), server, dir)!!
        assertEquals(card.zoomSourceID, preview.id)
        val page = preview.pages.single()
        assertEquals("clip.mov", page.label)
        assertTrue(page.isVideo)
        assertEquals(hash, page.file?.reference?.media?.hash)
    }
}
