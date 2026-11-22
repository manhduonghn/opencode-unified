package ai.opencode.feature.settings.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.cos
import kotlin.math.sin

/**
 * The playground's real camera is the shared [ai.opencode.feature.settings.servers.QRCameraPreview]
 * with `once = false`; without access it shows [SimulatedCameraFeed].
 */

private const val SimulatedFeedFrameNanos = 1_000_000_000L / 30

/**
 * Without camera access: a dim desk with a sample code drifting in the frame, so the scan reads
 * without a Mac. The code isn't decodable. Ticks at most 30 times a second while [isRunning].
 */
@Composable
internal fun SimulatedCameraFeed(isRunning: Boolean, modifier: Modifier = Modifier) {
    // The feed's clock (seconds); read only while drawing, so a tick redraws without recomposing.
    val time = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (nanos - last >= SimulatedFeedFrameNanos) {
                    last = nanos
                    time.doubleValue = nanos / 1e9
                }
            }
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = time.doubleValue
        val w = size.width
        val h = size.height
        drawRect(
            Brush.linearGradient(listOf(Color(0.16f, 0.16f, 0.16f), Color(0.07f, 0.07f, 0.07f)), start = Offset.Zero, end = Offset(w, h)),
        )
        // A hand holding a phone: the code drifts a few points and tilts a little.
        val pt = density
        val driftX = (sin(t * 0.9) * 4).toFloat() * pt
        val driftY = (cos(t * 0.7) * 3).toFloat() * pt
        val side = w * 0.58f
        val originX = (w - side) / 2 + driftX
        val originY = (h - side) / 2 + driftY
        translate(originX, originY) {
            rotate(degrees = (sin(t * 0.5) * 1.5).toFloat(), pivot = Offset(side / 2, side / 2)) {
                val inset = 10 * pt
                drawRoundRect(
                    color = Color(0.92f, 0.92f, 0.92f),
                    topLeft = Offset(-inset, -inset),
                    size = Size(side + 2 * inset, side + 2 * inset),
                    cornerRadius = CornerRadius(8 * pt),
                )
                val modules = 25
                val cell = side / modules
                val ink = Color(0.08f, 0.08f, 0.08f)
                val extra = 0.3f * pt
                for (y in 0 until modules) {
                    for (x in 0 until modules) {
                        if (!isDark(x, y, modules)) continue
                        drawRect(ink, topLeft = Offset(x * cell, y * cell), size = Size(cell + extra, cell + extra))
                    }
                }
            }
        }
    }
}

/** Three finder squares and a fixed scatter: QR-shaped, never a real code. */
internal fun isDark(x: Int, y: Int, n: Int): Boolean {
    for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
        if (x >= fx && x < fx + 7 && y >= fy && y < fy + 7) {
            val a = x - fx
            val b = y - fy
            return a == 0 || a == 6 || b == 0 || b == 6 || (a in 2..4 && b in 2..4)
        }
    }
    return (x * 17 + y * 31 + x * y * 7) % 11 < 5
}
