package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.transcript.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import ai.opencode.feature.transcript.list.transcriptNow
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The separator colour at its quietest. */
@Composable
internal fun quaternaryLine(): Color = OpenCodeTheme.colors.border

/**
 * A centred label between two hairlines that share what's left of the width: the label takes what
 * it needs, leaving each line at least [minLine].
 */
@Composable
internal fun RuleRow(
    lineColor: Color,
    modifier: Modifier = Modifier,
    minLine: Dp = 0.dp,
    label: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.background(lineColor))
            Box { label() }
            Box(Modifier.background(lineColor))
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val spacing = TranscriptMetrics.standard.roundToPx()
        val line = 1.dp.roundToPx().coerceAtLeast(1)
        val labelMax = (width - 2 * spacing - 2 * minLine.roundToPx()).coerceAtLeast(0)
        val labelPlaceable = measurables[1].measure(Constraints(maxWidth = labelMax))
        val lineWidth = ((width - 2 * spacing - labelPlaceable.width) / 2).coerceAtLeast(0)
        val left = measurables[0].measure(Constraints.fixed(lineWidth, line))
        val right = measurables[2].measure(Constraints.fixed(lineWidth, line))
        val height = maxOf(labelPlaceable.height, line)
        layout(width, height) {
            left.place(0, (height - line) / 2)
            labelPlaceable.place(lineWidth + spacing, (height - labelPlaceable.height) / 2)
            right.place(width - lineWidth, (height - line) / 2)
        }
    }
}

/** A labelled horizontal rule, like the TUI's compaction marker. */
@Composable
fun TranscriptRule(
    modifier: Modifier = Modifier,
    lineColor: Color = quaternaryLine(),
    labelColor: Color,
    label: @Composable RowScope.() -> Unit,
) {
    RuleRow(lineColor, modifier) {
        CompositionLocalProvider(LocalContentColor provides labelColor, LocalTextStyle provides OpenCodeTextStyles.caption1.copy(color = labelColor)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = label)
        }
    }
}

/**
 * Where the reader left off: a labelled rule like a compaction's, above the first row that arrived
 * while they were away. The first time the reader sees it ([glowStart], on [transcriptNow]'s clock;
 * null with Reduce Motion) its rule and label start brighter and dim to the quiet rule over
 * `dividerGlowDuration`.
 */
@Composable
fun NewMessagesDivider(modifier: Modifier = Modifier, glowStart: Double? = null) {
    val glow by produceState(initialValue = newDividerGlow(glowStart, transcriptNow()), glowStart) {
        while (value > 0f) {
            withFrameNanos { }
            value = newDividerGlow(glowStart, transcriptNow())
        }
    }
    val newMessagesDescription = stringResource(R.string.transcript_new_messages_description)
    val newLabel = stringResource(R.string.transcript_new_messages_label)
    Box(
        modifier
            .padding(vertical = TranscriptMetrics.sectionHalf)
            .testTag("transcript-new-messages")
            .clearAndSetSemantics { contentDescription = newMessagesDescription },
    ) {
        TranscriptRule(labelColor = OpenCodeTheme.colors.secondaryText) { Text(newLabel) }
        if (glow > 0f) {
            TranscriptRule(
                Modifier.matchParentSize().graphicsLayer { alpha = glow },
                lineColor = OpenCodeTheme.colors.secondaryText,
                labelColor = OpenCodeTheme.colors.text,
            ) { Text(newLabel) }
        }
    }
}

/**
 * The divider's glow [now]: from `dividerGlowLift` to nothing on a cubic over
 * `dividerGlowDuration`, none before it's seen.
 */
internal fun newDividerGlow(glowStart: Double?, now: Double): Float {
    if (glowStart == null) return 0f
    val duration = TranscriptTunables.dividerGlowDuration
    val progress = if (duration <= 0) 1.0 else ((now - glowStart) / duration).coerceIn(0.0, 1.0)
    val eased = progress * progress * (3 - 2 * progress)
    return (TranscriptTunables.dividerGlowLift * (1 - eased)).toFloat()
}
