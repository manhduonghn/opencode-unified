package ai.opencode.core.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A number whose digits roll on per-place springs with a vertical smear, drawn on a [Canvas]. Plain
 * cross-faded text under Reduce Motion, RTL, no ASCII digits, a stopped screen, or no room.
 */
@Composable
fun RollingNumber(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    color: Color = Color.Unspecified,
    motion: RollingNumberStyle = RollingNumberTunables.style(),
    alignment: RollingNumberAlignment = RollingNumberAlignment.Start,
    monospacedDigits: Boolean = true,
    accessibilityLabel: String? = null,
) {
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val resolvedStyle = remember(style, textColor, monospacedDigits) {
        style.copy(color = textColor, fontFeatureSettings = if (monospacedDigits) "tnum" else style.fontFeatureSettings)
    }
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val layoutDirection = LocalLayoutDirection.current
    val configurationLocale = LocalConfiguration.current.locales[0]
    val locale = remember(configurationLocale) { appFormattingLocale(configurationLocale) }
    val semantics = Modifier.clearAndSetSemantics { contentDescription = accessibilityLabel ?: text }

    val canRoll = !reduceMotion &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) &&
        layoutDirection == LayoutDirection.Ltr &&
        RollingNumberModel.isRollable(text) &&
        motion.duration > 0

    SideEffect { RollingNumberTunables.register() }
    val model = remember { RollingNumberModel() }
    model.sync(text, locale, animated = canRoll, style = motion, nowSeconds = System.nanoTime() / 1e9)

    if (!canRoll) {
        // One line, wrapping only at accessibility sizes.
        val maxLines = if (LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE) Int.MAX_VALUE else 1
        Crossfade(text, modifier.then(semantics), animationSpec = Motion.easeInOut(Motion.fadeStandard), label = "RollingNumber") {
            Text(it, style = resolvedStyle, maxLines = maxLines)
        }
        return
    }

    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val metrics = remember(resolvedStyle, density, fontFamilyResolver) {
        SharedRollingMetrics.get(resolvedStyle, density, fontFamilyResolver)
    }
    val targetWidth = RollingNumberModel.targetWidth(text, metrics, locale)

    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(text, motion) {
        while (model.isMoving) {
            withFrameNanos { frameNanos = it }
        }
    }

    BoxWithConstraints(modifier.then(semantics)) {
        if (constraints.maxWidth < targetWidth) {
            Text(text, style = resolvedStyle, maxLines = 1)
            return@BoxWithConstraints
        }
        val widthDp = with(density) { max(targetWidth, 1f).toDp() }
        val heightDp = with(density) { metrics.lineHeight.toDp() }
        // Clipped to its line: outgoing digits roll out of view, not over neighbours.
        Canvas(Modifier.size(widthDp, heightDp).clipToBounds().baselineAt(metrics.ascender)) {
            val time = if (frameNanos == 0L) System.nanoTime() / 1e9 else frameNanos / 1e9
            val frame = model.frame(time, metrics, motion, targetWidth, alignment)
            val fade = if (frame.isSettled) 0f else min(0.45f, max(0f, motion.edgeFade))
            drawRollingFrame(frame, metrics, textColor, fade)
        }
    }
}

/** [RollingNumber] for an integer, grouped as [appFormattingLocale] writes it. */
@Composable
fun RollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    color: Color = Color.Unspecified,
    motion: RollingNumberStyle = RollingNumberTunables.style(),
    alignment: RollingNumberAlignment = RollingNumberAlignment.Start,
    accessibilityLabel: String? = null,
) {
    val configurationLocale = LocalConfiguration.current.locales[0]
    val text = remember(value, configurationLocale) { NumberFormat.getIntegerInstance(appFormattingLocale(configurationLocale)).format(value) }
    RollingNumber(text, modifier, style, color, motion, alignment, accessibilityLabel = accessibilityLabel)
}

/** Exposes the glyphs' baseline so the canvas aligns with neighbouring text. */
private fun Modifier.baselineAt(ascender: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = ascender.roundToInt()
    layout(placeable.width, placeable.height, mapOf(FirstBaseline to baseline, LastBaseline to baseline)) {
        placeable.place(0, 0)
    }
}

/**
 * Glyph metrics shared by every [RollingNumber] with the same style, so rows scrolling in don't
 * measure glyphs again. Keeps the few most recent styles.
 */
private object SharedRollingMetrics {
    private const val MAX_STYLES = 8

    private data class Key(val style: TextStyle, val density: Float, val fontScale: Float, val resolver: FontFamily.Resolver)

    private val cache = LinkedHashMap<Key, TextRollingMetrics>(16, 0.75f, true)

    fun get(style: TextStyle, density: Density, resolver: FontFamily.Resolver): TextRollingMetrics {
        val layoutStyle = style.copy(color = Color.Unspecified)
        val key = Key(layoutStyle, density.density, density.fontScale, resolver)
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null) return cached
            // Rolling only runs left to right, so the glyphs are always measured that way.
            val measurer = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = 0)
            val metrics = TextRollingMetrics(measurer, layoutStyle)
            cache[key] = metrics
            if (cache.size > MAX_STYLES) {
                val oldest = cache.keys.first()
                cache.remove(oldest)
            }
            return metrics
        }
    }
}

/** Glyph metrics from Compose text layout. */
private class TextRollingMetrics(private val measurer: TextMeasurer, private val style: TextStyle) : RollingNumberMetrics {
    private val layouts = HashMap<String, TextLayoutResult>()
    private val advances = HashMap<String, Float>()

    fun layout(glyph: String): TextLayoutResult = layouts.getOrPut(glyph) { measurer.measure(glyph, style, maxLines = 1) }

    private val zero = layout("0")
    override val lineHeight: Float = max(1f, ceil(zero.size.height.toFloat()))
    override val ascender: Float = ceil(zero.firstBaseline)

    override fun advance(glyph: String): Float = advances.getOrPut(glyph) {
        val result = layout(glyph)
        result.getLineRight(0) - result.getLineLeft(0)
    }
}

private fun DrawScope.drawRollingFrame(frame: RollingNumberFrame, metrics: RollingNumberMetrics, color: Color, fade: Float) {
    val height = metrics.lineHeight
    val textMetrics = metrics as TextRollingMetrics
    val bounds = Rect(0f, 0f, size.width, size.height)
    val masked = fade > 0f
    if (masked) drawIntoCanvas { it.saveLayer(bounds, Paint()) }

    /** [smear]: vertical blur radius (px), already gated by [smearRadius]; 0 draws the glyph sharp. */
    fun glyph(glyph: String, centerX: Float, centerY: Float, alpha: Float, smear: Float, scale: Float = 1f) {
        if (alpha <= 0.001f) return
        val layout = textMetrics.layout(glyph)
        val topLeft = Offset(centerX - layout.size.width / 2f, centerY - layout.size.height / 2f)
        scale(scale, pivot = Offset(centerX, centerY)) {
            if (smear <= 0f) {
                drawText(layout, color = color, topLeft = topLeft, alpha = alpha)
            } else {
                // Vertical Gaussian, summed additively offscreen.
                drawIntoCanvas { it.saveLayer(bounds, Paint()) }
                for (k in -SMEAR_TAPS..SMEAR_TAPS) {
                    val t = k.toFloat() / SMEAR_TAPS
                    val weight = exp(-2.5f * t * t) / SMEAR_WEIGHT_TOTAL
                    drawText(
                        layout,
                        color = color,
                        topLeft = topLeft + Offset(0f, t * smear),
                        alpha = alpha * weight,
                        blendMode = BlendMode.Plus,
                    )
                }
                drawIntoCanvas { it.restore() }
            }
        }
    }

    for (slot in frame.slots) {
        val opacity = slot.opacity.toFloat()
        if (opacity <= 0.001f) continue
        val centerX = slot.x + slot.width / 2
        val smear = smearRadius(slot.smear, slot.blur)
        when (val content = slot.content) {
            is RollingNumberFrame.Content.Reel -> {
                val base = floor(content.position).toInt()
                for (k in (base - 1)..(base + 2)) {
                    val y = ((k - content.position) * height + slot.rise * height).toFloat()
                    if (y <= -height || y >= height) continue
                    glyph(rollingDigitGlyph(k), centerX, y + height / 2, opacity, smear)
                }
            }
            is RollingNumberFrame.Content.Symbol -> {
                val p = content.progress.toFloat()
                if (content.from != null && p < 1f) {
                    glyph(content.from, centerX, height / 2, opacity * (1 - p), smear, 1 + 0.04f * p)
                }
                if (content.to != null) {
                    val inScale = 0.96f + 0.04f * p + 0.03f * sin(p * Math.PI.toFloat())
                    glyph(content.to, centerX, height / 2, opacity * p, smear, inScale)
                }
            }
        }
    }

    if (masked) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                (1 - fade) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
    }
}

/** The blur one slot draws with. */
private fun smearRadius(band: Float, arrival: Float): Float =
    max(if (band >= BAND_BLUR_THRESHOLD) band else 0f, if (arrival > ARRIVAL_BLUR_THRESHOLD) arrival else 0f)

private const val BAND_BLUR_THRESHOLD = 0.45f
private const val ARRIVAL_BLUR_THRESHOLD = 0.3f

/** Body 28 pt over Large's 17 pt. */
private const val ACCESSIBILITY_FONT_SCALE = 28f / 17f
private const val SMEAR_TAPS = 4
private val SMEAR_WEIGHT_TOTAL: Float = (-SMEAR_TAPS..SMEAR_TAPS).sumOf { k ->
    val t = k.toDouble() / SMEAR_TAPS
    exp(-2.5 * t * t)
}.toFloat()
