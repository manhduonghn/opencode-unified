package ai.opencode.feature.session.header

import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max

/**
 * The band behind the session header, as resolved for this phone. Place it at the top of the
 * session screen, under the header controls and over the transcript, drawing up through the status
 * bar.
 *
 * Android mapping (no blur imitation, AGENTS.md):
 * - Once content scrolls under the header, the bar from the top of the screen to the bottom of the
 *   header row fills with the canvas over a hairline (the desktop title bar), with no soft tail (a
 *   fading tail left text showing through it). The fill fades in and out on the theme's effects
 *   spring, as `TopAppBar`'s container colour does.
 * - The wash ([SessionHeaderStyle.Appearance]) is the canvas colour at its opacity through the same
 *   stops; its optional material is a tonal surface (ultraThin → `surfaceContainerLow`, thin →
 *   `surfaceContainer`, regular → `surfaceContainerHigh`), revealed over its own `fadeIn`.
 *
 * It takes no touches and is hidden from TalkBack. Scroll is read in the draw phase, and the bar
 * recomposes only when content starts or stops being under it.
 *
 * @param backdrop the wash to draw, or null (`Resolved.backdrop`).
 * @param appBlur the edge band to draw as the scrolled bar, or null (`Resolved.appBlur`).
 * @param header the header row's height below [safeTop] (dp; `Resolved.header`).
 * @param safeTop the screen's top inset (status bar) above the header; 0 in an inset card.
 * @param scrolledUnderHeader how far content has scrolled under the header (dp), read per frame.
 * @param transcriptPlaced Whether the transcript has reported where it sits. Until it has, and for
 *     that first report, the bar follows the content without fading, so a cold launch's first frame
 *     already has it behind the rows.
 */
@Composable
fun SessionHeaderBackdrop(
    backdrop: SessionHeaderStyle.Appearance?,
    appBlur: SessionHeaderStyle.Resolved.AppBlur?,
    header: Float,
    safeTop: Dp,
    scrolledUnderHeader: () -> Float,
    transcriptPlaced: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (backdrop == null && appBlur == null) return
    val scheme = MaterialTheme.colorScheme
    val canvas = OpenCodeTheme.colors.canvas
    // The desktop's title bar: the pane's own canvas over a hairline, not a tonal fill.
    val scrim = canvas
    val hairline = OpenCodeTheme.colors.border
    val material = when (backdrop?.blur) {
        SessionHeaderStyle.Blur.UltraThin -> scheme.surfaceContainerLow
        SessionHeaderStyle.Blur.Thin -> scheme.surfaceContainer
        SessionHeaderStyle.Blur.Regular -> scheme.surfaceContainerHigh
        null -> Color.Transparent
    }
    val top = safeTop.value
    val barHeight = top + max(header, 0f)
    val height = max(barHeight, top + (backdrop?.fadeEnd ?: 0f))
    // M3 `TopAppBar`: the scrolled colour turns on as soon as anything is under the bar.
    val showsBar = appBlur != null && appBlur.radius > 0
    val barTarget = { if (showsBar && scrolledUnderHeader() > 0.5f) 1f else 0f }
    val bar = remember { Animatable(0f) }
    var barAnimates by remember { mutableStateOf(false) }
    val barSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    LaunchedEffect(showsBar) {
        snapshotFlow { barTarget() to transcriptPlaced() }.collectLatest { (target, placed) ->
            if (barAnimates) bar.animateTo(target, barSpec)
            else {
                bar.snapTo(target)
                barAnimates = placed
            }
        }
    }
    Spacer(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clearAndSetSemantics {}
            .drawBehind {
                val barAlpha = if (barAnimates) bar.value else barTarget()
                if (barAlpha > 0) {
                    drawRect(color = scrim, size = Size(size.width, barHeight.dp.toPx()), alpha = barAlpha)
                    val px = 1.dp.toPx()
                    drawRect(color = hairline, topLeft = androidx.compose.ui.geometry.Offset(0f, barHeight.dp.toPx() - px), size = Size(size.width, px), alpha = barAlpha)
                }
                if (backdrop != null) {
                    val revealed = backdrop.revealed(scrolledUnderHeader()).toFloat()
                    if (revealed > 0) {
                        if (backdrop.blur != null) drawBand(backdrop.stops(top, material, 1.0), top + backdrop.fadeEnd, revealed)
                        if (backdrop.opacity > 0) drawBand(backdrop.stops(top, canvas, backdrop.opacity), top + backdrop.fadeEnd, revealed)
                    }
                }
            },
    )
}

/** A vertical gradient band from the top to [heightDp], at [alpha]. */
private fun DrawScope.drawBand(stops: List<Pair<Float, Color>>, heightDp: Float, alpha: Float) {
    if (stops.size < 2 || heightDp <= 0) return
    val height = heightDp.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(*stops.toTypedArray(), startY = 0f, endY = height),
        size = Size(size.width, height),
        alpha = alpha,
    )
}

