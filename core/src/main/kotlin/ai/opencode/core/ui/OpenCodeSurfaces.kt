package ai.opencode.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The OpenCode desktop's floating surfaces: a flat fill a step off the canvas, a hairline ring and
 * a small shadow. No tonal elevation. For what Material's theme can't express.
 */
object OpenCodeSurfaces {
    /** Dialogs and popovers (desktop dialog ~10–12 px). */
    val dialogShape: Shape = RoundedCornerShape(OpenCodeRadius.panel)

    /** Popovers, menus and floating panels. */
    val popoverShape: Shape = RoundedCornerShape(OpenCodeRadius.card)

    /** The hairline every floating surface wears. */
    val hairline: Dp = 1.dp

    /** A small shadow under surfaces that float over content (desktop `--v2-elevation-raised`). */
    val raisedShadow: Dp = 1.5.dp

    /** Popovers and menus (desktop `--shadow-md`). */
    val popoverShadow: Dp = 4.dp

    /** The floating surface's fill: white in light, a step above the canvas in dark. */
    val color: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.floating

    /** Neutral and secondary buttons' fill (`surfaceContainerHighest`). Worn with the hairline. */
    val neutralFill: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest

    /** Copy-command and terminal boxes, clearly set off from the canvas. */
    val commandFill: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.let { if (it.isDark) it.userBubble else it.variant.surfaceStrong.color }

    /** The floating surface's hairline. */
    val border: BorderStroke
        @Composable @ReadOnlyComposable get() = BorderStroke(hairline, OpenCodeTheme.colors.border)
}

/**
 * The floating fill for these colours: the canvas in light (desktop popovers are white), a raised
 * step above the canvas in dark ([darkFloatingFill]). The System theme keeps its code band.
 */
val OpenCodeColors.floating: Color
    get() = when {
        !isDark -> canvas
        theme == AppTheme.System -> codeBand.copy(alpha = 1f)
        else -> variant.darkFloatingFill().color
    }

/**
 * Dark floating fill: the code band lifted about a third of the way to the user bubble, so floating
 * panels read as raised.
 */
internal fun ThemeVariant.darkFloatingFill(): ThemeRGBA =
    codeBand.composited(canvas).mixedInOklab(userBubble.composited(canvas), 0.35)

/** A hairline ring in [shape] (desktop `0 0 0 1px border-base`). */
@Composable
fun Modifier.openCodeHairline(shape: Shape): Modifier =
    border(OpenCodeSurfaces.hairline, OpenCodeTheme.colors.border, shape)

/**
 * Material's [AlertDialog] as the desktop's dialog: floating fill, hairline ring, 12 dp corners.
 * Semantics, buttons and dismissal are Material's.
 */
@Composable
fun OpenCodeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val shape = OpenCodeSurfaces.dialogShape
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.openCodeHairline(shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let { content -> { ProvideTextStyle(OpenCodeTextStyles.headline) { content() } } },
        text = text?.let { content -> { ProvideTextStyle(OpenCodeTextStyles.subheadline) { content() } } },
        shape = shape,
        containerColor = OpenCodeSurfaces.color,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/**
 * Switch colours for the curated themes: off border and thumb in [OpenCodeColors.tertiaryText] for
 * 3:1 against every surface (WCAG 1.4.11).
 */
object OpenCodeSwitchDefaults {
    @Composable
    fun colors(): SwitchColors {
        val colors = OpenCodeTheme.colors
        if (colors.theme == AppTheme.System) return SwitchDefaults.colors()
        return SwitchDefaults.colors(
            uncheckedThumbColor = colors.tertiaryText,
            uncheckedBorderColor = colors.tertiaryText,
            uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}
