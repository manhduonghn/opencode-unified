package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.cancelForm
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.replyForm
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * The pending permissions and forms of a session, and the composer's bar while it answers one.
 *
 * [Draft] is the session's own composer, parked in [State.parkedComposer] while a request takes the
 * composer's place. The session area owns that type, so the dock carries it opaquely.
 *
 * The rest are the session's, which calls [RequestReplies] for the reply flows and
 * [RequestAnswering] for the composer hand-over.
 */
class RequestDockFeature<Draft> : Reducer<RequestDockFeature.State<Draft>, RequestDockFeature.Action> {

    data class State<Draft>(
        val permissionRequests: List<OpenCodePermissionRequest> = emptyList(),
        val formRequests: List<OpenCodeFormRequest> = emptyList(),
        val formAnswers: Map<String, Map<String, OpenCodeFormValue>> = emptyMap(),
        val respondingRequestIDs: Set<String> = emptySet(),
        val questionPageIndices: Map<String, Int> = emptyMap(),
        /** Docked questions dragged down to their bar; tapping or dragging the bar up brings them back. */
        val collapsedRequestIDs: Set<String> = emptySet(),
        val requestLoadError: String? = null,
        val requestLoadAttempt: LoadAttempt? = null,
        val requestFailureAwaitingReconnect: Boolean = false,
        val requestsConnectionIsAvailable: Boolean = true,
        /**
         * A snapshot since the screen opened or reconnected is the baseline: requests new after
         * it arrived live (an event's reread, a later poll).
         */
        val requestArrivalsAreLive: Boolean = false,
        val requestErrors: Map<String, String> = emptyMap(),
        /**
         * While a pending request docks, the composer is its answer, and what was typed for
         * the session waits here.
         */
        val parkedComposer: Draft? = null,
        /**
         * The bar's text for each question page or request it has answered ("form/field", or
         * the request), so paging and switching keep it.
         */
        val answerDrafts: Map<String, String> = emptyMap(),
        /** Whose words the bar holds now (an [answerTarget]). */
        val answerShownTarget: String? = null,
        /** The bar's text as last loaded or applied: an edit that only adds or removes Return's newline isn't a new answer. */
        val answerBarBaseline: String = "",
        /** The pending request the dock shows; null shows the first. */
        val selectedRequestID: String? = null,
        /** How each request's last reply ended, when its dock has to say so. */
        val requestFailures: Map<String, RequestFailure> = emptyMap(),
        /** A successful reply must not become actionable again through an older poll. */
        val completedRequestIDs: Set<String> = emptySet(),
    ) {
        enum class LoadAttempt { Initial, Reconnect, Manual }

        /** How a request's last reply ended, when its dock has to say so. */
        enum class RequestFailure {
            /** The reply didn't reach the server: Send reads Retry, and the line reads “Couldn’t send …”. */
            Reply,

            /** Dismissing it didn't reach the server: its line says so, Send stays Send. */
            Dismissal,

            /** The server had already settled it, or no longer has it: nothing to retry or send, and × clears it here. */
            Settled,

            /** Settled, found by a dismissal: its line still says the dismissal failed. */
            SettledDismissal,
            ;

            val isSettled: Boolean get() = this == Settled || this == SettledDismissal
        }

        /** The docked form's current page: the form, its active field on this page, and the page. */
        data class DockedFormPage(val form: OpenCodeFormRequest, val field: OpenCodeFormField?, val page: Int, val count: Int)

        val isAnswering: Boolean get() = parkedComposer != null

        /** The docked request's last reply didn't reach the server: Send reads Retry. */
        val dockReplyFailed: Boolean get() = dockRequestID?.let { requestFailures[it] } == RequestFailure.Reply

        /** What the request's error line leads with when its last reply or dismissal failed; null when its error is something else. */
        fun replyFailureTitle(id: String, isForm: Boolean): String? {
            return when (requestFailures[id]) {
                RequestFailure.Dismissal, RequestFailure.SettledDismissal -> localized(R.string.composer_request_dismiss_failed)
                RequestFailure.Reply, RequestFailure.Settled -> if (isForm) localized(R.string.composer_request_answer_failed) else localized(R.string.composer_request_reply_failed)
                null -> null
            }
        }

        /** The reply or dismissal is going again (or the answer changed): last time's failure no longer stands. */
        fun clearReplyFailure(id: String): State<Draft> = copy(
            requestErrors = requestErrors - id,
            requestFailures = requestFailures - id,
        )

        val hasPendingRequests: Boolean get() = permissionRequests.isNotEmpty() || formRequests.isNotEmpty()

        val reviewRequestIDs: List<String> get() = formRequests.map { it.id } + permissionRequests.map { it.id }

        val waitingSessionIDs: Set<String>
            get() = (formRequests.map { it.sessionID }.filter { it != "global" } + permissionRequests.map { it.sessionID }).toSet()

        val dockRequestID: String?
            get() {
                val ids = reviewRequestIDs
                selectedRequestID?.let { if (it in ids) return it }
                return ids.firstOrNull()
            }

        fun isOnlyReturn(text: String): Boolean {
            if (!isAnswering) return false
            return text == answerBarBaseline + "\n"
        }

        val dockedFormPage: DockedFormPage?
            get() {
                val id = dockRequestID ?: return null
                val form = formRequests.firstOrNull { it.id == id } ?: return null
                val fields = form.activeFields(formAnswers[form.id] ?: emptyMap())
                val count = if (form.isQuestion) fields.size else minOf(fields.size, 1)
                val page = minOf(questionPageIndices[form.id] ?: 0, maxOf(count - 1, 0))
                return DockedFormPage(form, fields.getOrNull(page), page, count)
            }

        /** Where the bar's text belongs: the docked page's field, or the request. */
        val answerTarget: String?
            get() {
                val id = dockRequestID ?: return null
                val docked = dockedFormPage
                if (docked != null && docked.form.fitsDock && docked.field != null) return "${docked.form.id}/${docked.field.key}"
                return id
            }

        fun requestBar(draft: String): RequestBar? {
            if (!isAnswering) return null
            val id = dockRequestID ?: return null
            if (permissionRequests.any { it.id == id }) return RequestBar.Decline(RequestBar.Verb.Deny)
            val (form, field, page, count) = dockedFormPage ?: return null
            if (!form.fitsDock || field == null) return RequestBar.Decline(RequestBar.Verb.Dismiss)
            val value = formAnswers[form.id]?.get(field.key)
            val isLast = page >= count - 1
            val ready = RequestDockAnswer.hasAnswer(value)
            val options = RequestDockAnswer.options(field)
            if (!(options.isEmpty() || field.custom == true)) return RequestBar.Choose(isLast = isLast, ready = ready)
            return RequestBar.Answer(
                multiple = field.type == OpenCodeFormFieldType.Multiselect,
                isLast = isLast,
                ready = ready,
                isCustom = RequestDockAnswer.isCustom(draft, value),
            )
        }

        /** The bar's text for [target]: what it held there, or the field's own typed answer. */
        fun answerText(target: String?): String {
            target ?: return ""
            answerDrafts[target]?.let { return it }
            val docked = dockedFormPage ?: return ""
            val field = docked.field ?: return ""
            if ("${docked.form.id}/${field.key}" != target) return ""
            val value = formAnswers[docked.form.id]?.get(field.key)
            if (value is OpenCodeFormValue.Number) return formattedNumber(value.value)
            return RequestDockAnswer.customText(value, RequestDockAnswer.options(field))
        }

        /** The person typed or dictated in the bar: on an answer page, the words become the answer. */
        fun applyBarEdit(draft: String): State<Draft> {
            if (!isAnswering || requestBar(draft) !is RequestBar.Answer) return this
            val page = dockedFormPage ?: return this
            val field = page.field ?: return this
            if (page.form.id in respondingRequestIDs) return this
            return copy(answerBarBaseline = draft).setCustomAnswer(draft, field, page.form.id)
        }

        /**
         * Your own answer's mark: it takes the bar's words as the answer, or sets them aside
         * for the choices. The flag says whether a choice changed.
         */
        fun toggleCustomAnswer(draft: String): Pair<State<Draft>, Boolean> {
            val before = dockedFormPage?.let { formAnswers[it.form.id] }
            val bar = requestBar(draft) as? RequestBar.Answer ?: return this to false
            val page = dockedFormPage ?: return this to false
            val field = page.field ?: return this to false
            if (page.form.id in respondingRequestIDs) return this to false
            val formID = page.form.id
            val next = if (!bar.isCustom) {
                setCustomAnswer(draft, field, formID)
            } else {
                val value: OpenCodeFormValue? = if (field.type == OpenCodeFormFieldType.Multiselect) {
                    OpenCodeFormValue.Strings(RequestDockAnswer.knownValues(formAnswers[formID]?.get(field.key), field))
                } else null
                setFormAnswer(formID, field.key, value)
            }
            val after = next.dockedFormPage?.let { next.formAnswers[it.form.id] }
            return next to (after != before)
        }

        fun setFormAnswer(formID: String, key: String, value: OpenCodeFormValue?): State<Draft> {
            val answers = formAnswers[formID] ?: return this
            val updated = if (value == null) answers - key else answers + (key to value)
            val next = copy(formAnswers = formAnswers + (formID to updated))
            // A settled request's error stays: no answer can take now.
            return if (requestFailures[formID]?.isSettled == true) next else next.clearReplyFailure(formID)
        }

        private fun setCustomAnswer(text: String, field: OpenCodeFormField, formID: String): State<Draft> {
            val value: OpenCodeFormValue? = when (field.type) {
                OpenCodeFormFieldType.Multiselect -> OpenCodeFormValue.Strings(
                    RequestDockAnswer.knownValues(formAnswers[formID]?.get(field.key), field) +
                        (if (text.isBlank()) emptyList() else listOf(text)),
                )
                OpenCodeFormFieldType.Number, OpenCodeFormFieldType.Integer -> OpenCodeFormValue.typedNumber(text)
                else -> OpenCodeFormValue.Str(text)
            }
            return setFormAnswer(formID, field.key, value)
        }

        fun changeQuestionPage(requestID: String, index: Int): State<Draft> {
            if (requestID in respondingRequestIDs) return this
            val count = formRequests.firstOrNull { it.id == requestID }
                ?.activeFields(formAnswers[requestID] ?: emptyMap())?.size ?: 0
            if (index !in 0 until count) return this
            return copy(questionPageIndices = questionPageIndices + (requestID to index))
        }

        /**
         * Removes requests matching [shouldRemove] (by ID and owning session) and drops state
         * for requests no longer in the review list. The flag is true when a selected request
         * was cleared, so the caller can close the requests sheet if open.
         */
        fun prune(
            shouldRemove: (id: String, sessionID: String) -> Boolean = { _, _ -> false },
            keepingSelection: Set<String> = emptySet(),
        ): Pair<State<Draft>, Boolean> {
            val hadSelection = selectedRequestID != null
            val permissions = permissionRequests.filterNot { shouldRemove(it.id, it.sessionID) }
            val forms = formRequests.filterNot { shouldRemove(it.id, it.sessionID) }
            val live = (forms.map { it.id } + permissions.map { it.id }).toSet()
            val selected = selectedRequestID?.takeIf { it in live || it in keepingSelection }
            val next = copy(
                permissionRequests = permissions,
                formRequests = forms,
                formAnswers = formAnswers.filterKeys { it in live },
                questionPageIndices = questionPageIndices.filterKeys { it in live },
                requestErrors = requestErrors.filterKeys { it in live },
                collapsedRequestIDs = if (live.containsAll(collapsedRequestIDs)) collapsedRequestIDs else collapsedRequestIDs.intersect(live),
                requestFailures = if (live.containsAll(requestFailures.keys)) requestFailures else requestFailures.filterKeys { it in live },
                selectedRequestID = selected,
            )
            return next to (hadSelection && selected == null)
        }

        // The composer hand-over.

        /**
         * The composer becomes the answer: [composer] (what was typed) waits, and the bar
         * shows the returned text, what was written for the docked page. The caller also
         * closes the autocomplete popover and empties its composer before setting the text.
         * Null when already answering.
         */
        fun beginAnswering(composer: Draft): Pair<State<Draft>, String>? {
            if (parkedComposer != null) return null
            return copy(parkedComposer = composer).loadAnswerTarget()
        }

        /**
         * The docked page or request changed: the bar keeps what it held ([draft]) for the
         * last one and shows the returned text for this one.
         */
        fun moveAnswer(draft: String): Pair<State<Draft>, String> {
            val kept = answerShownTarget?.let { copy(answerDrafts = answerDrafts + (it to draft)) } ?: this
            return kept.loadAnswerTarget()
        }

        private fun loadAnswerTarget(): Pair<State<Draft>, String> {
            val target = answerTarget
            val text = answerText(target)
            return copy(answerShownTarget = target, answerBarBaseline = text) to text
        }

        /**
         * The composer is the session's again, as it was before the request: returns the
         * parked composer, to which the caller appends anything attached meanwhile (pending
         * attachments, files, a location when it had none). Null when not answering.
         */
        fun endAnswering(): Pair<State<Draft>, Draft>? {
            val parked = parkedComposer ?: return null
            return copy(parkedComposer = null, answerDrafts = emptyMap(), answerShownTarget = null, answerBarBaseline = "") to parked
        }

        /** Given the session's [RequestAnswering.wantsRequestDock]. */
        fun answerContext(wants: Boolean): AnswerContext = AnswerContext(wants, answerTarget)

        /**
         * A pending-requests snapshot: reconciles the fetched requests with the ones being answered
         * or from owners the read didn't cover, seeds default answers, prunes, and keeps what's
         * docked docked. [Reconciled.arrived] is whether new requests appeared (the session plays
         * `.attention` when [RequestAnswering.announcesArrival] also holds).
         */
        fun reconciled(
            requests: OpenCodePendingRequests,
            familySessionIDs: Set<String>,
            restoring: (State<Draft>) -> State<Draft> = { it },
        ): Reconciled<Draft> {
            val oldIDs = reviewRequestIDs.toSet()
            val docked = dockRequestID
            // A request whose error shows stays, a settled one included, until ×.
            val protectedIDs = respondingRequestIDs + requestErrors.keys
            val coveredOwners = familySessionIDs + "global"
            fun <R> reconcile(fetched: List<R>, requests: List<R>, id: (R) -> String, sessionID: (R) -> String): List<R> {
                val kept = fetched.filter { id(it) !in completedRequestIDs }
                val keptIDs = kept.map(id).toSet()
                return kept + requests.filter { previous ->
                    (id(previous) in protectedIDs || sessionID(previous) !in coveredOwners) && id(previous) !in keptIDs
                }
            }
            val forms = reconcile(requests.forms, formRequests, { it.id }, { it.sessionID })
            // Restores persisted answers before the defaults fill in what's left.
            var next = restoring(
                copy(
                    requestLoadAttempt = null,
                    requestFailureAwaitingReconnect = false,
                    permissionRequests = reconcile(requests.permissions, permissionRequests, { it.id }, { it.sessionID }),
                    formRequests = forms,
                    requestLoadError = null,
                ),
            )
            var answers = next.formAnswers
            for (form in next.formRequests) if (answers[form.id] == null) answers = answers + (form.id to form.defaultAnswers)
            next = next.copy(formAnswers = answers)
            // Answered elsewhere or interrupted: the dock moves on.
            val (pruned, clearedSelection) = next.prune(keepingSelection = next.respondingRequestIDs)
            next = pruned
            val liveReviewIDs = next.reviewRequestIDs.toSet()
            // What's docked stays until it's answered or the reader picks another; newer requests queue behind it.
            if (docked != null && docked in liveReviewIDs && next.dockRequestID != docked) next = next.copy(selectedRequestID = docked)
            return Reconciled(next, arrived = (liveReviewIDs - oldIDs).isNotEmpty(), clearedSelection = clearedSelection)
        }
    }

    data class AnswerContext(val wants: Boolean, val target: String?)

    /**
     * A snapshot applied. [arrived]: new requests appeared. [clearedSelection]: the selected
     * request left, so the session closes the requests sheet if it's open.
     */
    data class Reconciled<Draft>(val state: State<Draft>, val arrived: Boolean, val clearedSelection: Boolean)

    /** A reply settled (see [RequestReplies.responseFinished]); [clearedSelection] as in [Reconciled]. */
    data class Settled<Draft>(val state: State<Draft>, val clearedSelection: Boolean)

    sealed interface Action {
        data class PermissionReplyTapped(val id: String, val reply: OpenCodePermissionReply, val message: String? = null) : Action
        data class ResponseFinished(val sessionID: String, val requestID: String, val error: OpenCodeClientError?, val dismissal: Boolean = false) : Action
        data class ReviewRequestSelected(val id: String) : Action
        /** Opens the form in the review sheet instead of the dock. */
        data class SheetTapped(val id: String) : Action
        data object RefreshTapped : Action
        data object AutoRetry : Action
        data class QuestionPageChanged(val requestID: String, val index: Int) : Action
        /** The docked question was dragged down to its bar, or brought back. */
        data class CollapseChanged(val requestID: String, val collapsed: Boolean) : Action
        data class FormValueChanged(val formID: String, val key: String, val value: OpenCodeFormValue?) : Action
        data class FormSubmitTapped(val id: String) : Action
        /** Dismisses a pending form with `DELETE /api/form/{id}` so the agent's question tool receives a dismissal instead of hanging. */
        data class FormCancelTapped(val id: String, val message: String? = null) : Action
        /** Dismisses whichever request is in the dock: cancels a form/question or rejects a permission, carrying the bar's words as the reason. */
        data object DismissTapped : Action
        /** Your own answer's mark in the bar: picks or sets aside the bar's text. */
        data object CustomMarkTapped : Action
        /** Dictation into the answer bar finished and is ready to send for the request it was recorded for. */
        data class BarSent(val requestID: String) : Action
    }

    override fun reduce(state: State<Draft>, action: Action): Reduced<State<Draft>, Action> = when (action) {
        is Action.ReviewRequestSelected ->
            if (action.id !in state.reviewRequestIDs || state.respondingRequestIDs.isNotEmpty()) Reduced(state)
            else Reduced(state.copy(selectedRequestID = action.id))

        is Action.QuestionPageChanged -> Reduced(state.changeQuestionPage(action.requestID, action.index))

        is Action.CollapseChanged -> {
            val known = state.formRequests.any { it.id == action.requestID }
            if (!known || (action.requestID in state.collapsedRequestIDs) == action.collapsed) {
                Reduced(state)
            } else if (action.collapsed) {
                Reduced(state.copy(collapsedRequestIDs = state.collapsedRequestIDs + action.requestID))
            } else {
                Reduced(state.copy(collapsedRequestIDs = state.collapsedRequestIDs - action.requestID))
            }
        }

        is Action.FormValueChanged ->
            if (action.formID in state.respondingRequestIDs) Reduced(state)
            else Reduced(state.setFormAnswer(action.formID, action.key, action.value))

        is Action.PermissionReplyTapped,
        is Action.ResponseFinished,
        is Action.SheetTapped,
        Action.RefreshTapped,
        Action.AutoRetry,
        is Action.FormSubmitTapped,
        is Action.FormCancelTapped,
        Action.DismissTapped,
        Action.CustomMarkTapped,
        is Action.BarSent -> Reduced(state)
    }
}

/**
 * What the composer's bar does while a pending request docks above it: its text is
 * your own answer, or the reason a decline carries.
 */
sealed interface RequestBar {
    /** The bar's text answers the page: typing or dictating there picks it. */
    data class Answer(val multiple: Boolean, override val isLast: Boolean, val ready: Boolean, val isCustom: Boolean) : RequestBar

    /** Only the choices answer; the bar's text goes with × as a reason. */
    data class Choose(override val isLast: Boolean, val ready: Boolean) : RequestBar

    /** Deny (a permission) or Dismiss (a form for the sheet), carrying the bar's text as what to do instead. */
    data class Decline(val verb: Verb) : RequestBar {
        override val isLast: Boolean get() = true
    }

    enum class Verb(@StringRes private val titleRes: Int) {
        Deny(R.string.composer_request_deny),
        Dismiss(CoreR.string.common_dismiss),
        ;

        val title: String get() = localized(titleRes)
    }

    val placeholder: String
        get() = when (this) {
            is Answer -> if (multiple) localized(R.string.composer_review_add_own_placeholder) else localized(R.string.composer_review_own_words_placeholder)
            is Choose, is Decline -> localized(R.string.composer_request_decline_placeholder)
        }

    /** The field's accessibility label. */
    val fieldLabel: String get() = if (this is Answer) localized(R.string.composer_review_answer_description) else localized(R.string.composer_request_reason_description)

    val isReady: Boolean
        get() = when (this) {
            is Answer -> ready
            is Choose -> ready
            is Decline -> true
        }

    val isLast: Boolean
}

/** Pure functions over the session's values (the session area wires them). */
object RequestAnswering {
    /**
     * A pending request takes the composer once any dictation into the message has finished. A
     * floating call leaves the composer, so the request docks there: “Needs your input” always
     * has the request.
     */
    fun wantsRequestDock(
        hasPendingRequests: Boolean,
        dictationSendPending: Boolean,
        isAnsweringRequest: Boolean,
        hasLiveDictation: Boolean,
        hasDictationHold: Boolean,
        isSendingDictation: Boolean,
    ): Boolean {
        if (!hasPendingRequests || dictationSendPending) return false
        // A message still recording, or finalizing to send, goes as a message.
        return isAnsweringRequest || (!hasLiveDictation && !hasDictationHold && !isSendingDictation)
    }

    /**
     * A long paste becomes a Pasted Text card, and a pasted image an attachment, only in a
     * message: an answer or a reason takes text.
     */
    fun takesPasteAttachments(isAnsweringRequest: Boolean): Boolean = !isAnsweringRequest

    /** Bar drafts are keyed by request or "request/field" (`answerTarget`). */
    fun pruneAnswerDrafts(drafts: Map<String, String>, live: Set<String>): Map<String, String> {
        fun isLive(key: String): Boolean {
            if (key in live) return true
            val slash = key.indexOf('/')
            return slash >= 0 && key.substring(0, slash) in live
        }
        return if (drafts.keys.any { !isLive(it) }) drafts.filterKeys(::isLive) else drafts
    }

    /** The draft differs from the bar's last answer only by Return's newline. */
    fun isOnlyReturn(text: String, returnKeySends: Boolean, nonSendNewlineDraft: String?, dock: RequestDockFeature.State<*>): Boolean =
        returnKeySends && nonSendNewlineDraft != text && dock.isOnlyReturn(text)

    /** Whether an undo snapshot no longer matches the composer and should drop. */
    fun clearsEditedUndoDraft(current: String, restoredDraft: String?): Boolean = restoredDraft != null && current != restoredDraft

    /**
     * Whether a pending-requests snapshot with new requests plays `.attention`: only once the
     * session announces arrivals (it reads on view, or has loaded requests before), never
     * during a call, and only on the top, front screen.
     */
    fun announcesArrival(arrived: Boolean, announcesArrivals: Boolean, callOwnsAudio: Boolean, isCovered: Boolean, isFront: Boolean): Boolean =
        arrived && announcesArrivals && !callOwnsAudio && !isCovered && isFront
}

/**
 * The reply flows over the dock's state, so the session reducer only supplies the server and the
 * session's location.
 */
object RequestReplies {
    private fun <D> canRespond(state: RequestDockFeature.State<D>, id: String): Boolean =
        id !in state.completedRequestIDs && id !in state.respondingRequestIDs

    /** Allow Once, Always Allow (only with a scope to save) or Deny; only a denial carries a reason. */
    fun <D> replyPermission(
        state: RequestDockFeature.State<D>,
        id: String,
        reply: OpenCodePermissionReply,
        message: String?,
        server: OpenCodeServer,
    ): Reduced<RequestDockFeature.State<D>, RequestDockFeature.Action> {
        val request = state.permissionRequests.firstOrNull { it.id == id } ?: return Reduced(state)
        if (!canRespond(state, id) || (reply == OpenCodePermissionReply.Always && request.save.isNullOrEmpty())) return Reduced(state)
        val reason = if (reply == OpenCodePermissionReply.Reject) message?.trim()?.ifEmpty { null } else null
        return respond(state, id, request.sessionID, server) { client, srv ->
            client.replyPermission(srv, request.sessionID, id, reply, reason)
        }
    }

    fun <D> submitForm(
        state: RequestDockFeature.State<D>,
        id: String,
        server: OpenCodeServer,
        directory: String?,
    ): Reduced<RequestDockFeature.State<D>, RequestDockFeature.Action> {
        val form = state.formRequests.firstOrNull { it.id == id } ?: return Reduced(state)
        // Settled on the server already: sending again can't take (× clears it).
        if (!canRespond(state, id) || state.requestFailures[id]?.isSettled == true) return Reduced(state)
        val result = form.validatedAnswer(state.formAnswers[id] ?: emptyMap())
        val failure = result.exceptionOrNull() as? OpenCodeFormRequest.ValidationFailure
        if (failure != null) {
            dependency(HapticsClient).play(Haptic.error)
            return Reduced(
                state.copy(
                    requestErrors = state.requestErrors + (id to failure.error.message),
                    questionPageIndices = state.questionPageIndices + (id to failure.error.fieldIndex),
                    collapsedRequestIDs = state.collapsedRequestIDs - id,
                ),
            )
        }
        val answer = result.getOrThrow()
        return respond(state, id, form.sessionID, server) { client, srv ->
            client.replyForm(srv, form.sessionID, id, answer, directory = directory)
        }
    }

    fun <D> cancelForm(
        state: RequestDockFeature.State<D>,
        id: String,
        message: String?,
        server: OpenCodeServer,
        directory: String?,
    ): Reduced<RequestDockFeature.State<D>, RequestDockFeature.Action> {
        val form = state.formRequests.firstOrNull { it.id == id } ?: return Reduced(state)
        if (!canRespond(state, id)) return Reduced(state)
        // Settled or gone on the server: × only clears it here.
        if (state.requestFailures[id]?.isSettled == true) {
            val (pruned, _) = state.copy(completedRequestIDs = state.completedRequestIDs + id)
                .prune(shouldRemove = { requestID, _ -> requestID == id }, keepingSelection = state.respondingRequestIDs)
            return Reduced(pruned)
        }
        val reason = message?.trim()?.ifEmpty { null }
        return respond(state, id, form.sessionID, server, dismissal = true) { client, srv ->
            client.cancelForm(srv, form.sessionID, id, reason, directory = directory)
        }
    }

    /**
     * × (or TalkBack escape): cancels the docked form or rejects the docked permission.
     * [reason] is what the bar holds while answering (a held mic's words until it stops),
     * else null; the session merges stopping any live dictation into the bar.
     */
    fun <D> dismissRequest(
        state: RequestDockFeature.State<D>,
        reason: String?,
        server: OpenCodeServer,
        directory: String?,
    ): Reduced<RequestDockFeature.State<D>, RequestDockFeature.Action> {
        val id = state.dockRequestID ?: return Reduced(state)
        if (state.respondingRequestIDs.isNotEmpty()) return Reduced(state)
        if (state.formRequests.any { it.id == id }) return cancelForm(state, id, reason, server, directory)
        return replyPermission(state, id, OpenCodePermissionReply.Reject, reason, server)
    }

    /**
     * Sends the bar while it answers a request: the next page, the answer, or Deny/Dismiss
     * with its words ([reason]) as what to do instead.
     */
    fun <D> sendRequestBar(
        state: RequestDockFeature.State<D>,
        draft: String,
        reason: String?,
        server: OpenCodeServer,
        directory: String?,
    ): Reduced<RequestDockFeature.State<D>, RequestDockFeature.Action> {
        val id = state.dockRequestID ?: return Reduced(state)
        val bar = state.requestBar(draft) ?: return Reduced(state)
        if (state.respondingRequestIDs.isNotEmpty()) return Reduced(state)
        return when (bar) {
            is RequestBar.Decline -> dismissRequest(state, reason, server, directory)
            is RequestBar.Answer, is RequestBar.Choose -> {
                if (!bar.isReady) return Reduced(state)
                val page = state.dockedFormPage
                if (!bar.isLast && page != null) Reduced(state.changeQuestionPage(id, page.page + 1))
                else submitForm(state, id, server, directory)
            }
        }
    }

    /** Your own answer's mark: a choice changed, so it plays `.selection`. */
    fun <D> customMarkTapped(state: RequestDockFeature.State<D>, draft: String): RequestDockFeature.State<D> {
        val (next, changed) = state.toggleCustomAnswer(draft)
        if (changed) dependency(HapticsClient).play(Haptic.selection)
        return next
    }

    /**
     * How a reply settled: `.success` once (never `.tap` + `.success`) and the request leaves
     * for good, or `.error` and its dock reads “Couldn’t send …” keeping the answer. Null
     * when the reply isn't this session's to settle; otherwise the session refreshes its
     * requests after it.
     */
    fun <D> responseFinished(
        state: RequestDockFeature.State<D>,
        sessionID: String,
        id: String,
        error: OpenCodeClientError?,
        familySessionIDs: Set<String>,
        dismissal: Boolean = false,
    ): RequestDockFeature.Settled<D>? {
        val wasResponding = id in state.respondingRequestIDs
        val next = state.copy(respondingRequestIDs = state.respondingRequestIDs - id)
        val requestIsKnown = next.permissionRequests.any { it.id == id && it.sessionID == sessionID } ||
            next.formRequests.any { it.id == id && it.sessionID == sessionID }
        if (!(wasResponding || requestIsKnown || sessionID == "global" || sessionID in familySessionIDs)) return null
        dependency(HapticsClient).play(if (error == null) Haptic.success else Haptic.error)
        if (error != null) {
            val failure = if (error.requestSettled) {
                if (dismissal) RequestDockFeature.State.RequestFailure.SettledDismissal else RequestDockFeature.State.RequestFailure.Settled
            } else {
                if (dismissal) RequestDockFeature.State.RequestFailure.Dismissal else RequestDockFeature.State.RequestFailure.Reply
            }
            var failed = next.copy(
                requestErrors = next.requestErrors + (id to error.message),
                requestFailures = next.requestFailures + (id to failure),
            )
            // A folded question opens to show what went wrong.
            failed = failed.copy(collapsedRequestIDs = failed.collapsedRequestIDs - id)
            return RequestDockFeature.Settled(failed, clearedSelection = false)
        }
        val (pruned, cleared) = next.copy(completedRequestIDs = next.completedRequestIDs + id)
            .prune(shouldRemove = { requestID, _ -> requestID == id }, keepingSelection = next.respondingRequestIDs)
        return RequestDockFeature.Settled(pruned, cleared)
    }

    /** Marks a request as being answered, then reports how the reply settled. */
    private fun <D> respond(
        state: RequestDockFeature.State<D>,
        requestID: String,
        sessionID: String,
        server: OpenCodeServer,
        dismissal: Boolean = false,
        reply: suspend (OpenCodeClient, OpenCodeServer) -> Unit,
    ): Reduced<RequestDockFeature.State<D>, RequestDockFeature.Action> {
        val cleared = state.clearReplyFailure(requestID)
        val next = cleared.copy(respondingRequestIDs = cleared.respondingRequestIDs + requestID)
        val effect = Effect.run<RequestDockFeature.Action> { send ->
            val client = dependency(OpenCodeClient)
            val error = try {
                reply(client, server)
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: OpenCodeClientError) {
                error
            } catch (error: Exception) {
                OpenCodeClientError(error.message ?: localized(R.string.composer_request_failed))
            }
            send(RequestDockFeature.Action.ResponseFinished(sessionID, requestID, error, dismissal))
        }
        return Reduced(next, effect)
    }
}

/** Grouped, in the app's formatting locale. */
private fun formattedNumber(value: Double): String =
    DecimalFormat("#,##0.######", DecimalFormatSymbols.getInstance(appFormattingLocale())).format(value)
