package ai.opencode.server.testing

import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeRunMessagesPage

/**
 * `:server`'s internal wire decoders for other modules' tests. The decoders stay `internal` to
 * `:server`; only tests reach them, through here.
 */
object WireDecoders {
    /** One `/run/messages` page body, oldest message first. */
    fun decodeRunMessages(data: ByteArray): OpenCodeRunMessagesPage = ai.opencode.server.live.decodeRunMessages(data)

    /** One SSE `data:` payload: null for a frame the app ignores. */
    fun decodeStreamFrame(text: String): OpenCodeServerEvent? = ai.opencode.server.live.decodeStreamFrame(text)
}
