package ai.opencode.feature.session.header

import ai.opencode.feature.session.R
import ai.opencode.core.strings.localized
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.appFormattingLocale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.icu.text.CompactDecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How full the model's context window is: neutral, then orange from 80 % and red from 95 %,
 * matching where a compaction becomes worth it. Hidden from TalkBack: the control hosting it
 * carries [SessionContextText.accessibilityValue].
 *
 * @param fraction tokens used over the context limit (clamped to 0…1 for drawing).
 * @param modifier size it here (the ring fills its bounds, inset by half the line width).
 * @param lineWidth Stroke width.
 * @param trackOpacity the unfilled track's opacity; doubled under high contrast.
 */
@Composable
fun SessionContextRing(
    fraction: Double,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 3.5.dp,
    trackOpacity: Double = 0.2,
) {
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(
        targetValue = clamped,
        // The reduced fade under Reduce Motion.
        animationSpec = Motion.layout.animation(reduceMotion = reduceMotion),
        label = "contextRing",
    )
    val track = colors.text.copy(alpha = (if (colors.highContrast) trackOpacity * 2 else trackOpacity).toFloat().coerceIn(0f, 1f))
    val tint = SessionContextRing.tint(fraction, colors.isDark, colors.text, colors.destructive)
    Canvas(modifier.padding(lineWidth / 2).clearAndSetSemantics {}) {
        val width = lineWidth.toPx()
        drawCircle(color = track, radius = size.minDimension / 2, style = Stroke(width))
        // Butt ends read as a gauge rather than a spinner.
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset((size.width - size.minDimension) / 2, (size.height - size.minDimension) / 2),
            size = androidx.compose.ui.geometry.Size(size.minDimension, size.minDimension),
            style = Stroke(width, cap = StrokeCap.Butt),
        )
    }
}


/**
 * The context gauge around the header's More button. On the bar a bare arc around a plain ⋮ read as
 * part of the header, so here it is M3's determinate circular progress indicator: the same fill and
 * tint as [SessionContextRing], over a full `outlineVariant` track, so it always reads as a ring.
 */
@Composable
fun SessionContextGauge(fraction: Double, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0.0, 1.0).toFloat(),
        animationSpec = Motion.layout.animation(reduceMotion = reduceMotion),
        label = "contextGauge",
    )
    CircularProgressIndicator(
        progress = { animated },
        modifier = modifier.clearAndSetSemantics {},
        color = SessionContextRing.tint(fraction, colors.isDark, colors.text, colors.destructive),
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        strokeCap = StrokeCap.Round,
    )
}

object SessionContextRing {
    fun percent(fraction: Double): Int = (fraction * 100).roundToInt()

    /** Red from 95 %, orange from 80 %, else [primary]. */
    fun tint(fraction: Double, isDark: Boolean, primary: Color, red: Color): Color = when {
        fraction >= 0.95 -> red
        fraction >= 0.8 -> if (isDark) Color(1f, 0.6235f, 0.0392f) else Color(1f, 0.5843f, 0f)
        else -> primary
    }
}

/** "64% of 400K", or the token count when the window is unknown. */
object SessionContextText {
    fun summary(tokens: Int, limit: Int?, locale: Locale = appFormattingLocale()): String {
        if (limit == null || limit <= 0) return localized(R.string.session_sheet_context_tokens, NumberFormat.getIntegerInstance(locale).format(tokens))
        val percent = SessionContextRing.percent(tokens.toDouble() / limit.toDouble())
        val compact = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(limit.toLong())
        return localized(R.string.session_context_summary, percent, compact)
    }

    fun accessibilityValue(tokens: Int, limit: Int?, locale: Locale = appFormattingLocale()): String {
        val format = NumberFormat.getIntegerInstance(locale)
        if (limit == null || limit <= 0) return localized(R.string.session_sheet_context_tokens, format.format(tokens))
        val percent = SessionContextRing.percent(tokens.toDouble() / limit.toDouble())
        return localized(R.string.session_context_accessibility_value, percent, format.format(limit))
    }
}
