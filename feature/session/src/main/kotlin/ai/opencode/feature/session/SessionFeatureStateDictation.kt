package ai.opencode.feature.session

import ai.opencode.feature.composer.DraftState
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.SharedComposerDraft
import java.time.Instant

// The composer's dictation. `raiseToTalkGrace` stays in `State`'s companion (`SessionFeature.kt`).

val State.isDictating: Boolean get() = dictation.isDictating
val State.isPreparingDictation: Boolean get() = dictation.isPreparing
val State.isFinishingDictation: Boolean get() = dictation.isFinishing

/** Recording, or finalizing its last words. */
val State.hasLiveDictation: Boolean get() = dictation.hasLive

/** Raise to Talk never starts a call while the mic records or finalizes a message, nor within the grace after one ends. */
fun State.dictationBlocksRaise(now: () -> Instant = { dependency(DateClient).now() }): Boolean = dictation.blocksRaise(now)

/** Shows the words after what was typed: in the hold while the mic is held or locked, otherwise in the draft. */
fun State.showDictation(transcript: String): State {
    val (dictation, text) = dictation.show(transcript)
    val next = copy(dictation = dictation)
    if (text == null || text == draft) return next
    // The words land at the end: the caret the reader left mid-draft goes.
    val shown = next.withDraft(text)
    return shown.copy(autocomplete = shown.autocomplete.setCaret(offset = null, draftLength = text.length))
}

/** The composer as typed, without the running recording's words. */
val State.composerWithoutDictation: DraftState
    get() {
        val composer = draftState
        if (hasLiveDictation && dictationHold == null) {
            return composer.copy(text = if (dictationBase.endsWith(" ")) dictationBase.dropLast(1) else dictationBase)
        }
        return composer
    }

/** The typed text changed under a running recording: its words follow the new text. */
fun State.rebaseDictation(typed: String): State {
    val (dictation, text) = dictation.rebase(typed, draft)
    val next = copy(dictation = dictation)
    return if (text != null) next.withDraft(text) else next
}

val State.carriedDictation: DictationFeature.Carried?
    get() {
        if (!hasLiveDictation && sendingDictation == null) return null
        return DictationFeature.Carried(
            phase = dictationPhase,
            recording = dictationRecording,
            press = dictationPress,
            hold = dictationHold,
            transcript = dictation.transcript(draft),
            sending = sendingDictation,
            isAnswer = isAnsweringRequest && hasLiveDictation,
        )
    }

/** Text typed into the composer by the app: a running recording's words follow it. */
fun State.setTyped(text: String): State = if (hasLiveDictation) rebaseDictation(text) else withDraft(text)

/** Text the app adds below what's already typed, never over it, and once. */
fun State.typeBelow(text: String): State {
    val added = text.trim()
    return when {
        draft.isEmpty() -> setTyped(text)
        !hasLiveDictation && added.isNotEmpty() && !draft.contains(added) -> withDraft(SharedComposerDraft.joined(draft, text))
        else -> this
    }
}

/** Words that won't be sent after all land in this composer, after what's there, with their attachments. */
fun State.keepAsDraft(kept: DraftState): State {
    val next = applySessionComposer { it.appending(kept) }
    return if (next.parkedComposer == null) next.clearEditedUndoDraft() else next
}

/** Takes over a recording from the screen this one replaces. */
fun State.adoptDictation(carried: DictationFeature.Carried): State {
    var next = withDictationRecording(carried.recording).withDictationPress(carried.press).withSendingDictation(carried.sending)
    if (carried.phase == DictationFeature.Phase.Idle) return next
    if (carried.isAnswer) {
        // An answer's words never land in another session: the mic stops when this screen's task starts.
        return next.withDiscardedDictation(DictationFeature.Discarded(carried.recording, carried.press))
            .withDictationRecording(next.dictationRecording + 1)
    }
    // A message's recording is never an answer: the request waits for it.
    if (next.isAnsweringRequest) next = next.endAnswering()
    val phase = carried.phase
    next = if (phase is DictationFeature.Phase.Finishing && phase.send != null) {
        next.withDictationPhase(DictationFeature.Phase.Finishing(send = null))
    } else {
        next.withDictationPhase(phase)
    }
    next = next.withDictationBase(DictationFeature.State.base(next.draft))
    carried.hold?.let { carriedHold ->
        var hold = carriedHold
        if (!hold.isLocked) hold = hold.copy(isLocked = true, armed = null)
        next = next.withDictationHold(hold.copy(text = next.draft))
    }
    return next.showDictation(carried.transcript)
}

/** A dictated message on its way to Send: a request arriving meanwhile waits for it. */
val State.dictationSendPending: Boolean get() = dictation.sendPending
fun State.withDictationSendPending(value: Boolean): State = updateDictation { it.copy(sendPending = value) }
/** An answer's recording carried here from the screen it answered on, to stop as this screen's task starts. */
val State.discardedDictation: DictationFeature.Discarded? get() = dictation.discarded
fun State.withDiscardedDictation(value: DictationFeature.Discarded?): State = updateDictation { it.copy(discarded = value) }
