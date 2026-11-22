package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerPanHorizontalScroller
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.opencode.core.motion.Motion

/**
 * What every transcript band shares: code, tables, and opened inline rows. On this branch a band is
 * the desktop's inset block (`markdown.css` `.highlights`, `message-part.css` tool output): a faint
 * fill inside a hairline border with small rounded corners.
 */
object TranscriptBandStyle {
    /** From the border to the content's first line box. */
    val verticalPadding: Dp = 12.dp

    /** The content's inset from the border (desktop `padding: 12px`). */
    val horizontalPadding: Dp = 12.dp

    /** Desktop `--radius-md`-ish block corners. */
    val cornerRadius: Dp = 8.dp

    fun fill(colors: OpenCodeColors): Color = colors.codeBand
}

/**
 * Runs the content out past the row's gutters to the screen's edges: measured [gutter] wider on
 * each side and placed [gutter] to the left.
 */
fun Modifier.bleedHorizontal(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx()
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = (constraints.minWidth + 2 * extra).coerceAtMost(maxWidth), maxWidth = maxWidth))
    val width = (placeable.width - 2 * extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * One device pixel. Compose places layouts on whole pixels, so the rule never renders two pixels
 * wide or vanishes at a fractional position.
 */
@Composable
fun HairlineRule(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(hairline).background(OpenCodeTheme.colors.border.copy(alpha = OpenCodeTheme.colors.border.alpha * alpha)))
}

/**
 * Fades a horizontal scroll's edges where more content lies past them: the trailing edge until the
 * end, the leading edge once scrolled. The fades ease over 0.15 s; only drawing changes.
 */
@Composable
fun Modifier.horizontalOverflowFade(state: ScrollState, width: Dp = 40.dp): Modifier {
    val fade = Motion.swap.animation<Float>(reduceMotion = rememberReduceMotion())
    val leading by animateFloatAsState(if (state.value > 1) 1f else 0f, fade, label = "leadingFade")
    val trailing by animateFloatAsState(if (state.maxValue - state.value > 1) 1f else 0f, fade, label = "trailingFade")
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width / 2)
            if (leading > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = leading), 1f to Color.Transparent, startX = 0f, endX = w),
                    topLeft = Offset.Zero, size = Size(w, size.height), blendMode = BlendMode.DstOut,
                )
            }
            if (trailing > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = trailing), startX = size.width - w, endX = size.width),
                    topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstOut,
                )
            }
        }
}

/** Where a table's full-width rules sit, reported by the grid's layout and read only while drawing. */
@androidx.compose.runtime.Stable
class TableRules {
    /** Y of each rule in the scroll's content, header rule first. Written in layout, read in draw (a redraw, never a relayout). */
    var offsets: List<Float> by androidx.compose.runtime.mutableStateOf(emptyList())
}

/**
 * Code and tables sit in the desktop's inset block. Content scrolls horizontally inside the block,
 * fading where more lies past it. A table's rules keep the viewport's width while its columns
 * scroll underneath.
 *
 * [action] sits in the block's top-trailing corner, over the content (a code block's copy
 * button). The whole block takes the sideways drag, so a swipe that starts on the action
 * still scrolls and cancels its tap.
 *
 * @param bandModifier applied to the whole block: a drag source there lifts the whole band.
 */
@Composable
fun MarkdownBand(
    modifier: Modifier = Modifier,
    rules: TableRules? = null,
    bandModifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val scroll = rememberScrollState()
    val overscroll = rememberOverscrollEffect()
    val layoutDirection = LocalLayoutDirection.current
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Column(modifier.then(bandModifier)) {
        val shape = RoundedCornerShape(TranscriptBandStyle.cornerRadius)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(TranscriptBandStyle.fill(colors))
                .border(hairline, colors.border, shape)
                .drawerPanHorizontalScroller(scroll)
                .scrollable(
                    scroll,
                    Orientation.Horizontal,
                    overscrollEffect = overscroll,
                    reverseDirection = ScrollableDefaults.reverseDirection(layoutDirection, Orientation.Horizontal, false),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = TranscriptBandStyle.verticalPadding)
                    .drawWithContent {
                        drawContent()
                        val offsets = rules?.offsets ?: return@drawWithContent
                        val px = hairline.toPx()
                        for ((index, y) in offsets.withIndex()) {
                            drawRect(
                                colors.border.copy(alpha = colors.border.alpha * (if (index == 0) 1f else 0.6f)),
                                topLeft = Offset(0f, y),
                                size = Size(size.width, px),
                            )
                        }
                    }
                    .horizontalOverflowFade(scroll, width = 32.dp)
                    .horizontalScroll(scroll, overscrollEffect = overscroll, enabled = false)
                    .padding(horizontal = TranscriptBandStyle.horizontalPadding),
            ) { content() }
            if (action != null) {
                Box(Modifier.align(Alignment.TopEnd)) { action() }
            }
        }
    }
}
