package ai.opencode.feature.drawer.style

import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far the drawer's list has scrolled beneath its title, clamped to the header edge fade range.
 * Only [DrawerHeaderBackdrop] reads it, in its draw phase, so scrolling the list never recomposes
 * the drawer or its rows. A plain state holder: the list's scroll observer calls [update].
 */
@Stable
class DrawerHeaderScrollState {
    /** In dp. */
    var scrolledUnderHeader: Float by mutableFloatStateOf(0f)
        private set

    val edgeOpacity: Float
        get() = min(max(scrolledUnderHeader, 0f) / max(DrawerTunables.fade.value, 1f), 1f)

    /** [scrolled] is how far (dp) the list's content has passed under the header. */
    fun update(scrolled: Dp) {
        val fade = max(DrawerTunables.fade.value, 1f)
        val clamped = min(max(scrolled.value, 0f), fade)
        if (!(abs(clamped - scrolledUnderHeader) >= 0.25f || (clamped == 0f && scrolledUnderHeader != 0f))) return
        scrolledUnderHeader = clamped
    }
}

/**
 * Without a backdrop blur, the wash runs solid over the header and the controls, then fades out
 * over the same distance, so a row passing beneath never shows through.
 */
private const val NoBlurWashOpacity: Float = 1f

/**
 * Behind the drawer's title and search: a wash of the drawer's colour, solid through the status
 * bar, that eases out [DrawerTunables.fade] below the header. [opaque] (high contrast) draws the
 * header band solid. Never takes touches or reaches TalkBack.
 *
 * @param safeTop the status bar's height: the wash is solid there.
 * @param revealed 0…1, how far rows have scrolled beneath the title; [scrollState] wins when set.
 * @param background the drawer's colour (the surface the drawer sits on).
 */
@Composable
fun DrawerHeaderBackdrop(
    opaque: Boolean,
    safeTop: Dp,
    modifier: Modifier = Modifier,
    revealed: Float = 1f,
    scrollState: DrawerHeaderScrollState? = null,
    background: Color = OpenCodeTheme.colors.drawerCanvas,
) {
    Spacer(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawBehind {
                val header = size.height
                // How far below the header the edge runs out.
                val height = max(header + DrawerTunables.fade.toPx(), 1f)
                val safeStop = min(safeTop.toPx() / height, 0.5f)
                val washStop = min(max(header * DrawerTunables.washStop / height, safeStop), 1f)
                val effectiveRevealed = scrollState?.edgeOpacity ?: revealed
                val clampedReveal = effectiveRevealed.coerceIn(0f, 1f)
                if (clampedReveal <= 0f) return@drawBehind
                if (opaque) {
                    drawRect(background, size = Size(size.width, header), alpha = clampedReveal)
                } else {
                    val stops = DrawerHeaderBackdrop.stops(
                        background = background,
                        safeStop = safeStop,
                        washStop = washStop,
                        washOpacity = NoBlurWashOpacity,
                        curve = DrawerTunables.blurCurve,
                    )
                    drawRect(
                        brush = Brush.verticalGradient(colorStops = stops.toTypedArray(), startY = 0f, endY = height),
                        size = Size(size.width, height),
                        alpha = clampedReveal,
                    )
                }
            },
    )
}

object DrawerHeaderBackdrop {
    /** The wash's stops (location to colour) over the header plus its fade. */
    fun stops(
        background: Color,
        safeStop: Float,
        washStop: Float,
        washOpacity: Float,
        curve: DrawerEdgeCurve,
    ): List<Pair<Float, Color>> {
        val stops = mutableListOf(0f to background, safeStop to background)
        val start = min(max(washStop, safeStop), 1f)
        val span = max(1f - start, 0f)
        if (span <= 0.001f) {
            stops += start to background.copy(alpha = background.alpha * washOpacity)
            stops += 1f to background.copy(alpha = 0f)
            return stops
        }
        for (step in 0..8) {
            val t = step / 8f
            stops += (start + span * t) to background.copy(alpha = background.alpha * washOpacity * curve.falloff(t))
        }
        return stops
    }
}

/**
 * Behind New Session and Settings at the drawer's bottom: rows run on behind the upper [reach] of
 * the buttons, then fade into the drawer's colour all the way through the bottom inset to the
 * screen's bottom edge, so no row below the buttons or behind their lower edge stays legible under
 * them. As with the header. [opaque] (high contrast, the Hard edge) draws the band opaque. Size it
 * from the buttons' top edge to the screen's bottom; it draws [DrawerTunables.bottomFade] above its
 * bar. Never takes touches or reaches TalkBack.
 *
 * @param reach how far down from the buttons' top edge the bar starts (`drawerControlsReach`).
 * @param insetBottom the navigation bar inset at the very bottom of the screen.
 */
@Composable
fun DrawerFooterBackdrop(
    opaque: Boolean,
    reach: Dp,
    insetBottom: Dp,
    modifier: Modifier = Modifier,
    background: Color = OpenCodeTheme.colors.drawerCanvas,
) {
    Spacer(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawBehind {
                val footer = size.height
                val bar = max(footer - reach.toPx(), 1f)
                if (opaque) {
                    drawRect(background, topLeft = Offset(0f, footer - bar), size = Size(size.width, bar))
                    return@drawBehind
                }
                val height = max(bar + DrawerTunables.bottomFade.toPx(), 1f)
                val safeStop = min(insetBottom.toPx() / height, 0.5f)
                val washStop = min(max(bar * DrawerTunables.bottomWashStop / height, safeStop), 1f)
                val top = footer - height
                val stops = DrawerFooterBackdrop.stops(
                    background = background,
                    safeStop = safeStop,
                    washStop = washStop,
                    washOpacity = NoBlurWashOpacity,
                    curve = DrawerTunables.bottomBlurCurve,
                )
                drawRect(
                    brush = Brush.verticalGradient(colorStops = stops.toTypedArray(), startY = top, endY = footer),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, height),
                )
            },
    )
}

object DrawerFooterBackdrop {
    /** The wash's stops (location to colour), top (clear) to bottom (solid). */
    fun stops(
        background: Color,
        safeStop: Float,
        washStop: Float,
        washOpacity: Float,
        curve: DrawerEdgeCurve,
    ): List<Pair<Float, Color>> {
        val clampedSafe = safeStop.coerceIn(0f, 0.5f)
        val clampedWash = min(max(washStop, clampedSafe), 1f)
        val topFadeEnd = max(1f - clampedWash, 0f)
        val solidStart = min(max(1f - clampedSafe, topFadeEnd), 1f)
        val alpha = background.alpha
        val stops = mutableListOf<Pair<Float, Color>>()

        if (topFadeEnd > 0.001f) {
            for (step in 0..8) {
                val t = step / 8f
                stops += (topFadeEnd * t) to background.copy(alpha = alpha * washOpacity * curve.ease(t))
            }
        } else {
            stops += 0f to background.copy(alpha = alpha * washOpacity)
        }

        val midSpan = max(solidStart - topFadeEnd, 0f)
        if (midSpan > 0.001f) {
            for (step in 1..4) {
                val t = step / 4f
                val opacity = washOpacity + (1f - washOpacity) * curve.ease(t)
                stops += (topFadeEnd + midSpan * t) to background.copy(alpha = alpha * opacity)
            }
        }

        stops += 1f to background
        return stops
    }
}

/**
 * Full strength over the outer [fadeStart] fraction of the edge, easing to nothing at its inner
 * end. Android draws no variable blur (see [DrawerHeaderBackdrop]); the profile stays for parity
 * and for any future edge effect.
 */
object DrawerEdgeBlur {
    enum class Edge { Top, Bottom }

    fun strengths(fadeStart: Float, curve: DrawerEdgeCurve, edge: Edge = Edge.Top, samples: Int = 32): List<Float> {
        if (samples <= 0) return emptyList()
        return (0..samples).map { step ->
            val position = step.toFloat() / samples
            val y = if (edge == Edge.Top) position else 1f - position
            if (y <= fadeStart) return@map 1f
            val t = ((y - fadeStart) / max(1f - fadeStart, 0.001f)).coerceIn(0f, 1f)
            curve.falloff(t)
        }
    }
}

/** An edge's falloff. */
enum class DrawerEdgeCurve {
    Smoothstep, Linear;

    fun ease(t: Float): Float = when (this) {
        Smoothstep -> t * t * (3 - 2 * t)
        Linear -> t
    }

    fun falloff(t: Float): Float = when (this) {
        Smoothstep -> 1 - t * t * (3 - 2 * t)
        Linear -> 1 - t
    }
}

/** The drawer's bottom edge: soft (the fade) or hard (an opaque band). */
enum class DrawerEdgeStyle { Soft, Hard }

/** The bottom edge: the system's, or the custom backdrop. */
enum class DrawerBottomEdgeMode { System, Custom }
