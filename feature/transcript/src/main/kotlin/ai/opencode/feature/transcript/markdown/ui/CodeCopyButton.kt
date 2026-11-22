package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.R
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Android: a code block's Copy button, as the desktop app draws it (`markdown-copy-button` in
 * `packages/session-ui`: a 24 px ghost icon button in the block's top-right corner, always shown on
 * touch screens, a check for 2 s after copying). The 48 dp target fills the block's corner; the
 * square is filled with the block's colour so long lines pass under it cleanly.
 */
@Composable
fun CodeCopyButton(code: String, onCopy: (String) -> Unit) {
    val colors = OpenCodeTheme.colors
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000) // desktop COPY_FEEDBACK_MS
            copied = false
        }
    }
    val copy = {
        onCopy(code)
        copied = true
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = if (copied) stringResource(CoreR.string.common_copied) else stringResource(R.string.transcript_code_copy_description)
    val shape = RoundedCornerShape(OpenCodeRadius.xs)
    var face = Modifier
        .padding(top = 6.dp, end = 6.dp)
        .size(24.dp)
        .background(TranscriptBandStyle.fill(colors), shape)
    if (pressed) {
        face = face.background(colors.text.copy(alpha = 0.08f), shape)
    }
    Box(
        Modifier
            .size(OpenCodeMinTapTarget)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = copy)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { copy(); true }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(face, contentAlignment = Alignment.Center) {
            Crossfade(copied, animationSpec = Motion.easeInOut(0.2), label = "CodeCopyGlyph") { isCopied ->
                val tint = if (pressed || isCopied) colors.text else colors.secondaryText
                OpenCodeIcon(if (isCopied) "checkmark" else "square.on.square", contentDescription = null, size = 16.dp, tint = tint)
            }
        }
    }
}
