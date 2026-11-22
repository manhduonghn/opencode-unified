package ai.opencode.core.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Corner radii from the OpenCode desktop (`--radius-sm` 4 … `-xl` 10). Sheets keep a 16 dp top
 * radius.
 */
object OpenCodeRadius {
    /** Tiny marks: inline code, checkbox insets (desktop 4). */
    val xs: Dp = 4.dp
    /** Small fills and thumbnails (desktop 6). */
    val sm: Dp = 6.dp
    /** Tertiary-fill fields and rows (`tertiarySystemFill` boxes, desktop 8). */
    val fill: Dp = 8.dp
    /** Inline controls and trays (desktop 8). */
    val control: Dp = 8.dp
    /** Cards and glass cards (server list, error report, desktop 10). */
    val card: Dp = 10.dp
    /** Attachment chips, clipped media, settings tiles (desktop 10). */
    val tile: Dp = 10.dp
    /** Large panels: connect, server details, What's New cards (desktop dock 12). */
    val panel: Dp = 12.dp
    /** Sheets, scanner frame, model list, voice panel (16). */
    val sheet: Dp = 16.dp
}

/** [capsule] and [circle] stay true. */
object OpenCodeShape {
    val capsule = RoundedCornerShape(percent = 50)

    /** Buttons, pills and fields (desktop `--radius-md`/`-lg` at phone size). */
    val control = RoundedCornerShape(OpenCodeRadius.fill)
    val circle = CircleShape
    val card = RoundedCornerShape(OpenCodeRadius.card)
    val panel = RoundedCornerShape(OpenCodeRadius.panel)
    val sheet = RoundedCornerShape(OpenCodeRadius.sheet)
    val fill = RoundedCornerShape(OpenCodeRadius.fill)

    /** Top-only rounding for bottom sheets. */
    val sheetTop = RoundedCornerShape(topStart = OpenCodeRadius.sheet, topEnd = OpenCodeRadius.sheet)
}

/**
 * Material shape scale from the desktop radii: extraSmall 6, small 6, medium 8, large 10,
 * extraLarge 16.
 */
val OpenCodeShapes = Shapes(
    // Text fields and menus: the desktop's 6 px input and context-menu radius.
    extraSmall = RoundedCornerShape(OpenCodeRadius.sm),
    small = RoundedCornerShape(OpenCodeRadius.sm),
    medium = RoundedCornerShape(OpenCodeRadius.fill),
    large = RoundedCornerShape(OpenCodeRadius.card),
    extraLarge = RoundedCornerShape(OpenCodeRadius.sheet),
)

/**
 * Port of `GlassGeometry`: a rounded rectangle's radius is at most half its height. Clamp
 * hand-computed animated shapes with this.
 */
object OpenCodeShapeGeometry {
    fun radius(radius: Float, height: Float): Float = min(max(0f, radius), max(0f, height) / 2)
}

/** Spacing values, in dp. Prefer these to literals so layouts stay on the same grid. */
object OpenCodeSpacing {
    val s1: Dp = 1.dp
    val s2: Dp = 2.dp
    val s4: Dp = 4.dp
    val s6: Dp = 6.dp
    val s8: Dp = 8.dp
    val s10: Dp = 10.dp
    val s12: Dp = 12.dp
    val s14: Dp = 14.dp
    val s16: Dp = 16.dp
    val s18: Dp = 18.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    /** Screen and list-row horizontal inset. */
    val screenHorizontal: Dp = s16

    /** Default stack spacing between related items. */
    val stack: Dp = s8

    /** Between groups/sections. */
    val group: Dp = s12
}
