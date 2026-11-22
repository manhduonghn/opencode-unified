package ai.opencode.feature.transcript.rows

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.Motion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.transcriptDragSource
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * The reply's copy glyph in its slot (iOS `ReplyCopyGlyph`). The Working line draws the same one
 * in the same place, so its handoff to the reply's end lands exactly on the button that replaces
 * it.
 */
object ReplyCopyGlyph {
    /** iOS's 44 pt slot → Android's 48 dp target width. */
    val slot: Dp = TranscriptMetrics.minimumHitSide

    /**
     * The glyph, not its tap target, lines up with the reply's text: its centre is 9 pt in from
     * the text edge (iOS −13 for a 44 pt slot; −15 for 48 dp).
     */
    val leading: Dp = 9.dp - slot / 2

    /** How far the Copy row's targets reach below their glyphs: at least 44 pt tall in all. */
    val targetBottom: Dp get() = max(TranscriptMetrics.turnHalf.value, 26f).dp

    /** The glyph ([copied] shows a checkmark), sized by the copy glyph so the row never shrinks. */
    @Composable
    fun Glyph(modifier: Modifier = Modifier, copied: Boolean = false) {
        // `.subheadline`, capped at `.xxxLarge` (iOS `dynamicTypeSize(...xxxLarge)`).
        val scale = min(LocalDensity.current.fontScale, 1.35f)
        val size = (17f * scale).dp
        Box(modifier.width(slot), contentAlignment = Alignment.Center) {
            Crossfade(copied, animationSpec = Motion.swap.animation(reduceMotion = rememberReduceMotion()), label = "ReplyCopyGlyph") { isCopied ->
                OpenCodeIcon(if (isCopied) "checkmark" else "square.on.square", contentDescription = null, size = size, tint = OpenCodeTheme.colors.secondaryText)
            }
        }
    }
}

/**
 * Under a finished reply: copy the whole reply as Markdown (iOS `ReplyActions`). The reply's
 * texts are parsed and serialized at tap time; `SessionFeature` plays `.tap` and writes the
 * clipboard for `TranscriptAction.CopyTextTapped`, as on iOS.
 */
@Composable
fun ReplyActions(texts: List<String>, modifier: Modifier = Modifier, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_500)
        copied = false
    }
    val copy = {
        onCopy(OpenCodeMarkdownBlock.markdown(texts.flatMap { OpenCodeMarkdownBlock.blocks(it) }))
        copied = true
    }
    // Dragging the glyph carries the reply as Markdown (iOS `.markdownBlocks(blocks)`).
    val dragPayload = remember(texts) { TranscriptDragPayload.MarkdownBlocks(texts.flatMap { OpenCodeMarkdownBlock.blocks(it) }) }
    val label = if (copied) stringResource(CoreR.string.common_copied) else stringResource(R.string.transcript_reply_copy_description)
    // Its slot already starts 15 dp out from the text, 1 dp from the screen's edge; the target goes no further.
    ReplyCopyGlyph.Glyph(
        copied = copied,
        modifier = modifier
            .offset(x = ReplyCopyGlyph.leading)
            .rowHitTarget(
                top = TranscriptMetrics.flowHalf,
                bottom = ReplyCopyGlyph.targetBottom,
                leading = 0.dp,
                target = Modifier
                    .transcriptDragSource(dragPayload, cornerRadius = 8.dp)
                    .rowClickable(onClick = copy)
                    .clearAndSetSemantics {
                        contentDescription = label
                        role = Role.Button
                        onClick { copy(); true }
                    },
            ),
    )
}

/** A centred time, the day a step heavier, like Messages (iOS `TranscriptTimeStamp`). */
@Composable
fun TranscriptTimeStamp(text: String, modifier: Modifier = Modifier) {
    val parts = text.split("\t", limit = 2)
    val style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum")
    val annotated = buildAnnotatedString {
        if (parts.size == 2) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(parts[0]) }
            append(" ${parts[1]}")
        } else {
            append(text)
        }
    }
    Text(
        annotated,
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = TranscriptTimeStamps.accessibilityLabel(text) },
        style = style,
        color = OpenCodeTheme.colors.secondaryText,
        textAlign = TextAlign.Center,
    )
}
