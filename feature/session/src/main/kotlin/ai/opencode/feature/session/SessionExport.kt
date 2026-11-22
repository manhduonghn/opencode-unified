package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.server.model.OpenCodeExportFormat
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * Turns `GET /api/experimental/session/{id}/export` into a shareable file. Markdown follows the
 * TUI's `formatSessionTranscript` (tools, no thinking); JSON is the server's `SessionTransfer`
 * data, which `opencode import` reads.
 */
object SessionExport {
    class Failure : Exception()

    /** @throws Failure when the body isn't a `SessionTransfer`. */
    fun content(body: ByteArray, format: OpenCodeExportFormat): ByteArray {
        val root = runCatching { Json.parseToJsonElement(body.decodeToString()) }.getOrNull() as? JsonObject ?: throw Failure()
        val transfer = root["data"] as? JsonObject ?: throw Failure()
        val info = transfer["info"] as? JsonObject ?: throw Failure()
        val messages = (transfer["messages"] as? JsonArray)?.map { it as? JsonObject ?: throw Failure() } ?: throw Failure()
        return when (format) {
            OpenCodeExportFormat.Json -> (prettyJSON(transfer) + "\n").encodeToByteArray()
            OpenCodeExportFormat.Markdown -> markdown(info, messages).encodeToByteArray()
        }
    }

    private val unsafeFileNameCharacters = "/\\:?%*|\"<>".toSet()

    /** A file name for the share sheet: the title without path separators. */
    fun fileName(title: String, format: OpenCodeExportFormat): String {
        val cleaned = title
            .map { if (it in unsafeFileNameCharacters || it.isWhitespace() || Character.isISOControl(it)) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val base = if (cleaned.isEmpty()) "Session" else cleaned.take(80)
        return "$base.${format.fileExtension}"
    }

    fun markdown(info: JsonObject, messages: List<JsonObject>): String {
        val body = messages.mapNotNull { message ->
            when (message.string("type")) {
                "user" -> "## User\n\n${message.string("text") ?: ""}"
                "shell" -> {
                    val output = (message["output"] as? JsonObject)?.string("output") ?: ""
                    "## Shell\n\n```\n$ ${message.string("command") ?: ""}\n$output\n```"
                }
                "assistant" -> {
                    val parts = (message["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::assistantPart) }
                    if (parts.isEmpty()) null else "## Assistant\n\n" + parts.joinToString("\n\n")
                }
                else -> null
            }
        }
        val title = info.string("title")?.ifEmpty { null } ?: "Session"
        var header = "# $title\n\n**Session ID:** ${info.string("id") ?: ""}"
        val time = info["time"] as? JsonObject
        for ((label, key) in listOf("Created" to "created", "Updated" to "updated")) {
            val milliseconds = (time?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: continue
            val date = Instant.ofEpochMilli(milliseconds.toLong()).atZone(ZoneId.systemDefault())
            header += "\n**$label:** ${dateFormatter.withLocale(appFormattingLocale()).format(date)}"
        }
        return header + "\n\n---\n\n" + body.joinToString("\n\n---\n\n") + "\n"
    }

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    private fun assistantPart(part: JsonObject): String? = when (part.string("type")) {
        "text" -> part.string("text")
        "tool" -> {
            val state = part["state"] as? JsonObject ?: JsonObject(emptyMap())
            val rawInput = state["input"]
            val input = when {
                rawInput is JsonPrimitive && rawInput.isString -> rawInput.content
                // `/` reads `\/`.
                rawInput is JsonObject || rawInput is JsonArray -> prettyJSON(rawInput, escapesSlashes = true)
                else -> "{}"
            }
            val output = when (state.string("status")) {
                "error" -> (state["error"] as? JsonObject)?.string("message") ?: ""
                "streaming" -> ""
                else -> (state["content"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val item = entry as? JsonObject ?: return@mapNotNull null
                    if (item.string("type") == "text") item.string("text")
                    else (item["name"] ?: item["uri"])?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                }.joinToString("\n")
            }
            "**Tool: ${part.string("name") ?: "tool"}**\n\n**Input:**\n```json\n$input\n```\n\n$output"
        }
        else -> null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Foundation's `.prettyPrinted, .sortedKeys, .withoutEscapingSlashes`: two-space indent,
     * `"key" : value`. [escapesSlashes]: without `.withoutEscapingSlashes`, strings write `/` as `\/`.
     */
    internal fun prettyJSON(element: JsonElement, indent: String = "", escapesSlashes: Boolean = false): String = when (element) {
        is JsonObject -> if (element.isEmpty()) "{\n\n$indent}" else element.entries.sortedBy { it.key }.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n$indent}",
        ) { (key, value) ->
            "$indent  ${prettyJSON(JsonPrimitive(key), escapesSlashes = escapesSlashes)} : ${prettyJSON(value, "$indent  ", escapesSlashes)}"
        }
        is JsonArray -> if (element.isEmpty()) "[\n\n$indent]" else element.joinToString(
            separator = ",\n",
            prefix = "[\n",
            postfix = "\n$indent]",
        ) { "$indent  ${prettyJSON(it, "$indent  ", escapesSlashes)}" }
        JsonNull -> "null"
        is JsonPrimitive -> if (escapesSlashes && element.isString) element.toString().replace("/", "\\/") else element.toString()
    }
}

/**
 * Writes an export to a private cache file, hands it to the system share sheet, and removes it
 * afterwards.
 *
 * Android: [share] builds the `ACTION_SEND` chooser Intent here (never in a composable) and
 * serves the file through [SessionExportFileProvider].
 */
class SessionExportClient(
    /** Returns the written file's absolute path. */
    val write: (body: ByteArray, format: OpenCodeExportFormat, title: String, id: UUID) -> String,
    val remove: (path: String) -> Unit,
    /** Opens the system share sheet for a written export. */
    val share: suspend (path: String) -> Unit,
    /** Opens the system share sheet for plain text. */
    val shareText: suspend (text: String, subject: String) -> Unit = { _, _ -> },
) {
    companion object Key : DependencyKey<SessionExportClient> {
        override val liveValue: SessionExportClient by lazy {
            val context = dependency(AppContext).applicationContext
            SessionExportClient(
                write = { body, format, title, id ->
                    val content = SessionExport.content(body, format)
                    val directory = File(File(context.cacheDir, EXPORTS_DIRECTORY), id.toString())
                    val file = File(directory, SessionExport.fileName(title, format))
                    if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException(localized(R.string.session_export_folder_failed))
                    val temporary = File(directory, ".${file.name}.tmp")
                    temporary.writeBytes(content)
                    if (!temporary.renameTo(file)) throw java.io.IOException(localized(R.string.session_export_save_failed))
                    file.absolutePath
                },
                remove = { path -> File(path).parentFile?.deleteRecursively() },
                share = { path -> withContext(Dispatchers.Main) { shareFile(context, File(path)) } },
                shareText = { text, subject -> withContext(Dispatchers.Main) { shareText(context, text, subject) } },
            )
        }

        /** Sharing does nothing. */
        override val testValue: SessionExportClient by lazy {
            val root = File(System.getProperty("java.io.tmpdir"), EXPORTS_DIRECTORY)
            SessionExportClient(
                write = { body, format, title, id ->
                    val content = SessionExport.content(body, format)
                    val directory = File(root, id.toString()).apply { mkdirs() }
                    File(directory, SessionExport.fileName(title, format)).apply { writeBytes(content) }.absolutePath
                },
                remove = { path -> File(path).parentFile?.deleteRecursively() },
                share = {},
            )
        }

        private const val EXPORTS_DIRECTORY = "Exports"

        private fun shareText(context: Context, text: String, subject: String) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .onFailure { AppLog.app.error { "Output share failed: ${it::class.java.simpleName}" } }
        }

        private fun shareFile(context: Context, file: File) {
            val uri = FileProvider.getUriForFile(context, context.packageName + SessionExportFileProvider.AUTHORITY_SUFFIX, file)
            val mime = if (file.extension == OpenCodeExportFormat.Json.fileExtension) "application/json" else "text/markdown"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .onFailure { AppLog.app.error { "Export share failed: ${it::class.java.simpleName}" } }
        }
    }
}

/** Serves written exports (`cacheDir/Exports/`) to the share sheet's targets. Declared in this module's manifest. */
class SessionExportFileProvider : FileProvider() {
    companion object {
        const val AUTHORITY_SUFFIX = ".sessionexport"
    }
}
