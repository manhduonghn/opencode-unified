package ai.opencode.core.ui

import ai.opencode.core.motion.motionPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Android's minimum touch target (Material / Android accessibility). */
val OpenCodeMinTapTarget: Dp = 48.dp

/**
 * The one owner of a control's hit shape: at least [minSize] both ways, content centred. Put it
 * after `clickable`. [accessibility] merges the content into one TalkBack node.
 */
fun Modifier.openCodeTapTarget(minSize: Dp = OpenCodeMinTapTarget, accessibility: Boolean = true): Modifier {
    val minimum = max(OpenCodeMinTapTarget.value, minSize.value).dp
    val target = this.layout { measurable, constraints ->
        val min = minimum.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = max(placeable.width, min).coerceIn(constraints.minWidth, constrainMax(constraints.maxWidth))
        val height = max(placeable.height, min).coerceIn(constraints.minHeight, constrainMax(constraints.maxHeight))
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }
    return if (accessibility) target.semantics(mergeDescendants = true) {} else target
}

private fun constrainMax(max: Int) = if (max == Constraints.Infinity) Int.MAX_VALUE else max

enum class OpenCodeIconButtonStyle {
    /** Bare glyph. */
    Standard,

    /** A standalone control: the desktop's neutral icon button. */
    Circle,
}

/**
 * An icon control with the 48 dp target built in. [size] is the visible face; [tint] fills
 * [OpenCodeIconButtonStyle.Circle].
 */
@Composable
fun OpenCodeIconButton(
    symbol: SFSymbol,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OpenCodeIconButtonStyle = OpenCodeIconButtonStyle.Circle,
    size: Dp = OpenCodeMinTapTarget,
    iconSize: Dp = OpenCodeIconSize.medium,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val face = modifier.size(max(size.value, OpenCodeMinTapTarget.value).dp).motionPress(interactions)
    when (style) {
        OpenCodeIconButtonStyle.Standard -> IconButton(onClick = onClick, modifier = face, enabled = enabled, interactionSource = interactions) {
            OpenCodeIcon(symbol, contentDescription = contentDescription, size = iconSize)
        }
        OpenCodeIconButtonStyle.Circle -> {
            val container = tint ?: OpenCodeSurfaces.neutralFill
            OutlinedIconButton(
                onClick = onClick,
                modifier = face,
                enabled = enabled,
                shape = OpenCodeShape.card,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = container,
                    contentColor = tint?.highContrastForeground() ?: OpenCodeTheme.colors.text,
                ),
                border = if (tint == null) OpenCodeSurfaces.border else null,
                interactionSource = interactions,
            ) {
                OpenCodeIcon(symbol, contentDescription = contentDescription, size = iconSize)
            }
        }
    }
}
