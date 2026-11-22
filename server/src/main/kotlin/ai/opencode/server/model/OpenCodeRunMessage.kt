package ai.opencode.server.model

/**
 * A message as a voice watch reads a run: its text, the background subagents it launched, and the
 * subagent whose completion it reports.
 */
data class OpenCodeRunMessage(
    val id: String,
    val type: String,
    val text: String = "",
    val launches: List<Launch> = emptyList(),
    /** A `synthetic` subagent completion: the child session it reports. */
    val completedChildID: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
) {
    data class Launch(val sessionID: String, val description: String?)

    data class ToolCall(val id: String, val name: String, val input: OpenCodeJSONValue)
}

data class OpenCodeRunMessagesPage(
    /** Oldest first. */
    val messages: List<OpenCodeRunMessage>,
    /** The cursor of the older page, null at the start of the history. */
    val next: String?,
)
