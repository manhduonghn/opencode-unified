package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.ParsedMessageCache
import ai.opencode.server.live.decodeTranscriptPage
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A refresh reuses the rows of messages whose content hasn't changed, so it parses (Markdown,
 * syntax highlighting) only the ones that did.
 */
class ParsedMessageCacheTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun page(run: String, replies: List<String>): ByteArray {
        val messages = mutableListOf<String>()
        for ((index, text) in replies.withIndex()) {
            messages += """{"id":"msg_${run}_$index","type":"assistant","content":[{"type":"text","text":"$text"}]}"""
        }
        return """{"data":[${messages.asReversed().joinToString(",")}]}""".toByteArray()
    }

    private class Decoded(val items: List<OpenCodeTranscriptItem>, val parsed: Int)

    private suspend fun decode(data: ByteArray): Decoded {
        val tally = AtomicInteger()
        val items = ParsedMessageCache.parsed.withValue(tally) { decodeTranscriptPage(data, null).items }
        return Decoded(items, tally.get())
    }

    private fun newRun(): String = UUID.randomUUID().toString().take(8)

    @Test fun aRefreshParsesOnlyTheMessagesThatChanged() = runTest {
        val run = newRun()
        val replies = mutableListOf<String>()
        for (index in 0 until 30) {
            replies += "Reply $index with `code` and **bold**\n\n```swift\nlet x = $index\n```".replace("\n", "\\n")
        }
        val first = decode(page(run, replies))
        assertEquals(30, first.parsed)
        val again = decode(page(run, replies))
        assertEquals("An unchanged window parses nothing", 0, again.parsed)
        assertEquals(first.items, again.items)

        val edited = replies.toMutableList()
        edited[29] += " and more"
        val changed = decode(page(run, edited))
        assertEquals("Only the message that changed is parsed", 1, changed.parsed)
        assertEquals(first.items.dropLast(1), changed.items.dropLast(1))
        assertNotEquals(first.items.last(), changed.items.last())
    }

    @Test fun inlineImagesAreNeverKept() = runTest {
        val run = newRun()
        val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val data = """{"data":[{"id":"msg_${run}_img","type":"user","text":"Look","files":[{"mime":"image/png","data":"$png","source":{"type":"inline"}}]}]}""".toByteArray()
        assertEquals(1, decode(data).parsed)
        assertEquals("A message holding image bytes is parsed again rather than kept", 1, decode(data).parsed)
    }

    @Test fun largePastedFilesAreNeverKept() = runTest {
        val run = newRun()
        val text = Base64.getEncoder().encodeToString("pasted line\n".repeat(7_000).toByteArray())
        val data = """{"data":[{"id":"msg_${run}_paste","type":"user","text":"Read this","files":[{"mime":"text/plain","name":"paste.txt","data":"$text","source":{"type":"inline"}}]}]}""".toByteArray()
        val first = decode(data)
        assertTrue(first.items.any { it is OpenCodeTranscriptItem.UserFiles })
        assertEquals("A pasted file over 64 KB is parsed again rather than kept", 1, decode(data).parsed)
    }

    @Test fun theCacheStaysBounded() {
        val cache = ParsedMessageCache()
        for (index in 0..ParsedMessageCache.LIMIT) {
            cache.items("m$index", index) { listOf(OpenCodeTranscriptItem.UserText(id = "m$index", text = "")) }
        }
        var parsed = 0
        // The newest half survived the trim; the oldest went.
        cache.items("m${ParsedMessageCache.LIMIT}", ParsedMessageCache.LIMIT) { parsed += 1; emptyList() }
        cache.items("m0", 0) { parsed += 1; emptyList() }
        assertEquals(1, parsed)
    }
}
