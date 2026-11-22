package ai.opencode.feature.drawer.catchup

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.drawer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Per-frame state of one swipe overlay: the controller writes it, the overlay reads it in draw and
 * layer blocks only, so a swipe never recomposes. Units: dp.
 */
@Stable
class CatchUpBadgeState(val disposition: CatchUpDisposition) {
    /** Ring progress source (dp travelled toward [threshold]). */
    internal var ringDistance by mutableFloatStateOf(0f)
    /** Wash source; equals [ringDistance] except during a flight, which animates it separately. */
    internal var washDistance by mutableFloatStateOf(0f)
    internal var threshold by mutableFloatStateOf(1f)
    internal var isFull by mutableStateOf(false)
        private set
    internal val pop = Animatable(1f)
    private var lastDistance = -1f

    /** Ring progress 0…1 (fills as the drag approaches the commit threshold). */
    internal val ringProgress: Float get() = min(max(ringDistance, 0f) / max(threshold, 1f), 1f)

    /** Wash alpha: `pow(min(d / (threshold × 0.62), 1), 0.85)`. */
    internal val washAlpha: Float
        get() = min(max(washDistance, 0f) / max(threshold * 0.62f, 1f), 1f).pow(0.85f)

    fun update(distance: Float, threshold: Float, reducedMotion: Boolean, animatedPop: Boolean, scope: CoroutineScope?) {
        val clamped = max(distance, 0f)
        if (clamped == 0f && lastDistance == 0f) return
        lastDistance = clamped
        this.threshold = threshold
        washDistance = clamped
        setRing(clamped, reducedMotion, animatedPop, scope)
    }

    /** Moves only the ring (a flight fills it at once while its wash eases in). */
    internal fun setRing(distance: Float, reducedMotion: Boolean, animatedPop: Boolean, scope: CoroutineScope?) {
        val clamped = min(max(distance, 0f) / max(threshold, 1f), 1f)
        val full = clamped >= 1f
        val fullChanged = full != isFull
        if (abs(clamped - ringProgress) <= 0.0005f && !fullChanged) return
        isFull = full
        ringDistance = max(distance, 0f)
        if (fullChanged && full && animatedPop && !reducedMotion) {
            // The filled disc arrives on `MotionRole.MicroIn`, from as far above full size as a
            // micro arrival starts below it.
            scope?.launch {
                pop.snapTo(2f - MotionTunables.microInScale.value)
                pop.animateTo(1f, MotionRole.MicroIn.animation())
            }
        } else if (clamped < 0.01f && pop.isRunning) {
            scope?.launch { pop.snapTo(1f) }
        }
    }
}

/**
 * A single tinted surface follows the whole card, with a Slack-style progress circle and bold
 * label in the top-leading (Mark as Read) or top-trailing (Keep Unread) corner that fills solid
 * at the commit threshold. Not an accessibility element.
 *
 * @param opaque An opaque wash.
 */
@Composable
internal fun CatchUpSwipeOverlay(state: CatchUpBadgeState, opaque: Boolean, modifier: Modifier = Modifier) {
    val isRead = state.disposition == CatchUpDisposition.markRead
    val tint = if (isRead) CatchUpMetrics.readColor else CatchUpMetrics.keepColor
    val depth = Color(red = tint.red * 0.70f, green = tint.green * 0.70f, blue = tint.blue * 0.78f, alpha = 1f)
    val washAlpha = if (opaque) 1f else 0.96f
    val brush = Brush.linearGradient(listOf(tint.copy(alpha = washAlpha), depth.copy(alpha = washAlpha)))
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .graphicsLayer { alpha = state.washAlpha }
            .drawBehind { drawRect(brush) },
    ) {
        Column(
            modifier = Modifier
                .align(if (isRead) Alignment.TopStart else Alignment.TopEnd)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = if (isRead) Alignment.Start else Alignment.End,
        ) {
            CatchUpProgressCircle(state, tint, isRead)
            Text(
                text = if (isRead) stringResource(R.string.drawer_catch_up_swipe_mark_read) else stringResource(R.string.drawer_catch_up_swipe_keep_unread),
                style = OpenCodeTextStyles.title1.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                color = Color.White,
                textAlign = if (isRead) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

/** A Slack-style progress circle. */
@Composable
private fun CatchUpProgressCircle(state: CatchUpBadgeState, tone: Color, isRead: Boolean) {
    Canvas(Modifier.size(56.dp)) {
        scale(state.pop.value) {
            val scale = size.width / 100f
            val center = Offset(size.width / 2, size.height / 2)
            val full = state.isFull
            drawCircle(if (full) Color.White else Color.Black.copy(alpha = 0.18f), radius = 44 * scale, center = center)
            val ringRadius = 46 * scale
            val ringStroke = Stroke(width = 6.5f * scale, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.32f), radius = ringRadius, center = center, style = Stroke(width = 6.5f * scale))
            val progress = state.ringProgress
            if (progress > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = ringStroke,
                )
            }
            val glyphColor = if (full) tone else Color.White
            if (isRead) {
                val path = Path().apply {
                    moveTo(33 * scale, 51.5f * scale)
                    lineTo(44 * scale, 62.5f * scale)
                    lineTo(67 * scale, 38.5f * scale)
                }
                drawPath(path, glyphColor, style = Stroke(width = 7.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                val glyph = Stroke(width = 6.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawRoundRect(
                    glyphColor,
                    topLeft = Offset(29 * scale, 35 * scale),
                    size = Size(42 * scale, 30 * scale),
                    cornerRadius = CornerRadius(5 * scale),
                    style = glyph,
                )
                val path = Path().apply {
                    moveTo(31 * scale, 38 * scale)
                    lineTo(50 * scale, 52 * scale)
                    lineTo(69 * scale, 38 * scale)
                }
                drawPath(path, glyphColor, style = glyph)
            }
        }
    }
}
