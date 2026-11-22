package ai.opencode.feature.transcript.user

import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.list.TranscriptQueuedInputView
import ai.opencode.feature.transcript.rowInsets
import ai.opencode.feature.transcript.rows.TranscriptEntryView
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/**
 * A transcript entry or a pending inbox row: the one root view for message rows, so a plain user
 * message from either source takes the same branch and promotion animates in place. User messages
 * draw here; every other entry goes to [TranscriptEntryView].
 */
@Composable
fun TranscriptMessageRow(
    rowID: String,
    entry: TranscriptEntry?,
    queued: OpenCodeQueuedInput?,
    /** Why this message's send failed, if it did. */
    failure: TranscriptSendFailure?,
    /** Why it hasn't reached the server yet, when it's taking a while. */
    deliveryNote: String?,
    props: RowProps,
    /** Its place in a run of user messages ([UserMessageRun]). */
    run: UserMessageRun,
    revealArrival: Double?,
    modifier: Modifier = Modifier,
) {
    val context = LocalTranscriptRowContext.current
    val send = context.send
    val userMessage = remember(entry, queued) {
        if (entry != null) TranscriptUserMessage.from(entry) else queued?.let(TranscriptUserMessage::from)
    }

    // A run's messages stack as close as one message's own surfaces: the upper message's bottom
    // reaches into the lower one's top half. Only the upper row's edge moves; no message moves
    // inside its row as a run forms or breaks.
    val insets = entry?.rowInsets ?: queued?.rowInsets
    var top = insets?.top ?: TranscriptMetrics.turnHalf
    var bottom = insets?.bottom ?: TranscriptMetrics.turnHalf
    val half = TranscriptMetrics.turnHalf
    if (run.joinsAbove) top = half
    if (run.joinsBelow) bottom = UserMessageRun.spacing - half

    TopAnchoredRow(modifier) {
        // A negative bottom half (a run's join) overlaps the next row.
        Box(Modifier.fillMaxWidth().padding(top = top).negativeBottom(bottom)) {
            when {
                userMessage != null -> {
                    val id = userMessage.id
                    val canRevertOwnMessage = entry != null && failure == null && deliveryNote == null &&
                        userMessage.pending == null && !userMessage.isFromAgent && userMessage.canRestoreToComposer &&
                        props.canEditMessage
                    TranscriptUserMessageView(
                        message = userMessage,
                        onPreviewImage = { send(TranscriptAction.PreviewImage(it)) },
                        onOpenFile = { send(TranscriptAction.OpenFile(it)) },
                        onCopy = { send(TranscriptAction.CopyTextTapped(it)) },
                        onRevertToHere = if (canRevertOwnMessage) ({ send(TranscriptAction.RevertToHereTapped(id)) }) else null,
                        revealArrival = revealArrival,
                        failedSend = failure?.let { failure ->
                            TranscriptFailedSend(
                                failure = failure,
                                move = { send(TranscriptAction.FailedSendMoveTapped(id)) },
                                retry = { send(TranscriptAction.FailedSendRetryTapped(id)) },
                                edit = { send(TranscriptAction.FailedSendEditTapped(id)) },
                                delete = { send(TranscriptAction.FailedSendDeleteTapped(id)) },
                            )
                        },
                        deliveryNote = deliveryNote,
                        pendingActions = pendingActions(entry, queued, props.canSendNow, send),
                        fold = fold(context.disclosure, entry, rowID, id),
                        run = run,
                        onOpenLink = { send(TranscriptAction.LinkTapped(it)) },
                    )
                }
                entry != null -> TranscriptEntryView(entry, rowID, props)
                queued != null -> TranscriptQueuedInputView(queued)
            }
        }
    }
}

/**
 * Only a steering message still waiting in the inbox; delivered, it has the normal message menu.
 * One left behind when its run ended, with nothing running to take it, can be sent now.
 */
private fun pendingActions(
    entry: TranscriptEntry?,
    queued: OpenCodeQueuedInput?,
    canSendNow: Boolean,
    send: (TranscriptAction) -> Unit,
): TranscriptPendingActions? {
    if (entry != null || queued == null || queued.delivery != OpenCodeDelivery.Steer || queued.isCompaction || queued.notice != null) return null
    val id = queued.id
    return TranscriptPendingActions(
        sendNow = if (canSendNow) ({ send(TranscriptAction.PendingInputSendNowTapped(id)) }) else null,
        moveToQueue = { send(TranscriptAction.PendingInputMoveToQueueTapped(id)) },
        delete = { send(TranscriptAction.PendingInputCancelTapped(id)) },
        copy = { send(TranscriptAction.CopyTextTapped(it)) },
    )
}

/**
 * Opened and closed through the list's disclosure motion, keyed by the message, so a steering
 * message keeps its fold when it promotes.
 */
private fun fold(disclosure: TranscriptDisclosureState, entry: TranscriptEntry?, rowID: String, messageID: String): UserBubbleFold.State {
    val entryID = entry?.id ?: rowID
    return UserBubbleFold.State(isExpanded = disclosure.isOpen(TranscriptDisclosureState.Kind.UserMessages, messageID)) {
        disclosure.toggle(TranscriptDisclosureState.Kind.UserMessages, messageID, entryID, crossFades = true)
    }
}

/** Bottom padding that may be negative: the row reports that much less height. */
private fun Modifier.negativeBottom(bottom: androidx.compose.ui.unit.Dp): Modifier =
    if (bottom >= 0.dp) padding(bottom = bottom)
    else this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = (placeable.height + bottom.roundToPx()).coerceAtLeast(0)
            layout(placeable.width, height) { placeable.placeRelative(0, 0) }
        },
    )
