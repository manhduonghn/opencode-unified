package ai.opencode.feature.drawer.icons

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.ProjectMark
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.Immutable
import androidx.core.graphics.createBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Project metadata (`OpenCodeProjectIcon`, `ProjectMark`, `OpenCodeProject.initial` and
// `avatarSource`) lives in `:server` (`model/Project.kt`, `model/Models.kt`); this file adds the
// drawer's icon request, palette and decoded icon.

/** The built-in OpenCode icon, drawn locally instead of fetching [ProjectMark.OPEN_CODE_FAVICON_URL]. */
val ProjectMark.openCodeFaviconSVG: ByteArray
    get() = OPEN_CODE_FAVICON_SVG

private val OPEN_CODE_FAVICON_SVG: ByteArray = """
<svg width="512" height="512" viewBox="0 0 512 512" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect width="512" height="512" fill="#131010"/>
<path d="M320 224V352H192V224H320Z" fill="#5A5858"/>
<path fill-rule="evenodd" clip-rule="evenodd" d="M384 416H128V96H384V416ZM320 160H192V352H320V160Z" fill="white"/>
</svg>
""".trimIndent().toByteArray(Charsets.UTF_8)

/** The request to load this project's icon on [server], or null when the project uses its initial tile. */
fun OpenCodeProject.iconRequest(server: OpenCodeServer): ProjectIconRequest? {
    val source = avatarSource ?: return null
    return ProjectIconRequest(server = server, projectID = id, source = source)
}

/**
 * The project for a drawer session row, falling back to a folder project derived from
 * `session.directory` so every row has a stable tile from its first frame.
 */
fun OpenCodeProject.Companion.resolved(session: OpenCodeSession, projects: List<OpenCodeProject>): OpenCodeProject {
    if (session.projectID != "global") {
        projects.firstOrNull { it.id == session.projectID }?.let { return it }
    }
    projects.firstOrNull { it.directory == session.directory }?.let { return it }
    return OpenCodeProject.unlisted(session)
}

/** [resolved] for many sessions: the projects indexed once. */
class OpenCodeProjectLookup(projects: List<OpenCodeProject>) {
    private val byID = HashMap<String, OpenCodeProject>()
    private val byDirectory = HashMap<String, OpenCodeProject>()

    init {
        // The first of each, as `resolved` finds it.
        for (project in projects) {
            if (project.id !in byID) byID[project.id] = project
            if (project.directory !in byDirectory) byDirectory[project.directory] = project
        }
    }

    fun resolved(session: OpenCodeSession): OpenCodeProject {
        if (session.projectID != "global") {
            val match = byID[session.projectID]
            if (match != null) return match
        }
        return byDirectory[session.directory] ?: OpenCodeProject.unlisted(session)
    }
}

/** A project for a session in no known project, from its folder. */
private fun OpenCodeProject.Companion.unlisted(session: OpenCodeSession): OpenCodeProject {
    val name = ProjectMark.folderName(session.directory)
    val id = if (session.projectID.isEmpty() || session.projectID == "global") "folder:" + session.directory else session.projectID
    return OpenCodeProject(id = id, name = name.ifEmpty { session.directory }, directory = session.directory)
}

/**
 * Desktop V2 project avatar surface and border colours (`packages/ui/src/theme/v2/avatar.ts`),
 * defaulting to Muted Monochrome (`#5c5c5c` with `#aeaeae` dark / `#3a3a3a` light border).
 */
@Immutable
data class ProjectAvatarPalette(val background: Color, val border: Color, val foreground: Color) {
    companion object {
        fun resolve(colorKey: String?, isDark: Boolean): ProjectAvatarPalette {
            val key = colorKey?.trim()?.lowercase()
            val normalized = when (key) {
                "mint" -> "cyan"
                "lime" -> "green"
                else -> key
            }
            val white = Color.White
            return when (normalized) {
                "orange" -> if (isDark) ProjectAvatarPalette(hex(0x723d22), hex(0xff8648), white)
                else ProjectAvatarPalette(hex(0xee7330), hex(0xd16427), white)
                "yellow" -> if (isDark) ProjectAvatarPalette(hex(0x68552b), hex(0xe7af36), white)
                else ProjectAvatarPalette(hex(0xe7af36), hex(0xcb9f34), white)
                "cyan" -> if (isDark) ProjectAvatarPalette(hex(0x005a6e), hex(0x0096b8), white)
                else ProjectAvatarPalette(hex(0x0096b8), hex(0x007d9b), white)
                "green" -> if (isDark) ProjectAvatarPalette(hex(0x196130), hex(0x49c970), white)
                else ProjectAvatarPalette(hex(0x2eaf5a), hex(0x198b43), white)
                "red" -> if (isDark) ProjectAvatarPalette(hex(0x7a1f23), hex(0xd92e3c), white)
                else ProjectAvatarPalette(hex(0xd92e3c), hex(0xb82d35), white)
                "pink" -> if (isDark) ProjectAvatarPalette(hex(0x8c2d61), hex(0xe4429e), white)
                else ProjectAvatarPalette(hex(0xe4429e), hex(0xc83d8b), white)
                "blue" -> if (isDark) ProjectAvatarPalette(hex(0x263fa9), hex(0x7698fd), white)
                else ProjectAvatarPalette(hex(0x3250df), hex(0x2c47c8), white)
                "purple" -> if (isDark) ProjectAvatarPalette(hex(0x361f83), hex(0x7152f4), white)
                else ProjectAvatarPalette(hex(0x623be2), hex(0x5230c2), white)
                // Muted Monochrome (`DESKTOP_GRAY`): #5c5c5c background, #aeaeae (dark) / #3a3a3a (light) inner border.
                else -> if (isDark) ProjectAvatarPalette(hex(0x5c5c5c), hex(0xaeaeae), white)
                else ProjectAvatarPalette(hex(0x5c5c5c), hex(0x3a3a3a), white)
            }
        }

        fun hex(rgb: Int, alpha: Float = 1f): Color = Color(
            red = ((rgb shr 16) and 0xff) / 255f,
            green = ((rgb shr 8) and 0xff) / 255f,
            blue = (rgb and 0xff) / 255f,
            alpha = alpha,
        )
    }
}

/** Identifies one project icon fetch and its cache entry. */
@Immutable
data class ProjectIconRequest(val server: OpenCodeServer, val projectID: String, val source: String) {
    /**
     * The full resolved URL (or data URI) for caching: a new content hash `?v=<hash>` produces
     * a new URL and therefore a distinct cache entry without revalidation.
     */
    val resolvedURLString: String
        get() {
            val trimmed = source.trim()
            val relative = ProjectIconURLs.relativeReference(trimmed) ?: return trimmed
            return server.endpoint(relative.path, relative.query)?.toString() ?: trimmed
        }

    /**
     * True when the icon URL is versioned by content hash (`?v=<hash>`), a `data:` URI, or the
     * built-in OpenCode icon, so a cached hit never needs revalidation.
     */
    val isVersioned: Boolean
        get() {
            val trimmed = source.trim()
            if (trimmed.startsWith("//")) return false
            if (trimmed.lowercase().startsWith("data:") || trimmed == ProjectMark.OPEN_CODE_FAVICON_URL) return true
            return ProjectIconURLs.queryItems(trimmed)?.any { (name, value) -> name == "v" && value.isNotEmpty() } == true
        }

    /**
     * Scoped by server and the full resolved URL, so an updated `?v=<hash>` or override fetches a
     * fresh entry while identical versioned URLs share one. Worked out once per request: the tile
     * asks for it on every pass.
     */
    val cacheKey: String by lazy {
        // The decoder version keeps old renders from surviving on disk.
        val raw = "svg-v2|${server.persistenceID}|$resolvedURLString"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        "${server.persistenceID}|" + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

internal object ProjectIconURLs {
    class Relative(val path: String, val query: List<Pair<String, String>>)

    /** A server-relative reference (`/path?query`): no scheme, no host, a non-empty path. */
    fun relativeReference(trimmed: String): Relative? {
        if (!trimmed.startsWith("/") || trimmed.startsWith("//")) return null
        val uri = try { URI(trimmed) } catch (_: Exception) { return null }
        if (uri.scheme != null || uri.host != null) return null
        val path = uri.path ?: return null
        if (path.isEmpty()) return null
        return Relative(path, parseQuery(uri.rawQuery))
    }

    /** The decoded query items of [string], or null when it isn't a valid URL. */
    fun queryItems(string: String): List<Pair<String, String>>? {
        val uri = try { URI(string) } catch (_: Exception) { return null }
        return parseQuery(uri.rawQuery)
    }

    private fun parseQuery(rawQuery: String?): List<Pair<String, String>> {
        if (rawQuery == null) return emptyList()
        return rawQuery.split('&').map { item ->
            val equals = item.indexOf('=')
            if (equals < 0) {
                (percentDecoded(item) ?: item) to ""
            } else {
                val name = item.substring(0, equals)
                val value = item.substring(equals + 1)
                (percentDecoded(name) ?: name) to (percentDecoded(value) ?: value)
            }
        }
    }

    /** Null for a malformed escape or invalid UTF-8; `+` stays `+`. */
    fun percentDecoded(text: String): String? {
        if (!text.contains('%')) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%') {
                if (i + 2 >= text.length) return null
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(byte)
                i += 3
            } else {
                out.write(ch.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        val bytes = out.toByteArray()
        val decoder = Charsets.UTF_8.newDecoder()
        return try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { null }
    }
}

/**
 * A decoded, downsampled project icon ready to draw in a 20 × 20 dp drawer tile. Equal when
 * its PNG and backing are. Never mutated after decoding.
 */
@Immutable
class ProjectIcon private constructor(
    val bitmap: Bitmap,
    val png: ByteArray,
    val backing: Backing,
) {
    /** How the tile frames the decoded image. */
    enum class Backing(val rawValue: String) {
        /** Opaque or full-bleed icon (or pre-cropped squircle): fills the tile edge-to-edge. */
        FullBleed("fullBleed"),

        /** Transparent glyph with light or medium strokes: padded on a dark neutral surface. */
        PaddedDark("paddedDark"),

        /** Transparent glyph with dark strokes: padded on a light neutral surface. */
        PaddedLight("paddedLight");

        companion object {
            fun of(rawValue: String?): Backing? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    val image: ImageBitmap by lazy { bitmap.asImageBitmap() }

    override fun equals(other: Any?): Boolean =
        other is ProjectIcon && other.backing == backing && other.png.contentEquals(png)

    override fun hashCode(): Int = 31 * png.contentHashCode() + backing.hashCode()

    companion object {
        const val minimumPixels = 16
        const val maximumPixels = 96
        const val maximumSourceDimension = 4096
        const val maxInputBytes = 2 * 1024 * 1024

        /** Decodes PNG/JPEG/WebP/ICO/GIF/HEIF or SVG bytes; null when unusable (blank, flat, too small or large). */
        fun decode(data: ByteArray, precomputedBacking: Backing? = null): ProjectIcon? {
            if (data.isEmpty() || data.size > maxInputBytes) return null
            val image = if (looksLikeSVG(data)) {
                ProjectSVGRasterizer.rasterize(data, maximumPixels) ?: return null
            } else {
                decodeRaster(data) ?: return null
            }

            if (precomputedBacking != null) {
                val png = encodePNG(image) ?: return null
                return ProjectIcon(image, png, precomputedBacking)
            }

            val side = min(max(image.width, image.height), maximumPixels)
            val fitted = renderSquare(image, side) ?: return null
            val prepared = prepare(fitted, side) ?: return null
            val png = encodePNG(prepared.first) ?: return null
            return ProjectIcon(prepared.first, png, prepared.second)
        }

        /** Decodes a `data:image/...` URI (either `;base64,` or UTF-8 / URL-encoded SVG). */
        fun fromDataURL(dataURL: String): ProjectIcon? = decodeDataURL(dataURL)?.let { decode(it) }

        fun decodeDataURL(raw: String): ByteArray? {
            val trimmed = raw.trim()
            if (!trimmed.lowercase().startsWith("data:image/")) return null
            val comma = trimmed.indexOf(',')
            if (comma < 0) return null
            val header = trimmed.substring(0, comma).lowercase()
            val body = trimmed.substring(comma + 1)
            if (header.contains(";base64")) {
                // The MIME decoder skips anything outside the alphabet.
                return try { Base64.getMimeDecoder().decode(body) } catch (_: IllegalArgumentException) { null }
            }
            ProjectIconURLs.percentDecoded(body)?.let { return it.toByteArray(Charsets.UTF_8) }
            return body.toByteArray(Charsets.UTF_8)
        }

        fun looksLikeSVG(data: ByteArray): Boolean {
            val prefix = String(data, 0, min(data.size, 512), Charsets.UTF_8).trim().lowercase()
            return prefix.startsWith("<svg") || (prefix.startsWith("<?xml") && prefix.contains("<svg")) || prefix.contains("<svg ")
        }

        private class Rejected : RuntimeException() {
            override fun fillInStackTrace(): Throwable = this
        }

        /**
         * The image, downsampled to [maximumPixels] on its long side, or null when it's smaller
         * than [minimumPixels] on its short side or larger than [maximumSourceDimension]. A
         * multi-image file (ICO) decodes its largest image.
         */
        fun decodeRaster(data: ByteArray): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0 || w > maximumSourceDimension || h > maximumSourceDimension ||
                    min(w, h) < minimumPixels
                ) {
                    throw Rejected()
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (max(w, h) > maximumPixels) {
                    val scale = maximumPixels.toDouble() / max(w, h)
                    decoder.setTargetSize(max(1, Math.round(w * scale).toInt()), max(1, Math.round(h * scale).toInt()))
                }
            }
        } catch (_: Exception) {
            null
        }

        internal fun renderSquare(image: Bitmap, side: Int, cropRect: Rect? = null): Bitmap? {
            if (side <= 0) return null
            val source = cropRect?.takeIf { !it.isEmpty } ?: Rect(0, 0, image.width, image.height)
            val output = createBitmap(side, side)
            val canvas = Canvas(output)
            val scale = side.toFloat() / max(source.width(), source.height())
            val width = source.width() * scale
            val height = source.height() * scale
            val rect = RectF((side - width) / 2, (side - height) / 2, (side + width) / 2, (side + height) / 2)
            canvas.drawBitmap(image, source, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            return output
        }

        /**
         * Inspects alpha and luminance:
         * - Rejects blank or flat single-pixel-value images.
         * - Crops squircle icons' transparent gutters when the interior is opaque.
         * - Flags transparent glyphs for neutral backing + padding.
         */
        private fun prepare(square: Bitmap, side: Int): Pair<Bitmap, Backing>? {
            val w = square.width
            val h = square.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            square.getPixels(pixels, 0, w, 0, 0, w, h)

            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var maxAlpha = 0
            var transparentCount = 0
            var visibleCount = 0
            var luminanceSum = 0.0

            // Compare and weigh the same values.
            fun premultiplied(pixel: Int): IntArray {
                val a = (pixel ushr 24) and 0xff
                val r = (((pixel shr 16) and 0xff) * a + 127) / 255
                val g = (((pixel shr 8) and 0xff) * a + 127) / 255
                val b = ((pixel and 0xff) * a + 127) / 255
                return intArrayOf(r, g, b, a)
            }

            val first = premultiplied(pixels[0])
            var allFlat = true

            val centerMinX = (w * 0.22).toInt()
            val centerMaxX = (w * 0.78).toInt()
            val centerMinY = (h * 0.22).toInt()
            val centerMaxY = (h * 0.78).toInt()
            var centerTotal = 0
            var centerOpaque = 0

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val (r, g, b, a) = premultiplied(pixels[y * w + x])
                    if (a > maxAlpha) maxAlpha = a
                    if (allFlat && (abs(r - first[0]) > 4 || abs(g - first[1]) > 4 || abs(b - first[2]) > 4 || abs(a - first[3]) > 4)) {
                        allFlat = false
                    }
                    if (a < 32) {
                        transparentCount += 1
                    } else {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    if (a >= 128) {
                        visibleCount += 1
                        luminanceSum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / a.toDouble()
                    }
                    if (x in centerMinX until centerMaxX && y in centerMinY until centerMaxY) {
                        centerTotal += 1
                        if (a >= 235) centerOpaque += 1
                    }
                }
            }

            if (maxAlpha < 16 || allFlat || maxX < minX || maxY < minY) return null
            val totalPixels = w * h
            val transparentFraction = transparentCount.toDouble() / totalPixels
            val centerOpaqueFraction = if (centerTotal > 0) centerOpaque.toDouble() / centerTotal else 0.0

            // A pre-masked squircle app icon (such as a macOS 1024 px icon with a ~9 % transparent
            // outer gutter) has a solid opaque center and a symmetric outer margin. Cropping to its
            // inner bounds lets it fill the rounded tile cleanly without a double-border gap.
            if (transparentFraction > 0.04 && centerOpaqueFraction >= 0.92) {
                val boxW = maxX - minX + 1
                val boxH = maxY - minY + 1
                val insetX = max(minX, w - 1 - maxX)
                val insetY = max(minY, h - 1 - maxY)
                val cornerClear = max(insetX, insetY) + max(1, (min(boxW, boxH) * 0.03).toInt())
                if (cornerClear * 2 < min(w, h)) {
                    val crop = Rect(cornerClear, cornerClear, w - cornerClear, h - cornerClear)
                    renderSquare(square, side, crop)?.let { return it to Backing.FullBleed }
                }
                return square to Backing.FullBleed
            }

            // Transparent glyph or logo mark: pad on a contrasting neutral backing.
            if (transparentFraction >= 0.18 && centerOpaqueFraction < 0.90 && visibleCount > 0) {
                val meanLuminance = luminanceSum / visibleCount
                val backing = if (meanLuminance < 0.25) Backing.PaddedLight else Backing.PaddedDark
                return square to backing
            }

            return square to Backing.FullBleed
        }

        private fun encodePNG(image: Bitmap): ByteArray? {
            val out = ByteArrayOutputStream()
            return if (image.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    }
}

/** What loading a project icon came to. */
sealed interface ProjectIconResult {
    data class Icon(val value: ProjectIcon) : ProjectIconResult

    /** No usable icon (404, unsupported format, blank): remembered until expiry. */
    data object Missing : ProjectIconResult

    /** Deferred (offline, data saver); a later drawer open tries again. */
    data object Deferred : ProjectIconResult

    val icon: ProjectIcon? get() = (this as? Icon)?.value
}
