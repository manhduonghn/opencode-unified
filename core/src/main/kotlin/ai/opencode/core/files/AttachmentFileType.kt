package ai.opencode.core.files

import ai.opencode.core.R
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.appFormattingLocale
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * Moved to `:core` because the composer and the share target both use it. Mirrors the desktop app's
 * attachment typing (packages/app/src/composer/attachments/attachments.ts `attachmentMime`,
 * packages/session-ui/src/components/message-file.ts `typeLabel`). `MimeTypeMap` stands in for
 * `UTType`.
 */
object AttachmentFileType {
    val imageMimes: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    const val pdfMime = "application/pdf"

    /** The server rejects inline attachments above this size, so larger media takes the path route. */
    const val maxInlineBytes: Long = 20L * 1024 * 1024

    private val imageExtensions = mapOf(
        "gif" to "image/gif", "jpeg" to "image/jpeg", "jpg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    )
    private val textMimes = setOf(
        "application/json", "application/ld+json", "application/toml", "application/x-toml",
        "application/x-yaml", "application/xml", "application/yaml",
    )

    /** The declared MIME type for a file name, as a browser's `File.type` would report it. */
    fun declaredMime(fileName: String): String? {
        val suffix = fileExtension(fileName)
        if (suffix.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix)
    }

    /**
     * Text-like files normalize to text/plain so the chip labels them as text; every other
     * file keeps a binary type. [head] is the file's first bytes.
     */
    fun mime(fileName: String, declared: String?, head: ByteArray): String {
        val type = (declared ?: "").split(";", limit = 2).first().trim().lowercase()
        if (type in imageMimes || type == pdfMime) return type
        val suffix = fileExtension(fileName)
        val fallback = imageExtensions[suffix] ?: if (suffix == "pdf") pdfMime else null
        if ((type.isEmpty() || type == "application/octet-stream") && fallback != null) return fallback
        if (type.startsWith("text/") || type in textMimes || type.endsWith("+json") || type.endsWith("+xml")) {
            return "text/plain"
        }
        val binary = type.ifEmpty { "application/octet-stream" }
        val count = minOf(head.size, 4096)
        var control = 0
        for (index in 0 until count) {
            val byte = head[index].toInt() and 0xff
            if (byte == 0) return binary
            if (byte < 9 || (byte in 14..31)) control += 1
        }
        if (count > 0 && control.toDouble() / count > 0.3) return binary
        return "text/plain"
    }

    /** Reads only the first 4 KB to sniff text. */
    fun mime(file: File, fileName: String? = null): String {
        val name = fileName ?: file.name
        val head = runCatching {
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(4096)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }.getOrDefault(ByteArray(0))
        return mime(name, declaredMime(name), head)
    }

    /** The chip subtitle: "PDF", a language name, or the extension in capitals. */
    fun label(name: String, mime: String): String {
        if (mime == pdfMime) return "PDF"
        val suffix = fileExtension(name)
        if (suffix.isEmpty()) return localized(R.string.core_attachment_file_label)
        return languageNames[suffix] ?: suffix.uppercase()
    }

    fun subtitle(name: String, mime: String, size: Long): String =
        localized(R.string.core_attachment_subtitle, label(name, mime), byteCount(size))

    /** An SF Symbol name (drawn through `OpenCodeIcon(sfName, …)`). */
    fun symbolName(name: String, mime: String): String {
        if (mime == pdfMime) return "doc.richtext"
        if (mime.startsWith("image/")) return "photo"
        if (mime.startsWith("video/")) return "film"
        if (mime.startsWith("audio/")) return "waveform"
        if (mime == "text/plain") {
            return if (languageNames[fileExtension(name)] == null) "doc.text" else "chevron.left.forwardslash.chevron.right"
        }
        val type = mime.lowercase()
        if (archiveMimes.any { type == it } || type.endsWith("+zip")) return "doc.zipper"
        if (spreadsheetMarkers.any { type.contains(it) }) return "tablecells"
        if (presentationMarkers.any { type.contains(it) }) return "rectangle.on.rectangle"
        return "doc"
    }

    /** UTType `.archive`, `.spreadsheet`, `.presentation` conformance, by MIME type. */
    private val archiveMimes = setOf(
        "application/zip", "application/x-zip-compressed", "application/gzip", "application/x-gzip",
        "application/x-tar", "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar",
        "application/x-bzip2", "application/x-xz", "application/java-archive", "application/x-apple-diskimage",
    )
    private val spreadsheetMarkers = listOf(
        "vnd.ms-excel", "spreadsheetml", "opendocument.spreadsheet", "vnd.apple.numbers", "x-iwork-numbers",
    )
    private val presentationMarkers = listOf(
        "vnd.ms-powerpoint", "presentationml", "opendocument.presentation", "vnd.apple.keynote", "x-iwork-keynote",
    )

    /** A plain file name the server can use as the last path component. */
    fun sanitizedName(name: String): String {
        val cleaned = name.replace("/", "-").replace("\\", "-").trim { it.isWhitespace() || it.isControlCharacter() }
        val trimmed = cleaned.dropWhile { it == '.' }
        return trimmed.ifEmpty { "file" }
    }

    /** The lowercased extension of the last path component; a leading dot is a dotfile like .gitignore, not an extension. */
    fun fileExtension(name: String): String {
        val base = name.split("/").lastOrNull { it.isNotEmpty() } ?: name
        val dot = base.lastIndexOf('.')
        if (dot <= 0) return ""
        return base.substring(dot + 1).lowercase()
    }

    /**
     * `ByteCountFormatter.string(fromByteCount:countStyle: .file)`: decimal units, "Zero KB",
     * whole bytes and KB, one decimal for MB, two for GB and above, trailing zeros dropped.
     */
    fun byteCount(bytes: Long): String {
        if (bytes == 0L) return localized(R.string.core_byte_count_zero)
        val symbols = DecimalFormatSymbols(appFormattingLocale())
        val magnitude = kotlin.math.abs(bytes)
        if (magnitude < 1000) {
            val formatted = DecimalFormat("#,##0", symbols).format(bytes)
            return localizedPlural(R.plurals.core_byte_count_bytes, magnitude.toInt(), formatted)
        }
        val units = listOf(
            R.string.core_byte_count_kilobytes to 0,
            R.string.core_byte_count_megabytes to 1,
            R.string.core_byte_count_gigabytes to 2,
            R.string.core_byte_count_terabytes to 2,
            R.string.core_byte_count_petabytes to 2,
        )
        var value = bytes.toDouble() / 1000
        var index = 0
        while (index < units.lastIndex && kotlin.math.abs(value) >= 999.5) {
            value /= 1000
            index += 1
        }
        val (unit, digits) = units[index]
        val format = DecimalFormat(if (digits == 0) "#,##0" else "#,##0." + "#".repeat(digits), symbols)
        return localized(unit, format.format(value))
    }

    private fun Char.isControlCharacter(): Boolean {
        val type = Character.getType(this)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    /** The common subset of the language names desktop takes from Shiki. */
    private val languageNames = mapOf(
        "c" to "C", "cjs" to "JavaScript", "cpp" to "C++", "cs" to "C#", "css" to "CSS", "dart" to "Dart",
        "go" to "Go", "html" to "HTML", "java" to "Java", "js" to "JavaScript",
        "json" to "JSON", "jsx" to "JSX", "kt" to "Kotlin", "lua" to "Lua", "md" to "Markdown", "mjs" to "JavaScript",
        "php" to "PHP", "py" to "Python", "rb" to "Ruby", "rs" to "Rust", "sh" to "Shell", "sql" to "SQL",
        "swift" to "Swift", "toml" to "TOML", "ts" to "TypeScript", "tsx" to "TSX",
        "xml" to "XML", "yaml" to "YAML", "yml" to "YAML", "zsh" to "Zsh",
    )
}
