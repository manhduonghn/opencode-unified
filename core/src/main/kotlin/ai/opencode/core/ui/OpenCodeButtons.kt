package ai.opencode.core.ui

import ai.opencode.core.motion.motionPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A button's role. Red is only for [Destructive]. */
enum class OpenCodeButtonRole { Normal, Destructive }

object OpenCodeButtonDefaults {
    /**
     * Every peer button's height, the 48 dp touch target. Hierarchy comes from style, never size.
     */
    val height: Dp = 48.dp

    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
}

/** The primary action: Next, Send, Connect, Done. Red for [OpenCodeButtonRole.Destructive]. */
@Composable
fun OpenCodePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        val red = OpenCodeTheme.colors.destructive
        // SystemRed in every theme.
        ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)
    } else {
        ButtonDefaults.buttonColors()
    }
    val interactions = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = OpenCodeButtonDefaults.height).motionPress(interactions),
        enabled = enabled,
        shape = OpenCodeShape.control,
        colors = colors,
        contentPadding = OpenCodeButtonDefaults.contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

/** [OpenCodePrimaryButton] with a text label. */
@Composable
fun OpenCodePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
) = OpenCodePrimaryButton(onClick, modifier, enabled, role) { ButtonLabel(text) }

/**
 * The neutral companion: Back, Skip, Cancel, Not Now. Destructive keeps the neutral fill with red
 * text.
 */
@Composable
fun OpenCodeSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    content: @Composable RowScope.() -> Unit,
) {
    // Desktop neutral button: a light neutral fill with a hairline.
    val container = OpenCodeSurfaces.neutralFill
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = OpenCodeTheme.colors.destructive)
    } else {
        ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = OpenCodeTheme.colors.text)
    }
    val interactions = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = OpenCodeButtonDefaults.height).motionPress(interactions),
        enabled = enabled,
        shape = OpenCodeShape.control,
        border = OpenCodeSurfaces.border,
        colors = colors,
        contentPadding = OpenCodeButtonDefaults.contentPadding,
        interactionSource = interactions,
        content = content,
    )
}

/** [OpenCodeSecondaryButton] with a text label. */
@Composable
fun OpenCodeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
) = OpenCodeSecondaryButton(onClick, modifier, enabled, role) { ButtonLabel(text) }

@Composable
private fun ButtonLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
