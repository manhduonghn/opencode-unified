package ai.opencode.core.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** What a notice reports, which sets its glyph's colour. */
enum class OpenCodeNoticeTone {
    /** Still trying: a spinner instead of a glyph. */
    progress,

    /** Something failed. */
    failure,

    /** It needs the person to act, such as signing in again. */
    attention,
}

private data class OpenCodeNoticeContent(
    val title: String,
    val detail: String?,
    val tone: OpenCodeNoticeTone,
    val symbol: SFSymbol,
    val action: String?,
)

/**
 * How the app says something went wrong outside a form or a dialog: a floating chip with a glyph,
 * what failed, why, and a word naming its tap. A change of text swaps in place, and TalkBack
 * announces it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenCodeNotice(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: OpenCodeNoticeTone = OpenCodeNoticeTone.failure,
    symbol: SFSymbol = SFSymbol.ExclamationmarkCircleFill,
    action: String? = null,
    onClickLabel: String? = action,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val fontScale = LocalDensity.current.fontScale
    val tap = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .clip(OpenCodeShape.control)
            .combinedClickable(onClickLabel = onClickLabel, onLongClick = onLongClick, onClick = onClick)
            .openCodeTapTarget()
    }
    Surface(
        shape = OpenCodeShape.control,
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        shadowElevation = OpenCodeSurfaces.raisedShadow,
        modifier = modifier.then(tap).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        AnimatedContent(
            targetState = OpenCodeNoticeContent(title, detail, tone, symbol, action),
            transitionSpec = {
                val size = SizeTransform(clip = false) { _, _ -> Motion.layout.animation(reduceMotion = reduceMotion, visibilityThreshold = IntSize.VisibilityThreshold) }
                MotionTransition.swap(reduceMotion).content.using(size)
            },
            contentAlignment = Alignment.CenterStart,
            label = "openCodeNotice",
        ) { shown ->
            val tint = when (shown.tone) {
                OpenCodeNoticeTone.progress -> OpenCodeTheme.colors.secondaryText
                OpenCodeNoticeTone.failure -> OpenCodeTheme.colors.danger
                OpenCodeNoticeTone.attention -> OpenCodeTheme.colors.warning
            }
            Row(
                Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shown.tone == OpenCodeNoticeTone.progress) {
                    CircularProgressIndicator(Modifier.size((16 * fontScale).dp), color = tint, strokeWidth = 2.dp)
                } else {
                    OpenCodeIcon(shown.symbol, contentDescription = null, size = (18 * fontScale).dp, tint = tint, modifier = Modifier.width((18 * fontScale).dp))
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        shown.title,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = OpenCodeTheme.colors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shown.detail != null) {
                        Text(
                            shown.detail,
                            style = OpenCodeTextStyles.caption1,
                            color = OpenCodeTheme.colors.secondaryText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (shown.action != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        shown.action,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = OpenCodeTheme.colors.accent,
                        // The tap's label already says it.
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

/**
 * Shows a notice for [value] until it's null. Its room opens and closes from the bottom on
 * `layout`, so what sits above moves with it, and the last value stays drawn while it leaves.
 * Under Reduce Motion it cross-fades.
 */
@Composable
fun <T : Any> OpenCodeNoticePresence(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val reduceMotion = rememberReduceMotion()
    var shown by remember { mutableStateOf(value) }
    if (value != null) shown = value
    val threshold = IntSize.VisibilityThreshold
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter = expandVertically(Motion.layout.animation(MotionPhase.Enter, reduceMotion, visibilityThreshold = threshold), Alignment.Bottom, clip = false) +
            MotionTransition.micro(reduceMotion).enter,
        exit = shrinkVertically(Motion.layout.animation(MotionPhase.Exit, reduceMotion, visibilityThreshold = threshold), Alignment.Bottom, clip = false) +
            MotionTransition.micro(reduceMotion).exit,
    ) {
        shown?.let { content(it) }
    }
}
