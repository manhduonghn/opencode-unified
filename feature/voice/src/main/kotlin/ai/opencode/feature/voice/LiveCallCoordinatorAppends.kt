package ai.opencode.feature.voice

import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import ai.opencode.feature.voice.LiveCallCoordinator.AnswerSpeech
import java.util.Locale

/** A result nobody is waiting on in this turn: it speaks as soon as it
 * won't cut anyone off (within `liveResultMaxHold` of the voice agent's
 * speech, when that's set; never of the user's). */
internal suspend fun LiveCallCoordinator.announceLive(text: String, sessionID: String?, promptIDs: List<String>) {
    if (!running()) return
    val announcementID = nextEventID("ann")
    val now = clock.now()
    val role = LiveOutboundRole.Announcement(announcementID, sessionID, promptIDs, now)
    val items = LiveContentChunker.chunks(text).map {
        PendingAppend(role, LiveAppendKind.commentary, null, it, enqueuedAt = now)
    }
    if (items.isEmpty()) return
    channelDelay(role, now)?.let { delay ->
        hold(items)
        voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker,
            "Held notice so it doesn't cut off speech (${ReplyWatch.milliseconds(delay)}ms, ${quietGate.holdReason(now)}): ${VoiceWorkClient.excerpt(text, 120)}")
        return
    }
    if (outbound.hasEligibleItems(active?.id)) { hold(items); return }
    if (!acquireSendSlot()) return
    try {
        if (outbound.hasEligibleItems(active?.id) || channelDelay(role, clock.now(), holdingSlot = true) != null) { hold(items); return }
        sendBatch(items)
    } finally { releaseSendSlot() }
}

/** Unified channel-free policy across answers, announcements, session
 * context, wire closures, and ephemeral progress lines. Null when [role]
 * may go on the wire immediately. */
internal fun LiveCallCoordinator.channelDelay(role: LiveOutboundRole, now: Duration, holdingSlot: Boolean = false): Duration? {
    if (finished) return null
    when (role) {
        LiveOutboundRole.Progress -> {
            var wait = Duration.ZERO
            val socket = active
            val busy = !outbound.isEmpty || outboundFlushTask != null ||
                pendingDelegationTasks.isNotEmpty() || acknowledgements.isNotEmpty() ||
                (sendSlotTaken && !holdingSlot) ||
                socket?.ready != true || !socket.attached
            if (busy) wait = LiveCallCoordinator.progressRetry
            quietGate.delay(now)?.let { wait = maxOf(wait, it) }
            answerProtection(now)?.let { wait = maxOf(wait, it) }
            return wait.takeIf { it > Duration.ZERO }
        }
        is LiveOutboundRole.Answer -> {
            if (timing.liveAppendQuiet <= Duration.ZERO) return null
            val answer = LiveOutboundItem.Answer(role.readyAt, role.delegatedAt)
            val wait = quietGate.answerDelay(now, role.readyAt, isPressed(answer), timing.liveAnswerPressedHold)
            val heldSince = minOf(role.readyAt, outbound.oldestResultAt ?: role.readyAt)
            return wait?.let { limited(it, now - heldSince, now) }
        }
        is LiveOutboundRole.Announcement -> {
            if (timing.liveAppendQuiet <= Duration.ZERO) return null
            var wait = if (pendingDelegationTasks.isNotEmpty() || acknowledgements.isNotEmpty()) 200.milliseconds else Duration.ZERO
            quietGate.delay(now)?.let { wait = maxOf(wait, it) }
            val oldest = outbound.oldestAnnouncementAt?.let { minOf(it, role.enqueuedAt) } ?: role.enqueuedAt
            var result = limited(wait, now - oldest, now) ?: Duration.ZERO
            if (role.sessionID != null && hasActiveDelegations) {
                val delegationHold = maxOf(Duration.ZERO, LiveCallCoordinator.activeDelegationAnnouncementCap - (now - oldest))
                result = maxOf(result, delegationHold)
            }
            return result.takeIf { it > Duration.ZERO }
        }
        LiveOutboundRole.Context -> {
            if (timing.liveAppendQuiet <= Duration.ZERO) return null
            outbound.items.firstOrNull { it.isAnswer }?.let { return channelDelay(it.role, now, holdingSlot) }
            var wait = if (timing.liveContextReplyHold > Duration.ZERO) {
                quietGate.contextDelay(now, timing.liveContextReplyHold) ?: Duration.ZERO
            } else {
                quietGate.delay(now) ?: Duration.ZERO
            }
            answerProtection(now)?.let { wait = maxOf(wait, it) }
            return wait.takeIf { it > Duration.ZERO }
        }
    }
}

/** [wait], bounded by `liveResultMaxHold` (zero: none) except for user
 * speech and `answerProtection`, which always hold on their own clocks. */
private fun LiveCallCoordinator.limited(wait: Duration, heldFor: Duration, now: Duration): Duration? {
    var result = wait
    if (timing.liveResultMaxHold > Duration.ZERO) result = minOf(result, maxOf(Duration.ZERO, timing.liveResultMaxHold - heldFor))
    quietGate.userSpeechHold(now)?.let { result = maxOf(result, it) }
    answerProtection(now)?.let { result = maxOf(result, it) }
    return result.takeIf { it > Duration.ZERO }
}
/** How long a delegation's answer still holds what would cut it off: its
 * start (`liveAnswerStartWait`), then its speech and the short pauses in
 * it, until `liveAnswerSpeakingHold` after it was sent. Null when there's
 * none to protect. */
internal fun LiveCallCoordinator.answerProtection(now: Duration): Duration? {
    val left = when (val answer = answerSpeech) {
        is AnswerSpeech.Awaiting -> return answerStartDelay(now)
        is AnswerSpeech.Speaking -> {
            if (!quietGate.assistantSpeaking) return null
            minOf(timing.liveAppendQuiet, timing.liveAnswerSpeakingHold - (now - answer.sentAt))
        }
        is AnswerSpeech.Paused -> minOf(timing.liveAppendQuiet - (now - answer.at), timing.liveAnswerSpeakingHold - (now - answer.sentAt))
        null -> return null
    }
    return left.takeIf { it > Duration.ZERO }
}
private fun LiveCallCoordinator.answerStartDelay(now: Duration): Duration? {
    val answer = answerSpeech as? AnswerSpeech.Awaiting ?: return null
    return (timing.liveAnswerStartWait - (now - answer.since)).takeIf { it > Duration.ZERO }
}
internal fun LiveCallCoordinator.noteAnswerDelivered() {
    answerSpeech = if (timing.liveAnswerStartWait > Duration.ZERO) AnswerSpeech.Awaiting(clock.now()) else null
}
/** The voice agent started or stopped speaking, or ([frame]) a frame of its
 * speech arrived. Speech arriving while an answer is awaited is that
 * answer, even when it follows earlier speech without a break. */
internal fun LiveCallCoordinator.noteAssistantSpeech(speaking: Boolean, frame: Boolean = false) {
    val now = clock.now()
    val changed = speaking != quietGate.assistantSpeaking
    quietGate = quietGate.noteAssistantSpeaking(speaking, now)
    val answer = answerSpeech
    if (speaking && (changed || frame) && answer is AnswerSpeech.Awaiting) {
        answerSpeech = if (answerStartDelay(now) != null) AnswerSpeech.Speaking(answer.since) else null
        return
    }
    if (!changed) return
    when (answer) {
        is AnswerSpeech.Speaking -> if (!speaking) answerSpeech = AnswerSpeech.Paused(now, answer.sentAt)
        is AnswerSpeech.Paused -> if (speaking) answerSpeech = if (now - answer.at < timing.liveAppendQuiet) AnswerSpeech.Speaking(answer.sentAt) else null
        else -> Unit
    }
}

/** Appends still waiting for the channel. */
internal val LiveCallCoordinator.pendingAppendCount: Int get() = outbound.count

/** FIFO, cancellation-aware batch slot. End turns every queued caller away. */
internal suspend fun LiveCallCoordinator.acquireSendSlot(): Boolean {
    if (!running()) return false
    if (!sendSlotTaken) { sendSlotTaken = true; return true }
    val waiter = CompletableDeferred<Boolean>()
    sendSlotWaiters += waiter
    var granted = false
    try {
        granted = waiter.await()
        if (granted && !running()) { releaseSendSlot(); return false }
        return granted
    } catch (error: CancellationException) {
        // Cancellation can win dispatch after release handed us the slot.
        if (!granted && waiter.isCompleted && !waiter.isCancelled && waiter.getCompleted()) releaseSendSlot()
        throw error
    } finally { sendSlotWaiters.remove(waiter) }
}
internal fun LiveCallCoordinator.releaseSendSlot() {
    if (sendSlotWaiters.isEmpty()) sendSlotTaken = false
    else sendSlotWaiters.removeFirst().complete(true)
}
internal fun LiveCallCoordinator.rejectSendSlotWaiters() {
    val waiters = sendSlotWaiters.toList(); sendSlotWaiters.clear()
    waiters.forEach { it.complete(false) }
}

/** Enqueues appends for the next quiet moment and schedules the single
 * outbound flush task (`restart` wakes earlier when a shorter-hold item
 * joins behind context). */
internal fun LiveCallCoordinator.hold(appends: List<PendingAppend>) {
    if (appends.isEmpty()) return
    val now = clock.now()
    quietGate = quietGate.noteHeld(now)
    outbound.enqueue(appends, now)
    scheduleOutboundFlush(restart = appends.any { it.role != LiveOutboundRole.Context })
}
internal fun LiveCallCoordinator.cancelLiveAnnouncements(promptIDs: List<String>) {
    val removed = outbound.cancelAnnouncements(promptIDs.toSet())
    if (removed > 0) {
        voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker, "Suppressed held notice for ${promptIDs.joinToString(", ")} (answered by delegator)")
    }
    if (outbound.isEmpty) clearOutboundHold()
}
@Suppress("UNUSED_PARAMETER")
internal suspend fun LiveCallCoordinator.closeLiveDelegation(callID: String, reason: VoiceDelegationCloseReason) {
    stopLiveProgressLine(callID)
    pendingDelegationTasks.remove(callID)?.cancel()
    acknowledgements.remove(callID)?.deadline?.cancel()
    delegationOffsets.remove(callID)
    serverDelegationSettledAt.remove(callID)
    val removed = delegations.remove(callID)
    val removedServer = serverDelegationIDs.remove(callID)
    endServerDelegation(callID, "The request was dropped.")
    if (removed != null || removedServer) finishDelegationRelease()
}
private suspend fun LiveCallCoordinator.finishDelegationRelease() {
    discardStandbyAndRescheduleRotation()
    if (!hasActiveDelegations && !outbound.isEmpty) scheduleOutboundFlush(restart = true)
}
/** A newer move's context replaces any still held: each describes a
 * screen the call has left, and every append cuts GPT-Live off. */
internal fun LiveCallCoordinator.dropHeldContext() {
    val dropped = outbound.dropContext()
    if (dropped <= 0) return
    callLogMarker("Dropped $dropped held context update(s) for a newer move")
    if (outbound.isEmpty) clearOutboundHold()
}
internal suspend fun LiveCallCoordinator.completeLiveFunction(callID: String, output: String) {
    if (!running()) return
    stopLiveProgressLine(callID)
    if (serverDelegationIDs.remove(callID)) {
        endServerDelegation(callID, "The request finished.")
        val settledAt = serverDelegationSettledAt.remove(callID)
        val chunks = LiveContentChunker.commentaryChunks(output)
        if (chunks.isNotEmpty()) {
            val role = LiveOutboundRole.Answer(clock.now(), settledAt)
            sendAnswerBatch(orphanCommentaryAppends(chunks, role, lead = ""))
        }
        finishDelegationRelease()
        return
    }
    val record = delegations.remove(callID)
    val role = LiveOutboundRole.Answer(clock.now(), record?.delegatedAt)
    val verify = callID.startsWith("client_verify_")
    val text = if (verify && !VoiceActionClaim.correctsVoice(output)) "" else output
    val chunks = LiveContentChunker.commentaryChunks(text)
    if (chunks.isEmpty()) {
        if (record != null) finishDelegationRelease()
        return
    }
    if (verify || record?.claim != null) {
        val lead = record?.claim?.let { "${LiveCallCoordinator.claimLeadPrefix}$it”: " } ?: LiveCallCoordinator.verifyLead
        sendAnswerBatch(orphanCommentaryAppends(chunks, role, lead))
        finishDelegationRelease()
        return
    }
    val socket = active
    if (socket == null || !socket.ready || record?.socketID != socket.id) {
        sendAnswerBatch(orphanCommentaryAppends(chunks, role))
        if (record != null) finishDelegationRelease()
        return
    }
    val socketID = socket.id
    val appends = chunks.map { PendingAppend(role, LiveAppendKind.commentary, callID, it, socketID = socketID) }
    if (!socket.attached || outbound.hasAnswers(socketID) || channelDelay(role, clock.now()) != null) {
        hold(appends)
    } else {
        when (sendAnswer(appends, socketID)) {
            AnswerSend.sent, AnswerSend.dropped -> Unit
            AnswerSend.held -> hold(appends)
            AnswerSend.sessionChanged -> sendAnswerBatch(orphanCommentaryAppends(chunks, role))
        }
    }
    finishDelegationRelease()
}

private enum class AnswerSend { sent, held, sessionChanged, dropped }

private suspend fun LiveCallCoordinator.sendAnswer(appends: List<PendingAppend>, socketID: Int?): AnswerSend {
    val role = appends.firstOrNull()?.role ?: return AnswerSend.dropped
    if (!acquireSendSlot()) return AnswerSend.dropped
    try {
        val socket = active
        if (socket == null || socket.id != socketID || !socket.ready || !socket.attached) return AnswerSend.sessionChanged
        if (outbound.hasAnswers(socketID) || channelDelay(role, clock.now(), holdingSlot = true) != null) return AnswerSend.held
        sendBatch(appends) { rest -> orphanCommentaryAppends(rest.map { it.content }, role) }
        return AnswerSend.sent
    } finally { releaseSendSlot() }
}
private suspend fun LiveCallCoordinator.sendAnswerBatch(appends: List<PendingAppend>) {
    val role = appends.firstOrNull()?.role ?: return
    if (outbound.hasAnswers(active?.id) || channelDelay(role, clock.now()) != null) { holdLogged(appends); return }
    if (!acquireSendSlot()) return
    try {
        if (outbound.hasAnswers(active?.id) || channelDelay(role, clock.now(), holdingSlot = true) != null) { holdLogged(appends); return }
        sendBatch(appends)
    } finally { releaseSendSlot() }
}
private fun orphanCommentaryAppends(
    remainingChunks: List<String>,
    role: LiveOutboundRole,
    lead: String = LiveCallCoordinator.earlierConnectionLead,
): List<PendingAppend> = remainingChunks.flatMapIndexed { offset, chunk ->
    LiveContentChunker.chunks(if (offset == 0) lead + chunk else chunk)
        .map { PendingAppend(role, LiveAppendKind.commentary, null, it) }
}

internal suspend fun LiveCallCoordinator.sendAppend(append: PendingAppend) {
    if (!running()) return
    if (outbound.hasEligibleItems(active?.id) || channelDelay(append.role, clock.now()) != null) { holdLogged(listOf(append)); return }
    if (!acquireSendSlot()) return
    try {
        if (outbound.hasEligibleItems(active?.id) || channelDelay(append.role, clock.now(), holdingSlot = true) != null) {
            holdLogged(listOf(append)); return
        }
        sendBatch(listOf(append))
    } finally { releaseSendSlot() }
}
private fun LiveCallCoordinator.holdLogged(appends: List<PendingAppend>) {
    val first = appends.firstOrNull() ?: return
    val now = clock.now()
    hold(appends)
    if (first.kind == LiveAppendKind.commentary) channelDelay(first.role, now)?.let {
        voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker,
            "Held notice (${ReplyWatch.milliseconds(it)}ms quiet gate, ${quietGate.holdReason(now)}): ${VoiceWorkClient.excerpt(first.content, 120)}")
    }
}
private fun LiveCallCoordinator.isPressed(answer: LiveOutboundItem.Answer): Boolean {
    val delegated = answer.delegatedAt ?: return false
    val words = lastUserWordsAt ?: return false
    if (words - delegated <= timing.liveAnswerPressedAfter) return false
    return lastDelegatedAt?.let { it <= words } ?: true
}

internal fun LiveCallCoordinator.scheduleOutboundFlush(restart: Boolean = false) {
    if (outboundFlushTask != null && !restart) return
    val generation = ++outboundFlushGeneration
    outboundFlushTask = scope.launch {
        while (isActive) {
            val step = outboundFlushStep(generation) ?: return@launch
            clock.sleep(step)
        }
    }
}
private fun LiveCallCoordinator.clearOutboundHold() {
    quietGate = quietGate.clearHold(); outboundFlushTask = null; outboundFlushGeneration++
}
private fun LiveCallCoordinator.nextOutboundDelay(socketID: Int, now: Duration, holdingSlot: Boolean = false): Duration? {
    val roles = outbound.candidateRoles(socketID)
    if (roles.isEmpty()) return null
    var minimum: Duration? = null
    for (role in roles) {
        val delay = channelDelay(role, now, holdingSlot) ?: return null
        minimum = minimum?.let { minOf(it, delay) } ?: delay
    }
    return minimum
}
/** One wake of the flush task: how long to sleep before the next, or null
 * when it's done (flushed, or nothing it can send). */
private suspend fun LiveCallCoordinator.outboundFlushStep(generation: Int): Duration? {
    if (generation != outboundFlushGeneration || finished) return null
    val socket = active
    if (socket == null || !socket.ready || !socket.attached) { outboundFlushTask = null; return null }
    if (!outbound.hasEligibleItems(socket.id)) {
        outboundFlushTask = null
        if (outbound.isEmpty) quietGate = quietGate.clearHold()
        return null
    }
    val now = clock.now()
    nextOutboundDelay(socket.id, now)?.let { delay ->
        // Context waits out a reply that may start (and end) well before its
        // limit: look again at least every quiet window.
        return if (timing.liveAppendQuiet > Duration.ZERO) minOf(delay, maxOf(timing.liveAppendQuiet, 50.milliseconds)) else delay
    }
    flushOutbound()
    return null
}
private suspend fun LiveCallCoordinator.flushOutbound() {
    outboundFlushTask = null; outboundFlushGeneration++
    if (!acquireSendSlot()) return
    try {
        val socket = active
        if (finished || socket == null || !socket.ready || !socket.attached) return
        if (!outbound.hasEligibleItems(socket.id)) {
            if (outbound.isEmpty) quietGate = quietGate.clearHold()
            return
        }
        val now = clock.now()
        if (nextOutboundDelay(socket.id, now, holdingSlot = true) != null) { scheduleOutboundFlush(); return }
        // Popped from a copy: readiness reads the queue as it stood.
        val next = outbound.copy()
        val batch = next.popReadyBatch(socket.id) { role -> channelDelay(role, now, holdingSlot = true) == null }
        outbound = next
        if (outbound.isEmpty) quietGate = quietGate.clearHold()
        val drained = sendBatch(batch)
        if (drained > 0) callLogMarker("Sent $drained undelivered append${if (drained == 1) "" else "s"} to the voice agent on the new session")
        val current = active
        if (!finished && current != null && current.ready && current.attached && outbound.hasEligibleItems(current.id)) scheduleOutboundFlush()
    } finally { releaseSendSlot() }
}
/** A suspended send never lets a later batch interleave. Failure preserves the
 * failed chunk and everything after it, in order, for the replacement session.
 * Returns how many appends from an earlier socket went out. */
private suspend fun LiveCallCoordinator.sendBatch(
    appends: List<PendingAppend>,
    rewrite: (List<PendingAppend>) -> List<PendingAppend> = { rest -> rest.map { it.copy(delegationID = null) } },
): Int {
    val startedOn = active?.id
    var drainedFromEarlier = 0
    for (index in appends.indices) {
        if (finished) return drainedFromEarlier
        if (startedOn != null && active?.id != startedOn) {
            requeue(rewrite(appends.drop(index)), startedOn)
            return drainedFromEarlier
        }
        when (val delivery = deliverAppend(appends[index])) {
            Delivery.Sent -> if (appends[index].fromEarlierSocket) drainedFromEarlier++
            Delivery.Queued -> Unit
            is Delivery.Failed -> {
                if (finished) return drainedFromEarlier
                requeue(rewrite(appends.drop(index)), delivery.socketID)
                return drainedFromEarlier
            }
        }
    }
    return drainedFromEarlier
}

private sealed interface Delivery {
    data object Sent : Delivery
    data object Queued : Delivery
    data class Failed(val socketID: Int) : Delivery
}

private suspend fun LiveCallCoordinator.deliverAppend(original: PendingAppend): Delivery {
    if (finished) return Delivery.Queued
    var append = original
    if (append.socketID != null && append.socketID != active?.id) append = append.copy(delegationID = null)
    val socket = active
    if (socket == null || !socket.ready || !socket.attached) {
        var fallback = append
        val sameReadySocket = socket?.ready == true && fallback.socketID != null && fallback.socketID == socket.id
        if (!sameReadySocket) fallback = fallback.copy(delegationID = null, fromEarlierSocket = true)
        outbound.enqueue(listOf(fallback), clock.now())
        if (append.kind == LiveAppendKind.commentary) {
            voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker, "Queued notice for voice reconnect: ${VoiceWorkClient.excerpt(append.content, 120)}")
        }
        return Delivery.Queued
    }
    val note = deliveryNote(append)
    if (append.isAnswer) noteAnswerDelivered()
    try {
        sendAppendThrowing(append, socket.connection)
        // The append stops whatever GPT-Live was saying that the user talked
        // over; what it says next is about this.
        droppingAssistantSpeech = false
        note?.let(callLogMarker)
        val enqueuedAt = append.enqueuedAt
        if (append.kind == LiveAppendKind.commentary && enqueuedAt != null) {
            voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker,
                "Sent held notice (after ${ReplyWatch.milliseconds(clock.now() - enqueuedAt)}ms): ${VoiceWorkClient.excerpt(append.content, 120)}")
            voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.injected, append.content)
        }
        return Delivery.Sent
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { return Delivery.Failed(socket.id) }
}
private fun LiveCallCoordinator.requeue(appends: List<PendingAppend>, failedOn: Int) {
    if (finished || appends.isEmpty()) return
    outbound.requeueFront(appends.map { it.copy(fromEarlierSocket = true, afterSocketID = failedOn) })
    val current = active
    if (current != null && current.id != failedOn && current.ready && current.attached) scheduleOutboundFlush(restart = true)
}
internal fun LiveCallCoordinator.deliveryNote(append: PendingAppend): String? {
    if (append.kind == LiveAppendKind.commentary && append.enqueuedAt != null) return null
    val now = clock.now()
    fun seconds(duration: Duration) = String.format(Locale.ROOT, "%.1f s", duration.inWholeNanoseconds / 1e9)
    var text = "Sent ${append.kind.rawValue} to the voice agent · held ${seconds(append.enqueuedAt?.let { now - it } ?: Duration.ZERO)}"
    if (quietGate.assistantSpeaking) text += " · while it was speaking"
    else quietGate.lastAssistantSpeechAt?.let { text += " · it last spoke ${seconds(now - it)} before" }
    if (assistantAudioPlaying) text += " · while its audio played"
    return text
}

/** An unbound result or notice as a directive GPT-Live speaks when it
 * arrives as `session.instructions.append`: its delegation leads and
 * `[COMPLETE]` dropped, prefixed with [LiveCallCoordinator.spokenDirectiveLead]
 * unless it already tells the voice agent what to say. */
fun LiveCallCoordinator.Companion.spokenDirectiveContent(raw: String): String {
    var cleaned = raw.trim()
    for (lead in listOf(earlierConnectionLead, verifyLead)) {
        if (cleaned.startsWith(lead)) cleaned = cleaned.substring(lead.length).trim()
    }
    if (cleaned.startsWith(claimLeadPrefix)) {
        val end = cleaned.indexOf("”: ")
        if (end >= 0) cleaned = cleaned.substring(end + "”: ".length).trim()
    }
    cleaned = cleaned.replace(": [COMPLETE] ", ": ").replace(": [COMPLETE]", ": ").trim()
    if (cleaned.startsWith("[COMPLETE]")) cleaned = cleaned.removePrefix("[COMPLETE]").trim()
    if (cleaned.isEmpty()) return ""
    if (cleaned.startsWith(spokenDirectiveLead) || cleaned.startsWith("Say ") || cleaned.startsWith("Briefly tell ")) return cleaned
    return spokenDirectiveLead + cleaned
}

internal suspend fun LiveCallCoordinator.sendAppendThrowing(append: PendingAppend, connection: VoiceSocketConnection<LiveServerEvent, LiveCommand>) {
    if (!running()) throw CancellationException()
    if (append.instructionIntent == LiveInstructionIntent.spokenDirective && append.delegationID == null &&
        config?.unboundSpeechUsesInstructions == true
    ) {
        val directive = LiveCallCoordinator.spokenDirectiveContent(append.content)
        if (directive.isEmpty()) return
        for (chunk in LiveContentChunker.chunks(directive)) {
            val eventID = nextEventID(LiveAppendKind.instructions.rawValue)
            connection.sendControl(LiveCommand.AppendInstructions(eventID, null, chunk), "live.append")
        }
        lastCommentarySentAt = clock.now()
        if (append.origin == null) results = results.record(append.content)
        return
    }
    val wireKind = append.kind
    val id = nextEventID(wireKind.rawValue)
    // Responses delegation rejects every non-null delegation ID on appends.
    val delegationID = if (delegatesToResponses) null else append.delegationID
    val command = when (wireKind) {
        LiveAppendKind.instructions -> LiveCommand.AppendInstructions(id, delegationID, append.content)
        LiveAppendKind.thinking -> LiveCommand.AppendThinking(id, delegationID, append.content)
        LiveAppendKind.commentary -> LiveCommand.AppendCommentary(id, delegationID, append.content)
    }
    connection.sendControl(command, "live.append")
    if (append.kind != LiveAppendKind.commentary) return
    lastCommentarySentAt = clock.now()
    if (append.origin == null) results = results.record(append.content)
}
internal suspend fun LiveCallCoordinator.sendAppendIfPossible(append: PendingAppend, connection: VoiceSocketConnection<LiveServerEvent, LiveCommand>): Boolean =
    try { sendAppendThrowing(append, connection); true }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }
