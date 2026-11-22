package ai.opencode.server.live

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.internal.WireJson
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ComposerAutocompleteTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun experimentalFlagOffProducesByteIdenticalPromptBodyToMain() {
        val attachment = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000801",
            data = byteArrayOf(0x41, 0x42),
            mime = "image/png",
            name = "img.png",
        )
        val bodyWithoutMentions = promptBody(
            messageID = "msg_plain",
            text = "Check @kit-motion",
            attachments = listOf(attachment),
            files = emptyList(),
            delivery = OpenCodeDelivery.Steer,
            mentions = null,
        )
        val json = WireJson.encodeToString(PromptBody.serializer(), bodyWithoutMentions)
        assertFalse(json.contains("\"agents\""))
        assertFalse(json.contains("\"skills\""))
        assertFalse(json.contains("\"mention\""))
        assertFalse(json.contains("\"description\""))
    }
}
