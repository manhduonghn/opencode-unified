package ai.opencode.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class OpenCodePermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String>,
    val save: List<String>? = null,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val source: Source? = null,
) {
    /** The tool call that asked, when a tool did. */
    @Serializable
    data class Source(val messageID: String, val id: String)

    /** An edit's unified diff (`metadata.files[0].patch`) and its file. */
    val filePreview: Pair<String, String>?
        get() {
            val files = (metadata?.get("files") as? OpenCodeJSONValue.Array)?.values ?: return null
            val first = files.firstOrNull()?.obj ?: return null
            val patch = first["patch"]?.string?.nonEmpty ?: return null
            return (first["file"]?.string ?: resources.firstOrNull() ?: "") to patch
        }
}

@Serializable
enum class OpenCodePermissionReply {
    @SerialName("once") Once,
    @SerialName("always") Always,
    @SerialName("reject") Reject,
}

data class OpenCodePendingRequests(
    val permissions: List<OpenCodePermissionRequest>,
    val forms: List<OpenCodeFormRequest> = emptyList(),
)

/** An undelivered message in a session's inbox (steering or queued). */
data class OpenCodePendingInput(
    val id: String,
    val text: String,
    /** Compaction barriers queue like input but carry no prompt of their own. */
    val isCompaction: Boolean = false,
    /** Synthetic instructions are not user prompts: a notice the transcript folds into the active turn. */
    val notice: OpenCodeTranscriptItem? = null,
    val delivery: OpenCodeDelivery = OpenCodeDelivery.Steer,
    /** Path attachments from `metadata.attachments`; [text] excludes their reference lines. */
    val files: List<OpenCodeFileReference> = emptyList(),
    /** Quotes from `metadata.comments`; [text] excludes their blocks. */
    val quotes: List<OpenCodeQuote> = emptyList(),
    /** Set when an agent sent it. */
    val source: OpenCodePromptSource? = null,
    /** Inline files that aren't images: chips, and Pasted Text cards. */
    val inlineFiles: List<OpenCodeTranscriptFile> = emptyList(),
    /** Attached images from the inbox payload (inline base64, `media:<hash>`, or unavailable placeholder). */
    val images: List<OpenCodeImageAttachment> = emptyList(),
)

/**
 * Client presentation for an admitted input. Local outgoing images take precedence while in flight;
 * otherwise inbox images are shown.
 */
data class OpenCodeQueuedInput(val input: OpenCodePendingInput, val images: List<OpenCodeImageAttachment> = input.images) {
    val id: String get() = input.id
    val files: List<OpenCodeFileReference> get() = input.files
    val text: String get() = input.text
    val isCompaction: Boolean get() = input.isCompaction
    val notice: OpenCodeTranscriptItem? get() = input.notice
    val delivery: OpenCodeDelivery get() = input.delivery

    /** A plain message waiting for the response to finish; everything else shows in the transcript. */
    val waitsInQueuedPill: Boolean get() = !isCompaction && notice == null && delivery == OpenCodeDelivery.Queue
}

/** An MCP server from `GET /api/mcp` and its connection status. */
data class OpenCodeMCPServer(val name: String, val status: Status, val integrationID: String?) {
    sealed interface Status {
        data object Connected : Status
        data object Pending : Status
        data object Disabled : Status
        data class Failed(val error: String) : Status
        data class NeedsAuth(val error: String) : Status
        data class Unknown(val raw: String) : Status
    }

    val id: String get() = name
}

/** A sign-in the server holds for a provider (`GET /api/integration`). */
data class OpenCodeAccount(val integrationID: String, val provider: String, val label: String, val method: Method) {
    enum class Method(val rawValue: String) {
        OAuth("oauth"), Key("key"), External("external"), Environment("environment");

        companion object {
            fun of(raw: String): Method? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    val id: String get() = "$integrationID|${method.rawValue}|$label"
}

/** A sign-in attempt from `POST /api/integration/{id}/connect/oauth`: open [url]. */
data class OpenCodeOAuthAttempt(val attemptID: String, val url: URI, val instructions: String, val mode: Mode) {
    enum class Mode(val rawValue: String) { Auto("auto"), Code("code") }
}

sealed interface OpenCodeOAuthStatus {
    data object Pending : OpenCodeOAuthStatus
    data object Complete : OpenCodeOAuthStatus
    data object Expired : OpenCodeOAuthStatus
    data class Failed(val message: String) : OpenCodeOAuthStatus
}

/** What the server can do beyond sessions, read once for the server's own location. */
data class OpenCodeServerCapabilities(
    val location: String?,
    val mcpServers: List<OpenCodeMCPServer>,
    val skills: List<OpenCodeAutocompleteSkill>,
    val accounts: List<OpenCodeAccount>,
) {
    companion object {
        val empty = OpenCodeServerCapabilities(null, emptyList(), emptyList(), emptyList())
    }
}

/** Where a project file outside the open session's directory lives. */
data class FileReadTarget(val location: String, val relativePath: String)
