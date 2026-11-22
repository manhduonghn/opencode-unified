package ai.opencode.feature.transcript.user

import ai.opencode.core.R as CoreR
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.rows.rowClickable
import ai.opencode.feature.transcript.rows.rowHitTarget
import ai.opencode.server.model.OpenCodeDelivery
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A waiting steering message's actions: its long-press menu and the same names for TalkBack
 * (iOS `TranscriptPendingActions`).
 */
@Stable
class TranscriptPendingActions(
    val sendNow: (() -> Unit)? = null,
    val moveToQueue: () -> Unit,
    val delete: () -> Unit,
    val copy: (String) -> Unit = {},
) {
    /** The menu: Copy (and Select) for text, then [QueueAndDelete]. */
    @Composable
    fun Menu(copying: String?, dismiss: () -> Unit, selecting: (() -> Unit)? = null) {
        if (!copying.isNullOrEmpty()) {
            UserMenuItem(stringResource(CoreR.string.common_copy), "square.on.square", dismiss) { copy(copying) }
            if (selecting != null) UserMenuItem(stringResource(R.string.transcript_message_select), "selection.pin.in.out", dismiss, onClick = selecting)
        }
        QueueAndDelete(dismiss)
    }

    /** Send Now (when stranded), Move to Queue and Delete, after a menu's own Copy. */
    @Composable
    fun QueueAndDelete(dismiss: () -> Unit) {
        sendNow?.let { UserMenuItem(stringResource(R.string.transcript_pending_send_now), OpenCodeDelivery.Steer.symbolName, dismiss, onClick = it) }
        UserMenuItem(stringResource(R.string.transcript_pending_move_to_queue), OpenCodeDelivery.Queue.symbolName, dismiss, onClick = moveToQueue)
        UserMenuItem(stringResource(CoreR.string.common_delete), "trash", dismiss, destructive = true, onClick = delete)
    }

    fun accessibilityActions(copying: String? = null): List<CustomAccessibilityAction> = buildList {
        if (!copying.isNullOrEmpty()) add(action(localized(CoreR.string.common_copy)) { copy(copying) })
        sendNow?.let { add(action(localized(R.string.transcript_pending_send_now), it)) }
        add(action(localized(R.string.transcript_pending_move_to_queue), moveToQueue))
        add(action(localized(CoreR.string.common_delete), delete))
    }
}

/**
 * Why a send failed, and where its session can move when its folder is gone
 * (`SessionFeature.State.missingFolderMoveTarget`) (iOS `TranscriptSendFailure`).
 */
data class TranscriptSendFailure(
    val reason: String,
    /** The name of the project whose folder Move to takes the session to. */
    val moveTo: String? = null,
    /** The session's move is on its way: Move to and Retry wait for it. */
    val isMoving: Boolean = false,
)

/**
 * A message whose send failed (`SessionFeature.FailedSend`): what its bubble and the line below
 * it offer (iOS `TranscriptFailedSend`).
 */
@Stable
class TranscriptFailedSend(
    val failure: TranscriptSendFailure,
    val move: () -> Unit,
    val retry: () -> Unit,
    val edit: () -> Unit,
    val delete: () -> Unit,
) {
    val moveTitle: String? get() = failure.moveTo?.let { localized(R.string.transcript_failed_send_move_to, it) }

    /** The bubble's menu: why it failed, then what to do about it. */
    @Composable
    fun Actions(dismiss: () -> Unit) {
        Text(
            failure.reason,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 280.dp),
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
        )
        HorizontalDivider(color = OpenCodeTheme.colors.border)
        val moveTitle = moveTitle
        if (failure.isMoving) {
            UserMenuItem(moving, "folder", dismiss, enabled = false) {}
        } else if (moveTitle != null) {
            UserMenuItem(moveTitle, "folder", dismiss, onClick = move)
        }
        UserMenuItem(stringResource(CoreR.string.common_retry), "arrow.clockwise", dismiss, enabled = !failure.isMoving, onClick = retry)
        UserMenuItem(stringResource(CoreR.string.common_edit), "pencil", dismiss, onClick = edit)
        UserMenuItem(stringResource(CoreR.string.common_delete), "trash", dismiss, destructive = true, onClick = delete)
    }

    val accessibilityActions: List<CustomAccessibilityAction>
        get() = buildList {
            if (!failure.isMoving) {
                moveTitle?.let { add(action(it, move)) }
                add(action(localized(CoreR.string.common_retry), retry))
            }
            add(action(localized(CoreR.string.common_edit), edit))
            add(action(localized(CoreR.string.common_delete), delete))
        }

    override fun equals(other: Any?): Boolean = other is TranscriptFailedSend && other.failure == failure
    override fun hashCode(): Int = failure.hashCode()

    companion object {
        val status: String get() = localized(R.string.transcript_failed_send_status)
        val moving: String get() = localized(R.string.transcript_failed_send_moving)
    }
}

internal fun action(label: String, block: () -> Unit) = CustomAccessibilityAction(label) { block(); true }

/** The long-press menu every user surface opens: the platform menu, anchored on the surface. */
@Composable
internal fun UserMessageMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, properties = ai.opencode.feature.media.liftMenuProperties(), content = content)
}

@Composable
internal fun UserMenuItem(
    title: String,
    systemImage: String,
    dismiss: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> OpenCodeTheme.colors.tertiaryText
        destructive -> OpenCodeTheme.colors.destructive
        else -> OpenCodeTheme.colors.text
    }
    DropdownMenuItem(
        text = { Text(title, color = color) },
        onClick = {
            dismiss()
            onClick()
        },
        enabled = enabled,
        leadingIcon = { OpenCodeIcon(systemImage, contentDescription = null, tint = color) },
    )
}

/**
 * The glyph a touch smaller and heavier than its word, for the quiet labels above a message
 * (iOS `SmallGlyphLabelStyle`).
 */
@Composable
internal fun SmallGlyphLabel(
    title: String,
    systemImage: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    glyphColor: Color = color,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        OpenCodeIcon(systemImage, contentDescription = null, size = smallGlyph(style), tint = glyphColor)
        Text(title, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** iOS `.imageScale(.small)` beside [style]: about the text's cap height plus a little. */
internal fun smallGlyph(style: TextStyle): Dp = (style.fontSize.value * 0.95f).dp

/** “↗ Steering” or “Queued” above a pending message (iOS `TranscriptPendingLabel`). */
@Composable
internal fun TranscriptPendingLabel(delivery: OpenCodeDelivery, modifier: Modifier = Modifier) {
    SmallGlyphLabel(
        delivery.pendingLabel,
        delivery.symbolName,
        style = OpenCodeTextStyles.caption1,
        color = OpenCodeTheme.colors.secondaryText,
        modifier = modifier,
    )
}

/**
 * “! Couldn’t send · Retry · Edit · Delete” under a failed message. The status opens the same
 * menu as the bubble.
 */
@Composable
internal fun TranscriptFailedSendLine(failedSend: TranscriptFailedSend, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val style = OpenCodeTextStyles.footnote
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            SmallGlyphLabel(
                TranscriptFailedSend.status,
                "exclamationmark.circle.fill",
                style = style,
                color = OpenCodeTheme.colors.secondaryText,
                glyphColor = OpenCodeTheme.colors.destructive,
                modifier = Modifier
                    .rowHitTarget(top = 12.dp, bottom = 12.dp, target = Modifier.rowClickable(role = null) { menu = true })
                    .clearAndSetSemantics {
                        contentDescription = TranscriptFailedSend.status
                        stateDescription = failedSend.failure.reason
                        customActions = failedSend.accessibilityActions
                    },
            )
            UserMessageMenu(menu, { menu = false }) { failedSend.Actions { menu = false } }
        }
        Text("·", style = style, color = OpenCodeTheme.colors.tertiaryText, modifier = Modifier.clearAndSetSemantics { })
        // Retry can't help while the session's folder is gone: moving it can.
        if (failedSend.failure.isMoving) {
            Text(
                TranscriptFailedSend.moving,
                style = style,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                modifier = Modifier.testTag("failed-send-moving"),
            )
        } else {
            val moveTitle = failedSend.moveTitle
            FailedSendAction(
                moveTitle ?: stringResource(CoreR.string.common_retry),
                OpenCodeTheme.colors.accent,
                if (moveTitle == null) "failed-send-retry" else "failed-send-move",
                if (moveTitle == null) failedSend.retry else failedSend.move,
            )
            Text("·", style = style, color = OpenCodeTheme.colors.tertiaryText, modifier = Modifier.clearAndSetSemantics { })
            FailedSendAction(stringResource(CoreR.string.common_edit), OpenCodeTheme.colors.accent, "failed-send-edit", failedSend.edit)
            Text("·", style = style, color = OpenCodeTheme.colors.tertiaryText, modifier = Modifier.clearAndSetSemantics { })
            FailedSendAction(stringResource(CoreR.string.common_delete), OpenCodeTheme.colors.destructive, "failed-send-delete", failedSend.delete)
        }
    }
}

@Composable
private fun FailedSendAction(title: String, color: Color, tag: String, action: () -> Unit) {
    Text(
        title,
        style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .rowHitTarget(top = 12.dp, bottom = 12.dp, target = Modifier.rowClickable(role = null, onClick = action))
            .semantics { }
            .testTag(tag),
    )
}
