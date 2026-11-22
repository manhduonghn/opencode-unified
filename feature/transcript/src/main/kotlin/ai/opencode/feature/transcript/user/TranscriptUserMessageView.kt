package ai.opencode.feature.transcript.user

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.R
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeDelivery
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow

/**
 * One user message: its pending label, images, files, a shared location and the text bubble,
 * then a failed send's line or a delivery note (iOS `TranscriptUserMessageView`). A Voice message
 * draws as [TranscriptVoiceMessageView]; an agent's message reveals on arrival.
 */
@Composable
fun TranscriptUserMessageView(
    message: TranscriptUserMessage,
    onPreviewImage: (TranscriptImagePreview) -> Unit = {},
    onOpenFile: ((AgentFileReference) -> Unit)? = null,
    onCopy: (String) -> Unit = {},
    onRevertToHere: (() -> Unit)? = null,
    /** When an agent's message arrived ([TranscriptAgentMessageReveal]), to reveal it. */
    revealArrival: Double? = null,
    /** Set when its send failed: dimmed, with Couldn’t send and Retry below. */
    failedSend: TranscriptFailedSend? = null,
    /** A quiet line below a send waiting behind a failed one (“Waiting to send”); Send never waits for it. */
    deliveryNote: String? = null,
    /** A steering message's long press while it waits: Move to Queue, Delete. */
    pendingActions: TranscriptPendingActions? = null,
    /** A long message's fold: whether it's open, and Show More/Show Less. */
    fold: UserBubbleFold.State = UserBubbleFold.State(),
    /** Its place in a run of user messages: one Steering label for the run, and tight corners. */
    run: UserMessageRun = UserMessageRun.alone,
    /** A shared location's card opens its maps link (iOS `openURL`). */
    onOpenLink: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val delegation = message.voiceDelegation
    if (delegation != null) {
        TranscriptVoiceMessageView(
            delegation = delegation,
            prompt = message.text,
            pending = message.pending,
            arrival = revealArrival,
            pendingActions = pendingActions,
            onCopy = onCopy,
            run = run,
            messageID = message.id,
            modifier = modifier,
        )
    } else {
        AgentMessageRevealClock(if (message.isFromAgent) revealArrival else null) { reveal ->
            UserMessage(message, reveal, onPreviewImage, onOpenFile, onCopy, onRevertToHere, failedSend, deliveryNote, pendingActions, fold, run, onOpenLink, modifier)
        }
    }
}

/** The message's surfaces, top to bottom: images, files, quotes, a location, the bubble. */
private enum class Surface { Images, Files, Quotes, Location, Bubble }

@Composable
private fun UserMessage(
    message: TranscriptUserMessage,
    reveal: AgentMessageReveal.Frame,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: ((AgentFileReference) -> Unit)?,
    onCopy: (String) -> Unit,
    onRevertToHere: (() -> Unit)?,
    failedSend: TranscriptFailedSend?,
    deliveryNote: String?,
    pendingActions: TranscriptPendingActions?,
    fold: UserBubbleFold.State,
    run: UserMessageRun,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val morphs = LocalPromotionMorph.current
    val density = LocalDensity.current.density
    // Pending and failed messages both read dim: neither has reached the agent.
    val isPending = message.pending != null || failedSend != null
    val dim by animateFloatAsState(if (isPending) 0.6f else 1f, PromotionMotion.animation(reduceMotion, morphs), label = "dim")
    val surfaces = Surface.entries.filter {
        when (it) {
            Surface.Images -> message.images.isNotEmpty()
            Surface.Files -> message.files.isNotEmpty()
            Surface.Quotes -> message.quotes.isNotEmpty()
            Surface.Location -> message.location != null
            Surface.Bubble -> message.displayText.isNotEmpty()
        }
    }
    // Attachments keep their own round shape; only a bubble's corners join.
    fun corners(surface: Surface): UserSurfaceCorners =
        if (surface != Surface.Bubble) UserSurfaceCorners.round else run.bubbleCorners(hasAttachments = surfaces.size > 1)

    // The message's state, once: a failure, or its pending label and any delivery note.
    val status = if (failedSend != null) TranscriptFailedSend.status
    else listOfNotNull(message.pending?.pendingLabel, deliveryNote).joinToString(", ")
    val copyLabel = stringResource(CoreR.string.common_copy)
    val revertToHereLabel = stringResource(R.string.transcript_message_revert_to_here)
    val actions = when {
        pendingActions != null -> pendingActions.accessibilityActions(copying = message.displayText)
        failedSend == null -> buildList {
            if (message.displayText.isNotEmpty()) add(action(copyLabel) { onCopy(message.displayText) })
            if (onRevertToHere != null) add(action(revertToHereLabel, onRevertToHere))
        }
        else -> emptyList()
    }
    val noteSpec = if (reduceMotion) androidx.compose.animation.core.snap() else Motion.easeOut<Float>(0.25)

    Column(
        // A message joining or leaving a run gains or lets go of the label on the same spring
        // (the label's own enter and exit carry its space).
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
    ) {
        Box(
            Modifier.semantics {
                if (status.isNotEmpty()) stateDescription = status
                if (actions.isNotEmpty()) customActions = actions
            },
        ) {
            Column(horizontalAlignment = Alignment.End) {
                // A run waiting under Working has one label, over its first message.
                val labelDelivery = message.pending?.takeIf { !run.joinsAbove }
                val lastDelivery = remember { mutableStateOf<OpenCodeDelivery?>(null) }
                if (labelDelivery != null) lastDelivery.value = labelDelivery
                AnimatedVisibility(
                    visible = labelDelivery != null,
                    enter = if (morphs) EnterTransition.None else if (reduceMotion) fadeIn(Motion.reducedFadeSpec()) else PromotionMotion.pendingLabelEnter(),
                    exit = if (morphs) ExitTransition.None else if (reduceMotion) fadeOut(Motion.reducedFadeSpec()) else PromotionMotion.pendingLabelExit(density),
                ) {
                    lastDelivery.value?.let {
                        TranscriptPendingLabel(
                            it,
                            Modifier
                                .graphicsLayer { alpha = reveal.body.toFloat() }
                                // Its text lines up with the bubble's.
                                .padding(end = TranscriptMetrics.contentGutter, bottom = TranscriptMetrics.clustered - TranscriptMetrics.compact / 2),
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(UserMessageRun.spacing),
                ) {
                    if (message.images.isNotEmpty()) {
                        UserMessageImages(message.id, message.images, isPending, corners(Surface.Images), onPreviewImage)
                    }
                    if (message.files.isNotEmpty()) {
                        TranscriptFileChips(
                            message.files,
                            onOpen = if (isPending) null else onOpenFile,
                            trailingCorners = corners(Surface.Files),
                            modifier = Modifier.graphicsLayer { alpha = dim },
                        )
                    }
                    if (message.quotes.isNotEmpty()) {
                        TranscriptQuoteCards(
                            message.quotes,
                            trailingCorners = corners(Surface.Quotes),
                            modifier = Modifier.graphicsLayer { alpha = dim },
                        )
                    }
                    message.location?.let {
                        SharedLocationCard(it, isPending, corners(Surface.Location), onOpenLink)
                    }
                    if (message.displayText.isNotEmpty()) {
                        TranscriptUserBubble(
                            text = message.displayText,
                            isPending = isPending,
                            // A steering send lands on its dim bubble too.
                            itemID = message.id,
                            reveal = reveal,
                            failedSend = failedSend,
                            pendingActions = pendingActions,
                            onCopy = onCopy,
                            onRevertToHere = onRevertToHere,
                            corners = corners(Surface.Bubble),
                            fold = fold,
                        )
                    }
                }
            }
            // A message of only images or files has no bubble to hold.
            if (message.displayText.isEmpty() && pendingActions != null) {
                PendingAttachmentMenu(pendingActions, Modifier.matchParentSize())
            }
        }
        AnimatedVisibility(failedSend != null, enter = fadeIn(noteSpec), exit = fadeOut(noteSpec)) {
            failedSend?.let { TranscriptFailedSendLine(it, Modifier.padding(end = TranscriptMetrics.contentGutter)) }
        }
        AnimatedVisibility(failedSend == null && deliveryNote != null, enter = fadeIn(noteSpec), exit = fadeOut(noteSpec)) {
            val note = remember { mutableStateOf("") }
            if (deliveryNote != null) note.value = deliveryNote
            Text(
                note.value,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(end = TranscriptMetrics.contentGutter)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** The long-press menu on a waiting steering message that has no text bubble (iOS `PendingAttachmentMenu`). */
@Composable
private fun PendingAttachmentMenu(actions: TranscriptPendingActions, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    // Over the content, which keeps its identity when the message promotes.
    Box(modifier.transcriptDragSource(payload = null, onLift = { menu = true })) {
        UserMessageMenu(menu, { menu = false }) { actions.Menu(copying = null, dismiss = { menu = false }) }
    }
}
