package ai.opencode.feature.drawer.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight off-main-thread SVG rasterizer for project favicons and logos (`<svg>`, `<g>`,
 * `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polygon>`, `<polyline>`, `<path>`, local `<image>`
 * and Composer background paints). Unsupported image layers return null so the tile falls back to
 * the project's initial. Runs on any thread; never fetches anything.
 */
object ProjectSVGRasterizer {
    fun rasterize(data: ByteArray, side: Int, depth: Int = 0): Bitmap? {
        if (depth > 2 || data.size > ProjectIcon.maxInputBytes || side <= 0 || side > ProjectIcon.maximumPixels) return null
        val document = SVGDocumentParser(data).parse() ?: return null
        val viewBox = document.viewBox
        if (viewBox.width() <= 0 || viewBox.height() <= 0 || document.commands.isEmpty()) return null

        val bitmap = createBitmap(side, side)
        val canvas = Canvas(bitmap)

        // SVG and Android canvases both put the origin at the top left: no flip.
        val scale = min(side / viewBox.width(), side / viewBox.height())
        val offsetX = (side - viewBox.width() * scale) / 2
        val offsetY = (side - viewBox.height() * scale) / 2
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.translate(-viewBox.left, -viewBox.top)

        // A second parse, only for an icon that paints with a gradient.
        val gradients = if (document.commands.any { it.gradient != null }) ProjectSVGPaint.gradients(data) else emptyMap()
        for (command in document.commands) {
            val alpha = command.opacity
            val bytes = command.imageData
            if (bytes != null) {
                val decoded = if (ProjectIcon.looksLikeSVG(bytes)) {
                    rasterize(bytes, side, depth + 1)
                } else {
                    ProjectIcon.decodeRaster(bytes)
                } ?: return null
                val rect = RectF().also { command.path.computeBounds(it, true) }
                val fit = min(rect.width() / decoded.width, rect.height() / decoded.height)
                val width = decoded.width * fit
                val height = decoded.height * fit
                val target = RectF(
                    rect.centerX() - width / 2, rect.centerY() - height / 2,
                    rect.centerX() + width / 2, rect.centerY() + height / 2,
                )
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alphaByte(alpha) }
                canvas.drawBitmap(decoded, null, target, paint)
                continue
            }
            val gradient = command.gradient?.let { gradients[it] }
            if (gradient != null) {
                command.path.fillType = if (command.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                canvas.withSave {
                    clipPath(command.path)
                    val bounds = RectF().also { command.path.computeBounds(it, true) }
                    gradient.draw(this, bounds, alpha)
                }
            }
            command.fill?.let { fill ->
                command.path.fillType = if (command.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = fill.argb(alpha)
                }
                canvas.drawPath(command.path, paint)
            }
            val stroke = command.stroke
            if (stroke != null && command.strokeWidth > 0) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = stroke.argb(alpha)
                    strokeWidth = command.strokeWidth
                    strokeCap = command.lineCap
                    strokeJoin = command.lineJoin
                }
                canvas.drawPath(command.path, paint)
            }
        }
        return bitmap
    }

    private fun alphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255 + 0.5f).toInt()

    internal data class RGBA(val r: Float, val g: Float, val b: Float, val a: Float) {
        /** As an sRGB colour int, with the command's group [opacity] applied. */
        fun argb(opacity: Float): Int =
            android.graphics.Color.argb((a * opacity).coerceIn(0f, 1f), r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    internal class DrawCommand(
        val path: Path,
        val fill: RGBA?,
        val evenOdd: Boolean,
        val stroke: RGBA?,
        val strokeWidth: Float,
        val lineCap: Paint.Cap,
        val lineJoin: Paint.Join,
        val opacity: Float,
        val imageData: ByteArray? = null,
        val gradient: String? = null,
    )

    internal data class StyleState(
        val fill: RGBA? = RGBA(0f, 0f, 0f, 1f),
        val fillOpacity: Float = 1f,
        val evenOdd: Boolean = false,
        val stroke: RGBA? = null,
        val strokeOpacity: Float = 1f,
        val strokeWidth: Float = 1f,
        val lineCap: Paint.Cap = Paint.Cap.BUTT,
        val lineJoin: Paint.Join = Paint.Join.MITER,
        val opacity: Float = 1f,
        val gradient: String? = null,
    )

    internal class Document(val viewBox: RectF, val commands: List<DrawCommand>)

    private class SVGDocumentParser(private val data: ByteArray) {
        private var imageCount = 0
        private var invalidImage = false
        private var viewBox: RectF? = null
        private val commands = mutableListOf<DrawCommand>()
        private val styleStack = mutableListOf(StyleState())
        private var ignoredDepth = 0

        fun parse(): Document? {
            val ok = ProjectXml.parse(data, ::start, ::end)
            if (!ok || invalidImage) return null
            return Document(viewBox ?: RectF(0f, 0f, 64f, 64f), commands)
        }

        private fun start(elementName: String, attributeDict: Map<String, String>): Boolean {
            val tag = elementName.lowercase()
            if (ignoredDepth > 0 || tag in IGNORED_ELEMENTS) {
                ignoredDepth += 1
                return true
            }

            val attributes = HashMap(attributeDict)
            attributeDict["style"]?.let { inlineStyle ->
                for (pair in inlineStyle.split(';')) {
                    val parts = pair.split(':', limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        if (attributes[key] == null) attributes[key] = value
                    }
                }
            }

            val style = applyStyle(attributes, styleStack.lastOrNull() ?: StyleState())
            styleStack += style

            when (tag) {
                "svg" -> parseViewBox(attributes)?.let { parsed ->
                    if (viewBox == null || viewBox == RectF(0f, 0f, 512f, 512f)) viewBox = parsed
                }
                "rect" -> {
                    val w = number(attributes["width"]) ?: 0f
                    val h = number(attributes["height"]) ?: 0f
                    val x = number(attributes["x"]) ?: 0f
                    val y = number(attributes["y"]) ?: 0f
                    if (w > 0 && h > 0 && w <= LIMIT && h <= LIMIT && abs(x) <= LIMIT && abs(y) <= LIMIT) {
                        val rawRx = number(attributes["rx"]) ?: number(attributes["ry"]) ?: 0f
                        val rawRy = number(attributes["ry"]) ?: rawRx
                        val rx = max(0f, min(rawRx, Math.nextDown(w / 2)))
                        val ry = max(0f, min(rawRy, Math.nextDown(h / 2)))
                        val rect = RectF(x, y, x + w, y + h)
                        val path = Path()
                        if (rx > 0 && ry > 0) path.addRoundRect(rect, rx, ry, Path.Direction.CW) else path.addRect(rect, Path.Direction.CW)
                        appendCommand(path, style)
                    }
                }
                "circle" -> {
                    val r = number(attributes["r"]) ?: 0f
                    val cx = number(attributes["cx"]) ?: 0f
                    val cy = number(attributes["cy"]) ?: 0f
                    if (r > 0 && r <= LIMIT && abs(cx) <= LIMIT && abs(cy) <= LIMIT) {
                        appendCommand(Path().apply { addOval(RectF(cx - r, cy - r, cx + r, cy + r), Path.Direction.CW) }, style)
                    }
                }
                "ellipse" -> {
                    val rx = number(attributes["rx"]) ?: 0f
                    val ry = number(attributes["ry"]) ?: 0f
                    val cx = number(attributes["cx"]) ?: 0f
                    val cy = number(attributes["cy"]) ?: 0f
                    if (rx > 0 && ry > 0 && rx <= LIMIT && ry <= LIMIT && abs(cx) <= LIMIT && abs(cy) <= LIMIT) {
                        appendCommand(Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }, style)
                    }
                }
                "line" -> {
                    val x1 = number(attributes["x1"]) ?: 0f
                    val y1 = number(attributes["y1"]) ?: 0f
                    val x2 = number(attributes["x2"]) ?: 0f
                    val y2 = number(attributes["y2"]) ?: 0f
                    if (abs(x1) <= LIMIT && abs(y1) <= LIMIT && abs(x2) <= LIMIT && abs(y2) <= LIMIT) {
                        appendCommand(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, style)
                    }
                }
                "polygon", "polyline" -> {
                    val points = attributes["points"]
                    val values = points?.let(::parseNumbers) ?: emptyList()
                    if (values.size >= 4) {
                        val path = Path()
                        path.moveTo(values[0], values[1])
                        var idx = 2
                        while (idx + 1 < values.size) {
                            path.lineTo(values[idx], values[idx + 1])
                            idx += 2
                        }
                        if (tag == "polygon") path.close()
                        appendCommand(path, style)
                    }
                }
                "path" -> {
                    val path = attributes["d"]?.let(SVGPathParser::parse)
                    if (path != null) appendCommand(path, style)
                }
                "image" -> {
                    imageCount += 1
                    val source = attributes["href"] ?: attributes["xlink:href"]
                    val bytes = source?.takeIf { it.toByteArray(Charsets.UTF_8).size <= ProjectIcon.maxInputBytes }
                        ?.let(ProjectIcon::decodeDataURL)
                    val width = number(attributes["width"])
                    val height = number(attributes["height"])
                    if (imageCount > 8 || bytes == null || bytes.size > ProjectIcon.maxInputBytes ||
                        width == null || height == null || width <= 0 || height <= 0 || width > LIMIT || height > LIMIT
                    ) {
                        invalidImage = true
                        return false
                    }
                    val x = number(attributes["x"]) ?: 0f
                    val y = number(attributes["y"]) ?: 0f
                    if (abs(x) > LIMIT || abs(y) > LIMIT) {
                        invalidImage = true
                        return false
                    }
                    val path = Path().apply { addRect(RectF(x, y, x + width, y + height), Path.Direction.CW) }
                    appendCommand(path, style, imageData = bytes)
                }
            }
            return true
        }

        private fun end(@Suppress("UNUSED_PARAMETER") elementName: String) {
            if (ignoredDepth > 0) {
                ignoredDepth -= 1
                return
            }
            if (styleStack.size > 1) styleStack.removeAt(styleStack.lastIndex)
        }

        private fun appendCommand(path: Path, style: StyleState, imageData: ByteArray? = null) {
            val fill = style.fill?.let { f -> f.copy(a = f.a * style.fillOpacity).takeIf { it.a > 0 } }
            val stroke = style.stroke?.let { s -> s.copy(a = s.a * style.strokeOpacity).takeIf { it.a > 0 } }
            if (imageData == null && style.gradient == null && fill == null && !(stroke != null && style.strokeWidth > 0)) return
            commands += DrawCommand(
                path = path,
                fill = fill,
                evenOdd = style.evenOdd,
                stroke = stroke,
                strokeWidth = style.strokeWidth,
                lineCap = style.lineCap,
                lineJoin = style.lineJoin,
                opacity = style.opacity,
                imageData = imageData,
                gradient = style.gradient,
            )
        }

        private fun applyStyle(attributes: Map<String, String>, inherited: StyleState): StyleState {
            var style = inherited
            attributes["fill"]?.let { fillAttr ->
                style = style.copy(gradient = ProjectSVGPaint.reference(fillAttr))
                if (fillAttr.equals("none", ignoreCase = true) || fillAttr.lowercase().startsWith("url(")) {
                    style = style.copy(fill = null)
                } else {
                    parseColor(fillAttr)?.let { style = style.copy(fill = it) }
                }
            }
            number(attributes["fill-opacity"])?.let { style = style.copy(fillOpacity = it.coerceIn(0f, 1f)) }
            (attributes["fill-rule"] ?: attributes["clip-rule"])?.let { rule ->
                style = style.copy(evenOdd = rule.equals("evenodd", ignoreCase = true))
            }
            attributes["stroke"]?.let { strokeAttr ->
                if (strokeAttr.equals("none", ignoreCase = true) || strokeAttr.lowercase().startsWith("url(")) {
                    style = style.copy(stroke = null)
                } else {
                    parseColor(strokeAttr)?.let { style = style.copy(stroke = it) }
                }
            }
            number(attributes["stroke-opacity"])?.let { style = style.copy(strokeOpacity = it.coerceIn(0f, 1f)) }
            number(attributes["stroke-width"])?.let { style = style.copy(strokeWidth = max(0f, it)) }
            attributes["stroke-linecap"]?.lowercase()?.let { cap ->
                style = style.copy(
                    lineCap = when (cap) {
                        "round" -> Paint.Cap.ROUND
                        "square" -> Paint.Cap.SQUARE
                        else -> Paint.Cap.BUTT
                    },
                )
            }
            attributes["stroke-linejoin"]?.lowercase()?.let { join ->
                style = style.copy(
                    lineJoin = when (join) {
                        "round" -> Paint.Join.ROUND
                        "bevel" -> Paint.Join.BEVEL
                        else -> Paint.Join.MITER
                    },
                )
            }
            number(attributes["opacity"])?.let { style = style.copy(opacity = style.opacity * it.coerceIn(0f, 1f)) }
            return style
        }

        private fun parseViewBox(attributes: Map<String, String>): RectF? {
            (attributes["viewBox"] ?: attributes["viewbox"])?.let { vb ->
                val nums = parseNumbers(vb)
                if (nums.size == 4 && abs(nums[0]) <= LIMIT && abs(nums[1]) <= LIMIT &&
                    nums[2] > 0 && nums[3] > 0 && nums[2] <= LIMIT && nums[3] <= LIMIT
                ) {
                    return RectF(nums[0], nums[1], nums[0] + nums[2], nums[1] + nums[3])
                }
            }
            val w = number(attributes["width"])
            val h = number(attributes["height"])
            if (w != null && h != null && w > 0 && h > 0 && w <= LIMIT && h <= LIMIT) return RectF(0f, 0f, w, h)
            return null
        }

        private fun parseColor(raw: String): RGBA? {
            val text = raw.trim().lowercase()
            ProjectSVGPaint.color(text)?.let { color ->
                val c = ProjectSVGPaint.srgbComponents(color)
                return RGBA(c[0], c[1], c[2], c[3])
            }
            when (text) {
                "white", "currentcolor" -> return RGBA(1f, 1f, 1f, 1f)
                "black" -> return RGBA(0f, 0f, 0f, 1f)
                "transparent" -> return RGBA(0f, 0f, 0f, 0f)
            }
            if (text.startsWith("#")) {
                val hex = text.drop(1)
                if (hex.length == 3) {
                    val r = "${hex[0]}${hex[0]}".toIntOrNull(16) ?: return null
                    val g = "${hex[1]}${hex[1]}".toIntOrNull(16) ?: return null
                    val b = "${hex[2]}${hex[2]}".toIntOrNull(16) ?: return null
                    return RGBA(r / 255f, g / 255f, b / 255f, 1f)
                }
                if (hex.length == 6) {
                    hex.toLongOrNull(16)?.let { v ->
                        return RGBA(((v shr 16) and 0xff) / 255f, ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f, 1f)
                    }
                }
                if (hex.length == 8) {
                    hex.toLongOrNull(16)?.let { v ->
                        return RGBA(
                            ((v shr 24) and 0xff) / 255f, ((v shr 16) and 0xff) / 255f,
                            ((v shr 8) and 0xff) / 255f, (v and 0xff) / 255f,
                        )
                    }
                }
            }
            if (text.startsWith("rgb")) {
                val nums = parseNumbers(text)
                if (nums.size >= 3) {
                    val a = if (nums.size >= 4) nums[3].coerceIn(0f, 1f) else 1f
                    return RGBA(nums[0] / 255f, nums[1] / 255f, nums[2] / 255f, a)
                }
            }
            return null
        }

        companion object {
            private val IGNORED_ELEMENTS = setOf(
                "defs", "style", "script", "clippath", "mask", "lineargradient", "radialgradient",
                "filter", "title", "desc", "metadata", "symbol",
            )
        }
    }

    private const val LIMIT = 65_536f

    private fun number(raw: String?): Float? {
        var text = raw?.trim()?.ifEmpty { null } ?: return null
        if (text.endsWith("px")) text = text.dropLast(2)
        val value = text.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value.toFloat() else null
    }

    /** SVG number lists (`10,20 -5.5.5 1e3`). Drops non-finite values and any beyond ±65,536. */
    internal fun parseNumbers(text: String): List<Float> {
        val results = mutableListOf<Float>()
        val current = StringBuilder()
        var hasDot = false
        fun appendFinite(raw: String) {
            val v = raw.toDoubleOrNull() ?: return
            if (v.isFinite() && abs(v) <= LIMIT) results += v.toFloat()
        }
        for (ch in text) {
            when {
                ch.isDigit() -> current.append(ch)
                ch == '.' -> if (hasDot) {
                    appendFinite(current.toString())
                    current.clear().append("0.")
                } else {
                    hasDot = true
                    current.append(ch)
                }
                ch == '-' || ch == '+' -> if (current.isNotEmpty() && current.last() != 'e' && current.last() != 'E') {
                    appendFinite(current.toString())
                    current.clear().append(ch)
                    hasDot = false
                } else {
                    current.append(ch)
                }
                ch == 'e' || ch == 'E' -> current.append(ch)
                else -> if (current.isNotEmpty()) {
                    appendFinite(current.toString())
                    current.clear()
                    hasDot = false
                }
            }
        }
        if (current.isNotEmpty()) appendFinite(current.toString())
        return results
    }
}

/** Parses SVG `<path d="…">` commands into a [Path]. Arcs draw as lines. */
internal object SVGPathParser {
    private sealed interface Token {
        data class Command(val char: Char) : Token
        data class Number(val value: Float) : Token
    }

    private data class Point(val x: Float, val y: Float)

    fun parse(d: String): Path? {
        val tokens = tokenize(d)
        if (tokens.isEmpty()) return null
        val path = Path()
        var index = 0
        var currentCmd = 'M'
        var current = Point(0f, 0f)
        var start = Point(0f, 0f)
        var lastControl: Point? = null

        fun nextNumber(): Float? {
            val token = tokens.getOrNull(index) as? Token.Number ?: return null
            index += 1
            return token.value
        }

        fun result(): Path? = if (path.isEmpty) null else path

        while (index < tokens.size) {
            val token = tokens[index]
            if (token is Token.Command) {
                currentCmd = token.char
                index += 1
                if (currentCmd == 'Z' || currentCmd == 'z') {
                    path.close()
                    current = start
                    lastControl = null
                    continue
                }
            }

            when (currentCmd) {
                'M', 'm' -> {
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val pt = if (currentCmd == 'm') Point(current.x + x, current.y + y) else Point(x, y)
                    path.moveTo(pt.x, pt.y)
                    current = pt
                    start = pt
                    lastControl = null
                    currentCmd = if (currentCmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val pt = if (currentCmd == 'l') Point(current.x + x, current.y + y) else Point(x, y)
                    path.lineTo(pt.x, pt.y)
                    current = pt
                    lastControl = null
                }
                'H', 'h' -> {
                    val x = nextNumber() ?: return path
                    val pt = Point(if (currentCmd == 'h') current.x + x else x, current.y)
                    path.lineTo(pt.x, pt.y)
                    current = pt
                    lastControl = null
                }
                'V', 'v' -> {
                    val y = nextNumber() ?: return path
                    val pt = Point(current.x, if (currentCmd == 'v') current.y + y else y)
                    path.lineTo(pt.x, pt.y)
                    current = pt
                    lastControl = null
                }
                'C', 'c' -> {
                    val x1 = nextNumber() ?: return path
                    val y1 = nextNumber() ?: return path
                    val x2 = nextNumber() ?: return path
                    val y2 = nextNumber() ?: return path
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val rel = currentCmd == 'c'
                    val c1 = if (rel) Point(current.x + x1, current.y + y1) else Point(x1, y1)
                    val c2 = if (rel) Point(current.x + x2, current.y + y2) else Point(x2, y2)
                    val end = if (rel) Point(current.x + x, current.y + y) else Point(x, y)
                    path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
                    lastControl = c2
                    current = end
                }
                'S', 's' -> {
                    val x2 = nextNumber() ?: return path
                    val y2 = nextNumber() ?: return path
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val rel = currentCmd == 's'
                    val c1 = lastControl?.let { Point(2 * current.x - it.x, 2 * current.y - it.y) } ?: current
                    val c2 = if (rel) Point(current.x + x2, current.y + y2) else Point(x2, y2)
                    val end = if (rel) Point(current.x + x, current.y + y) else Point(x, y)
                    path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
                    lastControl = c2
                    current = end
                }
                'Q', 'q' -> {
                    val x1 = nextNumber() ?: return path
                    val y1 = nextNumber() ?: return path
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val rel = currentCmd == 'q'
                    val c1 = if (rel) Point(current.x + x1, current.y + y1) else Point(x1, y1)
                    val end = if (rel) Point(current.x + x, current.y + y) else Point(x, y)
                    path.quadTo(c1.x, c1.y, end.x, end.y)
                    lastControl = c1
                    current = end
                }
                'T', 't' -> {
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val rel = currentCmd == 't'
                    val c1 = lastControl?.let { Point(2 * current.x - it.x, 2 * current.y - it.y) } ?: current
                    val end = if (rel) Point(current.x + x, current.y + y) else Point(x, y)
                    path.quadTo(c1.x, c1.y, end.x, end.y)
                    lastControl = c1
                    current = end
                }
                'A', 'a' -> {
                    nextNumber() ?: return path // rx
                    nextNumber() ?: return path // ry
                    nextNumber() ?: return path // rotation
                    nextNumber() ?: return path // large-arc flag
                    nextNumber() ?: return path // sweep flag
                    val x = nextNumber() ?: return path
                    val y = nextNumber() ?: return path
                    val rel = currentCmd == 'a'
                    val end = if (rel) Point(current.x + x, current.y + y) else Point(x, y)
                    path.lineTo(end.x, end.y)
                    current = end
                    lastControl = null
                }
                else -> return path
            }
        }
        return result()
    }

    private fun tokenize(d: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val numBuffer = StringBuilder()
        var hasDot = false

        fun flushNumber() {
            if (numBuffer.isEmpty()) return
            val value = numBuffer.toString().toDoubleOrNull()
            if (value != null && value.isFinite() && abs(value) <= 65_536.0) tokens += Token.Number(value.toFloat())
            numBuffer.clear()
            hasDot = false
        }

        for (ch in d) {
            when {
                ch.isLetter() && ch != 'e' && ch != 'E' -> {
                    flushNumber()
                    tokens += Token.Command(ch)
                }
                ch.isDigit() -> numBuffer.append(ch)
                ch == '.' -> if (hasDot) {
                    flushNumber()
                    numBuffer.append("0.")
                    hasDot = true
                } else {
                    hasDot = true
                    numBuffer.append(ch)
                }
                ch == '-' || ch == '+' -> {
                    if (numBuffer.isNotEmpty() && numBuffer.last() != 'e' && numBuffer.last() != 'E') flushNumber()
                    numBuffer.append(ch)
                }
                ch == 'e' || ch == 'E' -> numBuffer.append(ch)
                else -> flushNumber()
            }
        }
        flushNumber()
        return tokens
    }
}
