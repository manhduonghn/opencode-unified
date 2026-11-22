package ai.opencode.feature.voice

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/*
 * The spoken "still working" line (`VoiceProgressLine`): once a delegation
 * has been out `liveProgress.firstAfter` with nobody speaking for
 * `liveProgress.quiet`, the voice agent says what the work is doing. Once,
 * then once more after `liveProgress.again`. It goes as commentary on the
 * delegation (the Live docs' "still working" update).
 *
 * A line never waits in a queue: it goes out only when the channel is free
 * (nobody speaking, nothing held or sending ahead of it), straight from the
 * send slot, and otherwise waits on its own timer and is built afresh. If
 * it can't go, it's dropped: a stale line is fine to lose, never to speak
 * after the result.
 */

/** A delegation's request settled: its line is due from now. */
internal fun LiveCallCoordinator.startProgressLine(id: String, now: Duration) {
    if (timing.liveProgress.firstAfter <= Duration.ZERO || finished) return
    progressLines[id] = VoiceProgressLine.Tracker(delegatedAt = now)
    scheduleProgressLine(id)
}

/** What the work is doing now, from its running tools (null: nothing named). */
internal fun LiveCallCoordinator.noteLiveProgressActivity(callID: String, activity: String?) {
    val tracker = progressLines[callID] ?: return
    progressLines[callID] = tracker.copy(activity = activity)
}

/** The result landed, the work was dropped, or the delegator spoke its own
 * status (`ios_voice_say`): no line is due. */
internal fun LiveCallCoordinator.stopLiveProgressLine(id: String) {
    progressTasks.remove(id)?.cancel()
    progressLines.remove(id)
}

/** When anyone last spoke: now while the voice agent is speaking. A
 * commentary append just sent (a result, a notice, another line) is
 * speech about to start. */
private val LiveCallCoordinator.progressSpeechAt: Duration?
    get() {
        if (quietGate.assistantSpeaking || assistantAudioPlaying) return clock.now()
        val lines = progressLines.values.mapNotNull { it.lastLineAt }
        return (listOfNotNull(quietGate.lastAssistantSpeechAt, quietGate.lastUserSpeechAt, quietGate.lastUserVoiceAt, lastCommentarySentAt) + lines)
            .maxOrNull()
    }

private fun LiveCallCoordinator.scheduleProgressLine(id: String, deadline: Duration? = null) {
    progressTasks.remove(id)?.cancel()
    if (finished) return
    val tracker = progressLines[id] ?: return
    val due = deadline ?: VoiceProgressLine.due(tracker, progressSpeechAt, timing.liveProgress) ?: return
    progressTasks[id] = scope.launch {
        val wait = due - clock.now()
        if (wait > Duration.ZERO) clock.sleep(wait)
        if (!isActive) return@launch
        progressLineDue(id)
    }
}

private suspend fun LiveCallCoordinator.progressLineDue(id: String) {
    progressTasks.remove(id)
    if (finished) return
    val tracker = progressLines[id] ?: return
    val due = VoiceProgressLine.due(tracker, progressSpeechAt, timing.liveProgress) ?: return
    val now = clock.now()
    // Someone spoke while it waited: it waits for the quiet again.
    if (due > now) { scheduleProgressLine(id); return }
    // Something else has the channel: look again once it's free, without
    // counting a line or keeping what it would have said.
    channelDelay(LiveOutboundRole.Progress, now)?.let { wait ->
        scheduleProgressLine(id, now + wait)
        return
    }
    if (!acquireSendSlot()) return
    try {
        // Waiting for the slot, the result may have landed or someone spoken.
        val sendAt = clock.now()
        if (finished) return
        var current = progressLines[id] ?: return
        VoiceProgressLine.due(current, progressSpeechAt, timing.liveProgress)?.let { next ->
            if (next > sendAt) { scheduleProgressLine(id); return }
        }
        channelDelay(LiveOutboundRole.Progress, sendAt, holdingSlot = true)?.let { wait ->
            scheduleProgressLine(id, sendAt + wait)
            return
        }
        val socket = active ?: return
        current = current.copy(spoken = current.spoken + 1, lastLineAt = sendAt)
        progressLines[id] = current
        val out = ReplyWatch.milliseconds(sendAt - current.delegatedAt)
        // Bound to its open client delegation when GPT-Live knows it; a
        // Responses delegation, a reconnect's orphan, or a delegation the call
        // made for a claim takes no ID.
        val append = PendingAppend(
            role = LiveOutboundRole.Progress,
            kind = LiveAppendKind.commentary,
            delegationID = if (id in openDelegations) id else null,
            content = VoiceProgressLine.instruction(current.activity),
            origin = id,
            socketID = socket.id,
        )
        try {
            sendAppendThrowing(append, socket.connection)
            droppingAssistantSpeech = false
            voiceLog.info("Progress line ${current.spoken} after ${out}ms")
            callLogMarker("Progress line ${current.spoken}, ${out}ms into the delegation: ${current.activity ?: "no named tool running"}")
            voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.injected, append.content)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            voiceLog.notice("Progress line ${current.spoken} dropped: its send failed")
        }
        scheduleProgressLine(id)
    } finally { releaseSendSlot() }
}
