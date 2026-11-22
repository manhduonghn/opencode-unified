package ai.opencode.feature.session

import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.unsentDrafts
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeQuote
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText
import java.io.File

// Stopped, restored, failed, and retried sends.

/** Stop held these back before they were posted: each shows as a failed send with Retry and Delete, kept for screens that open later. */
internal fun SessionFeature.holdStoppedSends(stopped: List<PendingPrompt>, sessionID: String, state: State): State {
    var next = state
    for (pending in stopped) {
        if (next.promptStates[pending.messageID] == null) continue
        val id = pending.messageID
        next = releaseAutoBackgroundClaim(id, next)
        next = next.copy(sentDeliveries = next.sentDeliveries - id, deliveryOverrides = next.deliveryOverrides - id)
        undeliveredPrompts.keep(pending, SessionFeature.stoppedReason, next.server.persistenceID, sessionID)
        next = markFailed(pending, SessionFeature.stoppedReason, next)
    }
    if (!next.hasSteerOnItsWay && !next.isExternallyActive) next = next.copy(isAwaitingReply = false)
    return next
}

/**
 * A draft's Stop. Once its session exists (even before this screen has heard), it's that session's
 * Stop: the screen moves to the session, then stops it as any session's Stop does. Before then,
 * the session's creation and every send not yet admitted are called off, their words, attachments,
 * and files go back to the composer ahead of anything typed since, in the order they were sent,
 * and the next send starts afresh.
 */
internal fun SessionFeature.stopDraft(state: State): Reduced<State, Action> {
    val draftID = state.outboxDraftID ?: return Reduced(state)
    return when (val decision = promptOutbox.stopDraft(PromptOutbox.Lane.Draft(draftID), state.server.persistenceID)) {
        is PromptOutbox.DraftStop.Created -> Reduced(
            state,
            Effect.concatenate(Effect.send(Action.SessionCreated(decision.session)), Effect.send(Action.InterruptTapped)),
        )
        is PromptOutbox.DraftStop.Cancelled -> {
            var next = state.copy(outboxDraftID = null)
            if (decision.pendings.isEmpty()) return Reduced(next)
            haptics.play(Haptic.cancel)
            for (pending in decision.pendings.asReversed()) {
                val id = pending.messageID
                next = next.copy(
                    items = next.items.filterNot { SessionFeature.item(it, id) },
                    queuedInputs = next.queuedInputs.filterNot { it.id == id },
                    pendingPromptPositions = next.pendingPromptPositions - id,
                    queuedInputImages = next.queuedInputImages - id,
                    promptStates = next.promptStates - id,
                    sentDeliveries = next.sentDeliveries - id,
                    deliveryOverrides = next.deliveryOverrides - id,
                    optimisticPromptIDs = next.optimisticPromptIDs - id,
                    cancelledInputIDs = next.cancelledInputIDs + id,
                )
                next = returnToComposer(pending, next)
            }
            next = next.copy(lastSentItemID = null)
            if (!next.hasMovingLocalPrompt) next = next.copy(isAwaitingReply = false)
            Reduced(next)
        }
    }
}

/**
 * Lays the outbox over what the screen restored (its cache or Home's snapshot), which never holds
 * unadmitted sends: the session's sends still on their way, shown as the screen that sent them did
 * and followed until they settle, and those admitted in the last couple of minutes, until the
 * transcript has them. Running it twice adds nothing.
 */
internal fun SessionFeature.restoreOutbox(state: State): Reduced<State, Action> {
    val session = state.session
    val draftID = state.outboxDraftID
    val lane: PromptOutbox.Lane = when {
        session != null -> PromptOutbox.Lane.Session(session.id)
        draftID != null -> PromptOutbox.Lane.Draft(draftID)
        else -> return Reduced(state)
    }
    var next = state
    val contents = promptOutbox.contents(next.server.persistenceID, lane)
    val promoted = next.promotedPromptIDs.toSet() + SessionFeature.promptIDs(next.items)
    for (pending in contents.admitted) {
        val id = pending.messageID
        if (next.promptStates[id] != null || id in promoted || id in next.cancelledInputIDs || next.queuedInputs.any { it.id == id }) continue
        next = showRestored(pending, State.PromptState.Admission.admitted, next).first
    }
    val effects = ArrayList<Effect<Action>>()
    for (entry in contents.unsent) {
        val pending = entry.pending
        val id = pending.messageID
        if (next.promptStates[id] != null || next.failedSends.withID(id) != null || next.queuedInputs.any { it.id == id }) continue
        val statuses = promptOutbox.statuses(id) ?: continue
        val (shown, restored) = showRestored(pending, State.PromptState.Admission.sent, next)
        next = shown
        // Waiting behind a failed or stopped send, nothing is working.
        if (!entry.isBlocked) next = next.copy(isAwaitingReply = true)
        effects += follow(statuses, restored, next.server)
    }
    promptOutbox.startIfNeeded(lane, next.server.persistenceID)
    return Reduced(next, Effect.merge(effects))
}

/** Keeps a mid-run send in `queuedInputs` (steering in the transcript, queued in the pill) with its position, predecessor ordering, and images. */
internal fun enqueueRunningPrompt(pending: PendingPrompt, state: State, isRetry: Boolean = false): State {
    val id = pending.messageID
    val input = OpenCodePendingInput(
        id = id, text = pending.text, delivery = pending.delivery, files = pending.files.mapNotNull { it.reference },
        inlineFiles = SessionFeature.inlineFiles(id, pending.attachments), quotes = pending.quotes.map { it.quote },
    )
    var next = state
    // A retry goes ahead of the sends that waited behind it, as it's delivered.
    val behind = if (isRetry) {
        next.queuedInputs.indexOfFirst { it.id != id && next.promptStates[it.id]?.admission == State.PromptState.Admission.sent }
    } else {
        -1
    }
    val queued = next.queuedInputs.toMutableList()
    if (behind >= 0) queued.add(behind, input) else queued += input
    next = next.copy(queuedInputs = queued)
    next = next.copy(
        pendingPromptPositions = next.pendingPromptPositions + (
            id to State.PendingPromptPosition(
                precedingPromptIDs = pendingPredecessors(next),
                precedingMessageIDs = SessionFeature.windowMessageIDs(next.items),
                requestEpoch = pending.requestEpoch,
            )
            ),
    )
    if (pending.delivery == OpenCodeDelivery.Steer) next = recordSteeringPredecessor(id, next)
    val images = localImages(pending.attachments, id)
    if (images.isNotEmpty()) next = next.copy(queuedInputImages = next.queuedInputImages + (id to images))
    return next
}

/** A send from an earlier screen, shown as that screen showed it. */
private fun showRestored(pending: PendingPrompt, admission: State.PromptState.Admission, state: State): Pair<State, PendingPrompt> {
    val epoch = state.nextPromptRequestEpoch + 1
    val restored = pending.copy(requestEpoch = epoch)
    val id = restored.messageID
    var next = state.copy(
        nextPromptRequestEpoch = epoch,
        promptStates = state.promptStates + (id to State.PromptState(restored.requestEpoch, admission, restored.ownsRun)),
        optimisticPromptIDs = state.optimisticPromptIDs + id,
    )
    if (restored.wasRunning) {
        next = enqueueRunningPrompt(restored, next)
        if (admission == State.PromptState.Admission.sent) next = next.copy(sentDeliveries = next.sentDeliveries + (id to restored.delivery))
        val model = restored.model
        if (restored.waitsBehindRun && model != null && next.queuedPicks[id] == null) next = next.copy(queuedPicks = next.queuedPicks + (id to model))
        val agent = restored.agent
        if (restored.waitsBehindRun && agent != null && next.queuedAgentPicks[id] == null) {
            next = next.withQueuedAgentPicks(next.queuedAgentPicks + (id to agent))
        }
    } else if (!SessionFeature.containsMessage(id, next.items)) {
        val previousFirstID = next.visibleItems.firstOrNull()?.id
        next = next.copy(
            items = next.items + SessionFeature.promptRows(id, restored.text, localImages(restored.attachments, id), restored.attachments, restored.files, restored.quotes),
        ).retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    }
    return next to restored
}

/**
 * A delivery's outcome is this screen's when it's for the session on screen or, on a draft, for
 * one of its own sends to the session the draft is creating (which this screen may not have heard of yet).
 */
internal fun SessionFeature.Companion.ownsDelivery(sessionID: String?, messageID: String, state: State): Boolean {
    sessionID ?: return true
    state.session?.id?.let { return it == sessionID }
    return state.promptStates[messageID] != null
}

/**
 * A sent message's images under the IDs the server's decoder gives them (`<message>-img-<n>`, `n`
 * its place among all the message's files, Pasted Text cards included), from the steering row
 * through delivery and the read after it, so its thumbnails keep their images and never redraw as
 * the message is delivered.
 */
internal fun localImages(attachments: List<OpenCodeOutgoingAttachment>, messageID: String): List<OpenCodeImageAttachment> =
    attachments.mapIndexedNotNull { index, attachment ->
        if (attachment.isImage) {
            OpenCodeImageAttachment(
                id = "$messageID-img-$index",
                data = attachment.data,
                mediaHash = attachment.mediaHash,
                name = attachment.name,
                mime = attachment.mime,
                byteCount = attachment.byteCount,
            )
        } else null
    }

/** A prompt's user rows as the server's decoder names them: images, then files, then text. */
internal fun SessionFeature.Companion.promptRows(
    messageID: String,
    text: String,
    source: OpenCodePromptSource? = null,
    images: List<OpenCodeImageAttachment>,
    fileReferences: List<OpenCodeFileReference>,
    inlineFiles: List<OpenCodeTranscriptFile>,
    quotes: List<OpenCodeQuote> = emptyList(),
): List<OpenCodeTranscriptItem> {
    val transcriptFiles = fileReferences.mapIndexed { index, file ->
        OpenCodeTranscriptFile(id = "$messageID-file-$index", name = file.name, mime = file.mime, path = file.path)
    } + inlineFiles
    val rows = ArrayList<OpenCodeTranscriptItem>()
    if (images.isNotEmpty()) rows += OpenCodeTranscriptItem.UserImages("$messageID-images", images)
    if (transcriptFiles.isNotEmpty()) rows += OpenCodeTranscriptItem.UserFiles("$messageID-files", transcriptFiles)
    if (text.isNotEmpty() || quotes.isNotEmpty()) rows += OpenCodeTranscriptItem.UserText(messageID, text, source, quotes)
    return rows
}

internal fun SessionFeature.Companion.promptRows(
    messageID: String,
    text: String,
    images: List<OpenCodeImageAttachment>,
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<ComposerFileAttachment>,
    quotes: List<ComposerQuote> = emptyList(),
): List<OpenCodeTranscriptItem> = promptRows(
    messageID = messageID, text = text, images = images,
    fileReferences = files.mapNotNull { it.reference },
    inlineFiles = inlineFiles(messageID, attachments),
    quotes = quotes.map { it.quote },
)

/** Inline attachments that aren't images, as the server's decoder names them (indices count images too): file chips and Pasted Text cards. */
internal fun SessionFeature.Companion.inlineFiles(messageID: String, attachments: List<OpenCodeOutgoingAttachment>): List<OpenCodeTranscriptFile> =
    attachments.mapIndexedNotNull { index, attachment ->
        if (attachment.isImage) return@mapIndexedNotNull null
        val text = attachment.pastedText
        OpenCodeTranscriptFile(
            id = "$messageID-file-inline-$index",
            name = attachment.name ?: if (text == null) "File" else PastedText.NAME,
            mime = attachment.mime,
            text = text,
            pasted = attachment.pasted,
            mediaHash = attachment.mediaHash,
            byteCount = attachment.byteCount,
        )
    }

/**
 * Sends a failed message again from its own place in the outbox, ahead of the sends that waited
 * behind it. In an idle session its bubble stays where it is; mid-run it becomes a pending row again.
 */
internal fun SessionFeature.retry(failed: FailedSend, state: State): Reduced<State, Action> {
    val next = removeFailedSend(failed.id, state, leavesOutbox = false, keepsBubble = !state.isRunning)
    // The same ID and delivery: the server admits an ID once, so a send that landed after all can't arrive twice.
    val pending = failed.pending
    return submit(
        messageID = failed.id, text = pending.text, attachments = pending.attachments, files = pending.files,
        delivery = pending.delivery, page = pending.page, mentions = pending.mentions, chips = pending.chips,
        quotes = pending.quotes, isRetry = true, state = next,
    )
}

/**
 * The message stays where the reader saw it go, now as a failed bubble: a pending row (steering or
 * queued) becomes one at the end. It stays optimistic, so refreshes carry it until Retry, Edit, or Delete.
 */
internal fun markFailed(pending: PendingPrompt, reason: String, state: State): State {
    val id = pending.messageID
    val images = state.queuedInputImages[id]
    var next = state.copy(
        promptStates = state.promptStates - id,
        pendingPromptPositions = state.pendingPromptPositions - id,
        queuedInputs = state.queuedInputs.filterNot { it.id == id },
        queuedInputImages = state.queuedInputImages - id,
    )
    if (!SessionFeature.containsMessage(id, next.items)) {
        val previousFirstID = next.visibleItems.firstOrNull()?.id
        next = next.copy(
            items = next.items + SessionFeature.promptRows(
                id, pending.text, images ?: localImages(pending.attachments, id), pending.attachments, pending.files, pending.quotes,
            ),
        ).retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    }
    val failed = FailedSend(pending, reason)
    val index = next.failedSends.indexOfFirst { it.id == id }
    val failedSends = if (index >= 0) next.failedSends.toMutableList().also { it[index] = failed } else next.failedSends + failed
    return next.copy(optimisticPromptIDs = next.optimisticPromptIDs + id, failedSends = failedSends)
}

/** Retry, Edit, and Delete all take the failed bubble away first. Edit and Delete also take it out of the outbox, so the sends behind it go on. */
internal fun SessionFeature.removeFailedSend(id: String, state: State, leavesOutbox: Boolean = true, keepsBubble: Boolean = false): State {
    var next = state.copy(failedSends = state.failedSends.filterNot { it.id == id })
    if (next.movingFailedSendID == id) next = next.copy(movingFailedSendID = null)
    if (!keepsBubble) {
        next = next.copy(
            queuedPicks = next.queuedPicks - id,
            items = next.items.filterNot { SessionFeature.item(it, id) },
            optimisticPromptIDs = next.optimisticPromptIDs - id,
        ).withQueuedAgentPicks(next.queuedAgentPicks - id)
    }
    undeliveredPrompts.resolve(setOf(id))
    if (leavesOutbox) promptOutbox.withdraw(id)
    // A draft whose only sends are gone is unsent again: its project can change, and the next send starts a fresh lane.
    if (leavesOutbox && next.session == null && next.failedSends.isEmpty() && next.items.isEmpty() &&
        next.promptStates.keys.none { it != id }
    ) {
        next = next.copy(outboxDraftID = null)
    }
    return next
}

/**
 * Failed sends kept for this session (or this draft's failed create) that the screen doesn't show
 * yet: leaving and coming back keeps them. One the transcript already holds from the server landed after all.
 */
internal fun SessionFeature.restoreFailedSends(state: State): State {
    var next = state
    // Every failed create is kept under the server: a draft shows only its own lane's.
    val ownIDs = if (next.session == null) {
        composerPersistence.unsentDrafts(next.server.persistenceID).firstOrNull { it.draftID == next.outboxDraftID }?.messageIDs ?: emptySet()
    } else {
        null
    }
    for (entry in undeliveredPrompts.entries(next.server.persistenceID, next.session?.id)) {
        val id = entry.pending.messageID
        if (ownIDs != null && id !in ownIDs) continue
        if (next.failedSends.withID(id) != null) continue
        if (SessionFeature.containsMessage(id, next.items) && id !in next.optimisticPromptIDs) {
            undeliveredPrompts.resolve(setOf(id))
            promptOutbox.settle(setOf(id))
        } else {
            next = markFailed(entry.pending, entry.reason, next)
        }
    }
    return next
}

/** Hands Home the confirmed transcript again, so its cache drops what left. */
internal fun publishCache(state: State): Effect<Action> {
    val session = state.session ?: return Effect.none()
    return Effect.send(Action.AcceptedTranscriptUpdated(session.id, state.cacheableItems))
}
