package ai.opencode.feature.drawer.icons

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Paint emitted by the V2 server's Icon Composer wrapper. Parsing and rasterization are entirely
 * local; SVG references never fetch resources.
 */
object ProjectSVGPaint {
    /** `url(#id)` → `id`. */
    fun reference(value: String): String? {
        if (!value.startsWith("url(#") || !value.endsWith(")")) return null
        return value.drop(5).dropLast(1)
    }

    /** `color(srgb r g b / a)` or `color(display-p3 r g b / a)`, each component in 0…1. */
    fun color(value: String): Color? {
        if (!value.startsWith("color(") || !value.endsWith(")")) return null
        val parts = value.drop(6).dropLast(1).split(Regex("[\\s/]+")).filter { it.isNotEmpty() }
        if (parts.size != 5) return null
        val space = when (parts[0]) {
            "srgb" -> ColorSpace.get(ColorSpace.Named.SRGB)
            "display-p3" -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            else -> return null
        }
        val values = parts.drop(1).mapNotNull { it.toDoubleOrNull() }
        if (values.size != 4 || !values.all { it.isFinite() && it in 0.0..1.0 }) return null
        return Color.valueOf(values[0].toFloat(), values[1].toFloat(), values[2].toFloat(), values[3].toFloat(), space)
    }

    /** A colour as sRGB components (r, g, b, a), clamped to 0…1. */
    internal fun srgbComponents(color: Color): FloatArray {
        val converted = color.convert(ColorSpace.get(ColorSpace.Named.SRGB))
        return floatArrayOf(
            converted.red().coerceIn(0f, 1f),
            converted.green().coerceIn(0f, 1f),
            converted.blue().coerceIn(0f, 1f),
            converted.alpha().coerceIn(0f, 1f),
        )
    }

    /** A linear gradient in object-bounding-box units. */
    data class Gradient(
        val colors: List<Color> = emptyList(),
        val stops: List<Float> = emptyList(),
        val startX: Float = 0f,
        val startY: Float = 0f,
        val endX: Float = 1f,
        val endY: Float = 0f,
    ) {
        /** Fills [bounds] (the clip is the caller's), extending the end colours past the stops. */
        fun draw(canvas: Canvas, bounds: RectF, alpha: Float = 1f) {
            if (colors.size < 2 || stops != stops.sorted()) return
            val ints = colors.map { color ->
                val c = srgbComponents(color)
                Color.argb(c[3], c[0], c[1], c[2])
            }.toIntArray()
            val shader = LinearGradient(
                bounds.left + startX * bounds.width(), bounds.top + startY * bounds.height(),
                bounds.left + endX * bounds.width(), bounds.top + endY * bounds.height(),
                ints, stops.toFloatArray(), Shader.TileMode.CLAMP,
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = shader
                this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            }
            canvas.drawRect(bounds, paint)
        }
    }

    /** The document's `linearGradient`s by id (at most 16, 64 stops each), or empty if it doesn't parse. */
    fun gradients(data: ByteArray): Map<String, Gradient> {
        val gradients = mutableMapOf<String, Gradient>()
        var currentID: String? = null
        var current: Gradient? = null
        val parsed = ProjectXml.parse(
            data,
            onStart = { name, attributes ->
                when (name) {
                    "linearGradient" -> {
                        val id = attributes["id"]
                        val units = attributes["gradientUnits"]
                        if (gradients.size < 16 && id != null &&
                            (units == null || units == "objectBoundingBox") &&
                            attributes["gradientTransform"] == null
                        ) {
                            currentID = id
                            current = Gradient(
                                startX = coordinate(attributes["x1"], 0f),
                                startY = coordinate(attributes["y1"], 0f),
                                endX = coordinate(attributes["x2"], 1f),
                                endY = coordinate(attributes["y2"], 0f),
                            )
                        }
                    }
                    "stop" -> {
                        val gradient = current
                        val raw = attributes["stop-color"]
                        val color = raw?.let(::color)
                        if (gradient != null && gradient.stops.size < 64 && color != null) {
                            current = gradient.copy(
                                colors = gradient.colors + color,
                                stops = gradient.stops + coordinate(attributes["offset"], 0f),
                            )
                        }
                    }
                }
                true
            },
            onEnd = { name ->
                val id = currentID
                val value = current
                if (name == "linearGradient" && id != null && value != null) {
                    gradients[id] = value
                    currentID = null
                    current = null
                }
            },
        )
        return if (parsed) gradients else emptyMap()
    }

    private fun coordinate(raw: String?, fallback: Float): Float {
        raw ?: return fallback
        val percent = raw.endsWith("%")
        val number = (if (percent) raw.dropLast(1) else raw).toDoubleOrNull()
        if (number == null || !number.isFinite()) return fallback
        return (number / if (percent) 100.0 else 1.0).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * A minimal XML walk: no namespaces, no DTD processing, so entities and references never resolve
 * anything outside the document.
 */
internal object ProjectXml {
    /**
     * Calls [onStart] for each element (name, attributes; return false to abort, which fails
     * the parse) and [onEnd] when it closes. False if the document doesn't parse.
     */
    fun parse(
        data: ByteArray,
        onStart: (name: String, attributes: Map<String, String>) -> Boolean,
        onEnd: (name: String) -> Unit,
    ): Boolean = try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(data), null)
        var ok = true
        loop@ while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val attributes = HashMap<String, String>(parser.attributeCount)
                    for (i in 0 until parser.attributeCount) attributes[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                    if (!onStart(parser.name, attributes)) {
                        ok = false
                        break@loop
                    }
                }
                XmlPullParser.END_TAG -> onEnd(parser.name)
                XmlPullParser.END_DOCUMENT -> break@loop
            }
        }
        ok
    } catch (_: Exception) {
        false
    }
}
