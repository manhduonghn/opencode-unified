package ai.opencode.feature.session

import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.RequestAnswering
import ai.opencode.feature.composer.RequestBar
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.session.SessionFeature.State

// The members that make the composer a pending request's answer. The dock's state, reducer and bar
// modes are `:feature:composer`'s `RequestDockFeature<DraftState>` / `RequestBar`; these members
// extend `SessionFeature.State`, so they live here, over the composer's pure helpers.

typealias AnswerContext = RequestDockFeature.AnswerContext

/** The composer is a pending request's answer. */
val State.isAnsweringRequest: Boolean get() = requestDock.isAnswering

/** A long paste becomes a Pasted Text card, and a pasted image an attachment, only in a message: an answer or a reason takes text. */
val State.takesPasteAttachments: Boolean get() = RequestAnswering.takesPasteAttachments(isAnsweringRequest)

/** A pending request takes the composer once any dictation into the message has finished. */
val State.wantsRequestDock: Boolean
    get() = RequestAnswering.wantsRequestDock(
        hasPendingRequests = hasPendingRequests,
        dictationSendPending = dictationSendPending,
        isAnsweringRequest = isAnsweringRequest,
        hasLiveDictation = hasLiveDictation,
        hasDictationHold = dictationHold != null,
        isSendingDictation = sendingDictation != null,
    )

/** The draft differs from the bar's last answer only by Return's newline. */
fun State.isOnlyReturn(text: String): Boolean =
    RequestAnswering.isOnlyReturn(text, returnKeySends.value, nonSendNewlineDraft, requestDock)

/** The docked form's current page: the form, its active fields, and the page. */
val State.dockedFormPage: RequestDockFeature.State.DockedFormPage? get() = requestDock.dockedFormPage

/** Where the bar's text belongs: the docked page's field, or the request. */
val State.answerTarget: String? get() = requestDock.answerTarget

val State.requestBar: RequestBar? get() = requestDock.requestBar(draft)

/** The bar's text for [target]: what it held there, or the field's own typed answer. */
fun State.answerText(target: String?): String = requestDock.answerText(target)

/** The person typed or dictated in the bar: on an answer page, the words become the answer (beside the choices picked, for several). */
fun State.applyBarEdit(): State = copy(requestDock = requestDock.applyBarEdit(draft)).clearEditedUndoDraft()

fun State.clearEditedUndoDraft(): State {
    val current = parkedComposer?.text ?: draft
    return if (RequestAnswering.clearsEditedUndoDraft(current, undoSnapshot?.restoredDraft)) withUndoSnapshot(null) else this
}

/** Your own answer's mark: it takes the bar's words as the answer, or sets them aside for the choices. Returns whether it changed. */
fun State.toggleCustomAnswer(): Pair<State, Boolean> {
    val (dock, changed) = requestDock.toggleCustomAnswer(draft)
    return copy(requestDock = dock) to changed
}

/** The session's own composer, parked while a request takes its place. */
val State.sessionComposer: DraftState get() = parkedComposer ?: composerWithoutDictation

fun State.setSessionComposer(composer: DraftState): State =
    if (parkedComposer != null) withParkedComposer(composer) else withDraftState(composer)

/** The composer becomes the answer: what was typed waits, and the bar shows what was written for the docked page. */
fun State.beginAnswering(): State {
    val (dock, text) = requestDock.beginAnswering(draftState) ?: return this
    return copy(autocomplete = autocomplete.closePopover(), requestDock = dock)
        .withDraftState(DraftState())
        .withDraft(text)
}

/** The docked page or request changed: the bar keeps what it held for the last one and shows what it holds for this one. */
fun State.moveAnswer(): State {
    val (dock, text) = requestDock.moveAnswer(draft)
    return copy(requestDock = dock).withDraft(text)
}

/** The composer is the session's again, as it was before the request. Anything attached meanwhile stays. */
fun State.endAnswering(): State {
    val (dock, parked) = requestDock.endAnswering() ?: return this
    val restored = parked.copy(
        attachments = parked.attachments + pendingAttachments,
        files = parked.files + pendingFiles,
        location = parked.location ?: pendingLocation,
    )
    return copy(requestDock = dock).withDraftState(restored)
}

val State.answerContext: AnswerContext get() = requestDock.answerContext(wantsRequestDock)
