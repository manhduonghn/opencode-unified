package ai.opencode.server.model

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class PastedTextThresholdTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun lines(count: Int): String = (1..count).joinToString("\n") { "line $it" }

    @Test
    fun pastesOverTwelveLinesOrAThousandCharactersBecomeCards() {
        assertFalse(PastedText.isLong("A short note"))
        assertFalse(PastedText.isLong(lines(12)))
        assertFalse("A trailing newline adds no line", PastedText.isLong(lines(12) + "\n"))
        assertTrue(PastedText.isLong(lines(13)))
        assertFalse(PastedText.isLong("a".repeat(1_000)))
        assertTrue(PastedText.isLong("a".repeat(1_001)))
    }

    @Test
    fun cardSummaryAndPreview() {
        val text = "\n\nFatal error: index out of range\n\tat UploadQueue.swift:42\n" + lines(40)
        assertEquals(44, PastedText.lineCount(text))
        assertEquals(3, PastedText.lineCount(lines(3) + "\n"))
        assertEquals(listOf("Fatal error: index out of range", "at UploadQueue.swift:42"), PastedText.preview(text))
        assertTrue(PastedTextSummary("one\ntwo").summary.startsWith("2 lines · "))
        assertEquals("3 lines", PastedTextSummary(lines(3)).length)
        assertEquals("2 KB", PastedTextSummary("a".repeat(2048)).length)
    }

    /**
     * 5 MB on one line and 25,000 lines: deciding, previewing, and counting stay quick, and the
     * preview never copies the long line.
     */
    @Test
    fun megabytePastesStayBounded() {
        val oneLine = "Fatal error: minified bundle " + "0123456789abcdef".repeat(5 * 1024 * 1024 / 16)
        val manyLines = (0 until 25_000).joinToString("\n") { "$it  UploadQueue.drain(batch:) retrying upload" }
        for (text in listOf(oneLine, manyLines)) {
            // Bounded: a prefix of at most 1,000 characters or 4 KB, however long.
            val decided = measureTime { assertTrue(PastedText.isLong(text)) }
            val previewed = measureTime { assertTrue(PastedText.preview(text).all { it.length <= 200 }) }
            if (System.getenv("CI") != "true") {
                assertTrue("decided in $decided", decided < 50.milliseconds)
                assertTrue("previewed in $previewed", previewed < 50.milliseconds)
            }
        }
        assertEquals(listOf("Fatal error: minified bundle " + "0123456789abcdef".repeat(11).take(171)), PastedText.preview(oneLine))
        assertEquals(
            listOf("0  UploadQueue.drain(batch:) retrying upload", "1  UploadQueue.drain(batch:) retrying upload"),
            PastedText.preview(manyLines),
        )

        val long = PastedTextSummary(oneLine)
        assertEquals(1, long.lineCount)
        assertEquals(oneLine.toByteArray().size, long.byteCount)
        assertEquals(oneLine.toByteArray().size, long.longestLineBytes)
        assertTrue("Past 2 MB, and one line too long for a row", long.opensAsPlainText)
        val many = PastedTextSummary(manyLines)
        assertEquals(25_000, many.lineCount)
        assertFalse("About 1 MB of short lines opens numbered", many.opensAsPlainText)
    }

    @Test
    fun attachmentIsAPlainTextFileNamedPastedText() {
        val attachment = PastedText.attachment("a\r\nb\rc")
        assertEquals("text/plain", attachment.mime)
        assertEquals("Pasted Text", attachment.name)
        assertEquals("Line endings normalised like desktop's", "a\nb\nc", attachment.pastedText)
        assertTrue(attachment.data.contentEquals("a\nb\nc".toByteArray()))
        assertEquals(PastedTextSummary("a\nb\nc"), attachment.pasted)
        assertTrue(attachment.dataURL.startsWith("data:text/plain;base64,"))
        assertNull(OpenCodeOutgoingAttachment(data = "%PDF".toByteArray(), mime = "application/pdf", name = "Spec.pdf").pastedText)
    }
}

/** What the app posts is what V2 stores. */
class PastedTextRoundTripTests {
    @get:Rule val isolation = TestIsolationRule()

    private val log = (1..30).joinToString("\n") { "2026-10-09 12:00:${"%02d".format(it)} worker[$it] retrying upload" }

    @Test
    fun promptCarriesThePasteAsAnInlineTextFile() = runTest {
        val fixture = WireFixture { Reply.Json(200, "{}") }
        try {
            val paste = PastedText.attachment(log)

            OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "Why does this loop?", listOf(paste), emptyList(), "msg_1", OpenCodeDelivery.Steer)

            val request = fixture.requests.first()
            val uri = "data:text/plain;base64," + Base64.getEncoder().encodeToString(log.toByteArray())
            assertEquals(
                Json.parseToJsonElement(
                    """{"id":"msg_1","text":"Why does this loop?","files":[{"uri":"$uri","name":"Pasted Text"}],"delivery":"steer"}""",
                ),
                request.json(),
            )
        } finally {
            fixture.close()
        }
    }
}
