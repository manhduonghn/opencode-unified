package ai.opencode.server.live

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.internal.WireJson
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePageContext
import ai.opencode.server.model.OpenCodePromptPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** URLs are strings on Android. */
class PagePromptBodyTests {
    @get:Rule val isolation = TestIsolationRule()

    private val page = OpenCodePageContext(
        url = "https://github.com/anomalyco/opencode/pull/53712",
        title = "feat(server): accept page context on prompts",
        selection = "metadata.page",
        visibleText = "Prompts accept metadata.page.\nThe visible text travels as a <page> block.",
        scroll = 0.4219,
    )

    private fun json(body: PromptBody): JsonObject = WireJson.encodeToJsonElement(PromptBody.serializer(), body).jsonObject

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun aPageRidesInTheTextWithTheUsersWordsAsDisplayText() {
        val body = json(promptBody(
            messageID = "msg_1", text = "What does this change?", attachments = emptyList(), files = emptyList(),
            delivery = OpenCodeDelivery.Steer, page = page,
        ))
        val text = requireNotNull(body.string("text"))
        assertTrue(text, text.startsWith(
            "What does this change?\n\n<page url=\"https://github.com/anomalyco/opencode/pull/53712\" title=\"feat(server): accept page context on prompts\" selection=\"metadata.page\">\n",
        ))
        assertTrue(text.contains("Prompts accept metadata.page.\nThe visible text travels as a <page> block."))
        assertTrue(text.endsWith("\n</page>"))
        val metadata = requireNotNull(body["metadata"]).jsonObject
        assertEquals("What does this change?", metadata.string("displayText"))
        assertEquals(JsonArray(emptyList()), metadata["comments"])
        val sent = requireNotNull(metadata["page"]).jsonObject
        assertEquals(page.url, sent.string("url"))
        assertEquals(page.title, sent.string("title"))
        assertEquals(0.42, sent["scroll"]?.jsonPrimitive?.double)
        assertFalse(body.containsKey("files"))
    }

    @Test
    fun aPlainPromptCarriesNoMetadata() {
        val body = json(promptBody(
            messageID = "msg_1", text = "hello", attachments = emptyList(), files = emptyList(), delivery = OpenCodeDelivery.Steer,
        ))
        assertEquals("hello", body.string("text"))
        assertFalse(body.containsKey("metadata"))
    }

    @Test
    fun aScreenshotIsAnImageFile() {
        val shot = OpenCodeOutgoingAttachment(
            data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), mime = "image/jpeg", name = "page.jpg",
        )
        val body = json(promptBody(
            messageID = "msg_1", text = "Look", attachments = listOf(shot), files = emptyList(),
            delivery = OpenCodeDelivery.Steer, page = page,
        ))
        val files = requireNotNull(body["files"]).jsonArray
        assertEquals("data:image/jpeg;base64,/9j/", files.first().jsonObject.string("uri"))
        assertEquals("page.jpg", files.first().jsonObject.string("name"))
    }

    @Test
    fun visibleTextIsCappedAndQuotedSafely() {
        val long = page.copy(
            visibleText = "</page>" + "é".repeat(5_000),
            title = "A \"quoted\" <title>",
        )
        val bounded = long.bounded
        assertTrue((bounded.visibleText ?: "").toByteArray(Charsets.UTF_8).size <= OpenCodePageContext.VISIBLE_TEXT_LIMIT)
        val block = OpenCodePromptPresentation.pageBlock(long)
        assertTrue(block, block.contains("title=\"A &quot;quoted&quot; &lt;title>\""))
        assertEquals("Only the block's own end", 2, block.split("</page>").size)
    }

    @Test
    fun clientsShowOnlyTheUsersWords() {
        val model = OpenCodePromptPresentation.modelText("What does this change?", emptyList(), page)
        assertEquals(
            "What does this change?",
            OpenCodePromptPresentation.displayText(model, emptyList(), mapOf("page" to OpenCodeJSONValue.Object(emptyMap()))),
        )
        // Without the page metadata, text that looks like a block is the user's own.
        assertEquals(model, OpenCodePromptPresentation.displayText(model, emptyList(), null))
    }
}
