package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The ghost rows that hold the list while the server picked answers, and the note left after a
// switch went back.

/** The ghost bars' lengths, as fractions of the row, in turn. */
internal val drawerGhostLengths = listOf(0.72f, 0.54f, 0.8f, 0.46f, 0.64f)

/**
 * A row's place while the server picked answers, a 12dp bar of a title's length with a slow sweep
 * (1.6 s, every bar in step; held under Reduce Motion). Hidden from TalkBack.
 */
@Composable
fun DrawerListGhostCell(index: Int, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val base = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.35f)
    val shine = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.2f)
    val fraction = drawerGhostLengths[index % drawerGhostLengths.size]
    // One ambient sweep per pass. A hidden drawer's ghosts hold still: no frame ticks for a drawer
    // nobody sees.
    val hidden = LocalDrawerHidden.current.value
    val sweeps = !reducedMotion && !hidden
    val sweep = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(sweeps) {
        if (!sweeps) return@LaunchedEffect
        while (true) {
            withFrameNanos { sweep.floatValue = AmbientClock.sweep(AmbientClock.mediaTime()).toFloat() }
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(DrawerMetrics.rowHeight + DrawerTunables.rowGap)
            .clearAndSetSemantics {},
    ) {
        val leading = (DrawerMetrics.rowEdge + DrawerMetrics.textInset).toPx()
        val available = (size.width - leading - (DrawerMetrics.trailingInset + DrawerMetrics.rowEdge).toPx()).coerceAtLeast(0f)
        val barHeight = 12.dp.toPx()
        val top = (size.height - DrawerTunables.rowGap.toPx() - barHeight) / 2
        val width = available * fraction
        val radius = CornerRadius(6.dp.toPx())
        drawRoundRect(base, topLeft = Offset(leading, top), size = Size(width, barHeight), cornerRadius = radius)
        if (sweeps && width > 0f) {
            val phase = sweep.floatValue
            // The sweep runs from the bar's right to its left, three bars wide with the shine in its middle.
            val sweepStart = leading + width - phase * 2 * width - width
            val brush = Brush.horizontalGradient(
                0.3f to Color.Transparent, 0.5f to shine, 0.7f to Color.Transparent,
                startX = sweepStart, endX = sweepStart + width * 3,
            )
            drawRoundRect(brush, topLeft = Offset(leading, top), size = Size(width, barHeight), cornerRadius = radius)
        }
    }
}

/**
 * The note left after a switch went back on its own, with the way to try again (or pair again, for
 * a sign-in it refused).
 */
@Composable
fun DrawerServerNoteRow(note: ServerSwitchNote, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val tint = when (note.reason) {
        is ServerSwitchNote.Reason.TimedOut -> colors.secondaryText
        ServerSwitchNote.Reason.Unreachable -> colors.danger
        ServerSwitchNote.Reason.SignInFailed -> colors.warning
    }
    Column(
        modifier
            .padding(horizontal = DrawerMetrics.rowEdge)
            .padding(top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            // The desktop card: floating fill, hairline ring, small radius.
            .background(OpenCodeSurfaces.color, RoundedCornerShape(OpenCodeRadius.card))
            .openCodeHairline(RoundedCornerShape(OpenCodeRadius.card))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("drawer-server-note"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon(note.systemImage, contentDescription = null, size = 20.dp, tint = tint)
            Text(
                note.title,
                modifier = Modifier.padding(start = 8.dp),
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        }
        Text(
            note.detail,
            modifier = Modifier.padding(start = 28.dp),
            style = OpenCodeTextStyles.footnote,
            color = colors.secondaryText,
        )
        OpenCodePrimaryButton(
            text = note.action,
            onClick = onAction,
            modifier = Modifier
                .padding(start = 28.dp, top = 10.dp)
                .testTag("drawer-server-note-action"),
        )
    }
}
