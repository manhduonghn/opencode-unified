package ai.opencode.server.model

import ai.opencode.core.files.AttachmentFileType
import kotlinx.serialization.Serializable

/**
 * A file the agent named in its reply, a tool row, or a sent attachment, opened through the
 * server's file reader. Follows desktop's local-link handling, which also strips a `:line:col`
 * suffix.
 */
@Serializable
data class AgentFileReference(
    /** The path as the agent wrote it, without a `file://` prefix or `:line:col`. */
    val path: String,
    /** The 1-based line a `path:42` reference points at, for text files. */
    val line: Int? = null,
    /** When set, path is a media URI, never a workspace path. */
    val media: MediaFileOriginal? = null,
) {
    val id: String get() = path

    /** The card the viewer zooms out of and back into. */
    val zoomSourceID: String get() = "file:$path"

    val name: String
        get() = media?.name ?: path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path

    val kind: Kind
        get() = when {
            media?.mime?.startsWith("video/") == true -> Kind.Video
            media?.mime?.startsWith("audio/") == true -> Kind.Audio
            media?.mime == "application/pdf" -> Kind.Pdf
            else -> Kind.of(name)
        }

    /** The MIME type implied by the extension; the server guesses the same way. */
    val mime: String get() = media?.mime ?: mimeForExtension(AttachmentFileType.fileExtension(name)) ?: "application/octet-stream"

    val typeLabel: String
        get() = when (kind) {
            Kind.Video -> "Video · " + AttachmentFileType.fileExtension(name).uppercase()
            Kind.Audio -> "Audio · " + AttachmentFileType.fileExtension(name).uppercase()
            else -> AttachmentFileType.label(name, mime)
        }

    val symbolName: String
        get() = when (kind) {
            Kind.Video -> "film"
            Kind.Audio -> "waveform"
            Kind.Image -> "photo"
            Kind.Pdf -> "doc.richtext"
            Kind.Text -> AttachmentFileType.symbolName(name, "text/plain")
            Kind.Document -> AttachmentFileType.symbolName(name, mime)
        }

    @Serializable
    enum class Kind(val rawValue: String) {
        Video("video"), Audio("audio"), Image("image"), Pdf("pdf"), Text("text"), Document("document");

        /** Media and documents earn a card under the reply; code paths stay inline links so a code-heavy answer doesn't sprout a row of cards. */
        val showsCard: Boolean get() = this != Text

        val isVisual: Boolean get() = this == Image || this == Video

        /** Streams through range requests when the server supports them. */
        val isTimeBased: Boolean get() = this == Video || this == Audio

        /** Shown in a dark room and dragged down to dismiss, like Photos. */
        val isMedia: Boolean get() = this == Image || isTimeBased

        companion object {
            fun of(fileName: String): Kind {
                val suffix = AttachmentFileType.fileExtension(fileName)
                if (suffix in textExtensions || fileName.lowercase() in knownFileNames) return Text
                if (suffix == "pdf") return Pdf
                if (suffix in imageExtensions) return Image
                if (suffix in videoExtensions) return Video
                if (suffix in audioExtensions) return Audio
                // System types recognized beyond the sets above.
                return when (suffix) {
                    in fallbackVideoExtensions -> Video
                    in fallbackAudioExtensions -> Audio
                    in fallbackImageExtensions -> Image
                    in fallbackTextExtensions -> Text
                    else -> Document
                }
            }
        }
    }

    /** A file (not a folder) whose name the viewer recognizes. */
    val hasViewableName: Boolean
        get() = !path.endsWith("/") &&
            (name.lowercase() in knownFileNames || AttachmentFileType.fileExtension(name) in viewableExtensions)

    // MARK: - Resolving against the server

    /**
     * Where the server reads it: the session directory when the file lives inside it,
     * otherwise the file's own folder (desktop's approach). A file outside the session starts a
     * location for that folder on the server, so only an explicit tap, or a Markdown image
     * drawn on screen, resolves one.
     */
    data class Target(val location: String, val relativePath: String, val absolutePath: String)

    fun target(directory: String?): Target? {
        if (media != null) return null
        if (isWindowsAbsolute(path) || (directory != null && isWindowsAbsolute(directory))) return windowsTarget(directory)
        var path = this.path
        if (path.startsWith("~/") || path == "~") {
            val home = home(directory) ?: return null
            path = home + path.drop(1)
        }
        if (!path.startsWith("/")) {
            if (directory == null || !directory.startsWith("/")) return null
            path = "$directory/$path"
        }
        val absolute = normalized(path) ?: return null
        if (absolute == "/") return null
        val base = directory?.let(::normalized)
        if (base != null && absolute.startsWith(if (base == "/") "/" else "$base/")) {
            val relative = absolute.drop(if (base == "/") 1 else base.length + 1)
            return Target(base, relative, absolute)
        }
        val components = absolute.split('/').filter { it.isNotEmpty() }
        val last = components.lastOrNull() ?: return null
        val parent = "/" + components.dropLast(1).joinToString("/")
        return Target(parent, last, absolute)
    }

    /**
     * The same rules as [target] on a Windows server, compared without regard to case or
     * separator. A file inside the session reads from the session's location exactly as the
     * server spelled it, so it never starts another location for the same folder.
     */
    private fun windowsTarget(directory: String?): Target? {
        var path = this.path.replace('\\', '/')
        val base = directory?.let(::normalizedWindows)
        if (path.startsWith("~/") || path == "~") {
            val home = home(directory) ?: return null
            path = home + path.drop(1)
        } else if (path.startsWith("/") && base != null) {
            // `\tmp\a.log` is on the session's drive.
            path = base.take(2) + path
        }
        if (!isWindowsAbsolute(path)) {
            if (base == null) return null
            path = "$base/$path"
        }
        val absolute = normalizedWindows(path) ?: return null
        if (absolute.length <= 3) return null
        if (directory != null && base != null) {
            val prefix = if (base.endsWith("/")) base else "$base/"
            if (absolute.lowercase().startsWith(prefix.lowercase())) {
                return Target(directory, absolute.drop(prefix.length), absolute)
            }
        }
        val slash = absolute.lastIndexOf('/')
        if (slash < 0) return null
        var parent = absolute.substring(0, slash)
        if (parent.length == 2) parent += "/"
        return Target(parent.replace('/', '\\'), absolute.substring(slash + 1), absolute)
    }

    // MARK: - Links inside rendered Markdown

    /** A private URL carried on the rendered text; the transcript opens it. */
    val linkURL: String
        get() = buildString {
            append(LINK_SCHEME).append("://open?path=").append(percentEncodeQueryValue(path))
            if (line != null) append("&line=").append(line)
        }

    companion object {
        const val LINK_SCHEME = "opencode-file"
        val linkScheme: String get() = LINK_SCHEME

        /** The reference an `opencode-file://open?path=…&line=…` link carries. */
        fun fromLinkURL(url: String): AgentFileReference? {
            if (urlScheme(url) != LINK_SCHEME) return null
            val items = queryItems(url) ?: return null
            val path = items.firstOrNull { it.first == "path" }?.second
            if (path.isNullOrEmpty()) return null
            return AgentFileReference(path, items.firstOrNull { it.first == "line" }?.second?.toIntOrNull())
        }

        // MARK: Recognizing references

        private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d+(?::\\d+)?$)")
        private val windowsDriveInFileURL = Regex("^/[A-Za-z]:/")
        private val lineAnchor = Regex("#L\\d+(?:-L?\\d+)?$")
        private val urlSchemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val codeBreakers = Regex("[\\s()\\[\\]{}*+=<>|&^\"';,$`]")
        private val colonLocation = Regex(":(\\d+)(?::\\d+)?$")
        private val anchorLocation = Regex("#L(\\d+)(?:-L?\\d+)?$")
        private val windowsAbsolute = Regex("^[A-Za-z]:[\\\\/]")

        /**
         * A Markdown link's destination when it names a local file: a relative or absolute
         * path, `~/…`, or a `file://` URL on this host. Web and other schemes, fragments, and
         * queries are not files.
         */
        fun fromLink(destination: String): AgentFileReference? {
            var value = destination.trim { it.isWhitespace() }.replace('\\', '/')
            if (value.isEmpty() || value.startsWith("#") || value.startsWith("?") || value.startsWith("//")) return null
            if (value.lowercase().startsWith("file:")) {
                val parsed = parseFileURL(value) ?: return null
                if (parsed.first != null && parsed.first != "" && parsed.first != "localhost") return null
                value = parsed.second
                // `file:///C:/Users/kit/a.pdf` names a Windows path.
                if (windowsDriveInFileURL.containsMatchIn(value)) value = value.drop(1)
            } else if (!isWindowsAbsolute(value) && schemePrefix.containsMatchIn(value)) {
                // A scheme, but not `SessionView.swift:42` (a dot is legal in schemes) or a Windows
                // drive (`C:/Users/kit/a.pdf`).
                return null
            } else {
                // Keep a GitHub-style `#L42` for `withLocation`; drop other fragments.
                val anchor = lineAnchor.find(value)?.value ?: ""
                value = value.substringBefore('#').substringBefore('?')
                value = (percentDecode(value) ?: value) + anchor
            }
            if (value.isEmpty() || value.any { isControl(it) }) return null
            return withLocation(value)
        }

        /**
         * Inline code that reads as a file path. Stricter than desktop's rule (which accepts any
         * slash): the last component needs a recognizable file name, so API routes, flags, and
         * `and/or` stay plain code.
         */
        fun fromInlineCode(code: String): AgentFileReference? {
            val text = code.trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
            val count = text.codePointCount(0, text.length)
            if (count !in 2..512 || urlSchemePrefix.containsMatchIn(text) || codeBreakers.containsMatchIn(text) ||
                text.startsWith("-") || text.startsWith("@")
            ) return null
            val reference = withLocation(text) ?: return null
            if (!reference.hasViewableName) return null
            // A bare `Type.member` is code, not a file; require a path or a real name.
            val name = reference.name
            if (!reference.path.contains("/") && name.lowercase() !in knownFileNames &&
                name.firstOrNull()?.isUpperCase() == true && codeLikeMemberAccess(name)
            ) return null
            return reference
        }

        /** Splits a trailing `:line` or `:line:col` (also `#L42`) off a path. */
        fun withLocation(raw: String): AgentFileReference? {
            var path = raw
            var line: Int? = null
            val colon = colonLocation.find(path)
            if (colon != null) {
                line = colon.groupValues[1].toIntOrNull()
                path = path.substring(0, colon.range.first)
            } else {
                val anchor = anchorLocation.find(path)
                if (anchor != null) {
                    line = anchor.groupValues[1].toIntOrNull()
                    path = path.substring(0, anchor.range.first)
                }
            }
            if (path.isEmpty() || path == "/" || path == "~" || path == "." || path == "..") return null
            return AgentFileReference(path, line)
        }

        /** `Config.lock` or `Logger.log` read as member access, not files, when the suffix doubles as a common identifier. */
        private fun codeLikeMemberAccess(name: String): Boolean =
            AttachmentFileType.fileExtension(name) in setOf("json", "map", "log", "lock", "env")

        /**
         * `/Users/kit`, `/home/kit`, or (on Windows) `C:/Users/kit` from a session directory;
         * the server's info doesn't report a home folder.
         */
        fun home(directory: String?): String? {
            if (directory == null) return null
            val windows = normalizedWindows(directory)
            if (windows != null) {
                // `C:/Users/kit` from `C:\Users\kit\Desktop\app`.
                val parts = windows.split('/').filter { it.isNotEmpty() }
                if (parts.size < 3 || parts[1].lowercase() != "users") return null
                return "${parts[0]}/${parts[1]}/${parts[2]}"
            }
            val parts = directory.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] !in setOf("Users", "home")) return null
            return "/${parts[0]}/${parts[1]}"
        }

        // MARK: Windows servers

        /** A Windows server reports its directories as `C:\Users\kit\app`; agents there write `C:\…` or `C:/…`. */
        fun isWindowsAbsolute(path: String): Boolean = windowsAbsolute.containsMatchIn(path)

        /** `C:\Users\kit\app\..\x` → `C:/Users/kit/x`: forward slashes, an upper-case drive, and `C:/` for the drive's root. */
        fun normalizedWindows(path: String): String? {
            val slashed = path.replace('\\', '/')
            if (!isWindowsAbsolute(slashed)) return null
            val rest = normalized(slashed.drop(2)) ?: return null
            return slashed.take(2).uppercase() + rest
        }

        /** A directory as the app compares paths: Windows ones normalized. */
        fun comparableDirectory(directory: String?): String? = directory?.let { normalizedWindows(it) ?: it }

        /** Collapses `.`, `..`, and repeated slashes without touching the phone's own file system (the path names a file on the server). */
        fun normalized(path: String): String? {
            if (!path.startsWith("/")) return null
            val stack = ArrayList<String>()
            for (component in path.split('/')) {
                if (component.isEmpty() || component == ".") continue
                if (component == "..") {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    continue
                }
                stack.add(component)
            }
            return "/" + stack.joinToString("/")
        }

        // MARK: Vocabulary

        val textExtensions: Set<String> = setOf(
            "astro", "bash", "c", "cc", "cfg", "cjs", "clj", "conf", "cpp", "cs", "css", "csv", "cts", "dart", "diff",
            "dockerfile", "env", "erl", "ex", "exs", "fish", "go", "gql", "gradle", "graphql", "h", "hpp", "hs", "htm",
            "html", "ini", "java", "jl", "js", "json", "jsonc", "jsonl", "jsx", "kt", "kts", "less", "lock", "log", "lua",
            "m", "markdown", "md", "mdx", "mjs", "ml", "mm", "mts", "nix", "patch", "php", "pl", "plist", "properties",
            "proto", "ps1", "py", "r", "rb", "rs", "sass", "scala", "scss", "sh", "sql", "svelte", "swift", "tf", "toml",
            "ts", "tsv", "tsx", "txt", "vue", "xml", "yaml", "yml", "zig", "zsh",
        )

        val videoExtensions: Set<String> = setOf("avi", "m4v", "mkv", "mov", "mp4", "ogv", "webm")

        val audioExtensions: Set<String> = setOf(
            "aac", "aif", "aiff", "caf", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "weba",
        )

        val imageExtensions: Set<String> = setOf(
            "bmp", "gif", "heic", "ico", "jpeg", "jpg", "png", "svg", "tif", "tiff", "webp",
        )

        val mediaExtensions: Set<String> = videoExtensions + audioExtensions

        val documentExtensions: Set<String> = setOf(
            "bmp", "doc", "docx", "epub", "gif", "heic", "ico", "jpeg", "jpg", "key", "numbers", "pages", "pdf", "png",
            "ppt", "pptx", "rtf", "svg", "tif", "tiff", "webp", "xls", "xlsx", "zip",
        )

        val viewableExtensions: Set<String> = textExtensions + mediaExtensions + documentExtensions

        val knownFileNames: Set<String> = setOf(
            "brewfile", "changelog", "codeowners", "containerfile", "dockerfile", "gemfile", "justfile", "license",
            "makefile", "podfile", "procfile", "readme", "vagrantfile",
        )

        // Stand-ins for system type conformance, for other extensions.
        private val fallbackVideoExtensions = setOf("3g2", "3gp", "flv", "m2ts", "m2v", "mpeg", "mpg", "mp2", "ts2", "vob", "wmv")
        private val fallbackAudioExtensions = setOf("amr", "au", "m4b", "m4r", "mid", "midi", "snd", "wma")
        private val fallbackImageExtensions = setOf(
            "avif", "cr2", "dng", "exr", "hdr", "heif", "jfif", "jp2", "jxl", "nef", "pbm", "pgm", "ppm", "psd", "raw", "tga",
        )
        private val fallbackTextExtensions = setOf(
            "asm", "bat", "c++", "cmake", "cmd", "coffee", "cxx", "d", "el", "f", "f90", "fs", "groovy", "hh", "hxx", "inc",
            "lisp", "mak", "pas", "perl", "pm", "rst", "s", "scm", "srt", "text", "tcl", "vb", "vtt", "y",
        )

        /**
         * The preferred MIME type for an extension, or null when the table doesn't know it. A fixed
         * table so JVM tests and every device agree.
         */
        fun mimeForExtension(suffix: String): String? = mimeTypes[suffix.lowercase()]

        private val mimeTypes: Map<String, String> = mapOf(
            "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
            "bmp" to "image/bmp", "ico" to "image/vnd.microsoft.icon", "svg" to "image/svg+xml", "tif" to "image/tiff",
            "tiff" to "image/tiff", "avif" to "image/avif",
            "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime", "avi" to "video/avi",
            "mkv" to "video/x-matroska", "webm" to "video/webm", "ogv" to "video/ogg", "3gp" to "video/3gpp",
            "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "flac" to "audio/flac",
            "aif" to "audio/aiff", "aiff" to "audio/aiff", "caf" to "audio/x-caf", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "opus" to "audio/opus", "weba" to "audio/webm", "mid" to "audio/midi", "midi" to "audio/midi",
            "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown", "html" to "text/html",
            "htm" to "text/html", "css" to "text/css", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
            "js" to "text/javascript", "mjs" to "text/javascript", "json" to "application/json", "xml" to "application/xml",
            "yaml" to "application/x-yaml", "yml" to "application/x-yaml", "toml" to "application/toml",
            "sh" to "application/x-sh", "py" to "text/x-python-script", "swift" to "text/x-swift", "rtf" to "text/rtf",
            "zip" to "application/zip", "gz" to "application/gzip", "tgz" to "application/x-gzip", "tar" to "application/x-tar",
            "bz2" to "application/x-bzip2", "7z" to "application/x-7z-compressed", "rar" to "application/x-rar-compressed",
            "xz" to "application/x-xz",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "key" to "application/x-iwork-keynote-sffkey", "numbers" to "application/x-iwork-numbers-sffnumbers",
            "pages" to "application/x-iwork-pages-sffpages", "epub" to "application/epub+zip",
            "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "odp" to "application/vnd.oasis.opendocument.presentation",
            "cjs" to "text/javascript", "c" to "text/x-c", "h" to "text/x-c", "cpp" to "text/x-c++src",
            "java" to "text/x-java-source", "rb" to "text/x-ruby-script", "ics" to "text/calendar", "vcf" to "text/vcard",
            "dmg" to "application/x-apple-diskimage", "apk" to "application/vnd.android.package-archive",
            "wasm" to "application/wasm",
        )

        // MARK: URL helpers (URLComponents-compatible, pure Kotlin)

        private fun isControl(c: Char): Boolean {
            val type = Character.getType(c)
            return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
        }

        /** The scheme of [url] as written, or null. */
        fun urlScheme(url: String): String? {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            val scheme = url.substring(0, colon)
            if (!scheme[0].isAsciiLetter() || !scheme.all { it.isAsciiLetter() || it.isDigit() || it == '+' || it == '-' || it == '.' }) return null
            return scheme
        }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        /** `URLComponents.queryItems`: names and percent-decoded values (`+` stays `+`), or null when there's no query. */
        fun queryItems(url: String): List<Pair<String, String?>>? {
            val question = url.indexOf('?')
            if (question < 0) return null
            val query = url.substring(question + 1).substringBefore('#')
            return query.split('&').map { item ->
                val equals = item.indexOf('=')
                if (equals < 0) (percentDecode(item) ?: item) to null
                else (percentDecode(item.substring(0, equals)) ?: item.substring(0, equals)) to percentDecode(item.substring(equals + 1))
            }
        }

        private const val QUERY_VALUE_ALLOWED = "-._~!$'()*+,;:@/?"

        /** Percent-encodes a query item value as `URLComponents` does, plus `&`, `=` and `#` so it round-trips. */
        fun percentEncodeQueryValue(value: String): String {
            val out = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF)
                val ch = c.toChar()
                if (c < 0x80 && (ch.isAsciiLetter() || ch in '0'..'9' || ch in QUERY_VALUE_ALLOWED)) {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                }
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        /** `removingPercentEncoding`: null when a `%` escape is malformed or the bytes aren't UTF-8. */
        fun percentDecode(value: String): String? {
            if (!value.contains('%')) return value
            val bytes = java.io.ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val high = Character.digit(value[i + 1], 16)
                    val low = Character.digit(value[i + 2], 16)
                    if (high < 0 || low < 0) return null
                    bytes.write(high * 16 + low)
                    i += 3
                } else {
                    val encoded = if (Character.isHighSurrogate(c) && i + 1 < value.length) {
                        value.substring(i, i + 2).also { i += 2 }
                    } else {
                        c.toString().also { i += 1 }
                    }
                    bytes.write(encoded.toByteArray(Charsets.UTF_8))
                }
            }
            val decoder = Charsets.UTF_8.newDecoder()
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }

        /** A `file:` URL's host and percent-decoded path (`URL.host`, `URL.path`), or null when it isn't one. */
        private fun parseFileURL(value: String): Pair<String?, String>? {
            var rest = value.substring("file:".length)
            var host: String? = null
            if (rest.startsWith("//")) {
                rest = rest.substring(2)
                val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
                host = rest.substring(0, end).substringAfterLast('@').substringBefore(':')
                rest = rest.substring(end)
            }
            val rawPath = rest.substringBefore('#').substringBefore('?')
            // Characters a URL can't hold are encoded instead of failing.
            val path = percentDecode(rawPath) ?: return null
            return host to path
        }
    }
}
