package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PromptPresentationTests {
    @get:Rule val isolation = TestIsolationRule()

    private val reference = OpenCodeFileReference(name = "Report.pdf", mime = "application/pdf", path = "/tmp/opencode/uploads/u1/Report.pdf")

    @Test
    fun modelTextAppendsDesktopReferenceLines() {
        assertEquals(
            "Summarize\nAttached file: `/tmp/opencode/uploads/u1/Report.pdf`",
            OpenCodePromptPresentation.modelText("Summarize", files = listOf(reference)),
        )
        assertEquals(
            "Attached file: `/tmp/opencode/uploads/u1/Report.pdf`",
            OpenCodePromptPresentation.modelText("  ", files = listOf(reference)),
        )
    }

    @Test
    fun displayTextDropsOnlyReferenceLines() {
        val text = "Summarize\nAttached file: `/tmp/opencode/uploads/u1/Report.pdf`\nThe user made the following comment regarding this file of a.ts: hi"
        assertEquals(
            "Summarize\nThe user made the following comment regarding this file of a.ts: hi",
            OpenCodePromptPresentation.displayText(text, files = listOf(reference)),
        )
    }

    @Test
    fun userMessagesRenderChipsInsteadOfReferenceLines() {
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[{"id":"msg_1","type":"user","text":"Summarize\nAttached file: `/tmp/opencode/uploads/u1/Report.pdf`",
              "metadata":{"displayText":"Summarize","comments":[],"attachments":[{"name":"Report.pdf","mime":"application/pdf","path":"/tmp/opencode/uploads/u1/Report.pdf"},{"name":"broken"}]},
              "files":[
                {"data":"JVBERg==","mime":"application/pdf","source":{"type":"inline"},"name":"Spec.pdf"},
                {"data":"","mime":"text/plain","source":{"type":"uri","uri":"file:///p/a.ts"},"name":"a.ts"},
                {"data":"aGk=","mime":"text/plain","source":{"type":"inline"},"name":"b.ts","mention":{"start":0,"end":5,"text":"@b.ts"}}
              ]}]}
            """.trimIndent().toByteArray(),
        )
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.UserFiles(
                    id = "msg_1-files",
                    files = listOf(
                        OpenCodeTranscriptFile(id = "msg_1-file-0", name = "Report.pdf", mime = "application/pdf", path = "/tmp/opencode/uploads/u1/Report.pdf"),
                        OpenCodeTranscriptFile(id = "msg_1-file-inline-0", name = "Spec.pdf", mime = "application/pdf"),
                    ),
                ),
                OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Summarize"),
            ),
            items,
        )
    }

    @Test
    fun fileOnlyMessagesHaveNoBubble() {
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[{"id":"msg_2","type":"user","text":"Attached file: `/tmp/a/clip.mov`",
              "metadata":{"displayText":"","comments":[],"attachments":[{"name":"clip.mov","mime":"video/quicktime","path":"/tmp/a/clip.mov"}]}}]}
            """.trimIndent().toByteArray(),
        )
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.UserFiles(
                    id = "msg_2-files",
                    files = listOf(OpenCodeTranscriptFile(id = "msg_2-file-0", name = "clip.mov", mime = "video/quicktime", path = "/tmp/a/clip.mov")),
                ),
            ),
            items,
        )
    }
}

class FileAttachmentWireTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun temporaryFile(name: String, contents: String): File {
        val folder = Files.createTempDirectory("wire").toFile()
        return File(folder, name).apply { writeText(contents) }
    }

    @Test
    fun promptWithFilesMatchesDesktopWireFormat() = runTest {
        val fixture = WireFixture { Reply.Json(200, "{}") }
        try {
            val pdf = OpenCodeOutgoingAttachment(data = "%PDF".toByteArray(), mime = "application/pdf", name = "Spec.pdf")
            val file = OpenCodeFileReference(name = "clip.mov", mime = "video/quicktime", path = "/tmp/opencode/uploads/u1/clip.mov")

            OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "Compare these", listOf(pdf), listOf(file), "msg_1", OpenCodeDelivery.Steer)

            val request = fixture.requests.first()
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {"id":"msg_1","text":"Compare these\nAttached file: `/tmp/opencode/uploads/u1/clip.mov`",
                     "files":[{"uri":"${pdf.dataURL}","name":"Spec.pdf"}],"delivery":"steer",
                     "metadata":{"displayText":"Compare these","comments":[],
                       "attachments":[{"name":"clip.mov","mime":"video/quicktime","path":"/tmp/opencode/uploads/u1/clip.mov"}]}}
                    """.trimIndent(),
                ),
                request.json(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun uploadStreamsTheFileIntoTheServerTemporaryDirectory() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/info" -> Reply.Json(200, """{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/srv/tmp"}}""")
                "POST /api/experimental/fs/write" ->
                    Reply.Json(200, """{"location":{"directory":"/a b"},"data":{"path":"/srv/tmp/uploads/u/notes.txt"}}""")
                else -> Reply.status(404)
            }
        }
        try {
            val file = temporaryFile("notes.txt", "hello world")

            val events = OpenCodeClient.liveValue.uploadFile(fixture.server, "/a b", file, "notes.txt").toList()

            assertEquals(OpenCodeUploadEvent.Finished("/srv/tmp/uploads/u/notes.txt"), events.last())
            val write = fixture.requests.first { it.route == "POST /api/experimental/fs/write" }
            assertEquals("/a b", write.queryValue("location[directory]"))
            val path = write.queryValue("path")
            assertNotNull(path)
            assertTrue(path, Regex("/srv/tmp/uploads/[0-9a-f-]{36}/notes\\.txt").matches(path!!))
            assertEquals("application/octet-stream", write.header("Content-Type"))
            assertEquals(fixture.server.basicAuthorization, write.header("Authorization"))
            if (write.body.isNotEmpty()) assertEquals("hello world", write.bodyText)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun uploadFailuresDistinguishAMissingRoute() = runTest {
        data class Case(val info: String, val writeStatus: Int, val writeBody: String, val unsupported: Boolean)
        val cases = listOf(
            Case("""{"version":"1","pid":1,"urls":[]}""", 200, "", true),
            Case("""{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/t"}}""", 404, "", true),
            Case("""{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/t"}}""", 404, """{"_tag":"LocationNotFoundError"}""", false),
            Case("""{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/t"}}""", 500, "", false),
        )
        for (case in cases) {
            val fixture = WireFixture { request ->
                if (request.route == "GET /api/info") Reply.Json(200, case.info) else Reply.Json(case.writeStatus, case.writeBody)
            }
            try {
                val file = temporaryFile("a.bin", "x")
                try {
                    OpenCodeClient.liveValue.uploadFile(fixture.server, null, file, "a.bin").toList()
                    fail("$case must fail")
                } catch (error: OpenCodeClientError) {
                    assertEquals(case.toString(), case.unsupported, error.isUnsupported)
                }
            } finally {
                fixture.close()
            }
        }
    }
}
