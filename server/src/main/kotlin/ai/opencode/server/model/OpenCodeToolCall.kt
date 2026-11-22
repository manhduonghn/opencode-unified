package ai.opencode.server.model

import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.strings.localized
import ai.opencode.server.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OpenCodeToolCall(
    val callID: String,
    val messageID: String? = null,
    val name: String,
    val status: Status,
    val input: OpenCodeJSONValue,
    val output: List<String>,
    val error: String? = null,
    /** The error's `type`, such as `permission.rejected`. */
    val errorType: String? = null,
    val metadata: Map<String, OpenCodeJSONValue>,
    val images: List<OpenCodeImageAttachment>? = null,
    /** When the tool began running (`time.ran`, else `time.created`). REST windows only. */
    @Serializable(with = InstantSerializer::class) val startedAt: Instant? = null,
    /** When it settled (`time.completed`), from REST windows only. */
    @Serializable(with = InstantSerializer::class) val completedAt: Instant? = null,
    /** Blob-backed movies are separate from the image mosaic. */
    val videos: List<OpenCodeTranscriptFile>? = null,
) {
    @Serializable
    enum class Status(val rawValue: String) {
        @SerialName("streaming") Streaming("streaming"),
        @SerialName("running") Running("running"),
        @SerialName("completed") Completed("completed"),
        @SerialName("error") Error("error");

        companion object {
            fun tool(value: String?): Status = entries.firstOrNull { it.rawValue == value } ?: Completed

            fun shell(value: String?, exit: Int?): Status = when {
                value == "running" -> Running
                value == "exited" && (exit == null || exit == 0) -> Completed
                else -> Error
            }
        }
    }

    val imageAttachments: List<OpenCodeImageAttachment> get() = images ?: emptyList()

    val isShell: Boolean get() = name == "shell" || name == "bash"

    /** The row title shared with the Used N summary: "Shell", "Read", "Web fetch". */
    val displayName: String get() = displayName(name)

    private fun inputString(key: String): String? = input.obj?.get(key)?.string?.trimmedWhitespace()?.nonEmpty

    /** The path a read, write, or edit touched (`path`, `filePath`, `file_path`). */
    val inputPath: String? get() = listOf("path", "filePath", "file_path").firstNotNullOfOrNull(::inputString)

    /** The file a read, write, or edit touched, when its name is one the viewer can open (folders and globs are not). */
    val fileReference: AgentFileReference?
        get() {
            if (isShell) return null
            val path = inputPath ?: return null
            if (path.contains("*")) return null
            return AgentFileReference.withLocation(path)?.takeIf { it.hasViewableName }
        }

    /** The row's one-line detail: the command, file name, pattern, URL, or query. */
    val subtitle: String?
        get() {
            if (isShell) return inputString("command")?.firstLine
            if (name == "execute") {
                inputString("code")?.let { code ->
                    val tools = calledTools(code)
                    return if (tools.isEmpty()) code.firstLine else tools.joinToString(", ")
                }
            }
            inputPath?.let { path -> return path.trimEnd('/').substringAfterLast('/').ifEmpty { path } }
            return listOf("pattern", "url", "query", "description", "name").firstNotNullOfOrNull(::inputString)?.firstLine
        }

    /** Readable arguments for the expanded row: scalars as text, anything structured as compact JSON. */
    val arguments: List<Pair<String, String>>
        get() {
            val values = input.obj ?: run {
                val raw = (input as? OpenCodeJSONValue.Str)?.value
                return if (!raw.isNullOrEmpty()) listOf("input" to raw) else emptyList()
            }
            return values.keys.sorted().mapNotNull { key ->
                if (isShell && (key == "command" || key == "description")) return@mapNotNull null
                when (val value = values[key] ?: return@mapNotNull null) {
                    OpenCodeJSONValue.Null -> null
                    is OpenCodeJSONValue.Str -> key to value.value
                    is OpenCodeJSONValue.Bool -> key to if (value.value) "true" else "false"
                    is OpenCodeJSONValue.Number -> key to formatPlainNumber(value.value)
                    is OpenCodeJSONValue.Array, is OpenCodeJSONValue.Object -> key to value.compactText
                }
            }
        }

    /** The question tool remains in transcript history after it is answered. */
    val questions: List<OpenCodeQuestion>?
        get() {
            if (name != "question") return null
            val values = (input.obj?.get("questions") as? OpenCodeJSONValue.Array)?.values ?: return null
            return values.mapNotNull(OpenCodeQuestion::fromToolInput).ifEmpty { null }
        }

    /** The question tool's answers, one list per question (`metadata.answers`). */
    val questionAnswers: List<List<String>>
        get() {
            val values = (metadata["answers"] as? OpenCodeJSONValue.Array)?.values ?: return emptyList()
            return values.map { value -> (value as? OpenCodeJSONValue.Array)?.values?.mapNotNull { it.string } ?: emptyList() }
        }

    /** The person said no: a denied permission or a dismissed question. */
    enum class Decline { Denied, Dismissed }

    val decline: Decline?
        get() {
            if (status != Status.Error) return null
            if (name == "question") return Decline.Dismissed
            if (errorType == "permission.rejected" || error == DECLINED_MESSAGE) return Decline.Denied
            return null
        }

    /** What the person said when they denied or dismissed with a reason. */
    val declineReason: String?
        get() {
            if (decline == null) return null
            val error = error?.trimmedWhitespace()?.nonEmpty ?: return null
            if (error == DECLINED_MESSAGE || error == "The user dismissed this question") return null
            return error
        }

    companion object {
        const val DECLINED_MESSAGE = "The user declined this tool call"

        fun displayName(name: String): String {
            if (name == "bash") return localized(R.string.server_tool_shell)
            val bare = if (name.startsWith("opencode_") && name.length > 9) name.drop(9) else name
            val words = bare.replace("_", " ").replace("-", " ")
            return words.take(1).uppercase() + words.drop(1)
        }

        private val CALLED_TOOLS = Regex(
            "\\btools(?:\\.([A-Za-z_][\\w]*)|\\[\\s*[\"']([^\"']+)[\"']\\s*\\])(?:\\.([A-Za-z_][\\w]*)|\\[\\s*[\"']([^\"']+)[\"']\\s*\\])?\\s*\\(",
        )

        /** The tools a Code Mode script calls, in order: `slack.search` for `tools.slack.search(`. */
        fun calledTools(code: String): List<String> {
            val seen = mutableListOf<String>()
            for (match in CALLED_TOOLS.findAll(code)) {
                val namespace = match.groups[1]?.value ?: match.groups[2]?.value ?: continue
                val member = match.groups[3]?.value ?: match.groups[4]?.value
                val name = listOfNotNull(namespace, member).joinToString(".")
                if (name !in seen) seen += name
            }
            return seen
        }
    }
}

object OpenCodeToolBounds {
    const val TEXT_LIMIT = 20_000

    fun output(texts: List<String>): List<String> = texts.map { it.bounded(TEXT_LIMIT) }

    fun metadata(metadata: Map<String, OpenCodeJSONValue>): Map<String, OpenCodeJSONValue> {
        if (metadata.values.any { it.needsBounding(TEXT_LIMIT, 100) }) return metadata.mapValues { it.value.bounded() }
        return metadata
    }
}

data class OpenCodeQuestion(val question: String, val header: String) {
    companion object {
        internal fun fromToolInput(input: OpenCodeJSONValue): OpenCodeQuestion? {
            val value = input.obj ?: return null
            val question = value["question"]?.string?.trimmedWhitespace()?.ifEmpty { null } ?: return null
            return OpenCodeQuestion(question, value["header"]?.string?.trimmedWhitespace() ?: "")
        }
    }
}
