package ai.opencode.feature.media

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The composer's image policy (iOS `SharedImageEncoder.maxPixelSize`, `compressionQuality`,
 * `mime`). Lives here because a drawing's export needs it and `:feature:composer`, which owns
 * `SharedImageEncoder`, sits above media.
 */
object PromptImageFormat {
    const val MAX_PIXEL_SIZE = 1568
    const val COMPRESSION_QUALITY = 0.8
    const val MIME = "image/jpeg"
}

/**
 * Coordinates are in the upright image's pixels, independent of the screen, its insets and the
 * eventual attachment size (iOS `ImageDrawing`).
 */
data class ImageDrawing(val ink: Ink = Ink.Red, val strokes: List<Stroke> = emptyList()) {
    enum class Ink(val argb: Int) {
        White(Color.WHITE),
        Black(Color.BLACK),
        Red(0xffff3b30.toInt()),
        Yellow(0xffffcc00.toInt()),
        Green(0xff34c759.toInt()),
        Blue(0xff0a84ff.toInt()),
    }

    data class Stroke(val ink: Ink, val points: List<Offset>) {
        fun draw(canvas: Canvas, width: Float, paint: Paint) {
            val first = points.firstOrNull() ?: return
            paint.color = ink.argb
            if (points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(first.x, first.y, width / 2, paint)
                return
            }
            val path = Path()
            path.moveTo(first.x, first.y)
            for (index in 0 until points.lastIndex) {
                val a = points[index]
                val b = points[index + 1]
                path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
            }
            path.lineTo(points.last().x, points.last().y)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
        }
    }

    val changed: Boolean get() = strokes.isNotEmpty()

    fun undo(): ImageDrawing = copy(strokes = strokes.dropLast(1))

    /**
     * Renders once at the composer's JPEG cap, rather than allocating another full-size bitmap
     * just to downsample it again. [image] is only read.
     */
    suspend fun attachment(image: Bitmap, id: String): OpenCodeOutgoingAttachment? {
        if (!changed || minOf(image.width, image.height) <= 0) return null
        return withContext(Dispatchers.Default) {
            val scale = minOf(1.0, PromptImageFormat.MAX_PIXEL_SIZE.toDouble() / maxOf(image.width, image.height))
            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
            val height = (image.height * scale).roundToInt().coerceAtLeast(1)
            val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(flat)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawColor(Color.BLACK)
                val target = Rect(0, 0, width, height)
                if (image.config == Bitmap.Config.HARDWARE) {
                    // ImageDecoder may supply a GPU-backed image a bitmap-backed Canvas can't
                    // read; shrink first so the software copy is bounded too.
                    val small = Bitmap.createScaledBitmap(image, width, height, true)
                    try {
                        val readable = small.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
                        try { canvas.drawBitmap(readable, null, target, paint) } finally { readable.recycle() }
                    } finally {
                        if (small !== image) small.recycle()
                    }
                } else {
                    canvas.drawBitmap(image, null, target, paint)
                }
                canvas.scale(width.toFloat() / image.width, height.toFloat() / image.height)
                strokes.forEach { it.draw(canvas, minOf(image.width, image.height) * 0.01f, paint) }
                val out = ByteArrayOutputStream()
                val quality = (PromptImageFormat.COMPRESSION_QUALITY * 100).roundToInt()
                if (!flat.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    null
                } else {
                    OpenCodeOutgoingAttachment(id = id, data = out.toByteArray(), mime = PromptImageFormat.MIME)
                }
            } finally {
                flat.recycle()
            }
        }
    }
}

/**
 * Flattens a drawing over its image into the copy Add to Prompt attaches (iOS
 * `ImageDrawingClient`, `@Dependency(\.imageDrawing)`).
 */
class ImageDrawingClient(
    val flatten: suspend (ImageDrawing, Bitmap, String) -> OpenCodeOutgoingAttachment? = { _, _, _ -> null },
) {
    companion object Key : DependencyKey<ImageDrawingClient> {
        override val liveValue: ImageDrawingClient = ImageDrawingClient(flatten = { drawing, image, id -> drawing.attachment(image, id) })
        override val testValue: ImageDrawingClient = ImageDrawingClient()
    }
}
