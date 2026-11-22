package ai.opencode.feature.transcript

import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeClientDecodingTests {
    @Test
    fun aFailedRunShowsItsStepsErrorOnceNotTheIdleRepeat() {
        val error = OpenCodeRunError(type = "provider.content-filter", message = "Provider blocked the response")
        val items = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_u", text = "Fix it"),
            OpenCodeTranscriptItem.RunError(id = "msg_a-error", error = error),
            OpenCodeTranscriptItem.RunError(id = "msg_idle", error = OpenCodeRunError(type = error.type, message = error.message, beforeReply = true)),
            OpenCodeTranscriptItem.UserText(id = "msg_u2", text = "Try another way"),
            OpenCodeTranscriptItem.RunError(id = "msg_idle2", error = OpenCodeRunError(type = "provider.no-route", message = "", beforeReply = true)),
        )
        val ids = TranscriptEntry.build(items).mapNotNull { entry ->
            ((entry as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.RunError)?.id
        }
        assertEquals(listOf("msg_a-error", "msg_idle2"), ids)
    }
}
