package ai.opencode.feature.composer

import ai.opencode.core.strings.localized
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormOption
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.TranscriptNoticeKind

/** Answer reading and writing the dock shares with its tests. */
object RequestDockAnswer {
    /**
     * A subagent's request names it, as the drawer does; a global or MCP form names its
     * server when present instead of falling back to “Subagent”.
     */
    fun caption(
        caption: String,
        sessionID: String,
        mcpServer: String? = null,
        currentSessionID: String?,
        familySessions: List<OpenCodeSession>,
    ): String {
        val owner = origin(sessionID, mcpServer, currentSessionID, familySessions) ?: return caption
        if (caption.isEmpty()) return owner
        if (sessionID == "global" && caption.contains(owner, ignoreCase = true)) return caption
        return "$owner · $caption"
    }

    fun origin(form: OpenCodeFormRequest, currentSessionID: String?, familySessions: List<OpenCodeSession>): String? =
        origin(form.sessionID, form.mcpServer, currentSessionID, familySessions)

    private fun origin(sessionID: String, mcpServer: String?, currentSessionID: String?, familySessions: List<OpenCodeSession>): String? {
        if (sessionID == currentSessionID) return null
        if (sessionID == "global") return mcpServer?.trim()?.ifEmpty { null }
        val owner = familySessions.firstOrNull { it.id == sessionID }
        return owner?.title ?: AgentDisplayName.of(owner?.agent) ?: TranscriptNoticeKind.Source.Subagent.title
    }

    fun options(field: OpenCodeFormField): List<OpenCodeFormOption> {
        if (field.type == OpenCodeFormFieldType.Boolean) {
            return listOf(OpenCodeFormOption(value = "true", label = localized(R.string.composer_request_yes)), OpenCodeFormOption(value = "false", label = localized(R.string.composer_request_no)))
        }
        return field.options ?: emptyList()
    }

    /** The question for a question form; the field's own title otherwise. */
    fun prompt(field: OpenCodeFormField, form: OpenCodeFormRequest): String {
        if (form.isQuestion) return field.description?.ifEmpty { null } ?: field.title?.ifEmpty { null } ?: field.key
        return field.title?.ifEmpty { null } ?: field.description?.ifEmpty { null } ?: form.title
    }

    fun values(value: OpenCodeFormValue?): List<String> = (value as? OpenCodeFormValue.Strings)?.values ?: emptyList()

    fun knownValues(value: OpenCodeFormValue?, field: OpenCodeFormField): List<String> {
        val known = options(field).map { it.value }.toSet()
        return values(value).filter { it in known }
    }

    fun toggling(option: String, value: OpenCodeFormValue?): OpenCodeFormValue {
        val current = values(value).toMutableList()
        val index = current.indexOf(option)
        if (index >= 0) current.removeAt(index) else current += option
        return OpenCodeFormValue.Strings(current)
    }

    fun pickedValue(option: String, field: OpenCodeFormField, current: OpenCodeFormValue?): OpenCodeFormValue = when (field.type) {
        OpenCodeFormFieldType.Multiselect -> toggling(option, current)
        OpenCodeFormFieldType.Boolean -> OpenCodeFormValue.Boolean(option == "true")
        else -> OpenCodeFormValue.Str(option)
    }

    fun isSelected(option: String, value: OpenCodeFormValue?): Boolean = when (value) {
        is OpenCodeFormValue.Str -> value.value == option
        is OpenCodeFormValue.Strings -> option in value.values
        is OpenCodeFormValue.Boolean -> value.value.toString() == option
        else -> false
    }

    /** What was written under Other: the answer that matches no option. */
    fun customText(value: OpenCodeFormValue?, options: List<OpenCodeFormOption>): String {
        val known = options.map { it.value }.toSet()
        return when (value) {
            is OpenCodeFormValue.Str -> if (value.value in known) "" else value.value
            is OpenCodeFormValue.Strings -> value.values.filter { it !in known }.joinToString(", ")
            else -> ""
        }
    }

    fun hasAnswer(value: OpenCodeFormValue?): Boolean = when (value) {
        is OpenCodeFormValue.Str -> value.value.isNotBlank()
        is OpenCodeFormValue.Strings -> value.values.any { it.isNotBlank() }
        is OpenCodeFormValue.Number, is OpenCodeFormValue.Boolean -> true
        null -> false
    }

    /** Whether the bar's words are the answer, rather than set aside for a choice. */
    fun isCustom(text: String, value: OpenCodeFormValue?): Boolean {
        if (text.isBlank()) return false
        return when (value) {
            is OpenCodeFormValue.Str -> value.value == text
            is OpenCodeFormValue.Strings -> text in value.values
            is OpenCodeFormValue.Number -> true
            else -> false
        }
    }

    /**
     * The tool call that asks, from this transcript or a subagent's: [transcripts] are the
     * session's items followed by its family transcript cache.
     */
    fun toolCall(permission: OpenCodePermissionRequest, transcripts: List<List<OpenCodeTranscriptItem>>): OpenCodeToolCall? {
        val source = permission.source ?: return null
        for (items in transcripts) {
            for (item in items) {
                if (item is OpenCodeTranscriptItem.Tool && item.call.callID == source.id && item.call.messageID == source.messageID) {
                    return item.call
                }
            }
        }
        return null
    }
}

/**
 * Questions dock, as does a form with one simple field; anything else opens the request
 * sheet from the dock. Decided by the form alone, so answering never moves it between the two.
 */
val OpenCodeFormRequest.fitsDock: Boolean
    get() = isQuestion || (fields.size == 1 && fields[0].type != OpenCodeFormFieldType.External)
