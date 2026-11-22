package ai.opencode.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The desktop's switch (`packages/ui` `forms/switch`) at touch size: a 40 × 24 dp track and 18 dp
 * thumb inside a 48 dp target. The off track's outline carries the 3:1 contrast. A drop-in for
 * Material's `Switch`.
 */
@Composable
fun OpenCodeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val colors = switchColors()

    var toggle: Modifier = Modifier
    if (onCheckedChange != null) {
        toggle = Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
    }

    val thumbWidthTarget = if (pressed && enabled) SwitchMetrics.pressedThumbWidth else SwitchMetrics.thumbSize
    val thumbXTarget = if (checked) {
        SwitchMetrics.trackWidth - SwitchMetrics.thumbInset - thumbWidthTarget
    } else {
        SwitchMetrics.thumbInset
    }
    val duration = SwitchMetrics.ANIMATION_MILLIS
    val thumbWidth by animateDpAsState(thumbWidthTarget, tween(duration), label = "switchThumbWidth")
    val thumbX by animateDpAsState(thumbXTarget, tween(duration), label = "switchThumbX")
    val trackColor by animateColorAsState(
        if (checked) colors.checkedTrack else colors.uncheckedTrack,
        tween(duration),
        label = "switchTrack",
    )
    val borderColor by animateColorAsState(
        if (checked) colors.checkedTrack else colors.uncheckedBorder,
        tween(duration),
        label = "switchBorder",
    )
    val thumbColor by animateColorAsState(
        if (checked) colors.checkedThumb else colors.uncheckedThumb,
        tween(duration),
        label = "switchThumb",
    )

    val trackShape = SwitchMetrics.trackShape
    val thumbShape = SwitchMetrics.thumbShape
    var thumbEdge: Modifier = Modifier
    if (colors.thumbEdge != Color.Transparent) {
        thumbEdge = Modifier
            .shadow(SwitchMetrics.thumbElevation, thumbShape, clip = false, ambientColor = colors.thumbShadow, spotColor = colors.thumbShadow)
            .border(SwitchMetrics.thumbEdgeWidth, colors.thumbEdge, thumbShape)
    }
    Box(
        modifier
            .then(toggle)
            .wrapContentSize(Alignment.Center)
            .requiredSize(SwitchMetrics.trackWidth, SwitchMetrics.trackHeight)
            .alpha(if (enabled) 1f else SwitchMetrics.DISABLED_ALPHA)
            .background(trackColor, trackShape)
            .border(SwitchMetrics.borderWidth, borderColor, trackShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(thumbWidth, SwitchMetrics.thumbSize)
                .then(thumbEdge)
                .background(thumbColor, thumbShape),
        )
    }
}

/** [OpenCodeSwitch]'s resolved colours for the current theme and appearance. */
private class SwitchColors(
    val checkedTrack: Color,
    val checkedThumb: Color,
    val uncheckedTrack: Color,
    val uncheckedBorder: Color,
    val uncheckedThumb: Color,
    /** A faint edge and shadow that lift a light thumb off a light track; transparent in dark. */
    val thumbEdge: Color,
    val thumbShadow: Color,
)

@Composable
private fun switchColors(): SwitchColors {
    val colors = OpenCodeTheme.colors
    val scheme = MaterialTheme.colorScheme
    // Off reads through its outline: the row colour mixed most of the way to the muted text
    // colour is 3:1 or more on the row in every theme (full strength under high contrast).
    val outlineStrength = if (colors.strongBorders) 1f else SwitchMetrics.OUTLINE_STRENGTH
    val outline = if (colors.theme == AppTheme.System) {
        scheme.outline
    } else {
        lerp(colors.groupedCard, colors.tertiaryText, outlineStrength)
    }
    val lightThumb = if (colors.isDark) colors.secondaryText else scheme.surfaceContainerLowest
    val edge = if (colors.isDark) Color.Transparent else Color.Black.copy(alpha = 0.10f)
    return SwitchColors(
        checkedTrack = colors.selection,
        checkedThumb = colors.onSelection,
        uncheckedTrack = scheme.surfaceContainerHighest,
        uncheckedBorder = outline,
        uncheckedThumb = lightThumb,
        thumbEdge = edge,
        thumbShadow = Color.Black.copy(alpha = 0.35f),
    )
}

/**
 * A sheet's drag handle as the desktop's resize handle: a 40 × 4 dp pill over Material's
 * `DragHandle`, which keeps its label and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeSheetHandle(modifier: Modifier = Modifier) {
    BottomSheetDefaults.DragHandle(
        modifier = modifier,
        width = OpenCodeSheetHandleDefaults.width,
        height = OpenCodeSheetHandleDefaults.height,
        shape = OpenCodeShape.capsule,
        color = OpenCodeSheetHandleDefaults.color,
    )
}

/** The handle pill's size and colour, for sheets that draw their own grabber band. */
object OpenCodeSheetHandleDefaults {
    val width: Dp = 40.dp
    val height: Dp = 4.dp

    val color: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.tertiaryText
}

/** [OpenCodeSwitch]'s geometry: the desktop switch's proportions at touch size. */
private object SwitchMetrics {
    val trackWidth: Dp = 40.dp
    val trackHeight: Dp = 24.dp
    val borderWidth: Dp = 1.dp
    val thumbInset: Dp = 3.dp
    val thumbSize: Dp = 18.dp
    val pressedThumbWidth: Dp = 21.dp
    val thumbElevation: Dp = 1.dp
    val thumbEdgeWidth: Dp = 0.5.dp
    val trackShape = RoundedCornerShape(7.dp)
    val thumbShape = RoundedCornerShape(OpenCodeRadius.xs)
    const val ANIMATION_MILLIS = 150
    const val DISABLED_ALPHA = 0.5f
    const val OUTLINE_STRENGTH = 0.85f
}
