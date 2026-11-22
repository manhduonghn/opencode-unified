package ai.opencode.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Icon sizes. Material Symbols are drawn on a 24 dp grid. */
object OpenCodeIconSize {
    /** Inline with caption/footnote text. */
    val small: Dp = 18.dp
    /** Default (body text, toolbar and list icons). */
    val medium: Dp = 24.dp
    /** Prominent (empty states use [hero]). */
    val large: Dp = 28.dp
    /** Empty-state / `ContentUnavailableView` glyph. */
    val hero: Dp = 48.dp
}

/**
 * Draws an SF Symbol name as its Material Symbols Rounded counterpart. [contentDescription] is
 * `null` when decorative.
 */
@Composable
fun OpenCodeIcon(
    symbol: SFSymbol,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = OpenCodeIconSize.medium,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(symbol.drawable),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** [OpenCodeIcon] for a symbol name that arrives as a string (unknown names draw `help`). */
@Composable
fun OpenCodeIcon(
    sfName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = OpenCodeIconSize.medium,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(sfSymbol(sfName)),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
