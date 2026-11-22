package ai.opencode.feature.transcript.rows

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What a subagent's (or a failed run's) status glyph shows. The orb lives in
 * `Session/AgentActivity`; `:feature:session` fills [TranscriptRowSlots.agentGlyph] with its port,
 * and rows fall back to a plain glyph.
 */
@Immutable
data class AgentGlyphState(
    val sessionID: String?,
    val phase: Phase,
    val needsInput: Boolean = false,
    val size: Dp = 18.dp,
) {
    enum class Phase { Spawning, Running, Done, Failed }
}

/** Upward views the rows host. Each slot is optional: a null slot draws the row's own fallback. */
@Immutable
data class TranscriptRowSlots(
    /** Scoped to the transcript. */
    val agentGlyph: (@Composable (state: AgentGlyphState, modifier: Modifier) -> Unit)? = null,
    /** Without one, model switches show filled sparkles. */
    val providerMark: (@Composable (providerID: String, modifier: Modifier) -> Unit)? = null,
)

val LocalTranscriptRowSlots = staticCompositionLocalOf { TranscriptRowSlots() }

/** Draws [state] with the session's orb when provided, else a plain status glyph. */
@Composable
fun AgentActivityGlyph(state: AgentGlyphState, modifier: Modifier = Modifier) {
    val slot = LocalTranscriptRowSlots.current.agentGlyph
    if (slot != null) {
        slot(state, modifier)
        return
    }
    val colors = OpenCodeTheme.colors
    Box(modifier.size(state.size), contentAlignment = Alignment.Center) {
        when {
            state.phase == AgentGlyphState.Phase.Failed ->
                OpenCodeIcon("xmark.circle.fill", contentDescription = null, size = state.size * 0.85f, tint = colors.danger)
            state.needsInput -> {
                val pulse by rememberInfiniteTransition(label = "needsInput").animateFloat(
                    0.45f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "needsInput",
                )
                Canvas(Modifier.size(state.size * 0.5f)) { drawCircle(colors.accent.copy(alpha = pulse)) }
            }
            state.phase == AgentGlyphState.Phase.Done ->
                Canvas(Modifier.size(state.size * 0.55f)) {
                    drawCircle(colors.secondaryText, style = Stroke(width = 1.5.dp.toPx()))
                }
            else -> CircularProgressIndicator(Modifier.size(state.size * 0.7f), color = colors.secondaryText, strokeWidth = 1.5.dp)
        }
    }
}
