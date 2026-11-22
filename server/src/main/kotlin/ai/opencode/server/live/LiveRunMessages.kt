package ai.opencode.server.live

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.internal.list
import ai.opencode.server.internal.lenient
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeRunMessagesPage
import ai.opencode.server.model.nonEmpty
import ai.opencode.server.model.trimmedWhitespace
import kotlinx.serialization.json.JsonElement

internal suspend fun requestRunMessages(server: OpenCodeServer, sessionID: String, cursor: String?): OpenCodeRunMessagesPage {
    val response = perform(
        "/api/session/$sessionID/message",
        query = listOf("limit" to "40", if (cursor != null) "cursor" to cursor else "order" to "desc"),
        server = server,
    )
    return decodeRunMessages(response.data)
}

/**
 * A newest-first `/message` page, oldest first. Parts it can't read are skipped: a launch it
 * misses is only unwatched, never a failed read.
 */
internal fun decodeRunMessages(data: ByteArray): OpenCodeRunMessagesPage = decoded(data, "transcript") { element ->
    val payload = element.obj()
    val messages = payload.req("data") { it.list(RunMessagePayload::decode) }
    val next = payload.opt("cursor") { it.obj().opt("next") { n -> n.str() } }
    OpenCodeRunMessagesPage(messages.asReversed().map(RunMessagePayload::runMessage), next)
}

private class RunPartPayload(
    val id: String?,
    val type: String,
    val text: String?,
    val name: String?,
    val input: OpenCodeJSONValue?,
    val metadata: Map<String, OpenCodeJSONValue>?,
) {
    companion object {
        fun decode(element: JsonElement): RunPartPayload {
            val o = element.obj()
            val type = o.req("type") { it.str() }
            val isTool = type == "tool"
            // `try? decodeIfPresent(State.self, …)`: a malformed state drops both of its fields.
            val state = if (isTool) {
                o.lenient("state") { s ->
                    val so = s.obj()
                    so.opt("input") { OpenCodeJSONValue.of(it) } to so.opt("metadata") { m -> m.obj().mapValues { OpenCodeJSONValue.of(it.value) } }
                }
            } else {
                null
            }
            return RunPartPayload(
                id = if (isTool) o.lenient("id") { it.str() } else null,
                type = type,
                text = if (type == "text") o.lenient("text") { it.str() } else null,
                name = if (isTool) o.lenient("name") { it.str() } else null,
                input = state?.first,
                metadata = state?.second,
            )
        }
    }
}

private class RunMessagePayload(
    val id: String,
    val type: String,
    val text: String?,
    val content: List<RunPartPayload>?,
    val metadata: Map<String, OpenCodeJSONValue>?,
) {
    fun runMessage(): OpenCodeRunMessage {
        val parts = content.orEmpty()
        val partsText = parts.mapNotNull { it.text }.joinToString("\n")
        val text = when (type) {
            "user" -> text?.nonEmpty ?: partsText
            "assistant" -> partsText
            else -> ""
        }
        val launches = parts.mapNotNull { part ->
            if (part.type != "tool" || (part.name != "subagent" && part.name != "task")) return@mapNotNull null
            val input = part.input?.obj
            if (input?.get("notify") == OpenCodeJSONValue.Bool(false)) return@mapNotNull null
            val running = part.metadata?.get("status")?.string == "running"
            val legacyBackground = input?.get("background") == OpenCodeJSONValue.Bool(true)
            if (!running && !legacyBackground) return@mapNotNull null
            val sessionID = part.metadata?.get("sessionID")?.string ?: return@mapNotNull null
            OpenCodeRunMessage.Launch(sessionID, input?.get("description")?.string)
        }
        val toolCalls = parts.withIndex().mapNotNull { (index, part) ->
            val name = part.name
            if (part.type != "tool" || name == null || !name.startsWith("ios_voice_")) return@mapNotNull null
            OpenCodeRunMessage.ToolCall(
                id = part.id ?: "$id-tool-$index",
                name = name,
                input = (part.input ?: OpenCodeJSONValue.Object(emptyMap())).bounded(),
            )
        }
        val child = if (type == "synthetic" && metadata?.get("source")?.string == "subagent") metadata["childID"]?.string else null
        return OpenCodeRunMessage(
            id = id,
            type = type,
            text = text.trimmedWhitespace(),
            launches = launches,
            completedChildID = child,
            toolCalls = toolCalls,
        )
    }

    companion object {
        fun decode(element: JsonElement): RunMessagePayload {
            val o = element.obj()
            return RunMessagePayload(
                id = o.req("id") { it.str() },
                type = o.req("type") { it.str() },
                text = o.lenient("text") { it.str() },
                content = o.lenient("content") { it.list(RunPartPayload::decode) },
                metadata = o.lenient("metadata") { m -> m.obj().mapValues { OpenCodeJSONValue.of(it.value) } },
            )
        }
    }
}
